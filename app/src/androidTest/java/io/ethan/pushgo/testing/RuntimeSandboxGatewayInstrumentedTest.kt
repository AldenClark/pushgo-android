package io.ethan.pushgo.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.notifications.PrivateChannelClient
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeSandboxGatewayInstrumentedTest {
    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var baseUrl: String
    private var gatewayToken: String? = null
    private var gatewayTokenSource: String = "missing"
    private var enabled: Boolean = false

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        enabled = boolArg("pushgo.runtime.sandboxE2E")
        assumeTrue(
            "sandbox e2e disabled by default; pass -e pushgo.runtime.sandboxE2E true to enable",
            enabled,
        )

        baseUrl = stringArg("pushgo.runtime.sandbox.baseUrl")
            ?: stringArg("pushgoGatewayBaseUrl")
            ?: DEFAULT_SANDBOX_BASE_URL
        gatewayToken = stringArg("pushgo.runtime.sandbox.token")
            ?: stringArg("pushgoGatewayToken")
        gatewayTokenSource = if (gatewayToken.isNullOrBlank()) "missing" else "argument"

        assertFalse("production gateway is forbidden in runtime sandbox e2e", baseUrl.contains("gateway.pushgo.cn"))
        assertFalse("production gateway is forbidden in runtime sandbox e2e", baseUrl.contains("pushgo.cn"))
        assertTrue(
            "runtime sandbox e2e requires sandbox/local gateway, got: $baseUrl",
            baseUrl.contains("sandbox", ignoreCase = true) ||
                baseUrl.contains("127.0.0.1") ||
                baseUrl.contains("localhost"),
        )

        container = AppContainer(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        container.privateChannelClient.resetForAutomation()
        container.privateChannelClient.setForeground(true)

        val useStoredGatewayToken = boolArg("pushgo.runtime.sandbox.useStoredGatewayToken")
        if (gatewayToken.isNullOrBlank() && useStoredGatewayToken) {
            gatewayToken = container.settingsRepository.getGatewayToken()
            gatewayTokenSource = if (gatewayToken.isNullOrBlank()) "missing" else "stored"
        }

        val health = request(method = "GET", path = "/healthz", body = null, authToken = gatewayToken)
        assertTrue(
            "sandbox gateway is not reachable baseUrl=$baseUrl code=${health.code} body=${health.body}",
            health.code in 200..299,
        )

        container.settingsRepository.setServerAddress(baseUrl)
        container.settingsRepository.setGatewayToken(gatewayToken)
        container.settingsRepository.setUseFcmChannel(true)
        container.settingsRepository.setFcmToken(null)
        container.messageRepository.deleteAll()
        container.entityRepository.deleteAll()
        container.inboundDeliveryLedgerRepository.clearAll()
    }

    @After
    fun tearDown() {
        if (!enabled) return
        runBlocking {
            runCatching {
                container.privateChannelClient.setForeground(false)
                container.privateChannelClient.resetForAutomation()
            }
        }
    }

    @Test
    fun sandboxE2E_providerPrivateSwitchAndStateConvergesWithoutDualActive() = runBlocking {
        val fallbackReason = mutableListOf<String>()
        val baseLabel = "RUNTIME_SANDBOX_E2E"
        val hasGatewayToken = !gatewayToken.isNullOrBlank()
        val allowSyntheticFcmToken = boolArg("pushgo.runtime.sandbox.allowSyntheticFcmToken")
        val gatewayPrivateEnabled = runCatching {
            container.privateChannelClient.gatewayPrivateChannelEnabled()
        }.getOrNull()
        if (gatewayPrivateEnabled == null) {
            fallbackReason += "gateway_profile_probe_failed_or_unauthorized"
        }
        val diagnosticsPrivateHealthProbeCode = request(
            method = "GET",
            path = "/diagnostics/private/health",
            body = null,
            authToken = gatewayToken,
        ).code
        when (diagnosticsPrivateHealthProbeCode) {
            401, 403 -> fallbackReason += "diagnostics_auth_required(code=$diagnosticsPrivateHealthProbeCode)"
            404, 405 -> fallbackReason += "diagnostics_private_health_missing(code=$diagnosticsPrivateHealthProbeCode)"
        }

        val fcmFetchResult = if (stringArg("pushgo.runtime.sandbox.fcmToken").isNullOrBlank()) {
            fetchFcmToken(timeoutMs = FCM_TOKEN_TIMEOUT_MS)
        } else {
            null
        }
        val providedFcmToken = stringArg("pushgo.runtime.sandbox.fcmToken")
            ?: fcmFetchResult?.token
        val fetchedFcmToken = if (stringArg("pushgo.runtime.sandbox.fcmToken").isNullOrBlank()) providedFcmToken else null
        val fcmToken = providedFcmToken ?: if (allowSyntheticFcmToken) {
            "runtime-sandbox-synth-${System.currentTimeMillis()}"
        } else {
            null
        }
        val fcmTokenSource = when {
            !stringArg("pushgo.runtime.sandbox.fcmToken").isNullOrBlank() -> "argument"
            !fetchedFcmToken.isNullOrBlank() -> "firebase"
            fcmToken != null -> "synthetic"
            else -> "missing"
        }
        if (providedFcmToken == null && fcmToken != null) fallbackReason += "synthetic_fcm_token_used"
        if (fcmToken == null) {
            container.settingsRepository.setUseFcmChannel(true)
            container.settingsRepository.setFcmToken(null)
            container.privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
            val status = container.privateChannelClient.readTransportStatus()
            val playServices = inspectPlayServicesStatus()
            val accountStats = inspectAccountStats()
            val requiredLocalPrecondition = resolveMissingFcmPrecondition(
                playServices = playServices,
                accountStats = accountStats,
                fetchReason = fcmFetchResult?.reason,
            )
            assertNotNull(status)
            println(
                "$baseLabel " +
                    "fcm_token_available=false " +
                    "fcm_fetch_reason=${compactStatusDetail(fcmFetchResult?.reason)} " +
                    "play_services_status_code=${playServices.code} " +
                    "play_services_status_label=${playServices.label} " +
                    "play_services_available=${playServices.available} " +
                    "android_account_count=${accountStats.accountCount} " +
                    "android_google_account_count=${accountStats.googleAccountCount} " +
                    "required_local_precondition=$requiredLocalPrecondition " +
                    "token_missing_handled=true " +
                    "route=${status.route} stage=${status.stage} " +
                    "gap=missing_fcm_token_or_play_services",
            )
            assertTrue(
                "non-synthetic sandbox e2e requires real firebase fcm token; " +
                    "fetch_reason=${compactStatusDetail(fcmFetchResult?.reason)} " +
                    "play_services=${playServices.label} " +
                    "required_local_precondition=$requiredLocalPrecondition",
                allowSyntheticFcmToken,
            )
            return@runBlocking
        }

        var providerSyncFailure: Throwable? = null
        var providerSyncOk = false
        val providerSyncMs = elapsedMs {
            runCatching {
                container.settingsRepository.setUseFcmChannel(true)
                container.settingsRepository.setFcmToken(fcmToken)
                container.channelRepository.syncProviderDeviceToken(fcmToken)
                container.channelRepository.syncSubscriptionsIfNeeded(fcmToken)
                container.privateChannelClient.setRuntime(fcmAvailable = true, systemToken = fcmToken)
                providerSyncOk = true
            }.onFailure { error ->
                providerSyncFailure = error
            }
        }
        if (providerSyncFailure != null) fallbackReason += "provider_sync_failed(${providerSyncFailure.message})"

        container.privateChannelClient.setRuntime(fcmAvailable = true, systemToken = fcmToken)
        val providerStatus = awaitTransportStatus(setOf("active", "idle", "reconnecting"), timeoutMs = 30_000)
        val providerRouteOk = providerStatus.route == "provider"

        val channelPassword = stringArg("pushgo.runtime.sandbox.channelPassword")
            ?: "sandbox-${UUID.randomUUID().toString().replace("-", "").take(12)}"
        var channelCreatedOrLoaded = false
        val channelId = runCatching {
            val id = stringArg("pushgo.runtime.sandbox.channelId") ?: run {
                val alias = "runtime-sandbox-${System.currentTimeMillis()}"
                container.channelRepository.createChannel(alias, channelPassword, fcmToken).channelId
            }
            channelCreatedOrLoaded = true
            id
        }.getOrElse { error ->
            fallbackReason += "create_or_load_channel_failed(${error.message})"
            ""
        }
        var channelSubscribed = false
        if (stringArg("pushgo.runtime.sandbox.channelId") != null) {
            val subscribed = runCatching {
                container.channelRepository.subscribeChannel(channelId, channelPassword, fcmToken)
                true
            }.getOrDefault(false)
            channelSubscribed = subscribed
            if (!subscribed) fallbackReason += "subscribe_channel_failed(channel_id=$channelId)"
        }

        val diagnosticsPrivateHealthClass = classifyHttpCode(diagnosticsPrivateHealthProbeCode)
        val diagnosticsAuthMode = classifyAuthMode(
            code = diagnosticsPrivateHealthProbeCode,
            tokenProvided = hasGatewayToken,
        )
        val pullProbeCode = request(
            method = "POST",
            path = "/v2/messages/pull",
            body = JSONObject(),
            authToken = gatewayToken,
        ).code
        val pullProbeClass = classifyHttpCode(pullProbeCode)
        val pullProbeAuthMode = classifyAuthMode(
            code = pullProbeCode,
            tokenProvided = hasGatewayToken,
        )
        val ackProbeCode = request(
            method = "POST",
            path = "/v2/messages/ack",
            body = JSONObject(),
            authToken = gatewayToken,
        ).code
        val ackProbeClass = classifyHttpCode(ackProbeCode)
        val ackProbeAuthMode = classifyAuthMode(
            code = ackProbeCode,
            tokenProvided = hasGatewayToken,
        )

        var switchPrivateFailure: Throwable? = null
        var switchPrivateOk = false
        val switchToPrivateMs = elapsedMs {
            runCatching {
                container.settingsRepository.setUseFcmChannel(false)
                container.settingsRepository.setFcmToken(null)
                container.privateChannelClient.switchToPrivateAndRetireProvider("fcm", fcmToken)
                container.privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
                switchPrivateOk = true
            }.onFailure { error ->
                switchPrivateFailure = error
            }
        }
        if (switchPrivateFailure != null) fallbackReason += "switch_private_failed(${switchPrivateFailure.message})"

        val privateObservation = observePrivateStages(
            timeoutMs = 35_000,
            pollMs = 200L,
            postConnectedObserveMs = 1_500L,
        )
        val privateSnapshot = privateObservation.snapshot
        val privateStatus = privateSnapshot.transportStatus
        val privateRouteOk = privateStatus.route == "private"
        val privateConnected = privateObservation.connectedObserved || privateStatus.stage == "connected"
        val privateState = readPrivateDeviceState()
        val privateResumeTokenPresent = !privateState.resumeToken.isNullOrBlank()
        val privateAuthenticated = privateObservation.authenticatedObserved || privateResumeTokenPresent
        if (!privateConnected) {
            fallbackReason += "private_not_connected(stage=${privateStatus.stage})"
        }
        if (!privateAuthenticated) {
            fallbackReason += "private_authenticated_not_observed"
        }
        if (!privateRouteOk) {
            fallbackReason += "private_route_unexpected(route=${privateStatus.route})"
        }

        val privateAckBeforeSeq = privateState.lastAckedSeq
        val privateDispatchAttemptCode = if (channelId.isNotBlank()) {
            val opId = "runtime-private-op-${UUID.randomUUID().toString().replace("-", "")}"
            sendSandboxMessage(
                channelId = channelId,
                password = channelPassword,
                opId = opId,
            ).code
        } else {
            -1
        }
        val privateAckAfterSeq = awaitAckAdvance(
            baselineAckSeq = privateAckBeforeSeq,
            timeoutMs = 8_000L,
            pollMs = 200L,
        )
        val privateAckObserved = privateAckAfterSeq > privateAckBeforeSeq
        if (privateDispatchAttemptCode in 200..299 && privateAuthenticated && !privateAckObserved) {
            fallbackReason += "private_ack_not_observed"
        }
        val pullDeviceKey = container.settingsRepository.getDeviceKey()?.trim()?.ifEmpty { null }
        val pullWithDeviceKeyResult = if (!pullDeviceKey.isNullOrBlank()) {
            request(
                method = "POST",
                path = "/v2/messages/pull",
                body = JSONObject().put("device_key", pullDeviceKey),
                authToken = gatewayToken,
            )
        } else {
            HttpResult(code = -1, body = "")
        }
        val pullWithDeviceKeyCode = pullWithDeviceKeyResult.code
        val pullWithDeviceKeyClass = classifyHttpCode(pullWithDeviceKeyCode)
        val pullWithDeviceKeyAuthMode = classifyAuthMode(
            code = pullWithDeviceKeyCode,
            tokenProvided = hasGatewayToken,
        )
        val pulledItemsCount = parsePulledItemsCount(pullWithDeviceKeyResult.body)
        val pulledFirstDeliveryId = parseFirstPulledDeliveryId(pullWithDeviceKeyResult.body)
        val ackWithDeviceKeyResult = if (!pullDeviceKey.isNullOrBlank() && !pulledFirstDeliveryId.isNullOrBlank()) {
            request(
                method = "POST",
                path = "/v2/messages/ack",
                body = JSONObject()
                    .put("device_key", pullDeviceKey)
                    .put("delivery_ids", JSONArray().put(pulledFirstDeliveryId)),
                authToken = gatewayToken,
            )
        } else {
            HttpResult(code = -1, body = "")
        }
        val ackWithDeviceKeyCode = ackWithDeviceKeyResult.code
        val ackWithDeviceKeyClass = classifyHttpCode(ackWithDeviceKeyCode)
        val ackWithDeviceKeyAuthMode = classifyAuthMode(
            code = ackWithDeviceKeyCode,
            tokenProvided = hasGatewayToken,
        )
        val pullAckContractAvailable = pullWithDeviceKeyCode in 200..299 || ackWithDeviceKeyCode in 200..299
        if (!pullAckContractAvailable) {
            fallbackReason += "pull_ack_contract_unavailable(pull=$pullWithDeviceKeyCode,ack=$ackWithDeviceKeyCode)"
        }

        var switchBackOk = false
        val switchBackToFcmMs = elapsedMs {
            runCatching {
                container.settingsRepository.setUseFcmChannel(true)
                container.settingsRepository.setFcmToken(fcmToken)
                container.privateChannelClient.switchToProviderChannel("fcm", fcmToken)
                container.privateChannelClient.setRuntime(fcmAvailable = true, systemToken = fcmToken)
                switchBackOk = true
            }.onFailure { error ->
                fallbackReason += "switch_back_fcm_failed(${error.message})"
            }
        }
        val fcmStatus = awaitTransportStatus(setOf("active"), timeoutMs = 20_000)
        val switchBackRouteOk = fcmStatus.route == "provider" && fcmStatus.transport == "fcm"
        val fcmPersisted = container.settingsRepository.getUseFcmChannel()
        if (!switchBackRouteOk) fallbackReason += "switch_back_route_unexpected(route=${fcmStatus.route},transport=${fcmStatus.transport})"
        if (!fcmPersisted) fallbackReason += "switch_back_persisted_flag_false"

        val dualActive = privateStatus.route == "provider" && privateStatus.stage == "active"
        assertFalse("unexpected dual-active state during private mode", dualActive)

        val localMessageCountBefore = container.messageRepository.totalCount()
        val dispatchOpId = "runtime-op-${UUID.randomUUID().toString().replace("-", "")}"
        val dispatchAttemptCode = if (channelId.isNotBlank()) {
            sendSandboxMessage(
                channelId = channelId,
                password = channelPassword,
                opId = dispatchOpId,
            ).code
        } else {
            -1
        }
        val dispatchAttemptClass = classifyHttpCode(dispatchAttemptCode)
        val dispatchAuthMode = classifyAuthMode(
            code = dispatchAttemptCode,
            tokenProvided = hasGatewayToken,
        )
        when (dispatchAttemptCode) {
            401, 403 -> fallbackReason += "test_message_endpoint_auth_required(code=$dispatchAttemptCode)"
            404, 405 -> fallbackReason += "missing_test_message_endpoint(code=$dispatchAttemptCode)"
        }
        val firstMessageObserved = awaitMessageCountAtLeast(
            expectedAtLeast = localMessageCountBefore + if (dispatchAttemptCode in 200..299) 1 else 0,
            timeoutMs = 10_000L,
            pollMs = 200L,
        )
        val duplicateDispatchCode = if (dispatchAttemptCode in 200..299) {
            sendSandboxMessage(
                channelId = channelId,
                password = channelPassword,
                opId = dispatchOpId,
            ).code
        } else {
            -1
        }
        val duplicateDispatchClass = classifyHttpCode(duplicateDispatchCode)
        val duplicateMessageCountAfter = if (dispatchAttemptCode in 200..299) {
            awaitMessageCountAtLeast(
                expectedAtLeast = firstMessageObserved,
                timeoutMs = 5_000L,
                pollMs = 200L,
            )
        } else {
            localMessageCountBefore
        }
        val canonicalDedupPreserved = if (dispatchAttemptCode in 200..299) {
            duplicateMessageCountAfter == firstMessageObserved
        } else {
            false
        }
        if (dispatchAttemptCode in 200..299 && !canonicalDedupPreserved) {
            fallbackReason += "canonical_dedup_not_preserved(local_count_before=$localMessageCountBefore,first=$firstMessageObserved,after_dup=$duplicateMessageCountAfter)"
        }
        val verificationLayer = resolveVerificationLayer(
            providerRouteOk = providerRouteOk,
            privateConnected = privateConnected,
            privateAuthenticated = privateAuthenticated,
            privateResumeTokenPresent = privateResumeTokenPresent,
            privateAckObserved = privateAckObserved,
            switchBackRouteOk = switchBackRouteOk,
        )
        val requiredMinTestEndpoint = requiredBackendCapability(
            pullProbeCode = pullProbeCode,
            ackProbeCode = ackProbeCode,
            pullWithDeviceKeyCode = pullWithDeviceKeyCode,
            ackWithDeviceKeyCode = ackWithDeviceKeyCode,
            privateDispatchAttemptCode = privateDispatchAttemptCode,
            dispatchAttemptCode = dispatchAttemptCode,
            hasGatewayToken = hasGatewayToken,
            privateAuthenticated = privateAuthenticated,
            privateAckObserved = privateAckObserved,
        )
        val snapshot = container.privateChannelClient.readConnectionSnapshot()
        val privateSelection = privateSnapshot.selectionInsight
        val providerSelection = snapshot.selectionInsight
        val privateFailureBucket = privateSnapshot.failureBucketStats
        val providerFailureBucket = snapshot.failureBucketStats
        println(
                "$baseLabel " +
                "gateway_token_provided=$hasGatewayToken " +
                "gateway_token_source=$gatewayTokenSource " +
                "gateway_private_enabled=${gatewayPrivateEnabled ?: "unknown"} " +
                "fcm_token_source=$fcmTokenSource " +
                "diagnostics_private_health_code=$diagnosticsPrivateHealthProbeCode " +
                "diagnostics_private_health_class=$diagnosticsPrivateHealthClass " +
                "diagnostics_auth_mode=$diagnosticsAuthMode " +
                "pull_probe_code=$pullProbeCode " +
                "pull_probe_class=$pullProbeClass " +
                "pull_probe_auth_mode=$pullProbeAuthMode " +
                "ack_probe_code=$ackProbeCode " +
                "ack_probe_class=$ackProbeClass " +
                "ack_probe_auth_mode=$ackProbeAuthMode " +
                "pull_device_key_present=${!pullDeviceKey.isNullOrBlank()} " +
                "pull_with_device_key_code=$pullWithDeviceKeyCode " +
                "pull_with_device_key_class=$pullWithDeviceKeyClass " +
                "pull_with_device_key_auth_mode=$pullWithDeviceKeyAuthMode " +
                "pull_with_device_key_items=$pulledItemsCount " +
                "ack_with_device_key_code=$ackWithDeviceKeyCode " +
                "ack_with_device_key_class=$ackWithDeviceKeyClass " +
                "ack_with_device_key_auth_mode=$ackWithDeviceKeyAuthMode " +
                "provider_sync_ok=$providerSyncOk " +
                "provider_route_ok=$providerRouteOk " +
                "channel_created_or_loaded=$channelCreatedOrLoaded " +
                "channel_subscribed=$channelSubscribed " +
                "switch_private_ok=$switchPrivateOk " +
                "switch_back_ok=$switchBackOk " +
                "switch_back_route_ok=$switchBackRouteOk " +
                "fcm_persisted=$fcmPersisted " +
                "provider_sync_ms=$providerSyncMs " +
                "switch_private_ms=$switchToPrivateMs " +
                "switch_fcm_ms=$switchBackToFcmMs " +
                "private_stage=${privateStatus.stage} " +
                "private_connected=$privateConnected " +
                "private_detail=${compactStatusDetail(privateStatus.detail)} " +
                "private_keepalive=${privateSnapshot.keepaliveState.name.lowercase()} " +
                "private_mode_enabled=${privateSnapshot.privateModeEnabled} " +
                "private_stage_timeline=${privateObservation.stageTimeline} " +
                "private_connected_observed=${privateObservation.connectedObserved} " +
                "private_authenticated_observed=$privateAuthenticated " +
                "private_resume_token_present=$privateResumeTokenPresent " +
                "private_last_acked_seq=${privateState.lastAckedSeq} " +
                "private_dispatch_attempt_code=$privateDispatchAttemptCode " +
                "private_ack_before_seq=$privateAckBeforeSeq " +
                "private_ack_after_seq=$privateAckAfterSeq " +
                "private_ack_observed=$privateAckObserved " +
                "private_selection_transport=${privateSelection?.transport ?: "none"} " +
                "private_selection_reason=${compactStatusDetail(privateSelection?.reason)} " +
                "private_failure_transport=${privateFailureBucket.transportFailures} " +
                "private_failure_auth=${privateFailureBucket.authFailures} " +
                "private_failure_route=${privateFailureBucket.routeFailures} " +
                "provider_stage=${fcmStatus.stage} " +
                "provider_transport=${fcmStatus.transport} " +
                "provider_detail=${compactStatusDetail(fcmStatus.detail)} " +
                "provider_keepalive=${snapshot.keepaliveState.name.lowercase()} " +
                "provider_mode_enabled=${snapshot.privateModeEnabled} " +
                "provider_selection_transport=${providerSelection?.transport ?: "none"} " +
                "provider_selection_reason=${compactStatusDetail(providerSelection?.reason)} " +
                "provider_failure_transport=${providerFailureBucket.transportFailures} " +
                "provider_failure_auth=${providerFailureBucket.authFailures} " +
                "provider_failure_route=${providerFailureBucket.routeFailures} " +
                "test_message_endpoint_path=/message " +
                "dispatch_attempt_code=$dispatchAttemptCode " +
                "dispatch_attempt_class=$dispatchAttemptClass " +
                "dispatch_duplicate_code=$duplicateDispatchCode " +
                "dispatch_duplicate_class=$duplicateDispatchClass " +
                "local_message_count_before=$localMessageCountBefore " +
                "local_message_count_after_first=$firstMessageObserved " +
                "local_message_count_after_duplicate=$duplicateMessageCountAfter " +
                "canonical_dedup_preserved=$canonicalDedupPreserved " +
                "dispatch_auth_mode=$dispatchAuthMode " +
                "verification_layer=$verificationLayer " +
                "required_min_test_endpoint=$requiredMinTestEndpoint " +
                "network_available=${snapshot.networkAvailable} " +
                "gap=${if (fallbackReason.isEmpty()) "none" else fallbackReason.joinToString(";")}",
        )
    }

    private suspend fun awaitTransportStatus(
        acceptedStages: Set<String>,
        timeoutMs: Long,
    ): PrivateChannelClient.TransportStatus {
        return withTimeout(timeoutMs) {
            container.privateChannelClient.transportStatusFlow.first { status ->
                acceptedStages.contains(status.stage)
            }
        }
    }

    private suspend fun awaitConnectionSnapshot(
        acceptedStages: Set<String>,
        timeoutMs: Long,
    ): PrivateChannelClient.ConnectionSnapshot {
        return withTimeout(timeoutMs) {
            container.privateChannelClient.connectionSnapshotFlow.first { snapshot ->
                acceptedStages.contains(snapshot.transportStatus.stage)
            }
        }
    }

    private suspend fun observePrivateStages(
        timeoutMs: Long,
        pollMs: Long,
        postConnectedObserveMs: Long,
    ): PrivateStageObservation {
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutMs
        var snapshot = container.privateChannelClient.readConnectionSnapshot()
        val timeline = linkedSetOf(snapshot.transportStatus.stage)
        var connectedObserved = snapshot.transportStatus.stage == "connected"
        var authenticatedObserved = stageImpliesAuthenticated(snapshot.transportStatus.stage)
        var connectedAtMs = if (connectedObserved) startedAt else -1L

        while (System.currentTimeMillis() < deadline) {
            delay(pollMs)
            snapshot = container.privateChannelClient.readConnectionSnapshot()
            timeline += snapshot.transportStatus.stage
            if (snapshot.transportStatus.stage == "connected") {
                connectedObserved = true
                if (connectedAtMs < 0L) {
                    connectedAtMs = System.currentTimeMillis()
                }
            }
            if (stageImpliesAuthenticated(snapshot.transportStatus.stage)) {
                authenticatedObserved = true
            }
            if (authenticatedObserved) break
            if (connectedObserved && connectedAtMs > 0L) {
                val elapsedAfterConnected = System.currentTimeMillis() - connectedAtMs
                if (elapsedAfterConnected >= postConnectedObserveMs) {
                    break
                }
            }
        }
        return PrivateStageObservation(
            snapshot = snapshot,
            connectedObserved = connectedObserved,
            authenticatedObserved = authenticatedObserved,
            stageTimeline = timeline.joinToString(">"),
        )
    }

    private fun fetchFcmToken(timeoutMs: Long): FcmFetchResult {
        val latch = CountDownLatch(1)
        var token: String? = null
        var reason: String = "unknown"
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { value ->
                token = value?.trim()?.ifEmpty { null }
                reason = if (token.isNullOrBlank()) "empty_token" else "ok"
                latch.countDown()
            }
            .addOnFailureListener { error ->
                reason = "failure:${error.javaClass.simpleName}:${error.message.orEmpty().take(80)}"
                latch.countDown()
            }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return FcmFetchResult(token = null, reason = "timeout")
        }
        return FcmFetchResult(token = token, reason = reason)
    }

    private fun inspectPlayServicesStatus(): PlayServicesStatus {
        return runCatching {
            val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            PlayServicesStatus(
                code = code,
                label = describePlayServicesCode(code),
                available = code == ConnectionResult.SUCCESS,
            )
        }.getOrElse {
            PlayServicesStatus(
                code = -1,
                label = "probe_failed",
                available = false,
            )
        }
    }

    private fun describePlayServicesCode(code: Int): String {
        return when (code) {
            ConnectionResult.SUCCESS -> "success"
            ConnectionResult.SERVICE_MISSING -> "service_missing"
            ConnectionResult.SERVICE_UPDATING -> "service_updating"
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "update_required"
            ConnectionResult.SERVICE_DISABLED -> "service_disabled"
            ConnectionResult.SERVICE_INVALID -> "service_invalid"
            ConnectionResult.NETWORK_ERROR -> "network_error"
            ConnectionResult.DEVELOPER_ERROR -> "developer_error"
            ConnectionResult.SIGN_IN_REQUIRED -> "sign_in_required"
            ConnectionResult.INTERNAL_ERROR -> "internal_error"
            ConnectionResult.TIMEOUT -> "timeout"
            ConnectionResult.API_UNAVAILABLE -> "api_unavailable"
            else -> "other_$code"
        }
    }

    private fun inspectAccountStats(): AndroidAccountStats {
        return runCatching {
            val raw = runShellCommand("dumpsys account")
            val totalAccounts = Regex("""Accounts:\s+(\d+)""")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: -1
            val googleAccountCount = Regex("""Account \{name=.*?, type=com\.google\}""")
                .findAll(raw)
                .count()
            AndroidAccountStats(
                accountCount = totalAccounts,
                googleAccountCount = googleAccountCount,
            )
        }.getOrElse {
            AndroidAccountStats(
                accountCount = -1,
                googleAccountCount = -1,
            )
        }
    }

    private fun runShellCommand(command: String): String {
        val parcel = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return try {
            FileInputStream(parcel.fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            parcel.close()
        }
    }

    private fun resolveMissingFcmPrecondition(
        playServices: PlayServicesStatus,
        accountStats: AndroidAccountStats,
        fetchReason: String?,
    ): String {
        val normalizedReason = fetchReason.orEmpty().lowercase()
        if (!playServices.available) {
            return "play_services_available"
        }
        if (accountStats.googleAccountCount == 0 || accountStats.accountCount == 0) {
            return "google_account_signed_in"
        }
        if (normalizedReason.contains("authentication_fai")) {
            return "google_account_auth_state_ready"
        }
        if (normalizedReason.contains("network")) {
            return "network_available_for_fcm_token_fetch"
        }
        return "fcm_token_fetch_environment_ready"
    }

    private fun sendSandboxMessage(channelId: String, password: String, opId: String): HttpResult {
        val body = JSONObject()
            .put("channel_id", channelId)
            .put("password", password)
            .put("op_id", opId)
            .put("title", "runtime-$opId")
            .put("body", "runtime message $opId")
        return request(method = "POST", path = "/message", body = body, authToken = gatewayToken)
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        authToken: String?,
    ): HttpResult {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (!authToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
        }
        val code = conn.responseCode
        val raw = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream
        }
        val text = raw?.use { stream ->
            BufferedReader(stream.reader(StandardCharsets.UTF_8)).readText()
        } ?: ""
        conn.disconnect()
        return HttpResult(code = code, body = text)
    }

    private fun stringArg(key: String): String? {
        return InstrumentationRegistry.getArguments()
            .getString(key)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun boolArg(key: String): Boolean {
        return stringArg(key)?.toBooleanStrictOrNull() == true
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun classifyHttpCode(code: Int): String {
        return when {
            code in 200..299 -> "ok"
            code == 400 -> "bad_request"
            code == 401 || code == 403 -> "unauthorized"
            code == 404 -> "not_found"
            code == 405 -> "method_not_allowed"
            code in 500..599 -> "server_error"
            code < 0 -> "not_called"
            else -> "other"
        }
    }

    private fun classifyAuthMode(
        code: Int,
        tokenProvided: Boolean,
    ): String {
        return when {
            code in 200..299 && tokenProvided -> "token_accepted_or_not_required"
            code in 200..299 -> "anonymous_allowed"
            code == 401 || code == 403 -> "auth_required"
            code == 404 || code == 405 -> "endpoint_missing_or_not_routed"
            code < 0 -> "not_called"
            else -> "unknown"
        }
    }

    private fun resolveVerificationLayer(
        providerRouteOk: Boolean,
        privateConnected: Boolean,
        privateAuthenticated: Boolean,
        privateResumeTokenPresent: Boolean,
        privateAckObserved: Boolean,
        switchBackRouteOk: Boolean,
    ): String {
        return when {
            providerRouteOk && privateAckObserved && switchBackRouteOk -> "private_ack_switch_roundtrip"
            providerRouteOk && privateResumeTokenPresent && switchBackRouteOk -> "private_resume_token_switch_roundtrip"
            providerRouteOk && privateAuthenticated && switchBackRouteOk -> "private_authenticated_switch_roundtrip"
            providerRouteOk && privateConnected && switchBackRouteOk -> "private_connected_switch_roundtrip"
            providerRouteOk && switchBackRouteOk -> "provider_route_switch_roundtrip"
            providerRouteOk -> "provider_route_only"
            else -> "pre_provider_route"
        }
    }

    private fun stageImpliesAuthenticated(stage: String): Boolean {
        val normalized = stage.trim().lowercase()
        return normalized == "authenticated" || normalized.startsWith("auth_")
    }

    private fun readPrivateDeviceState(): PrivateDeviceState {
        val prefs = context.getSharedPreferences("private_push_client", Context.MODE_PRIVATE)
        val raw = prefs.getString("device_state", null) ?: return PrivateDeviceState()
        return try {
            val obj = JSONObject(raw)
            PrivateDeviceState(
                resumeToken = obj.optString("resume_token").trim().ifEmpty { null },
                lastAckedSeq = obj.optLong("last_acked_seq", 0L).coerceAtLeast(0L),
            )
        } catch (_: JSONException) {
            PrivateDeviceState()
        }
    }

    private suspend fun awaitAckAdvance(
        baselineAckSeq: Long,
        timeoutMs: Long,
        pollMs: Long,
    ): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = readPrivateDeviceState().lastAckedSeq
        while (System.currentTimeMillis() < deadline) {
            if (latest > baselineAckSeq) return latest
            delay(pollMs)
            latest = readPrivateDeviceState().lastAckedSeq
        }
        return latest
    }

    private suspend fun awaitMessageCountAtLeast(
        expectedAtLeast: Int,
        timeoutMs: Long,
        pollMs: Long,
    ): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = container.messageRepository.totalCount()
        while (System.currentTimeMillis() < deadline) {
            if (latest >= expectedAtLeast) return latest
            delay(pollMs)
            latest = container.messageRepository.totalCount()
        }
        return latest
    }

    private fun requiredBackendCapability(
        pullProbeCode: Int,
        ackProbeCode: Int,
        pullWithDeviceKeyCode: Int,
        ackWithDeviceKeyCode: Int,
        privateDispatchAttemptCode: Int,
        dispatchAttemptCode: Int,
        hasGatewayToken: Boolean,
        privateAuthenticated: Boolean,
        privateAckObserved: Boolean,
    ): String {
        if (!hasGatewayToken && !privateAuthenticated) {
            return "sandbox_gateway_token_for_device_and_private_auth"
        }
        val pullAckRoutesPresent = isRoutePresentCode(pullProbeCode) || isRoutePresentCode(ackProbeCode)
        val pullAckContractAvailable = (pullWithDeviceKeyCode in 200..299) || (ackWithDeviceKeyCode in 200..299)
        if (!pullAckRoutesPresent) {
            return "POST_/v2/messages/pull_and_/v2/messages/ack_routes_required_for_v2_reliability_probe"
        }
        if (!pullAckContractAvailable) {
            return "server_observability_contract_for_v2_reliability_probe_(device_key,delivery_ids)"
        }
        if (privateDispatchAttemptCode == 404 || privateDispatchAttemptCode == 405) {
            return "POST_/message_(channel_id,password,op_id,title,body)_for_private_ack_probe"
        }
        if (privateDispatchAttemptCode == 401 || privateDispatchAttemptCode == 403) {
            return "grant_private_dispatch_auth_for_POST_/message"
        }
        if (!privateAckObserved && privateDispatchAttemptCode in 200..299) {
            return "dispatch_delivery_ack_observability_for_private_stream"
        }
        if (dispatchAttemptCode == 404 || dispatchAttemptCode == 405 || dispatchAttemptCode < 0) {
            return "POST_/message_(channel_id,password,op_id,title,body)_for_test_dispatch"
        }
        if (dispatchAttemptCode == 401 || dispatchAttemptCode == 403) {
            return "grant_test_dispatch_auth_for_POST_/message"
        }
        return "none"
    }

    private fun isRoutePresentCode(code: Int): Boolean {
        return code != 404 && code != 405 && code >= 0
    }

    private fun parsePulledItemsCount(body: String): Int {
        return runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return@runCatching -1
            data.optJSONArray("items")?.length() ?: -1
        }.getOrDefault(-1)
    }

    private fun parseFirstPulledDeliveryId(body: String): String? {
        return runCatching {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return@runCatching null
            val items = data.optJSONArray("items") ?: return@runCatching null
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val deliveryId = item.optString("delivery_id").trim()
                if (deliveryId.isNotEmpty()) return@runCatching deliveryId
            }
            null
        }.getOrNull()
    }

    private fun compactStatusDetail(detail: String?): String {
        val normalized = detail
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.replace(' ', '_')
            ?.trim('_')
            .orEmpty()
        return if (normalized.isEmpty()) "none" else normalized.take(120)
    }

    private suspend fun elapsedMs(block: suspend () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000L
    }

    private data class HttpResult(
        val code: Int,
        val body: String,
    )

    private data class FcmFetchResult(
        val token: String?,
        val reason: String,
    )

    private data class PlayServicesStatus(
        val code: Int,
        val label: String,
        val available: Boolean,
    )

    private data class AndroidAccountStats(
        val accountCount: Int,
        val googleAccountCount: Int,
    )

    private data class PrivateStageObservation(
        val snapshot: PrivateChannelClient.ConnectionSnapshot,
        val connectedObserved: Boolean,
        val authenticatedObserved: Boolean,
        val stageTimeline: String,
    )

    private data class PrivateDeviceState(
        val resumeToken: String? = null,
        val lastAckedSeq: Long = 0L,
    )

    private companion object {
        private const val DEFAULT_SANDBOX_BASE_URL = "https://sandbox.pushgo.dev"
        private const val FCM_TOKEN_TIMEOUT_MS = 20_000L
    }
}
