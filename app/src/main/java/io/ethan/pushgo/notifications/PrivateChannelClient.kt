package io.ethan.pushgo.notifications

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.ethan.pushgo.R
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.ChannelIdValidator
import io.ethan.pushgo.data.ChannelNameValidator
import io.ethan.pushgo.data.ChannelPasswordValidator
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.ChannelSubscriptionException
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.InboundDeliveryLedgerRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

internal const val PRIVATE_STREAM_ACK_STATUS_IGNORE = 0
internal const val PRIVATE_STREAM_ACK_STATUS_OK = 1

enum class AckDrainOutcome {
    IDLE,
    DRAINED,
    PARTIAL,
    FAILED,
}

internal fun parseAckedDeliveryIds(response: JSONObject): Set<String> {
    val ackedArray = response.optJSONArray("acked_delivery_ids") ?: return emptySet()
    val rawValues = ArrayList<String>(ackedArray.length())
    for (index in 0 until ackedArray.length()) {
        rawValues += ackedArray.optString(index)
    }
    return normalizeAckedDeliveryIds(rawValues)
}

internal fun normalizeAckedDeliveryIds(rawValues: Iterable<String>): Set<String> {
    val ackedIds = linkedSetOf<String>()
    rawValues.forEach { rawValue ->
        val value = rawValue.trim()
        if (value.isNotEmpty()) {
            ackedIds += value
        }
    }
    return ackedIds
}

internal fun normalizePendingAckDeliveryIds(rawValues: Iterable<String>): List<String> {
    return rawValues
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}

internal object PrivateStreamAckPolicy {
    fun statusForHandledResult(result: Result<Boolean>): Int {
        return if (result.getOrDefault(false)) {
            PRIVATE_STREAM_ACK_STATUS_OK
        } else {
            PRIVATE_STREAM_ACK_STATUS_IGNORE
        }
    }
}

enum class KeepaliveState {
    NOT_REQUIRED,
    IDLE,
    APP_FOREGROUND,
    FGS_ACTIVE,
    FGS_LOST,
    NETWORK_BLOCKED,
}

class PrivateChannelClient(
    private val appContext: Context,
    private val channelRepository: ChannelSubscriptionRepository,
    private val inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
    private val messageRepository: MessageRepository,
    private val entityRepository: EntityRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    @Volatile
    private var localFailureBucketStats: LocalFailureBucketStats = loadLocalFailureBucketStats()
    @Volatile
    private var lastFailureBucketFingerprint: String? = null
    private val transportStatusState = MutableStateFlow(loadTransportStatusSnapshot())
    private var loopJob: Job? = null
    private var foregroundActive = false
    private var keepaliveServiceActive = false
    private var keepaliveState = KeepaliveState.NOT_REQUIRED
    private val connectionSnapshotState = MutableStateFlow(buildConnectionSnapshot())
    private var keepaliveLossSticky = false
    private var fcmAvailable = false
    private var systemToken: String? = null
    private var runtimeConfigured = false
    private var failureStreak = 0
    private var nextAllowedPullAtMs = 0L
    private var lastSubscribedSignature: String? = null
    private var lastSubscribeAtMs = 0L
    private var lastRouteEnsureAtMs = 0L
    private var lastRouteEnsureFingerprint: String? = null
    private var lastRouteRepairAtMs = 0L
    private var currentDeviceState: DeviceState? = null
    private val routeEnsureMutex = Mutex()
    private var routeEnsureInFlight: CompletableDeferred<Unit>? = null
    private var routeEnsureInFlightFingerprint: String? = null
    @Volatile
    private var activeSessionHandle: Long = 0L
    @Volatile
    private var networkAvailable = true
    @Volatile
    private var resetAckWatermarkOnReconnect = false
    @Volatile
    private var welcomeMaxBackoffSecs = 60
    @Volatile
    private var lastTransportStatusFingerprint: String? = null
    @Volatile
    private var lastNotificationStatusFingerprint: String? = null
    private var performanceMode: PrivatePerformanceMode = loadPerformanceMode()
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val validatedInternetNetworks: MutableSet<Network> =
        Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile
    private var validatedNetworkCallbackRegistered = false
    @Volatile
    private var latestNativeSessionProfile: NativeSessionProfile? = null
    @Volatile
    private var latestTransportSelectionInsight: TransportSelectionInsight? = null
    @Volatile
    private var activeSessionNetworkClass: String = "unknown"
    @Volatile
    private var lastLocalPinnedTransport: String? = null
    @Volatile
    private var lastLocalPinnedNetworkFingerprint: String? = null
    @Volatile
    private var lastLocalPinnedNetworkClass: String? = null
    @Volatile
    private var lastAuthRouteHealAtMs: Long = 0L
    @Volatile
    private var observedNetworkClass: String = "unknown"
    @Volatile
    private var observedNetworkFingerprint: String = "unknown"
    @Volatile
    private var lastNetworkSwitchReconnectAtMs: Long = 0L
    @Volatile
    private var forceProfileRefreshOnce: Boolean = false
    @Volatile
    private var consecutiveStreamFailureCount: Int = 0
    @Volatile
    private var lastForcedProfileRefreshAtMs: Long = 0L

    init {
        io.ethan.pushgo.util.SilentSink.i(TAG, "private channel client revision=$CLIENT_REV")
        registerNetworkCallback()
    }

    val transportStatusFlow: StateFlow<TransportStatus> = transportStatusState.asStateFlow()
    val connectionSnapshotFlow: StateFlow<ConnectionSnapshot> = connectionSnapshotState.asStateFlow()

    fun setRuntime(fcmAvailable: Boolean, systemToken: String?) {
        val wasConfigured = runtimeConfigured
        val previousFcmAvailable = this.fcmAvailable
        runtimeConfigured = true
        this.fcmAvailable = fcmAvailable
        this.systemToken = systemToken
        if (fcmAvailable) {
            keepaliveLossSticky = false
        }
        if (!fcmAvailable && (!wasConfigured || previousFcmAvailable)) {
            // Force a fresh route upsert when switching from provider to private mode.
            lastRouteEnsureAtMs = 0L
            lastRouteEnsureFingerprint = null
            forceProfileRefreshOnce = true
            maybeForceProfileRefreshForAppVersion()
        }
        if (fcmAvailable) {
            saveTransportStatus(
                route = "provider",
                transport = "fcm",
                stage = "active",
                detail = "private channel disabled by provider mode",
            )
        } else if (!wasConfigured || previousFcmAvailable) {
            saveTransportStatus(
                route = "private",
                transport = "none",
                stage = "idle",
                detail = "waiting for private stream loop",
            )
        }
        recomputeKeepaliveState()
        refreshLoop()
    }

    fun onGatewayConfigChanged() {
        // Gateway host/token changed: invalidate private route/session caches and reconnect.
        currentDeviceState = null
        lastSubscribedSignature = null
        lastSubscribeAtMs = 0L
        lastRouteEnsureAtMs = 0L
        lastRouteEnsureFingerprint = null
        lastRouteRepairAtMs = 0L
        nextAllowedPullAtMs = 0L
        forceProfileRefreshOnce = true

        loopJob?.cancel()
        loopJob = null

        if (!fcmAvailable) {
            saveTransportStatus(
                route = "private",
                transport = "none",
                stage = "reconnecting",
                detail = "gateway config changed, restarting private stream",
            )
            recomputeKeepaliveState()
            refreshLoop()
            triggerProviderWakeupRecovery()
        }
    }

    fun setForeground(active: Boolean) {
        val changed = foregroundActive != active
        foregroundActive = active
        if (changed) {
            syncActiveSessionPowerHint()
        }
        if (changed && active) {
            maybeForceForegroundRecovery()
        }
        recomputeKeepaliveState()
        refreshLoop()
    }

    suspend fun gatewayPrivateChannelEnabled(): Boolean? {
        val (baseUrl, token) = channelRepository.loadGatewayConfig()
        return runCatching {
            fetchGatewayProfileSnapshot(baseUrl, token).privateChannelEnabled
        }.getOrNull()
    }

    fun setKeepaliveServiceActive(active: Boolean) {
        val changed = keepaliveServiceActive != active
        keepaliveServiceActive = active
        if (active) {
            keepaliveLossSticky = false
        }
        if (active && changed) {
            notifyKeepaliveNotificationChanged()
        }
        recomputeKeepaliveState()
        refreshLoop()
    }

    fun setPerformanceMode(mode: PrivatePerformanceMode) {
        performanceMode = mode
        prefs.edit().putString(KEY_PERF_MODE, mode.wireValue).apply()
        nextAllowedPullAtMs = 0L
        syncActiveSessionPowerHint()
        refreshLoop()
    }

    fun triggerProviderWakeupRecovery(deliveryId: String? = null) {
        scope.launch { requestProviderWakeupRecovery(deliveryId) }
    }

    private fun shouldPreferWakeupStreamRecovery(): Boolean {
        if (!runtimeConfigured || fcmAvailable) return false
        return foregroundActive || keepaliveServiceActive || activeSessionHandle != 0L || loopJob != null
    }

    fun refreshNetworkStateFromSystem() {
        refreshNetworkAvailabilityFromSystem(reason = "system_probe")
    }

    suspend fun drainAckOutboxNow(): AckDrainOutcome {
        // Wakeup-pull no longer requires HTTP ACK draining.
        return AckDrainOutcome.IDLE
    }

    private suspend fun requestProviderWakeupRecovery(deliveryId: String? = null) {
        if (!runtimeConfigured || fcmAvailable) return
        val deliveryHint = deliveryId?.trim()?.takeIf { it.isNotEmpty() }
        nextAllowedPullAtMs = 0L
        failureStreak = 0
        saveTransportStatus(
            route = "private",
            transport = transportStatusState.value.transport,
            stage = "reconnecting",
            detail = if (deliveryHint != null) {
                "wakeup delivery received, recovering private stream loop"
            } else {
                "wakeup requested, recovering private stream loop"
            },
        )
        if (!shouldPreferWakeupStreamRecovery() || !requestActiveSessionForceReconnect("wakeup_stream_recovery")) {
            stopActiveSession("wakeup_stream_recovery")
            restartLoop()
        }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        refreshNetworkAvailabilityFromSystem(reason = "callback_register")
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshNetworkAvailabilityFromSystem(reason = "callback_available")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    refreshNetworkAvailabilityFromSystem(reason = "callback_capabilities")
                }

                override fun onLost(network: Network) {
                    applyNetworkAvailability(
                        available = false,
                        reason = "callback_lost",
                    )
                }

                override fun onUnavailable() {
                    applyNetworkAvailability(
                        available = false,
                        reason = "callback_unavailable",
                    )
                }
            })
        }.onFailure {
            io.ethan.pushgo.util.SilentSink.w(TAG, "register network callback failed: ${it.message}")
        }
        runCatching {
            val request = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    validatedInternetNetworks += network
                    applyNetworkAvailability(
                        available = true,
                        reason = "validated_available",
                    )
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (hasUsableInternet(networkCapabilities, requireValidated = true)) {
                        validatedInternetNetworks += network
                    } else {
                        validatedInternetNetworks -= network
                    }
                    refreshNetworkAvailabilityFromSystem(reason = "validated_capabilities")
                }

                override fun onLost(network: Network) {
                    validatedInternetNetworks -= network
                    if (validatedInternetNetworks.isEmpty()) {
                        applyNetworkAvailability(
                            available = false,
                            reason = "validated_lost",
                        )
                    } else {
                        refreshNetworkAvailabilityFromSystem(reason = "validated_lost")
                    }
                }

                override fun onUnavailable() {
                    validatedInternetNetworks.clear()
                    applyNetworkAvailability(
                        available = false,
                        reason = "validated_unavailable",
                    )
                }
            })
            validatedNetworkCallbackRegistered = true
            refreshNetworkAvailabilityFromSystem(reason = "validated_registered")
        }.onFailure {
            io.ethan.pushgo.util.SilentSink.w(
                TAG,
                "register validated network callback failed: ${it.message}",
            )
        }
    }

    private fun refreshNetworkAvailabilityFromSystem(reason: String): Boolean {
        val cm = connectivityManager ?: return networkAvailable
        val snapshot = runCatching {
            val active = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(active)
            ActiveNetworkSnapshot(
                available = computeNetworkAvailability(capabilities),
                networkClass = classifyNetworkClass(capabilities),
                networkFingerprint = buildNetworkFingerprint(active, capabilities),
            )
        }.getOrElse {
            ActiveNetworkSnapshot(
                available = networkAvailable,
                networkClass = observedNetworkClass,
                networkFingerprint = observedNetworkFingerprint,
            )
        }
        applyNetworkAvailability(available = snapshot.available, reason = reason)
        if (snapshot.available) {
            handleNetworkTopologyChanged(
                previousFingerprint = observedNetworkFingerprint,
                previousNetworkClass = observedNetworkClass,
                newFingerprint = snapshot.networkFingerprint,
                newNetworkClass = snapshot.networkClass,
                reason = reason,
            )
        }
        return snapshot.available
    }

    private fun computeNetworkAvailability(capabilities: NetworkCapabilities?): Boolean {
        if (hasUsableInternet(capabilities, requireValidated = true)) {
            return true
        }
        if (validatedInternetNetworks.isNotEmpty()) {
            return true
        }
        if (validatedNetworkCallbackRegistered) {
            return false
        }
        return hasUsableInternet(capabilities)
    }

    private fun hasUsableInternet(
        capabilities: NetworkCapabilities?,
        requireValidated: Boolean = false,
    ): Boolean {
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            return false
        }
        if (requireValidated &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return false
        }
        return true
    }

    private fun classifyNetworkClass(capabilities: NetworkCapabilities?): String {
        return when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "other"
            else -> "unknown"
        }
    }

    private fun buildNetworkFingerprint(
        activeNetwork: Network?,
        capabilities: NetworkCapabilities?,
    ): String {
        if (activeNetwork == null) return "unknown"
        val networkClass = classifyNetworkClass(capabilities)
        return "${activeNetwork}|$networkClass"
    }

    private fun handleNetworkTopologyChanged(
        previousFingerprint: String,
        previousNetworkClass: String,
        newFingerprint: String,
        newNetworkClass: String,
        reason: String,
    ) {
        observedNetworkFingerprint = newFingerprint
        observedNetworkClass = newNetworkClass
        if (newFingerprint == previousFingerprint) {
            return
        }
        if (previousFingerprint == "unknown" || newFingerprint == "unknown") {
            return
        }
        invalidateLocalPinOnNetworkSwitch(
            previousFingerprint = previousFingerprint,
            previousNetworkClass = previousNetworkClass,
            reason = reason,
            newNetworkClass = newNetworkClass,
        )
    }

    private fun invalidateLocalPinOnNetworkSwitch(
        previousFingerprint: String,
        previousNetworkClass: String,
        reason: String,
        newNetworkClass: String,
    ) {
        val preference = loadLocalTransportPreference(previousFingerprint) ?: return
        val nowMs = System.currentTimeMillis()
        val switchCooldownMs = networkSwitchPinCooldownMs(previousNetworkClass)
        val reducedConfidence = (preference.confidence - 1).coerceAtLeast(0)
        saveLocalTransportPreference(
            preference.copy(
                confidence = reducedConfidence,
                updatedAtMs = nowMs,
                cooldownUntilMs = nowMs + switchCooldownMs,
            )
        )
        io.ethan.pushgo.util.SilentSink.w(
            TAG,
            "network switch preference invalidate previous=$previousFingerprint class=$previousNetworkClass newClass=$newNetworkClass confidence=$reducedConfidence cooldown=${switchCooldownMs}ms reason=$reason",
        )
        if (!runtimeConfigured || fcmAvailable) {
            return
        }
        val handle = activeSessionHandle
        if (handle <= 0L) {
            return
        }
        if (nowMs - lastNetworkSwitchReconnectAtMs < NETWORK_SWITCH_RECONNECT_MIN_INTERVAL_MS) {
            return
        }
        lastNetworkSwitchReconnectAtMs = nowMs
        nextAllowedPullAtMs = 0L
        failureStreak = 0
        saveTransportStatus(
            route = "private",
            transport = transportStatusState.value.transport,
            stage = "reconnecting",
            detail = "network switched($previousNetworkClass->$newNetworkClass), local preference reset and reconnect",
        )
        if (!requestActiveSessionForceReconnect("network_switch:$reason")) {
            stopActiveSession("network_switch:$reason")
            restartLoop()
        }
    }

    private fun applyNetworkAvailability(available: Boolean, reason: String) {
        val changed = networkAvailable != available
        networkAvailable = available
        if (!runtimeConfigured || fcmAvailable) {
            recomputeKeepaliveState()
            return
        }
        val stage = transportStatusState.value.stage
        if (available) {
            val shouldForceRecovery = changed
                || stage == "offline_wait"
                || ((stage == "backoff" || stage == "recovering" || stage == "reconnecting")
                    && activeSessionHandle == 0L)
            if (shouldForceRecovery) {
                forceResetAckWatermarkIfNeeded("network_restored:$reason")
                nextAllowedPullAtMs = 0L
                failureStreak = 0
                saveTransportStatus(
                    route = "private",
                    transport = "none",
                    stage = "reconnecting",
                    detail = "network restored, reconnecting private stream",
                )
                recomputeKeepaliveState()
                if (!requestActiveSessionForceReconnect("network_restored:$reason")) {
                    stopActiveSession(reason)
                    restartLoop()
                }
            } else {
                recomputeKeepaliveState()
            }
            return
        }
        if (changed || transportStatusState.value.stage != "offline_wait") {
            resetAckWatermarkOnReconnect = true
            saveTransportStatus(
                route = "private",
                transport = "none",
                stage = "offline_wait",
                detail = "network unavailable, waiting for callback",
            )
            recomputeKeepaliveState()
            stopActiveSession(reason)
            restartLoop()
        } else {
            recomputeKeepaliveState()
        }
    }

    suspend fun switchToProviderChannel(
        channelType: String,
        providerToken: String?,
    ) {
        val (baseUrl, token) = channelRepository.loadGatewayConfig()
        withDeviceStateRetry(baseUrl, token) { state ->
            privatePost(baseUrl, token, "/device/register", JSONObject().apply {
                put("device_key", state.deviceKey)
                put("platform", "android")
                put("channel_type", channelType.trim().lowercase())
                if (!providerToken.isNullOrBlank()) {
                    put("provider_token", providerToken.trim())
                }
            })
            privatePost(baseUrl, token, "/channel/device/delete", JSONObject().apply {
                put("device_key", state.deviceKey)
                put("channel_type", "private")
            })
        }
        // Next private operation must not be skipped by route ensure cache.
        lastRouteEnsureAtMs = 0L
        lastRouteEnsureFingerprint = null
    }

    suspend fun switchToPrivateAndRetireProvider(channelType: String, providerToken: String?) {
        val (baseUrl, token) = channelRepository.loadGatewayConfig()
        withDeviceStateRetry(baseUrl, token) { state ->
            if (!providerToken.isNullOrBlank()) {
                privatePost(baseUrl, token, "/device/register", JSONObject().apply {
                    put("device_key", state.deviceKey)
                    put("platform", "android")
                    put("channel_type", channelType.trim().lowercase())
                    put("provider_token", providerToken.trim())
                })
            }
            ensurePrivateRoute(baseUrl, token, state, force = true)
            privatePost(baseUrl, token, "/channel/device/delete", JSONObject().apply {
                put("device_key", state.deviceKey)
                put("channel_type", channelType.trim().lowercase())
            })
        }
    }

    suspend fun privateSubscribeChannel(rawChannelId: String, rawPassword: String): Boolean {
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val password = ChannelPasswordValidator.normalize(rawPassword)
        val (baseUrl, token) = channelRepository.loadGatewayConfig()
        return withDeviceStateRetry(baseUrl, token) { state ->
            privateWithRouteRetry(baseUrl, token, state) {
                privatePost(baseUrl, token, "/channel/subscribe", JSONObject().apply {
                    put("device_key", state.deviceKey)
                    put("channel_id", channelId)
                    put("password", password)
                })
            }
            lastSubscribedSignature = null
            lastSubscribeAtMs = 0L
            true
        }
    }

    suspend fun privateCreateChannel(rawChannelName: String, rawPassword: String): ChannelCreateResult {
        val channelName = ChannelNameValidator.normalize(rawChannelName)
        val password = ChannelPasswordValidator.normalize(rawPassword)
        val (baseUrl, token) = channelRepository.loadGatewayConfig()
        return withDeviceStateRetry(baseUrl, token) { state ->
            val resp = privateWithRouteRetry(baseUrl, token, state) {
                privatePost(baseUrl, token, "/channel/subscribe", JSONObject().apply {
                    put("device_key", state.deviceKey)
                    put("channel_name", channelName)
                    put("password", password)
                })
            }
            val channelId = resp.optString("channel_id").trim()
            if (channelId.isEmpty()) {
                throw ChannelSubscriptionException("gateway response missing channel_id")
            }
            lastSubscribedSignature = null
            lastSubscribeAtMs = 0L
            ChannelCreateResult(
                channelId = channelId,
                channelName = resp.optString("channel_name").trim().ifEmpty { channelName },
                created = resp.optBoolean("created", false),
                subscribed = resp.optBoolean("subscribed", true),
            )
        }
    }

    suspend fun privateUnsubscribeChannel(rawChannelId: String): Boolean {
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val (baseUrl, token) = channelRepository.loadGatewayConfig()
        return withDeviceStateRetry(baseUrl, token) { state ->
            privateWithRouteRetry(baseUrl, token, state) {
                privatePost(baseUrl, token, "/channel/unsubscribe", JSONObject().apply {
                    put("device_key", state.deviceKey)
                    put("channel_id", channelId)
                })
            }
            lastSubscribedSignature = null
            lastSubscribeAtMs = 0L
            true
        }
    }

    private fun refreshLoop() {
        if (!runtimeConfigured) return
        val shouldRun = !fcmAvailable && (foregroundActive || keepaliveServiceActive)
        if (!shouldRun) {
            loopJob?.cancel()
            loopJob = null
            return
        }
        if (loopJob != null) return
        loopJob = scope.launch {
            while (true) {
                val streamResult = runCatching { streamOnce() }
                    .onFailure {
                        onFailure("stream_preflight", it)
                        io.ethan.pushgo.util.SilentSink.w(TAG, "stream mode failed error=${it.message}")
                    }
                    .getOrDefault(false)
                if (streamResult) {
                    // Graceful GOAWAY/close: reconnect quickly to reduce delivery gap.
                    delay(200)
                } else {
                    val now = System.currentTimeMillis()
                    val waitMs = (nextAllowedPullAtMs - now).coerceAtLeast(500L).coerceAtMost(10_000L)
                    delay(waitMs)
                }
            }
        }
    }

    private fun restartLoop() {
        loopJob?.cancel()
        loopJob = null
        refreshLoop()
    }

    private suspend fun handlePulledMessage(payload: JSONObject, deliveryId: String): Boolean {
        val payloadMap = payloadStringMap(payload).toMutableMap().apply {
            if (this["delivery_id"].isNullOrBlank()) {
                this["delivery_id"] = deliveryId
            }
        }
        val keyBytes = settingsRepository.getNotificationKeyBytes()
        val parsed = NotificationIngressParser.parse(
            data = payloadMap,
            transportMessageId = deliveryId,
            keyBytes = keyBytes,
            textLocalizer = NotificationIngressParser.NotificationTextLocalizer.fromContext(appContext),
        ) ?: run {
            io.ethan.pushgo.util.SilentSink.w(TAG, "ignore unparseable private payload deliveryId=$deliveryId")
            return false
        }
        return InboundPersistenceCoordinator.persistAndNotify(
            context = appContext,
            messageRepository = messageRepository,
            entityRepository = entityRepository,
            inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
            settingsRepository = settingsRepository,
            inbound = parsed,
        ).shouldAck
    }

    private fun payloadStringMap(payload: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val keys = payload.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = payload.opt(key)
            if (value == null || value == JSONObject.NULL) continue
            out[key] = value.toString()
        }
        return out
    }

    private suspend fun streamOnce(): Boolean {
        val nowMs = System.currentTimeMillis()
        if (nowMs < nextAllowedPullAtMs) return false
        if (!networkAvailable) {
            refreshNetworkAvailabilityFromSystem(reason = "stream_probe")
        }
        if (!networkAvailable) {
            saveTransportStatus(
                route = "private",
                transport = "none",
                stage = "offline_wait",
                detail = "network unavailable, waiting for callback",
            )
            return false
        }
        val (baseUrl, token) = channelRepository.loadGatewayConfig()
        val state = withDeviceStateRetry(baseUrl, token) { state ->
            ensurePrivateRoute(baseUrl, token, state)
            subscribeAll(baseUrl, token, state)
            state
        }
        val transportProfile = resolvePrivateTransportProfile(baseUrl, token)
        val host = runCatching { URL(baseUrl).host }.getOrNull()?.trim().orEmpty()
        if (host.isEmpty()) {
            onFailure("stream_preflight", IllegalStateException("empty gateway host"))
            return false
        }
        if (!WarpLinkNativeBridge.isAvailable()) {
            onFailure("stream_native", IllegalStateException("native bridge unavailable"))
            return false
        }
        saveTransportStatus(
            route = "private",
            transport = "none",
            stage = "connecting",
            detail = "connecting private transport (quic->tcp->wss)",
        )
        return streamOverWarpSession(
            host = host,
            state = state,
            bearerToken = token,
            transportProfile = transportProfile,
        )
    }

    private suspend fun streamOverWarpSession(
        host: String,
        state: DeviceState,
        bearerToken: String?,
        transportProfile: PrivateTransportProfile,
    ): Boolean {
        latestNativeSessionProfile = null
        val effectiveProfile = transportProfile
        val runtimePolicy = localRuntimePolicy(foregroundActive)
        val effectiveConnectBudgetMs = runtimePolicy.connectBudgetMs
        val effectiveBackoffCapMs = runtimePolicy.backoffCapMs
        val sessionNetworkClass = currentNetworkClass()
        val sessionNetworkFingerprint = currentNetworkFingerprint()
        activeSessionNetworkClass = sessionNetworkClass
        val initialPinTransport = localPinnedTransportCandidate(
            networkFingerprint = sessionNetworkFingerprint,
            networkClass = sessionNetworkClass,
            nowMs = System.currentTimeMillis(),
            isForeground = foregroundActive,
        )
        val initialPinTtlMs = if (initialPinTransport == null) {
            null
        } else {
            localPinnedTransportTtlMs(
                networkClass = sessionNetworkClass,
                isForeground = foregroundActive,
            )
        }
        lastLocalPinnedTransport = initialPinTransport
        lastLocalPinnedNetworkFingerprint =
            if (initialPinTransport == null) null else sessionNetworkFingerprint
        lastLocalPinnedNetworkClass = if (initialPinTransport == null) null else sessionNetworkClass
        if (initialPinTransport != null) {
            io.ethan.pushgo.util.SilentSink.i(
                TAG,
                "apply local transport preference transport=$initialPinTransport network=$sessionNetworkClass fingerprint=$sessionNetworkFingerprint ttl=${initialPinTtlMs ?: 0}ms",
            )
        }
        val handle = runInterruptible(Dispatchers.IO) {
            WarpLinkNativeBridge.sessionStart(
                JSONObject().apply {
                    put("host", host)
                    put("quic_enabled", effectiveProfile.quicEnabled)
                    put("quic_port", effectiveProfile.quicPort)
                    put("wss_enabled", effectiveProfile.wssEnabled)
                    put("wss_port", effectiveProfile.wssPort)
                    put("tcp_enabled", effectiveProfile.tcpEnabled)
                    put("tcp_port", effectiveProfile.tcpPort)
                    put("wss_path", effectiveProfile.wssPath)
                    put("wss_subprotocol", effectiveProfile.wsSubprotocol)
                    put("bearer_token", bearerToken ?: JSONObject.NULL)
                    put("cert_pin_sha256", privateCertPinSha256() ?: JSONObject.NULL)
                    put("identity", state.deviceKey)
                    put("gateway_token", bearerToken ?: JSONObject.NULL)
                    put("resume_token", state.resumeToken ?: JSONObject.NULL)
                    put("last_acked_seq", state.lastAckedSeq)
                    put("perf_tier", effectivePerformanceMode(foregroundActive).wireValue)
                    put("app_state", if (foregroundActive) "foreground" else "background")
                    put("ack_wait_timeout_ms", ACK_WAIT_TIMEOUT_MS)
                    put("connect_budget_ms", effectiveConnectBudgetMs)
                    put("backoff_max_ms", effectiveBackoffCapMs)
                    put("scheduler_v2_enabled", true)
                    put("drain_timeout_ms", 8_000L)
                    put("cutover_guard_ms", 1_500L)
                    put("initial_preferred_transport", initialPinTransport ?: JSONObject.NULL)
                    put("initial_preferred_ttl_ms", initialPinTtlMs ?: JSONObject.NULL)
                }.toString()
            )
        }
        if (handle == 0L) {
            onFailure("stream_start", IllegalStateException("native session start failed"))
            return false
        }
        activeSessionHandle = handle

        var activeAuthToken = bearerToken?.trim()?.ifEmpty { null }
        var activePowerTier = effectivePerformanceMode(foregroundActive).wireValue
        var activeAppState = if (foregroundActive) "foreground" else "background"
        var nextControlSyncAtMs = 0L
        var lastEventAtMs = System.currentTimeMillis()
        var welcomeReceived = false
        return try {
            while (true) {
                val nowMs = System.currentTimeMillis()
                if (nowMs >= nextControlSyncAtMs) {
                    val hasNetwork = refreshNetworkAvailabilityFromSystem(reason = "session_control_tick")
                    if (!hasNetwork) {
                        throw IllegalStateException("network unavailable during active private session")
                    }
                    val latestAuthToken = runCatching {
                        channelRepository.loadGatewayConfig().second?.trim()?.ifEmpty { null }
                    }.getOrNull()
                    if (latestAuthToken != activeAuthToken) {
                        val replaced = runInterruptible(Dispatchers.IO) {
                            WarpLinkNativeBridge.sessionReplaceAuthToken(handle, latestAuthToken)
                        }
                        if (replaced) {
                            activeAuthToken = latestAuthToken
                        } else {
                            io.ethan.pushgo.util.SilentSink.w(TAG, "stream token replace failed")
                        }
                    }
                    val latestPowerTier = effectivePerformanceMode(foregroundActive).wireValue
                    val latestAppState = if (foregroundActive) "foreground" else "background"
                    if (latestPowerTier != activePowerTier || latestAppState != activeAppState) {
                        val updated = runInterruptible(Dispatchers.IO) {
                            WarpLinkNativeBridge.sessionSetPowerHint(
                                handle,
                                latestAppState,
                                latestPowerTier,
                            )
                        }
                        if (updated) {
                            activePowerTier = latestPowerTier
                            activeAppState = latestAppState
                        } else {
                            io.ethan.pushgo.util.SilentSink.w(TAG, "stream power hint update failed")
                        }
                    }
                    nextControlSyncAtMs = nowMs + 5_000L
                }
                val pollTimeoutMs = (nextControlSyncAtMs - System.currentTimeMillis())
                    .coerceIn(200L, 5_000L)
                    .toInt()
                val rawEvent = runInterruptible(Dispatchers.IO) {
                    WarpLinkNativeBridge.sessionPollEvent(handle, pollTimeoutMs)
                }
                if (rawEvent == null) {
                    if (!welcomeReceived &&
                        System.currentTimeMillis() - lastEventAtMs >= PRIVATE_WELCOME_STALL_TIMEOUT_MS
                    ) {
                        throw IllegalStateException(
                            "private stream stalled before welcome for ${PRIVATE_WELCOME_STALL_TIMEOUT_MS}ms"
                        )
                    }
                    continue
                }
                lastEventAtMs = System.currentTimeMillis()
                val root = JSONObject(rawEvent)
                val eventType = root.optString("type").trim().lowercase()
                if (eventType == "welcome") {
                    welcomeReceived = true
                }
                if (eventType == "session_ended") {
                    val reason = root.optString("reason").trim().ifEmpty { "session ended" }
                    val errorText = root.optString("error").trim().ifEmpty { null }
                    if (welcomeReceived && errorText == null) {
                        saveTransportStatus(
                            route = "private",
                            transport = transportStatusState.value.transport,
                            stage = "reconnecting",
                            detail = "$reason, restarting private session",
                        )
                        return true
                    }
                    val detail = buildString {
                        append(reason)
                        if (!errorText.isNullOrBlank()) {
                            append(": ")
                            append(errorText)
                        }
                    }
                    throw IllegalStateException(
                        if (welcomeReceived) {
                            "private stream ended after welcome: $detail"
                        } else {
                            "private stream ended before welcome: $detail"
                        }
                    )
                }
                handleSessionEvent(handle, root)
            }
            @Suppress("UNREACHABLE_CODE")
            true
        } catch (_: CancellationException) {
            false
        } catch (error: Throwable) {
            val authRouteRecoverable = isAuthRouteRecoverableError(
                error.message?.lowercase().orEmpty()
            )
            if (!welcomeReceived && !authRouteRecoverable) {
                recordPinnedTransportFailure(
                    reason = "session_end_before_welcome",
                    detail = error.message.orEmpty(),
                )
            }
            onFailure("stream_session", error)
            false
        } finally {
            if (activeSessionHandle == handle) {
                activeSessionHandle = 0L
            }
            activeSessionNetworkClass = "unknown"
            lastLocalPinnedTransport = null
            lastLocalPinnedNetworkFingerprint = null
            lastLocalPinnedNetworkClass = null
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    WarpLinkNativeBridge.sessionStop(handle)
                }
            }
        }
    }

    private fun stopActiveSession(reason: String) {
        val handle = activeSessionHandle
        if (handle <= 0L) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                WarpLinkNativeBridge.sessionStop(handle)
            }.onFailure {
                io.ethan.pushgo.util.SilentSink.w(TAG, "session stop failed reason=$reason error=${it.message}")
            }
        }
    }

    private fun requestActiveSessionForceReconnect(reason: String): Boolean {
        val handle = activeSessionHandle
        if (handle <= 0L) return false
        val accepted = runCatching {
            WarpLinkNativeBridge.sessionForceReconnect(handle)
        }.getOrDefault(false)
        if (!accepted) {
            io.ethan.pushgo.util.SilentSink.w(
                TAG,
                "session force reconnect failed reason=$reason",
            )
            return false
        }
        saveTransportStatus(
            route = "private",
            transport = transportStatusState.value.transport,
            stage = "reconnecting",
            detail = "force reconnect requested: $reason",
        )
        return true
    }

    private fun syncActiveSessionPowerHint() {
        val handle = activeSessionHandle
        if (handle <= 0L) return
        val appState = if (foregroundActive) "foreground" else "background"
        val powerTier = effectivePerformanceMode(foregroundActive).wireValue
        scope.launch(Dispatchers.IO) {
            runCatching {
                WarpLinkNativeBridge.sessionSetPowerHint(handle, appState, powerTier)
            }.onFailure {
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "session power hint sync failed error=${it.message}",
                )
            }
        }
    }

    private suspend fun handleSessionEvent(handle: Long, root: JSONObject) {
        when (root.optString("type").trim().lowercase()) {
            "session_profile" -> {
                val profile = NativeSessionProfile(
                    connectBudgetMs = root.optLong("connect_budget_ms", 0L).coerceAtLeast(0L),
                    tcpDelayMs = root.optLong("tcp_delay_ms", 0L).coerceAtLeast(0L),
                    wssDelayMs = root.optLong("wss_delay_ms", 0L).coerceAtLeast(0L),
                    quicPort = root.optInt("quic_port", 0).coerceAtLeast(0),
                    tcpPort = root.optInt("tcp_port", 0).coerceAtLeast(0),
                    wssPort = root.optInt("wss_port", 0).coerceAtLeast(0),
                )
                latestNativeSessionProfile = profile
                io.ethan.pushgo.util.SilentSink.i(
                    TAG,
                    "transport_profile connect_budget=${profile.connectBudgetMs}ms tcp_delay=${profile.tcpDelayMs}ms wss_delay=${profile.wssDelayMs}ms ports(quic/tcp/wss)=${profile.quicPort}/${profile.tcpPort}/${profile.wssPort}",
                )
            }
            "connected" -> {
                val transport = root.optString("transport").trim().ifEmpty { "none" }
                val elapsedMs = root.optLong("elapsed_ms", -1L).takeIf { it >= 0L }
                val profile = latestNativeSessionProfile
                val selectionReason = deriveSelectionReason(transport, elapsedMs, profile)
                val previousInsight = latestTransportSelectionInsight
                latestTransportSelectionInsight = TransportSelectionInsight(
                    transport = transport,
                    elapsedMs = elapsedMs,
                    reason = selectionReason,
                    connectBudgetMs = profile?.connectBudgetMs,
                    tcpDelayMs = profile?.tcpDelayMs,
                    wssDelayMs = profile?.wssDelayMs,
                    inSessionProbeRttMs = previousInsight?.inSessionProbeRttMs,
                    inSessionProbeSource = previousInsight?.inSessionProbeSource,
                    inSessionProbeAtMs = previousInsight?.inSessionProbeAtMs,
                    recordedAtMs = System.currentTimeMillis(),
                )
                saveTransportStatus(
                    route = "private",
                    transport = transport,
                    stage = "connected",
                    detail = buildString {
                        append("$transport stream connected")
                        elapsedMs?.let { append(" elapsed=${it}ms") }
                        selectionReason?.let { append(" reason=$it") }
                    },
                )
                logAttemptInference(transport = transport, elapsedMs = elapsedMs)
                recordTransportSuccess(
                    transport = transport,
                    networkClass = activeSessionNetworkClass,
                )
            }
            "welcome" -> {
                val wireVersion = root.optInt("wire_version", WIRE_VERSION_V2)
                val payloadVersion = root.optInt("payload_version", PRIVATE_PAYLOAD_VERSION_V1)
                if (wireVersion != WIRE_VERSION_V2 || payloadVersion != PRIVATE_PAYLOAD_VERSION_V1) {
                    throw IllegalStateException(
                        "incompatible welcome versions wire=$wireVersion payload=$payloadVersion"
                    )
                }
                val resumeToken = root.optString("resume_token").trim().ifEmpty { null }
                updateResumeState(resumeToken, null)
                welcomeMaxBackoffSecs = root.optInt("max_backoff_secs", 60).coerceIn(10, 300)
                onSuccess()
            }
            "message" -> {
                val ackId = if (root.has("ack_id") && !root.isNull("ack_id")) {
                    root.optLong("ack_id", 0L)
                } else {
                    0L
                }
                val deliveryId = root.optString("delivery_id").trim()
                if (deliveryId.isEmpty()) {
                    if (ackId > 0L) {
                        runCatching {
                            runInterruptible(Dispatchers.IO) {
                                WarpLinkNativeBridge.sessionResolveMessage(handle, ackId, PRIVATE_STREAM_ACK_STATUS_IGNORE)
                            }
                        }
                    }
                    return
                }
                val decodeOk = root.optBoolean("decode_ok", true)
                if (!decodeOk) {
                    io.ethan.pushgo.util.SilentSink.w(TAG, "stream payload decode failed id=$deliveryId")
                    if (ackId > 0L) {
                        runCatching {
                            runInterruptible(Dispatchers.IO) {
                                WarpLinkNativeBridge.sessionResolveMessage(handle, ackId, PRIVATE_STREAM_ACK_STATUS_IGNORE)
                            }
                        }
                    }
                    return
                }
                val payload = root.optJSONObject("payload") ?: JSONObject()
                val seq = if (root.has("seq") && !root.isNull("seq")) root.optLong("seq", 0L) else 0L
                val handledResult = runCatching {
                    handlePulledMessage(payload, deliveryId)
                }.onFailure {
                    io.ethan.pushgo.util.SilentSink.w(TAG, "stream deliver failed id=$deliveryId error=${it.message}")
                }
                val handledOk = handledResult.getOrDefault(false)
                val shouldMarkAcked = handledOk && inboundDeliveryLedgerRepository.shouldAck(deliveryId)
                var streamAcked = false
                if (ackId > 0L) {
                    val status = PrivateStreamAckPolicy.statusForHandledResult(handledResult)
                    val resolved = runInterruptible(Dispatchers.IO) {
                        WarpLinkNativeBridge.sessionResolveMessage(handle, ackId, status)
                    }
                    if (!resolved) {
                        io.ethan.pushgo.util.SilentSink.w(TAG, "stream resolve message failed id=$deliveryId ackId=$ackId")
                    } else if (status == PRIVATE_STREAM_ACK_STATUS_OK) {
                        streamAcked = true
                    }
                }
                if (shouldMarkAcked && streamAcked) {
                    inboundDeliveryLedgerRepository.markAcked(listOf(deliveryId))
                }
                if (handledOk && seq > 0L) {
                    updateResumeState(null, seq)
                }
            }
            "disconnected" -> {
                val transport = root.optString("transport").trim().ifEmpty { "none" }
                val reason = root.optString("reason").trim().ifEmpty { "disconnected" }
                val stage = if (reason.contains("goaway", ignoreCase = true)) "goaway" else "closed"
                saveTransportStatus(
                    route = "private",
                    transport = transport,
                    stage = stage,
                    detail = reason,
                )
                val elapsedMs = root.optLong("elapsed_ms", -1L).takeIf { it >= 0L }
                if (reason.contains("os error 103", ignoreCase = true)) {
                    io.ethan.pushgo.util.SilentSink.w(
                        TAG,
                        "transport_attempt signal transport=$transport elapsed=${elapsedMs ?: -1}ms detail=os error 103 (socket aborted by system or network)",
                    )
                }
            }
            "reconnecting" -> {
                val attempt = root.optInt("attempt", 0).coerceAtLeast(0)
                val backoffMs = root.optLong("backoff_ms", 0L).coerceAtLeast(0L)
                saveTransportStatus(
                    route = "private",
                    transport = "none",
                    stage = "backoff",
                    detail = "attempt=$attempt next_retry=${backoffMs}ms",
                )
            }
            "scheduler_state_changed" -> {
                val state = root.optString("state").trim().ifEmpty { "unknown" }
                val reasonCode = root.optString("reason_code").trim().ifEmpty { "none" }
                io.ethan.pushgo.util.SilentSink.i(
                    TAG,
                    "scheduler_state_changed state=$state reason_code=$reasonCode",
                )
            }
            "cutover_committed" -> {
                val from = root.optString("from").trim().ifEmpty { "unknown" }
                val to = root.optString("to").trim().ifEmpty { "unknown" }
                val decisionId = root.optLong("decision_id", 0L)
                saveTransportStatus(
                    route = "private",
                    transport = to,
                    stage = "connected",
                    detail = "cutover committed from=$from to=$to decision=$decisionId",
                )
            }
            "cutover_rollback" -> {
                val restored = root.optString("restored").trim().ifEmpty { "unknown" }
                val failed = root.optString("failed").trim().ifEmpty { "unknown" }
                val decisionId = root.optLong("decision_id", 0L)
                val reason = root.optString("reason").trim().ifEmpty { "unknown" }
                saveTransportStatus(
                    route = "private",
                    transport = restored,
                    stage = "recovering",
                    detail = "cutover rollback failed=$failed restored=$restored decision=$decisionId reason=$reason",
                )
            }
            "decision_trace" -> {
                val winnerLayer = root.optString("winner_layer").trim().ifEmpty { "unknown" }
                val reasonCode = root.optString("reason_code").trim().ifEmpty { "unknown" }
                val selected = root.optString("selected_transport").trim().ifEmpty { "none" }
                io.ethan.pushgo.util.SilentSink.i(
                    TAG,
                    "decision_trace winner=$winnerLayer reason_code=$reasonCode selected=$selected",
                )
            }
            "dead_connection_detected" -> {
                val reasonCode = root.optString("reason_code").trim().ifEmpty { "unknown" }
                val eventTransport = root.optString("transport").trim().ifEmpty { "none" }
                saveTransportStatus(
                    route = "private",
                    transport = eventTransport,
                    stage = "recovering",
                    detail = "dead connection detected: $reasonCode",
                )
            }
            "recovery_tier_entered" -> {
                val tier = root.optInt("tier", 0).coerceAtLeast(0)
                val reasonCode = root.optString("reason_code").trim().ifEmpty { "unknown" }
                io.ethan.pushgo.util.SilentSink.i(
                    TAG,
                    "recovery_tier_entered tier=$tier reason_code=$reasonCode",
                )
            }
            "fatal" -> {
                val error = root.optString("error").trim().ifEmpty { "unknown error" }
                saveTransportStatus(
                    route = "private",
                    transport = "none",
                    stage = "recovering",
                    detail = error.take(160),
                )
                maybeRepairPrivateRoute(error)
            }
            "probe_rtt" -> {
                val transport = root.optString("transport").trim().ifEmpty { "none" }
                val rttMs = root.optLong("rtt_ms", -1L).takeIf { it >= 0L }
                val source = root.optString("source").trim().ifEmpty { "unknown" }
                val previousInsight = latestTransportSelectionInsight
                latestTransportSelectionInsight = TransportSelectionInsight(
                    transport = if (transport == "none") {
                        previousInsight?.transport ?: "none"
                    } else {
                        transport
                    },
                    elapsedMs = previousInsight?.elapsedMs,
                    reason = previousInsight?.reason,
                    connectBudgetMs = previousInsight?.connectBudgetMs,
                    tcpDelayMs = previousInsight?.tcpDelayMs,
                    wssDelayMs = previousInsight?.wssDelayMs,
                    inSessionProbeRttMs = rttMs,
                    inSessionProbeSource = source,
                    inSessionProbeAtMs = System.currentTimeMillis(),
                    recordedAtMs = previousInsight?.recordedAtMs ?: System.currentTimeMillis(),
                )
                connectionSnapshotState.value = buildConnectionSnapshot()
                io.ethan.pushgo.util.SilentSink.i(
                    TAG,
                    "in_session_probe transport=$transport source=$source rtt=${rttMs ?: -1}ms",
                )
            }
        }
    }

    private fun logAttemptInference(transport: String, elapsedMs: Long?) {
        val profile = latestNativeSessionProfile ?: return
        if (elapsedMs == null) return
        when (transport.lowercase()) {
            "quic" -> {
                if (elapsedMs < profile.tcpDelayMs) {
                    io.ethan.pushgo.util.SilentSink.i(
                        TAG,
                        "transport_attempt inference transport=tcp outcome=not_started reason=quic_connected_before_tcp_delay elapsed=${elapsedMs}ms tcp_delay=${profile.tcpDelayMs}ms",
                    )
                } else {
                    io.ethan.pushgo.util.SilentSink.i(
                        TAG,
                        "transport_attempt inference transport=tcp outcome=unknown reason=quic_connected_after_tcp_delay elapsed=${elapsedMs}ms tcp_delay=${profile.tcpDelayMs}ms",
                    )
                }
            }
            "wss" -> {
                io.ethan.pushgo.util.SilentSink.i(
                    TAG,
                    "transport_attempt inference transport=tcp outcome=unknown reason=wss_connected elapsed=${elapsedMs}ms tcp_delay=${profile.tcpDelayMs}ms wss_delay=${profile.wssDelayMs}ms",
                )
            }
        }
    }

    private fun deriveSelectionReason(
        transport: String,
        elapsedMs: Long?,
        profile: NativeSessionProfile?,
    ): String? {
        val elapsed = elapsedMs ?: return null
        val runtime = profile ?: return null
        return when (transport.lowercase()) {
            "quic" -> when {
                elapsed < runtime.tcpDelayMs -> {
                    "quic_connected_before_tcp_delay(elapsed=${elapsed}ms,tcp_delay=${runtime.tcpDelayMs}ms)"
                }
                elapsed < runtime.wssDelayMs -> {
                    "quic_connected_before_wss_delay(elapsed=${elapsed}ms,wss_delay=${runtime.wssDelayMs}ms)"
                }
                else -> {
                    "quic_connected_after_fallback_windows(elapsed=${elapsed}ms,budget=${runtime.connectBudgetMs}ms)"
                }
            }
            "tcp" -> when {
                elapsed < runtime.wssDelayMs -> {
                    "tcp_connected_before_wss_delay(elapsed=${elapsed}ms,wss_delay=${runtime.wssDelayMs}ms)"
                }
                else -> {
                    "tcp_connected_after_wss_delay(elapsed=${elapsed}ms,budget=${runtime.connectBudgetMs}ms)"
                }
            }
            "wss" -> {
                "wss_connected_after_quic_tcp_fallback(elapsed=${elapsed}ms,budget=${runtime.connectBudgetMs}ms)"
            }
            else -> null
        }
    }

    private fun localRuntimePolicy(isForeground: Boolean): LocalRuntimePolicy {
        return if (isForeground) {
            LocalRuntimePolicy(
                connectBudgetMs = PRIVATE_CONNECT_BUDGET_FG_MS,
                backoffCapMs = PRIVATE_BACKOFF_CAP_FG_MS,
            )
        } else {
            LocalRuntimePolicy(
                connectBudgetMs = PRIVATE_CONNECT_BUDGET_BG_MS,
                backoffCapMs = PRIVATE_BACKOFF_CAP_BG_MS,
            )
        }
    }

    private fun localPinnedTransportTtlMs(networkClass: String, isForeground: Boolean): Long {
        val policy = localPinPolicy(networkClass)
        return if (isForeground) {
            policy.ttlForegroundMs
        } else {
            policy.ttlBackgroundMs
        }
    }

    private fun currentNetworkClass(): String {
        if (observedNetworkClass != "unknown") {
            return observedNetworkClass
        }
        val cm = connectivityManager ?: return "unknown"
        val capabilities = runCatching {
            cm.getNetworkCapabilities(cm.activeNetwork)
        }.getOrNull()
        return classifyNetworkClass(capabilities)
    }

    private fun currentNetworkFingerprint(): String {
        if (observedNetworkFingerprint != "unknown") {
            return observedNetworkFingerprint
        }
        val cm = connectivityManager ?: return "unknown"
        val active = runCatching { cm.activeNetwork }.getOrNull()
        val capabilities = runCatching {
            cm.getNetworkCapabilities(active)
        }.getOrNull()
        return buildNetworkFingerprint(active, capabilities)
    }

    private fun localPinnedTransportCandidate(
        networkFingerprint: String,
        networkClass: String,
        nowMs: Long,
        isForeground: Boolean,
    ): String? {
        if (networkFingerprint == "unknown") return null
        val preference = loadLocalTransportPreference(networkFingerprint) ?: return null
        val policy = localPinPolicy(networkClass)
        val minConfidence = policy.minConfidenceFor(isForeground)
        if (preference.confidence < minConfidence) return null
        if (nowMs - preference.updatedAtMs > policy.maxAgeMs) return null
        if (preference.cooldownUntilMs?.let { nowMs < it } == true) return null
        return preference.transport
    }

    private fun recordTransportSuccess(
        transport: String,
        networkClass: String,
        networkFingerprint: String = currentNetworkFingerprint(),
    ) {
        val normalized = normalizeTransportName(transport) ?: return
        if (networkClass == "unknown" || networkFingerprint == "unknown") return
        val nowMs = System.currentTimeMillis()
        val existing = loadLocalTransportPreference(networkFingerprint)
        val confidence = if (existing?.transport == normalized) {
            (existing.confidence + 1).coerceAtMost(LOCAL_PIN_MAX_CONFIDENCE)
        } else {
            LOCAL_PIN_INIT_CONFIDENCE
        }
        saveLocalTransportPreference(
            LocalTransportPreference(
                networkFingerprint = networkFingerprint,
                networkClass = networkClass,
                transport = normalized,
                confidence = confidence,
                updatedAtMs = nowMs,
                cooldownUntilMs = null,
            )
        )
    }

    private fun recordPinnedTransportFailure(reason: String, detail: String) {
        val pinnedTransport = lastLocalPinnedTransport ?: return
        val pinnedNetworkFingerprint = lastLocalPinnedNetworkFingerprint ?: return
        val pinnedNetworkClass = lastLocalPinnedNetworkClass ?: return
        val normalized = normalizeTransportName(pinnedTransport) ?: return
        val existing = loadLocalTransportPreference(pinnedNetworkFingerprint) ?: return
        if (existing.transport != normalized) return
        val policy = localPinPolicy(pinnedNetworkClass)
        val nowMs = System.currentTimeMillis()
        val reducedConfidence = (existing.confidence - 2).coerceAtLeast(0)
        val minConfidence = policy.minConfidenceFor(foregroundActive)
        val cooldownUntilMs = if (reducedConfidence < minConfidence) {
            nowMs + policy.failureCooldownMs
        } else {
            existing.cooldownUntilMs
        }
        saveLocalTransportPreference(
            existing.copy(
                confidence = reducedConfidence,
                updatedAtMs = nowMs,
                cooldownUntilMs = cooldownUntilMs,
            )
        )
        io.ethan.pushgo.util.SilentSink.w(
            TAG,
            "local preference degraded transport=$normalized fingerprint=$pinnedNetworkFingerprint class=$pinnedNetworkClass confidence=$reducedConfidence reason=$reason detail=${detail.take(120)}",
        )
    }

    private fun normalizeTransportName(raw: String?): String? {
        return raw
            ?.trim()
            ?.lowercase()
            ?.takeIf { it == "quic" || it == "tcp" || it == "wss" }
    }

    private fun localPinPolicy(networkClass: String): LocalPinPolicy {
        return when (networkClass) {
            "cellular" -> LocalPinPolicy(
                minConfidenceForeground = LOCAL_PIN_CELLULAR_MIN_CONFIDENCE_FG,
                minConfidenceBackground = LOCAL_PIN_CELLULAR_MIN_CONFIDENCE_BG,
                maxAgeMs = LOCAL_PIN_CELLULAR_MAX_AGE_MS,
                failureCooldownMs = LOCAL_PIN_CELLULAR_FAILURE_COOLDOWN_MS,
                ttlForegroundMs = LOCAL_PIN_CELLULAR_TTL_FG_MS,
                ttlBackgroundMs = LOCAL_PIN_CELLULAR_TTL_BG_MS,
            )
            "wifi", "ethernet" -> LocalPinPolicy(
                minConfidenceForeground = LOCAL_PIN_DEFAULT_MIN_CONFIDENCE_FG,
                minConfidenceBackground = LOCAL_PIN_DEFAULT_MIN_CONFIDENCE_BG,
                maxAgeMs = LOCAL_PIN_DEFAULT_MAX_AGE_MS,
                failureCooldownMs = LOCAL_PIN_DEFAULT_FAILURE_COOLDOWN_MS,
                ttlForegroundMs = LOCAL_PIN_DEFAULT_TTL_FG_MS,
                ttlBackgroundMs = LOCAL_PIN_DEFAULT_TTL_BG_MS,
            )
            else -> LocalPinPolicy(
                minConfidenceForeground = LOCAL_PIN_OTHER_MIN_CONFIDENCE_FG,
                minConfidenceBackground = LOCAL_PIN_OTHER_MIN_CONFIDENCE_BG,
                maxAgeMs = LOCAL_PIN_OTHER_MAX_AGE_MS,
                failureCooldownMs = LOCAL_PIN_OTHER_FAILURE_COOLDOWN_MS,
                ttlForegroundMs = LOCAL_PIN_OTHER_TTL_FG_MS,
                ttlBackgroundMs = LOCAL_PIN_OTHER_TTL_BG_MS,
            )
        }
    }

    private fun networkSwitchPinCooldownMs(networkClass: String): Long {
        return when (networkClass) {
            "cellular" -> NETWORK_SWITCH_PIN_COOLDOWN_CELLULAR_MS
            "wifi", "ethernet" -> NETWORK_SWITCH_PIN_COOLDOWN_DEFAULT_MS
            else -> NETWORK_SWITCH_PIN_COOLDOWN_OTHER_MS
        }
    }

    private fun loadLocalTransportPreference(networkFingerprint: String): LocalTransportPreference? {
        val raw = prefs.getString(KEY_LOCAL_TRANSPORT_PREF, null) ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val node = root.optJSONObject(networkFingerprint) ?: return null
        val transport = normalizeTransportName(node.optString("transport")) ?: return null
        val storedFingerprint = node.optString("network_fingerprint").trim().ifEmpty { networkFingerprint }
        val networkClass = node.optString("network_class").trim().ifEmpty { "unknown" }
        val confidence = node.optInt("confidence", 0).coerceIn(0, LOCAL_PIN_MAX_CONFIDENCE)
        val updatedAtMs = node.optLong("updated_at_ms", 0L).coerceAtLeast(0L)
        val cooldownUntilMs = node.optLong("cooldown_until_ms", 0L).takeIf { it > 0L }
        return LocalTransportPreference(
            networkFingerprint = storedFingerprint,
            networkClass = networkClass,
            transport = transport,
            confidence = confidence,
            updatedAtMs = updatedAtMs,
            cooldownUntilMs = cooldownUntilMs,
        )
    }

    private fun saveLocalTransportPreference(preference: LocalTransportPreference) {
        val root = runCatching {
            JSONObject(prefs.getString(KEY_LOCAL_TRANSPORT_PREF, null) ?: "{}")
        }.getOrElse { JSONObject() }
        root.put(
            preference.networkFingerprint,
            JSONObject().apply {
                put("network_fingerprint", preference.networkFingerprint)
                put("network_class", preference.networkClass)
                put("transport", preference.transport)
                put("confidence", preference.confidence)
                put("updated_at_ms", preference.updatedAtMs)
                put("cooldown_until_ms", preference.cooldownUntilMs ?: JSONObject.NULL)
            }
        )
        prefs.edit().putString(KEY_LOCAL_TRANSPORT_PREF, root.toString()).apply()
    }

    private fun loadLocalFailureBucketStats(): LocalFailureBucketStats {
        val raw = prefs.getString(KEY_LOCAL_FAILURE_BUCKETS, null) ?: return LocalFailureBucketStats()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return LocalFailureBucketStats()
        return LocalFailureBucketStats(
            transportFailures = root.optInt("transport_failures", 0).coerceIn(0, 9_999),
            authFailures = root.optInt("auth_failures", 0).coerceIn(0, 9_999),
            routeFailures = root.optInt("route_failures", 0).coerceIn(0, 9_999),
            updatedAtMs = root.optLong("updated_at_ms", 0L).coerceAtLeast(0L),
        )
    }

    private fun saveLocalFailureBucketStats(stats: LocalFailureBucketStats) {
        val root = JSONObject().apply {
            put("transport_failures", stats.transportFailures)
            put("auth_failures", stats.authFailures)
            put("route_failures", stats.routeFailures)
            put("updated_at_ms", stats.updatedAtMs)
        }
        prefs.edit().putString(KEY_LOCAL_FAILURE_BUCKETS, root.toString()).apply()
    }

    private fun maybeRepairPrivateRoute(reason: String) {
        if (fcmAvailable) return
        val lowered = reason.lowercase()
        if (!lowered.contains("device not on private channel")) return
        val now = System.currentTimeMillis()
        if (now - lastRouteRepairAtMs < PRIVATE_ROUTE_REPAIR_INTERVAL_MS) {
            return
        }
        lastRouteRepairAtMs = now
        scope.launch {
            runCatching {
                val (baseUrl, token) = channelRepository.loadGatewayConfig()
                withDeviceStateRetry(baseUrl, token) { state ->
                    ensurePrivateRoute(baseUrl, token, state, force = true)
                }
            }.onFailure {
                io.ethan.pushgo.util.SilentSink.w(TAG, "private route repair failed: ${it.message}")
            }
        }
    }

    private suspend fun ensureDeviceState(
        baseUrl: String,
        token: String?,
        forceRefresh: Boolean = false,
    ): DeviceState {
        val existing = loadState()
        if (!forceRefresh && existing != null && existing.deviceKey.isNotBlank()) {
            return existing
        }
        val register = privatePost(baseUrl, token, "/device/register", JSONObject().apply {
            put("platform", "android")
            put("channel_type", "private")
            if (!existing?.deviceKey.isNullOrBlank()) {
                put("device_key", existing.deviceKey)
            }
        })
        val deviceKey = register.optString("device_key").trim()
        if (deviceKey.isEmpty()) {
            throw ChannelSubscriptionException("gateway response missing device_key")
        }
        val state = DeviceState(
            deviceKey = deviceKey,
            issuedAt = register.optLong("issued_at", Instant.now().epochSecond),
            subscribedChannels = existing?.subscribedChannels.orEmpty(),
            resumeToken = existing?.resumeToken,
            lastAckedSeq = existing?.lastAckedSeq ?: 0L,
        )
        saveState(state)
        // Device identity changed/refreshed: force route upsert on next private operation.
        lastRouteEnsureAtMs = 0L
        lastRouteEnsureFingerprint = null
        return state
    }

    private suspend fun ensurePrivateRoute(
        baseUrl: String,
        token: String?,
        state: DeviceState,
        force: Boolean = false,
    ) {
        val routeFingerprint = buildRouteEnsureFingerprint(
            baseUrl = baseUrl,
            token = token,
            deviceKey = state.deviceKey,
        )
        val now = System.currentTimeMillis()
        if (!force
            && lastRouteEnsureFingerprint == routeFingerprint
            && now - lastRouteEnsureAtMs < 300_000L
        ) {
            return
        }
        var createdDeferred: CompletableDeferred<Unit>? = null
        val existingInFlight = routeEnsureMutex.withLock {
            val current = routeEnsureInFlight
            if (current != null && routeEnsureInFlightFingerprint == routeFingerprint) {
                return@withLock current
            }
            if (!force
                && lastRouteEnsureFingerprint == routeFingerprint
                && System.currentTimeMillis() - lastRouteEnsureAtMs < 300_000L
            ) {
                return@withLock null
            }
            CompletableDeferred<Unit>().also { deferred ->
                routeEnsureInFlight = deferred
                routeEnsureInFlightFingerprint = routeFingerprint
                createdDeferred = deferred
            }
            null
        }
        if (existingInFlight != null) {
            existingInFlight.await()
            return
        }
        val owner = createdDeferred ?: return
        try {
            privatePost(baseUrl, token, "/device/register", JSONObject().apply {
                put("device_key", state.deviceKey)
                put("platform", "android")
                put("channel_type", "private")
            })
            lastRouteEnsureAtMs = System.currentTimeMillis()
            lastRouteEnsureFingerprint = routeFingerprint
            owner.complete(Unit)
        } catch (error: Throwable) {
            owner.completeExceptionally(error)
            throw error
        } finally {
            routeEnsureMutex.withLock {
                if (routeEnsureInFlight === owner) {
                    routeEnsureInFlight = null
                    routeEnsureInFlightFingerprint = null
                }
            }
        }
    }

    private suspend fun <T> privateWithRouteRetry(
        baseUrl: String,
        token: String?,
        state: DeviceState,
        operation: suspend () -> T,
    ): T {
        return try {
            operation()
        } catch (error: ChannelSubscriptionException) {
            if (!isProviderRouteError(error)) {
                throw error
            }
            ensurePrivateRoute(baseUrl, token, state, force = true)
            operation()
        }
    }

    private suspend fun <T> withDeviceStateRetry(
        baseUrl: String,
        token: String?,
        block: suspend (DeviceState) -> T,
    ): T {
        var state = ensureDeviceState(baseUrl, token)
        currentDeviceState = state
        return try {
            block(state)
        } catch (error: Throwable) {
            if (!isDeviceKeyMissing(error)) {
                throw error
            }
            io.ethan.pushgo.util.SilentSink.w(TAG, "gateway device_key missing, re-registering device")
            state = ensureDeviceState(baseUrl, token, forceRefresh = true)
            currentDeviceState = state
            block(state)
        }
    }

    private fun isDeviceKeyMissing(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("device_key_not_found")
            || message.contains("device_key not found")
            || message.contains("device key not found")
    }

    private fun isProviderRouteError(error: ChannelSubscriptionException): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("device route is provider")
            || message.contains("provider_token required")
    }

    private suspend fun subscribeAll(baseUrl: String, token: String?, state: DeviceState) {
        val credentials = channelRepository.loadActiveCredentials()
        val currentChannelIds = credentials
            .map { it.first.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val previousChannelIds = loadState()?.subscribedChannels.orEmpty().toSet()
        val signature = credentials
            .sortedBy { it.first }
            .joinToString(",") { "${it.first}|${it.second}" }
            .let { raw -> MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
        val now = System.currentTimeMillis()
        if (signature == lastSubscribedSignature && now - lastSubscribeAtMs < 300_000L) {
            return
        }
        for (removedId in previousChannelIds.subtract(currentChannelIds)) {
            privatePost(baseUrl, token, "/channel/unsubscribe", JSONObject().apply {
                put("device_key", state.deviceKey)
                put("channel_id", removedId)
            })
        }
        val staleChannels = mutableListOf<String>()
        val passwordMismatchChannels = mutableListOf<String>()
        if (credentials.isNotEmpty()) {
            val syncResponse = privatePost(baseUrl, token, "/channel/sync", JSONObject().apply {
                put("device_key", state.deviceKey)
                put("channels", org.json.JSONArray().apply {
                    credentials.forEach { (channelId, password) ->
                        put(JSONObject().apply {
                            put("channel_id", channelId)
                            put("password", password)
                        })
                    }
                })
            })
            val channelResults = syncResponse.optJSONArray("channels")
            if (channelResults != null) {
                for (index in 0 until channelResults.length()) {
                    val item = channelResults.optJSONObject(index) ?: continue
                    if (item.optBoolean("subscribed", false)) continue
                    val channelId = item.optString("channel_id", "").trim()
                    if (channelId.isEmpty()) continue
                    when (item.optString("error_code", "").trim().lowercase()) {
                        "channel_not_found" -> staleChannels += channelId
                        "password_mismatch" -> passwordMismatchChannels += channelId
                    }
                }
            }
        }
        val invalidChannels = (staleChannels + passwordMismatchChannels).distinct()
        invalidChannels.forEach { channelId ->
            runCatching {
                channelRepository.softDeleteLocalSubscription(
                    rawChannelId = channelId,
                    deleteLocalMessages = false,
                )
            }.onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(TAG, "soft delete invalid subscription failed channelId=$channelId error=${error.message}")
            }
        }
        val syncedChannelIds = currentChannelIds.subtract(invalidChannels.toSet())
        loadState()?.let { existing ->
            saveState(existing.copy(subscribedChannels = syncedChannelIds.sorted()))
        }
        if (passwordMismatchChannels.isNotEmpty()) {
            io.ethan.pushgo.util.SilentSink.w(
                TAG,
                "sync found password mismatch channels=${passwordMismatchChannels.joinToString(",")}",
            )
        }
        lastSubscribedSignature = signature
        lastSubscribeAtMs = now
    }

    private suspend fun privatePost(
        baseUrl: String,
        token: String?,
        path: String,
        payload: JSONObject?,
    ): JSONObject {
        val endpoint = "${baseUrl.trim().removeSuffix("/")}$path"
        var attempt = 1
        while (true) {
            try {
                val data = privatePostOnce(endpoint, token, payload)
                persistDeviceKeyFromGatewayResponse(data)
                return data
            } catch (error: ChannelSubscriptionException) {
                if (!isRetryablePrivateError(error) || attempt >= PRIVATE_HTTP_MAX_ATTEMPTS) {
                    throw error
                }
                val backoffMs = (PRIVATE_HTTP_RETRY_BASE_MS * attempt) + Random.nextLong(120, 420)
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "private request retry endpoint=$endpoint attempt=$attempt/$PRIVATE_HTTP_MAX_ATTEMPTS backoff=${backoffMs}ms error=${error.message}",
                )
                delay(backoffMs)
                attempt += 1
            }
        }
    }

    private suspend fun privateGet(
        baseUrl: String,
        token: String?,
        path: String,
    ): JSONObject {
        val endpoint = "${baseUrl.trim().removeSuffix("/")}$path"
        var attempt = 1
        while (true) {
            try {
                return privateGetOnce(endpoint, token)
            } catch (error: ChannelSubscriptionException) {
                if (!isRetryablePrivateError(error) || attempt >= PRIVATE_HTTP_MAX_ATTEMPTS) {
                    throw error
                }
                val backoffMs = (PRIVATE_HTTP_RETRY_BASE_MS * attempt) + Random.nextLong(120, 420)
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "private request retry endpoint=$endpoint attempt=$attempt/$PRIVATE_HTTP_MAX_ATTEMPTS backoff=${backoffMs}ms error=${error.message}",
                )
                delay(backoffMs)
                attempt += 1
            }
        }
    }

    private fun persistDeviceKeyFromGatewayResponse(data: JSONObject) {
        val resolvedDeviceKey = data.optString("device_key", "").trim()
        if (resolvedDeviceKey.isEmpty()) {
            return
        }
        val existing = loadState()
        if (existing?.deviceKey == resolvedDeviceKey) {
            return
        }
        val nextState = if (existing != null) {
            existing.copy(deviceKey = resolvedDeviceKey)
        } else {
            DeviceState(
                deviceKey = resolvedDeviceKey,
                issuedAt = Instant.now().epochSecond,
                subscribedChannels = emptyList(),
                resumeToken = null,
                lastAckedSeq = 0L,
            )
        }
        saveState(nextState)
        currentDeviceState = nextState
        // Force route refresh with the new identity when needed.
        lastRouteEnsureAtMs = 0L
        lastRouteEnsureFingerprint = null
        io.ethan.pushgo.util.SilentSink.i(
            TAG,
            "private state device_key refreshed from gateway response",
        )
    }

    private suspend fun privatePostOnce(
        endpoint: String,
        token: String?,
        payload: JSONObject?,
    ): JSONObject = privateRequestOnce(endpoint, token, "POST", payload)

    private suspend fun privateGetOnce(
        endpoint: String,
        token: String?,
    ): JSONObject = privateRequestOnce(endpoint, token, "GET", null)

    private suspend fun privateRequestOnce(
        endpoint: String,
        token: String?,
        method: String,
        payload: JSONObject?,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer ${token.trim()}")
            }
            connectTimeout = PRIVATE_HTTP_CONNECT_TIMEOUT_MS
            readTimeout = PRIVATE_HTTP_READ_TIMEOUT_MS
        }
        return@withContext try {
            val bytes = payload?.toString()?.toByteArray(Charsets.UTF_8)
            connection.doOutput = bytes != null
            if (bytes != null) {
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it)).readText() } ?: ""
            val envelope = try {
                JSONObject(body)
            } catch (ex: JSONException) {
                val compactBody = body.replace(Regex("\\s+"), " ").trim().take(160)
                throw ChannelSubscriptionException(
                    "private http $code for $endpoint, non-json body=${if (compactBody.isBlank()) "<empty>" else compactBody}",
                )
            }
            if (!envelope.optBoolean("success", false)) {
                val detail = envelope.opt("error")?.toString()?.trim().orEmpty()
                val errorCode = envelope.optString("error_code", "").trim().lowercase()
                val message = if (detail.isNotEmpty()) {
                    detail
                } else {
                    "private request failed (http $code, endpoint=$endpoint)"
                }
                if (errorCode.isEmpty()) {
                    throw ChannelSubscriptionException(message)
                }
                throw ChannelSubscriptionException("$errorCode: $message")
            }
            envelope.optJSONObject("data") ?: JSONObject()
        } catch (error: Throwable) {
            if (error is ChannelSubscriptionException) {
                io.ethan.pushgo.util.SilentSink.w(TAG, "private request failed endpoint=$endpoint error=${error.message}")
                throw error
            }
            val detail = error.message?.trim().takeUnless { it.isNullOrEmpty() }
                ?: error::class.java.simpleName
            io.ethan.pushgo.util.SilentSink.e(TAG, "private request transport failure endpoint=$endpoint error=$detail", error)
            throw ChannelSubscriptionException("private request failed: $detail")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun resolvePrivateTransportProfile(
        baseUrl: String,
        token: String?,
    ): PrivateTransportProfile {
        val forceRefresh = forceProfileRefreshOnce
        if (forceRefresh) {
            forceProfileRefreshOnce = false
        }
        val cached = loadPrivateTransportProfileCache(baseUrl)
        if (!forceRefresh && cached != null && isPrivateTransportProfileCacheFresh(cached.fetchedAtMs)) {
            return cached.profile
        }
        return try {
            val gatewayProfile = fetchGatewayProfileSnapshot(baseUrl, token)
            if (!gatewayProfile.privateChannelEnabled) {
                throw ChannelSubscriptionException("private_channel_disabled: gateway private channel is disabled")
            }
            val profile = gatewayProfile.transport ?: defaultPrivateTransportProfile()
            savePrivateTransportProfileCache(baseUrl, profile)
            profile
        } catch (error: ChannelSubscriptionException) {
            if (isGatewayPrivateDisabledError(error)) {
                throw error
            }
            cached?.profile?.also {
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "private profile fetch failed, using cached profile error=${error.message}",
                )
            } ?: run {
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "private profile fetch failed, using built-in defaults error=${error.message}",
                )
                defaultPrivateTransportProfile()
            }
        }
    }

    private suspend fun fetchGatewayProfileSnapshot(
        baseUrl: String,
        token: String?,
    ): GatewayProfileSnapshot {
        val data = privateGet(baseUrl, token, GATEWAY_PROFILE_ENDPOINT)
        return parseGatewayProfileSnapshot(data)
    }

    private fun parseGatewayProfileSnapshot(data: JSONObject): GatewayProfileSnapshot {
        val privateEnabled = data.optBoolean(
            "private_channel_enabled",
            data.optBoolean("private_enabled", false),
        )
        val transport = data.optJSONObject("transport")
            ?.let { transport ->
                parsePrivateTransportProfile(transport)
            }
        return GatewayProfileSnapshot(
            privateChannelEnabled = privateEnabled,
            transport = transport,
        )
    }

    private fun parsePrivateTransportProfile(transport: JSONObject): PrivateTransportProfile {
        val defaultProfile = defaultPrivateTransportProfile()
        return PrivateTransportProfile(
            quicEnabled = transport.optBoolean("quic_enabled", true),
            quicPort = transport.optInt("quic_port", defaultProfile.quicPort)
                .coerceIn(1, 65535),
            tcpEnabled = transport.optBoolean("tcp_enabled", true),
            tcpPort = transport.optInt("tcp_port", defaultProfile.tcpPort)
                .coerceIn(1, 65535),
            wssEnabled = transport.optBoolean("wss_enabled", true),
            wssPort = transport.optInt("wss_port", defaultProfile.wssPort)
                .coerceIn(1, 65535),
            wssPath = transport.optString("wss_path").trim()
                .ifEmpty { defaultProfile.wssPath },
            wsSubprotocol = transport.optString("ws_subprotocol").trim()
                .ifEmpty { defaultProfile.wsSubprotocol },
        )
    }

    private fun loadPrivateTransportProfileCache(baseUrl: String): CachedPrivateTransportProfile? {
        val raw = prefs.getString(KEY_PROFILE_CACHE, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val cachedBaseUrl = obj.optString("gateway_base_url").trim()
            if (cachedBaseUrl != normalizedGatewayBaseUrl(baseUrl)) {
                return null
            }
            val transport = obj.optJSONObject("transport") ?: return null
            CachedPrivateTransportProfile(
                profile = parsePrivateTransportProfile(transport),
                fetchedAtMs = obj.optLong("fetched_at_ms", 0L).coerceAtLeast(0L),
            )
        }.getOrNull()
    }

    private fun savePrivateTransportProfileCache(baseUrl: String, profile: PrivateTransportProfile) {
        val obj = JSONObject().apply {
            put("gateway_base_url", normalizedGatewayBaseUrl(baseUrl))
            put("fetched_at_ms", System.currentTimeMillis())
            put(
                "transport",
                JSONObject().apply {
                    put("quic_enabled", profile.quicEnabled)
                    put("quic_port", profile.quicPort)
                    put("tcp_enabled", profile.tcpEnabled)
                    put("tcp_port", profile.tcpPort)
                    put("wss_enabled", profile.wssEnabled)
                    put("wss_port", profile.wssPort)
                    put("wss_path", profile.wssPath)
                    put("ws_subprotocol", profile.wsSubprotocol)
                }
            )
        }
        prefs.edit().putString(KEY_PROFILE_CACHE, obj.toString()).apply()
    }

    private fun isPrivateTransportProfileCacheFresh(fetchedAtMs: Long): Boolean {
        if (fetchedAtMs <= 0L) return false
        return System.currentTimeMillis() - fetchedAtMs <= PRIVATE_PROFILE_REFRESH_INTERVAL_MS
    }

    private fun normalizedGatewayBaseUrl(baseUrl: String): String {
        return baseUrl.trim().removeSuffix("/")
    }

    private fun isRetryablePrivateError(error: ChannelSubscriptionException): Boolean {
        val message = error.message?.lowercase().orEmpty()
        if (message.isBlank()) return false
        return message.contains("timeout")
            || message.contains("socket")
            || message.contains("connect")
            || message.contains("connection reset")
            || message.contains("broken pipe")
    }

    private fun isGatewayPrivateDisabledError(error: ChannelSubscriptionException): Boolean {
        val message = error.message?.lowercase().orEmpty()
        if (message.isBlank()) return false
        return message.contains("private_channel_disabled")
            || message.contains("private channel is disabled")
            || message.contains("private_channel_enabled=false")
    }

    private fun isAuthFailureError(message: String): Boolean {
        return message.contains("auth failed")
            || message.contains("authentication failed")
            || message.contains("unauthorized")
            || message.contains("gateway token invalid")
    }

    private fun isRouteRecoverableError(message: String): Boolean {
        return message.contains("device_key not found")
            || message.contains("device key not found")
            || message.contains("device not on private channel")
            || message.contains("device route is provider")
            || message.contains("provider_token required")
    }

    private fun isAuthRouteRecoverableError(message: String): Boolean {
        return isAuthFailureError(message) || isRouteRecoverableError(message)
    }

    private fun maybeScheduleAuthRouteSelfHeal(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        if (!isAuthRouteRecoverableError(message)) {
            return false
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastAuthRouteHealAtMs < AUTH_ROUTE_HEAL_MIN_INTERVAL_MS) {
            return false
        }
        lastAuthRouteHealAtMs = nowMs
        failureStreak = (failureStreak + 1).coerceIn(1, 4)
        val baseDelayMs = 1_000L * (1L shl (failureStreak - 1))
        val jitterMs = Random.nextLong(100, 500)
        val delayMs = (baseDelayMs + jitterMs).coerceAtMost(15_000L)
        nextAllowedPullAtMs = nowMs + delayMs
        saveTransportStatus(
            route = "private",
            transport = transportStatusState.value.transport,
            stage = "backoff",
            detail = "auth_route_self_heal next_retry=${delayMs}ms error=${error.message.orEmpty().take(120)}",
        )
        scope.launch {
            runCatching {
                val (baseUrl, token) = channelRepository.loadGatewayConfig()
                val refreshed = ensureDeviceState(baseUrl, token, forceRefresh = true)
                currentDeviceState = refreshed
                lastRouteEnsureAtMs = 0L
                lastRouteEnsureFingerprint = null
                ensurePrivateRoute(baseUrl, token, refreshed, force = true)
                if (!requestActiveSessionForceReconnect("auth_route_self_heal")) {
                    stopActiveSession("auth_route_self_heal")
                    restartLoop()
                }
            }.onFailure { healError ->
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "auth/route self heal failed error=${healError.message}",
                )
            }
        }
        io.ethan.pushgo.util.SilentSink.w(
            TAG,
            "auth/route self heal scheduled delay=${delayMs}ms error=${error.message}",
        )
        return true
    }

    private fun recordLocalFailureBucket(stage: String, error: Throwable) {
        val message = error.message?.lowercase().orEmpty()
        val nowMs = System.currentTimeMillis()
        val next = when {
            isRouteRecoverableError(message) -> {
                localFailureBucketStats.copy(
                    routeFailures = (localFailureBucketStats.routeFailures + 1).coerceAtMost(9_999),
                    updatedAtMs = nowMs,
                )
            }
            isAuthFailureError(message) -> {
                localFailureBucketStats.copy(
                    authFailures = (localFailureBucketStats.authFailures + 1).coerceAtMost(9_999),
                    updatedAtMs = nowMs,
                )
            }
            else -> {
                localFailureBucketStats.copy(
                    transportFailures = (localFailureBucketStats.transportFailures + 1).coerceAtMost(9_999),
                    updatedAtMs = nowMs,
                )
            }
        }
        localFailureBucketStats = next
        saveLocalFailureBucketStats(next)
        connectionSnapshotState.value = buildConnectionSnapshot()
        val fingerprint = "${next.transportFailures}|${next.authFailures}|${next.routeFailures}"
        if (fingerprint != lastFailureBucketFingerprint) {
            lastFailureBucketFingerprint = fingerprint
            io.ethan.pushgo.util.SilentSink.i(
                TAG,
                "failure_bucket stage=$stage transport=${next.transportFailures} auth=${next.authFailures} route=${next.routeFailures}",
            )
        }
    }

    private fun onSuccess() {
        failureStreak = 0
        nextAllowedPullAtMs = 0L
        consecutiveStreamFailureCount = 0
    }

    private fun maybeForceForegroundRecovery() {
        if (!runtimeConfigured || fcmAvailable) return
        val stage = transportStatusState.value.stage
        val shouldRecover = stage == "backoff"
            || stage == "offline_wait"
            || stage == "reconnecting"
            || stage == "recovering"
            || stage == "fgs_lost"
            || (stage == "idle" && activeSessionHandle == 0L)
            || loopJob == null
        if (!shouldRecover) return
        failureStreak = 0
        nextAllowedPullAtMs = 0L
        saveTransportStatus(
            route = "private",
            transport = "none",
            stage = "reconnecting",
            detail = "app foregrounded, retrying private stream immediately",
        )
        if (!requestActiveSessionForceReconnect("foreground_recovery")) {
            stopActiveSession("foreground_recovery")
            triggerProviderWakeupRecovery()
        }
    }

    private fun onFailure(stage: String, error: Throwable) {
        maybeForceProfileRefreshForConsecutiveFailures(stage)
        if (error is ChannelSubscriptionException && isGatewayPrivateDisabledError(error)) {
            failureStreak = 0
            nextAllowedPullAtMs = System.currentTimeMillis() + 120_000L
            saveTransportStatus(
                route = "private",
                transport = "none",
                stage = "gateway_private_disabled",
                detail = "gateway private channel is disabled; switch to FCM",
            )
            io.ethan.pushgo.util.SilentSink.w(TAG, "stage=$stage gateway private channel disabled")
            return
        }
        recordLocalFailureBucket(stage, error)
        if (stage == "stream_session" && maybeScheduleAuthRouteSelfHeal(error)) {
            return
        }
        PushGoAutomation.recordRuntimeError(
            source = "private.$stage",
            error = error,
            category = "private",
        )
        if (stage.startsWith("stream_")) {
            // Phase C: keep reconnect authority inside warp-link runtime, app loop only gates on network/manual triggers.
            failureStreak = 0
            nextAllowedPullAtMs = 0L
            saveTransportStatus(
                route = if (fcmAvailable) "provider" else "private",
                transport = transportStatusState.value.transport,
                stage = "reconnecting",
                detail = "stage=$stage reconnect delegated to warp-link runtime error=${error.message.orEmpty().take(120)}",
            )
            io.ethan.pushgo.util.SilentSink.w(TAG, "stage=$stage delegated reconnect error=${error.message}")
            return
        }
        if (isQuickRecoverableFailure(stage, error)) {
            failureStreak = (failureStreak + 1).coerceIn(1, 3)
            val jitterMs = Random.nextLong(120, 420)
            val baseDelayMs = when (failureStreak) {
                1 -> 800L
                2 -> 1_600L
                else -> 3_000L
            }
            val delayMs = baseDelayMs + jitterMs
            nextAllowedPullAtMs = System.currentTimeMillis() + delayMs
            saveTransportStatus(
                route = if (fcmAvailable) "provider" else "private",
                transport = transportStatusState.value.transport,
                stage = "backoff",
                detail = "fast_recovery stage=$stage next_retry=${delayMs}ms error=${error.message.orEmpty().take(120)}",
            )
            io.ethan.pushgo.util.SilentSink.w(
                TAG,
                "stage=$stage failureStreak=$failureStreak fastRecovery=${delayMs}ms error=${error.message}",
            )
            return
        }
        failureStreak = (failureStreak + 1).coerceAtMost(8)
        val exp = (failureStreak - 1).coerceAtLeast(0)
        val base = 2 * (1 shl exp)
        val delaySec = minOf(welcomeMaxBackoffSecs, base.coerceAtLeast(2))
        val jitterMs = Random.nextLong(0, 1000)
        nextAllowedPullAtMs = System.currentTimeMillis() + delaySec * 1000L + jitterMs
        val transport = when {
            stage.contains("quic", ignoreCase = true) -> "quic"
            stage.contains("wss", ignoreCase = true) || stage.contains("ws", ignoreCase = true) -> "wss"
            stage.contains("tcp", ignoreCase = true) -> "tcp"
            else -> "none"
        }
        saveTransportStatus(
            route = if (fcmAvailable) "provider" else "private",
            transport = transport,
            stage = "backoff",
            detail = "stage=$stage next_retry=${delaySec}s+${jitterMs}ms error=${error.message.orEmpty().take(120)}",
        )
        io.ethan.pushgo.util.SilentSink.w(TAG, "stage=$stage failureStreak=$failureStreak backoff=${delaySec}s error=${error.message}")
    }

    private fun isQuickRecoverableFailure(stage: String, error: Throwable): Boolean {
        if (!networkAvailable) return false
        if (stage != "stream_session") return false
        val message = error.message?.lowercase().orEmpty()
        if (!message.contains("after welcome")) return false
        if (message.contains("unauthorized")
            || message.contains("authentication")
            || message.contains("rate limited")
            || message.contains("device not on private channel")
        ) {
            return false
        }
        return true
    }

    fun readTransportStatus(): TransportStatus {
        return transportStatusState.value
    }

    fun summarizeTransportStatus(privateModeEnabled: Boolean): String {
        return summarizeConnectionStatus(readConnectionSnapshot(), privateModeEnabled)
    }

    fun summarizeTransportStatus(status: TransportStatus, privateModeEnabled: Boolean): String {
        val snapshot = readConnectionSnapshot().copy(transportStatus = status)
        return summarizeConnectionStatus(snapshot, privateModeEnabled)
    }

    fun readConnectionSnapshot(): ConnectionSnapshot {
        return connectionSnapshotState.value
    }

    fun requestInSessionProbe(): Boolean {
        if (fcmAvailable) return false
        val handle = activeSessionHandle
        if (handle <= 0L) return false
        return WarpLinkNativeBridge.sessionRequestProbe(handle)
    }

    fun requestForceReconnect(reason: String = "manual"): Boolean {
        if (fcmAvailable) return false
        forceProfileRefreshOnce = true
        lastForcedProfileRefreshAtMs = System.currentTimeMillis()
        val triggered = requestActiveSessionForceReconnect("manual:$reason")
        if (!triggered) {
            saveTransportStatus(
                route = "private",
                transport = "none",
                stage = "reconnecting",
                detail = "manual reconnect requested: $reason",
            )
            restartLoop()
        }
        return true
    }

    private fun maybeForceProfileRefreshForConsecutiveFailures(stage: String) {
        if (!stage.startsWith("stream_")) {
            return
        }
        consecutiveStreamFailureCount = (consecutiveStreamFailureCount + 1).coerceAtMost(256)
        if (consecutiveStreamFailureCount < PROFILE_FORCE_REFRESH_FAILURE_THRESHOLD) {
            return
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastForcedProfileRefreshAtMs < PROFILE_FORCE_REFRESH_FAILURE_COOLDOWN_MS) {
            return
        }
        forceProfileRefreshOnce = true
        lastForcedProfileRefreshAtMs = nowMs
        consecutiveStreamFailureCount = 0
        io.ethan.pushgo.util.SilentSink.i(
            TAG,
            "profile refresh forced after repeated stream failures",
        )
    }

    private fun maybeForceProfileRefreshForAppVersion() {
        val currentVersion = BuildConfig.VERSION_NAME.trim().ifEmpty { return }
        val lastVersion = prefs.getString(KEY_LAST_PRIVATE_PROFILE_REFRESH_VERSION, null)?.trim()
        if (lastVersion == currentVersion) {
            return
        }
        forceProfileRefreshOnce = true
        prefs.edit().putString(KEY_LAST_PRIVATE_PROFILE_REFRESH_VERSION, currentVersion).apply()
    }

    fun pinTransport(transport: String, ttlMs: Long = 0L): Boolean {
        if (fcmAvailable) return false
        val normalized = transport.trim().lowercase()
        if (normalized != "quic" && normalized != "tcp" && normalized != "wss") {
            return false
        }
        val handle = activeSessionHandle
        if (handle <= 0L) return true
        return WarpLinkNativeBridge.sessionPinTransport(handle, normalized, ttlMs)
    }

    fun clearPinnedTransport(): Boolean {
        if (fcmAvailable) return false
        val handle = activeSessionHandle
        if (handle <= 0L) return true
        return WarpLinkNativeBridge.sessionClearPin(handle)
    }

    fun summarizeConnectionStatus(snapshot: ConnectionSnapshot, privateModeEnabled: Boolean): String {
        if (!privateModeEnabled) {
            return appContext.getString(R.string.private_transport_status_disconnected)
        }
        return PrivateTransportStatusPresenter.summarize(
            context = appContext,
            privateModeEnabled = privateModeEnabled,
            route = snapshot.transportStatus.route,
            transport = snapshot.transportStatus.transport,
            stage = snapshot.transportStatus.stage,
            networkAvailable = snapshot.networkAvailable,
            keepaliveState = snapshot.keepaliveState,
        )
    }

    fun resetForAutomation() {
        loopJob?.cancel()
        loopJob = null
        currentDeviceState = null
        lastSubscribedSignature = null
        lastSubscribeAtMs = 0L
        lastRouteEnsureAtMs = 0L
        lastRouteEnsureFingerprint = null
        lastRouteRepairAtMs = 0L
        nextAllowedPullAtMs = 0L
        failureStreak = 0
        keepaliveLossSticky = false
        keepaliveState = KeepaliveState.NOT_REQUIRED
        latestNativeSessionProfile = null
        latestTransportSelectionInsight = null
        observedNetworkClass = "unknown"
        observedNetworkFingerprint = "unknown"
        lastNetworkSwitchReconnectAtMs = 0L
        localFailureBucketStats = LocalFailureBucketStats()
        lastFailureBucketFingerprint = null
        lastTransportStatusFingerprint = null
        prefs.edit().clear().apply()
        saveTransportStatus(
            route = if (fcmAvailable) "provider" else "private",
            transport = if (fcmAvailable) "fcm" else "none",
            stage = if (fcmAvailable) "active" else "idle",
            detail = if (fcmAvailable) {
                "private channel disabled by provider mode"
            } else {
                "automation reset"
            },
        )
        recomputeKeepaliveState()
        refreshLoop()
    }

    fun onKeepaliveNotificationDismissed(reason: String) {
        keepaliveLossSticky = true
        keepaliveServiceActive = false
        recomputeKeepaliveState()
        if (!foregroundActive) {
            saveTransportStatus(
                route = "private",
                transport = "none",
                stage = "fgs_lost",
                detail = reason,
            )
            stopActiveSession("fgs_lost:$reason")
        }
        refreshLoop()
    }

    private fun saveTransportStatus(route: String, transport: String, stage: String, detail: String?) {
        val status = TransportStatus(
            route = route,
            transport = transport,
            stage = stage,
            detail = detail,
            updatedAtMs = System.currentTimeMillis(),
        )
        val obj = JSONObject().apply {
            put("route", route)
            put("transport", transport)
            put("stage", stage)
            put("detail", detail ?: "")
            put("updated_at_ms", status.updatedAtMs)
        }
        prefs.edit().putString(KEY_TRANSPORT_STATUS, obj.toString()).apply()
        transportStatusState.value = status
        connectionSnapshotState.value = buildConnectionSnapshot()
        val fingerprint = "$route|$transport|$stage|${detail.orEmpty()}"
        if (fingerprint != lastTransportStatusFingerprint) {
            lastTransportStatusFingerprint = fingerprint
            io.ethan.pushgo.util.SilentSink.i(
                TAG,
                "transport_status route=$route transport=$transport stage=$stage detail=${detail.orEmpty().take(160)}"
            )
        }
        val notificationFingerprint = notificationStatusFingerprint(route, transport, stage)
        if (notificationFingerprint != lastNotificationStatusFingerprint) {
            lastNotificationStatusFingerprint = notificationFingerprint
            notifyKeepaliveNotificationChanged()
        }
    }

    private fun recomputeKeepaliveState() {
        keepaliveState = when {
            fcmAvailable -> KeepaliveState.NOT_REQUIRED
            !networkAvailable -> KeepaliveState.NETWORK_BLOCKED
            foregroundActive -> KeepaliveState.APP_FOREGROUND
            keepaliveServiceActive -> KeepaliveState.FGS_ACTIVE
            keepaliveLossSticky -> KeepaliveState.FGS_LOST
            else -> KeepaliveState.IDLE
        }
        connectionSnapshotState.value = buildConnectionSnapshot()
    }

    private fun buildConnectionSnapshot(): ConnectionSnapshot {
        return ConnectionSnapshot(
            transportStatus = transportStatusState.value,
            keepaliveState = keepaliveState,
            networkAvailable = networkAvailable,
            foregroundActive = foregroundActive,
            privateModeEnabled = !fcmAvailable,
            selectionInsight = latestTransportSelectionInsight,
            failureBucketStats = localFailureBucketStats,
        )
    }

    private fun loadTransportStatusSnapshot(): TransportStatus {
        val raw = prefs.getString(KEY_TRANSPORT_STATUS, null) ?: return defaultTransportStatus()
        return runCatching {
            val obj = JSONObject(raw)
            TransportStatus(
                route = obj.optString("route").trim().ifEmpty { "private" },
                transport = obj.optString("transport").trim().ifEmpty { "none" },
                stage = obj.optString("stage").trim().ifEmpty { "idle" },
                detail = obj.optString("detail").trim().ifEmpty { null },
                updatedAtMs = obj.optLong("updated_at_ms", 0L),
            )
        }.getOrElse {
            defaultTransportStatus(detail = "status parse failed")
        }
    }

    private fun defaultTransportStatus(detail: String? = null): TransportStatus {
        return TransportStatus(
            route = if (fcmAvailable) "provider" else "private",
            transport = if (fcmAvailable) "fcm" else "none",
            stage = if (fcmAvailable) "active" else "idle",
            detail = detail ?: if (fcmAvailable) {
                "private channel disabled by provider mode"
            } else {
                "no status yet"
            },
            updatedAtMs = 0L,
        )
    }

    private fun notificationStatusFingerprint(route: String, transport: String, stage: String): String {
        val summary = PrivateTransportStatusPresenter.summarize(
            context = appContext,
            privateModeEnabled = !fcmAvailable,
            route = route,
            transport = transport,
            stage = stage,
            networkAvailable = networkAvailable,
        )
        return "$route|$transport|$stage|$summary"
    }

    private fun notifyKeepaliveNotificationChanged() {
        if (!keepaliveServiceActive) return
        runCatching {
            appContext.startService(
                android.content.Intent(appContext, PrivateChannelForegroundService::class.java).apply {
                    action = PrivateChannelForegroundService.ACTION_REFRESH_STATUS
                }
            )
        }.onFailure {
            io.ethan.pushgo.util.SilentSink.w(
                TAG,
                "notify keepalive notification failed error=${it.message}",
            )
        }
    }

    private fun loadState(): DeviceState? {
        val raw = prefs.getString(KEY_STATE, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            DeviceState(
                deviceKey = obj.optString("device_key"),
                issuedAt = obj.optLong("issued_at", 0),
                subscribedChannels = obj.optJSONArray("subscribed_channels")
                    ?.let { arr ->
                        buildList {
                            for (i in 0 until arr.length()) {
                                val item = arr.optString(i).trim()
                                if (item.isNotEmpty()) add(item)
                            }
                        }
                    }
                    .orEmpty(),
                resumeToken = obj.optString("resume_token").trim().ifEmpty { null },
                lastAckedSeq = obj.optLong("last_acked_seq", 0L).coerceAtLeast(0L),
            )
        }.getOrNull()
    }

    private fun saveState(state: DeviceState) {
        val obj = JSONObject().apply {
            put("device_key", state.deviceKey)
            put("issued_at", state.issuedAt)
            put("subscribed_channels", org.json.JSONArray(state.subscribedChannels))
            put("resume_token", state.resumeToken ?: "")
            put("last_acked_seq", state.lastAckedSeq)
        }
        prefs.edit().putString(KEY_STATE, obj.toString()).apply()
    }

    private data class DeviceState(
        val deviceKey: String,
        val issuedAt: Long,
        val subscribedChannels: List<String> = emptyList(),
        val resumeToken: String? = null,
        val lastAckedSeq: Long = 0L,
    )

    private data class PrivateTransportProfile(
        val quicEnabled: Boolean,
        val quicPort: Int,
        val tcpEnabled: Boolean,
        val tcpPort: Int,
        val wssEnabled: Boolean,
        val wssPort: Int,
        val wssPath: String,
        val wsSubprotocol: String,
    )

    private data class CachedPrivateTransportProfile(
        val profile: PrivateTransportProfile,
        val fetchedAtMs: Long,
    )

    private data class GatewayProfileSnapshot(
        val privateChannelEnabled: Boolean,
        val transport: PrivateTransportProfile?,
    )

    data class ChannelCreateResult(
        val channelId: String,
        val channelName: String,
        val created: Boolean,
        val subscribed: Boolean,
    )

    data class TransportStatus(
        val route: String,
        val transport: String,
        val stage: String,
        val detail: String?,
        val updatedAtMs: Long,
    )

    data class ConnectionSnapshot(
        val transportStatus: TransportStatus,
        val keepaliveState: KeepaliveState,
        val networkAvailable: Boolean,
        val foregroundActive: Boolean,
        val privateModeEnabled: Boolean,
        val selectionInsight: TransportSelectionInsight?,
        val failureBucketStats: LocalFailureBucketStats,
    )

    private data class NativeSessionProfile(
        val connectBudgetMs: Long,
        val tcpDelayMs: Long,
        val wssDelayMs: Long,
        val quicPort: Int,
        val tcpPort: Int,
        val wssPort: Int,
    )

    private data class LocalRuntimePolicy(
        val connectBudgetMs: Long,
        val backoffCapMs: Long,
    )

    private data class LocalPinPolicy(
        val minConfidenceForeground: Int,
        val minConfidenceBackground: Int,
        val maxAgeMs: Long,
        val failureCooldownMs: Long,
        val ttlForegroundMs: Long,
        val ttlBackgroundMs: Long,
    ) {
        fun minConfidenceFor(isForeground: Boolean): Int {
            return if (isForeground) {
                minConfidenceForeground
            } else {
                minConfidenceBackground
            }
        }
    }

    private data class ActiveNetworkSnapshot(
        val available: Boolean,
        val networkClass: String,
        val networkFingerprint: String,
    )

    private data class LocalTransportPreference(
        val networkFingerprint: String,
        val networkClass: String,
        val transport: String,
        val confidence: Int,
        val updatedAtMs: Long,
        val cooldownUntilMs: Long?,
    )

    data class LocalFailureBucketStats(
        val transportFailures: Int = 0,
        val authFailures: Int = 0,
        val routeFailures: Int = 0,
        val updatedAtMs: Long = 0L,
    )

    data class TransportSelectionInsight(
        val transport: String,
        val elapsedMs: Long?,
        val reason: String?,
        val connectBudgetMs: Long?,
        val tcpDelayMs: Long?,
        val wssDelayMs: Long?,
        val inSessionProbeRttMs: Long?,
        val inSessionProbeSource: String?,
        val inSessionProbeAtMs: Long?,
        val recordedAtMs: Long,
    )

    private fun privateCertPinSha256(): String? {
        val raw = BuildConfig.PRIVATE_CERT_PIN_SHA256.trim()
        if (raw.isEmpty()) return null
        val normalized = raw.removePrefix("sha256:").replace(":", "").lowercase()
        return normalized.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    private fun effectivePerformanceMode(isForeground: Boolean): PrivatePerformanceMode {
        if (isForeground) {
            return performanceMode
        }
        return when (performanceMode) {
            PrivatePerformanceMode.HIGH -> PrivatePerformanceMode.BALANCED
            PrivatePerformanceMode.BALANCED -> PrivatePerformanceMode.LOW
            PrivatePerformanceMode.LOW -> PrivatePerformanceMode.LOW
        }
    }

    private fun updateResumeState(newResumeToken: String?, newAckedSeq: Long?) {
        val existing = currentDeviceState ?: return
        var changed = false
        var resumeToken = existing.resumeToken
        var ackedSeq = existing.lastAckedSeq
        if (!newResumeToken.isNullOrBlank() && newResumeToken != resumeToken) {
            resumeToken = newResumeToken
            // Resume token rotates when gateway resets server-side session state.
            // The sequence space restarts with the new token, so the client ACK watermark
            // must also reset to avoid falsely ACKing unseen deliveries after reconnect.
            ackedSeq = 0L
            changed = true
        }
        if (newAckedSeq != null && newAckedSeq > ackedSeq) {
            ackedSeq = newAckedSeq
            changed = true
        }
        if (!changed) return
        val next = existing.copy(resumeToken = resumeToken, lastAckedSeq = ackedSeq)
        currentDeviceState = next
        saveState(next)
    }

    private fun forceResetAckWatermarkIfNeeded(reason: String) {
        if (!resetAckWatermarkOnReconnect) {
            return
        }
        resetAckWatermarkOnReconnect = false
        val existing = currentDeviceState ?: return
        if (existing.lastAckedSeq <= 0L) {
            return
        }
        val next = existing.copy(lastAckedSeq = 0L)
        currentDeviceState = next
        saveState(next)
        io.ethan.pushgo.util.SilentSink.i(
            TAG,
            "private ack watermark reset before reconnect reason=$reason",
        )
    }

    enum class PrivatePerformanceMode(val wireValue: String) {
        HIGH("high"),
        BALANCED("balanced"),
        LOW("low");

        companion object {
            fun fromWire(raw: String?): PrivatePerformanceMode {
                return when (raw?.trim()?.lowercase()) {
                    HIGH.wireValue -> HIGH
                    LOW.wireValue -> LOW
                    else -> BALANCED
                }
            }
        }
    }

    private fun loadPerformanceMode(): PrivatePerformanceMode {
        return PrivatePerformanceMode.fromWire(prefs.getString(KEY_PERF_MODE, null))
    }

    private fun buildRouteEnsureFingerprint(
        baseUrl: String,
        token: String?,
        deviceKey: String,
    ): String {
        return buildString {
            append(baseUrl.trim())
            append('|')
            append(deviceKey.trim())
            append('|')
            append(token?.trim().orEmpty())
        }
    }

    companion object {
        private const val TAG = "PrivateChannelClient"
        private const val CLIENT_REV = "2026-03-27-r9"
        private const val PREFS_NAME = "private_push_client"
        private const val KEY_STATE = "device_state"
        private const val KEY_TRANSPORT_STATUS = "transport_status"
        private const val KEY_PERF_MODE = "performance_mode"
        private const val KEY_PROFILE_CACHE = "transport_profile_cache"
        private const val KEY_LAST_PRIVATE_PROFILE_REFRESH_VERSION = "last_private_profile_refresh_version"
        private const val KEY_LOCAL_TRANSPORT_PREF = "local_transport_pref"
        private const val KEY_LOCAL_FAILURE_BUCKETS = "local_failure_buckets"
        private const val GATEWAY_PROFILE_ENDPOINT = "/gateway/profile"
        private const val PRIVATE_QUIC_PORT = 443
        private const val PRIVATE_TCP_PORT = 5223
        private const val PRIVATE_HTTP_CONNECT_TIMEOUT_MS = 10_000
        private const val PRIVATE_HTTP_READ_TIMEOUT_MS = 20_000
        private const val PRIVATE_HTTP_MAX_ATTEMPTS = 3
        private const val PRIVATE_HTTP_RETRY_BASE_MS = 450L
        private const val PRIVATE_PROFILE_REFRESH_INTERVAL_MS = 6 * 60 * 60_000L
        private const val PROFILE_FORCE_REFRESH_FAILURE_THRESHOLD = 3
        private const val PROFILE_FORCE_REFRESH_FAILURE_COOLDOWN_MS = 10 * 60_000L
        private const val PRIVATE_ROUTE_REPAIR_INTERVAL_MS = 5_000L
        private const val WIRE_VERSION_V2 = 2
        private const val PRIVATE_PAYLOAD_VERSION_V1 = 1
        private const val ACK_WAIT_TIMEOUT_MS = 10_000
        private const val PRIVATE_WELCOME_STALL_TIMEOUT_MS = 20_000L
        private const val PRIVATE_CONNECT_BUDGET_FG_MS = 4_500L
        private const val PRIVATE_CONNECT_BUDGET_BG_MS = 9_000L
        private const val PRIVATE_BACKOFF_CAP_FG_MS = 45_000L
        private const val PRIVATE_BACKOFF_CAP_BG_MS = 120_000L
        private const val LOCAL_PIN_INIT_CONFIDENCE = 2
        private const val LOCAL_PIN_MAX_CONFIDENCE = 6
        private const val LOCAL_PIN_DEFAULT_MIN_CONFIDENCE_FG = 3
        private const val LOCAL_PIN_DEFAULT_MIN_CONFIDENCE_BG = 3
        private const val LOCAL_PIN_DEFAULT_MAX_AGE_MS = 15 * 60_000L
        private const val LOCAL_PIN_DEFAULT_FAILURE_COOLDOWN_MS = 5 * 60_000L
        private const val LOCAL_PIN_DEFAULT_TTL_FG_MS = 90_000L
        private const val LOCAL_PIN_DEFAULT_TTL_BG_MS = 180_000L
        private const val LOCAL_PIN_CELLULAR_MIN_CONFIDENCE_FG = 4
        private const val LOCAL_PIN_CELLULAR_MIN_CONFIDENCE_BG = 5
        private const val LOCAL_PIN_CELLULAR_MAX_AGE_MS = 8 * 60_000L
        private const val LOCAL_PIN_CELLULAR_FAILURE_COOLDOWN_MS = 3 * 60_000L
        private const val LOCAL_PIN_CELLULAR_TTL_FG_MS = 45_000L
        private const val LOCAL_PIN_CELLULAR_TTL_BG_MS = 90_000L
        private const val LOCAL_PIN_OTHER_MIN_CONFIDENCE_FG = 5
        private const val LOCAL_PIN_OTHER_MIN_CONFIDENCE_BG = 5
        private const val LOCAL_PIN_OTHER_MAX_AGE_MS = 5 * 60_000L
        private const val LOCAL_PIN_OTHER_FAILURE_COOLDOWN_MS = 2 * 60_000L
        private const val LOCAL_PIN_OTHER_TTL_FG_MS = 30_000L
        private const val LOCAL_PIN_OTHER_TTL_BG_MS = 60_000L
        private const val NETWORK_SWITCH_PIN_COOLDOWN_DEFAULT_MS = 45_000L
        private const val NETWORK_SWITCH_PIN_COOLDOWN_CELLULAR_MS = 90_000L
        private const val NETWORK_SWITCH_PIN_COOLDOWN_OTHER_MS = 60_000L
        private const val NETWORK_SWITCH_RECONNECT_MIN_INTERVAL_MS = 5_000L
        private const val AUTH_ROUTE_HEAL_MIN_INTERVAL_MS = 8_000L

        private fun defaultPrivateTransportProfile(): PrivateTransportProfile {
            return PrivateTransportProfile(
                quicEnabled = true,
                quicPort = PRIVATE_QUIC_PORT,
                tcpEnabled = true,
                tcpPort = PRIVATE_TCP_PORT,
                wssEnabled = true,
                wssPort = PRIVATE_QUIC_PORT,
                wssPath = "/private/ws",
                wsSubprotocol = "pushgo-private.v1",
            )
        }
    }
}
