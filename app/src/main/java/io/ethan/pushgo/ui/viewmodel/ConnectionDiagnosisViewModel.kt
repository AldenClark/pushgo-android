package io.ethan.pushgo.ui.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.data.AppConstants
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.notifications.KeepaliveState
import io.ethan.pushgo.notifications.PrivateChannelClient
import io.ethan.pushgo.util.DiagnosticLogEntry
import io.ethan.pushgo.util.DiagnosticLogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis
import kotlin.math.roundToInt

class ConnectionDiagnosisViewModel(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val privateChannelClient: PrivateChannelClient,
) : ViewModel() {
    companion object {
        private const val MAX_UI_LOGS = 320
        private const val DIAG_DURATION_MS = 75_000L
        private const val NETWORK_SAMPLE_INTERVAL_MS = 5_000L
        private const val GATEWAY_PROBE_INTERVAL_MS = 12_000L
        private const val CHANNEL_PROBE_STABILITY_INTERVAL_MS = 25_000L
        private const val CONTINUOUS_CHANNEL_PROBE_INTERVAL_MS = 1_000L
        private const val CHANNEL_PROBE_ROLLING_WINDOW_MS = 30_000L
        private const val GATEWAY_CONNECT_TIMEOUT_MS = 8_000
        private const val GATEWAY_READ_TIMEOUT_MS = 12_000
        private const val CHANNEL_PROBE_CONNECT_TIMEOUT_MS = 2_500
        private const val FCM_PROBE_HOST = "fcm.googleapis.com"
        private const val FCM_PROBE_PORT = 443
        private const val DIAG_SCHEMA_VERSION = "1.0"
        private val IPV4_REGEX = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
        private val IPV6_REGEX = Regex("""(?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F]{1,4}""")
        private val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US)
    }

    var isRunning by mutableStateOf(false)
        private set
    var isCompleted by mutableStateOf(false)
        private set
    var statusTitle by mutableStateOf("等待开始")
        private set
    var statusDetail by mutableStateOf("进入页面后会自动开始一次 75 秒的网络诊断。")
        private set
    var networkSummary by mutableStateOf("尚未采集")
        private set
    var natSummary by mutableStateOf("尚未采集")
        private set
    var proxySummary by mutableStateOf("尚未采集")
        private set
    var gatewaySummary by mutableStateOf("尚未采集")
        private set
    var channelProbeSummary by mutableStateOf("尚未采集")
        private set
    var transportSummary by mutableStateOf("尚未采集")
        private set
    var recommendation by mutableStateOf("尚未形成诊断结论")
        private set
    var logLines by mutableStateOf<List<ConnectionDiagnosisLogLine>>(emptyList())
        private set
    var reportText by mutableStateOf("")
        private set
    var toastMessage by mutableStateOf<String?>(null)
        private set
    var sessionMode by mutableStateOf(DiagnosisSessionMode.FULL)
        private set
    var diagnosisProfile by mutableStateOf(DiagnosisProfile.LINK_STABILITY)
        private set
    var channelProbeLegs by mutableStateOf<List<ChannelProbeLeg>>(emptyList())
        private set
    var channelProbeModeLabel by mutableStateOf("unknown")
        private set
    var channelProbeProfileRttMs by mutableStateOf<Long?>(null)
        private set
    var gatewayAggregate by mutableStateOf(
        GatewayAggregate(
            total = 0,
            success = 0,
            failure = 0,
            avgLatencyMs = null,
            minLatencyMs = null,
            maxLatencyMs = null,
            latestError = null,
        ),
    )
        private set
    var protocolSelectionHistory by mutableStateOf<List<ProtocolSelectionHistoryItem>>(emptyList())
        private set
    var sessionId by mutableStateOf("")
        private set
    var sessionDurationMs by mutableStateOf(DIAG_DURATION_MS)
        private set
    var sessionElapsedMs by mutableStateOf(0L)
        private set
    val sessionProgressPercent: Int
        get() {
            if (sessionDurationMs <= 0L) return 0
            return ((sessionElapsedMs * 100) / sessionDurationMs).toInt().coerceIn(0, 100)
        }
    val sessionDurationSeconds: Long
        get() = (sessionDurationMs / 1000L).coerceAtLeast(1L)
    val sessionElapsedSeconds: Long
        get() = (sessionElapsedMs / 1000L).coerceAtLeast(0L)
    var clientCapabilities by mutableStateOf(
        ClientCapabilities(
            supportsGatewayHttpProbe = true,
            supportsPrivateTransportProfile = true,
            supportsQuicProbe = true,
            supportsTcpTlsProbe = true,
            supportsWssTlsProbe = true,
            supportsProviderProbe = true,
            supportsLogExportJson = true,
            supportsLogExportText = true,
        ),
    )
        private set
    var pendingShareUris by mutableStateOf<List<Uri>>(emptyList())
        private set
    var redactSensitiveData by mutableStateOf(false)
        private set
    var enhancedProbeState by mutableStateOf<EnhancedProbeState>(EnhancedProbeState.Idle)
        private set

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var sessionJob: Job? = null
    private var sessionProgressJob: Job? = null
    private var continuousChannelProbeJob: Job? = null
    private var appLogListenerId: Long? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var sessionStartedAtMs: Long = 0L
    private var sessionFinishedAtMs: Long = 0L
    private var lastConnectionFingerprint: String? = null
    private var sessionGatewayBaseUrl: String? = null
    private var sessionGatewayToken: String? = null
    private var sessionUseFcmChannel: Boolean = false
    private val channelProbeAccumulators = linkedMapOf<String, ChannelProbeAccumulator>()
    private var latestTransportSelectionInsight: TransportSelectionInsight? = null

    private val networkSamples = mutableListOf<NetworkSample>()
    private val gatewayProbes = mutableListOf<GatewayProbe>()
    private val connectionEvents = mutableListOf<ConnectionEvent>()
    private val channelProbeHistory = mutableListOf<ChannelProbeSnapshot>()
    private var latestChannelProbe: ChannelProbeResult? = null

    fun startDiagnosisIfIdle() {
        ensureContinuousChannelProbeLoop()
        if (sessionJob?.isActive == true) return
        startDiagnosis(forceRestart = false)
    }

    fun restartDiagnosis() {
        startDiagnosis(forceRestart = true)
    }

    fun stopDiagnosis() {
        if (!isRunning) return
        sessionJob?.cancel()
        viewModelScope.launch {
            completeDiagnosis(manuallyStopped = true)
        }
    }

    fun updateSessionMode(mode: DiagnosisSessionMode) {
        if (isRunning) {
            toastMessage = "诊断进行中，当前不可切换模式"
            return
        }
        sessionMode = mode
    }

    fun updateDiagnosisProfile(profile: DiagnosisProfile) {
        if (isRunning) {
            toastMessage = "诊断进行中，当前不可切换模板"
            return
        }
        diagnosisProfile = profile
    }

    fun consumeToastMessage() {
        toastMessage = null
    }

    fun consumePendingShareUris() {
        pendingShareUris = emptyList()
    }

    fun updateRedactSensitiveData(enabled: Boolean) {
        redactSensitiveData = enabled
    }

    fun startEnhancedProbe(leg: ChannelProbeLeg) {
        enhancedProbeState = EnhancedProbeState.Running(leg = leg, startedAtMs = System.currentTimeMillis())
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                runEnhancedProbeInternal(leg)
            }
            enhancedProbeState = EnhancedProbeState.Result(report)
        }
    }

    fun clearEnhancedProbe() {
        enhancedProbeState = EnhancedProbeState.Idle
    }

    fun copyReportToClipboard() {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val payload = currentReportText()
        if (clipboard == null || payload.isBlank()) {
            toastMessage = "当前没有可复制的诊断结果"
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("pushgo-connection-diagnosis", payload))
        toastMessage = "诊断日志已复制"
    }

    fun exportReportToUri(
        uri: Uri,
        format: DiagnosisExportFormat,
        scope: DiagnosisExportScope = DiagnosisExportScope.ALL,
    ) {
        viewModelScope.launch {
            val exported = runCatching {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                        OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                            BufferedWriter(writer).use { buffered ->
                                when (format) {
                                    DiagnosisExportFormat.TEXT -> buffered.write(buildReportText(redact = redactSensitiveData))
                                    DiagnosisExportFormat.JSON -> buffered.write(buildReportJsonText(scope, redact = redactSensitiveData))
                                }
                                buffered.flush()
                            }
                        }
                    } ?: throw IllegalStateException("unable to open output stream")
                }
            }
            exported.onSuccess {
                toastMessage = when (format) {
                    DiagnosisExportFormat.TEXT -> "诊断日志 TXT 已导出"
                    DiagnosisExportFormat.JSON -> {
                        if (scope == DiagnosisExportScope.FAIL_ONLY) {
                            "诊断日志 JSON（仅失败链路）已导出"
                        } else {
                            "诊断日志 JSON 已导出"
                        }
                    }
                }
            }.onFailure {
                toastMessage = "导出失败: ${it.message?.trim().ifNullOrBlank { it::class.java.simpleName }}"
            }
        }
    }

    fun prepareSupportBundleForShare() {
        viewModelScope.launch {
            val prepared = runCatching {
                withContext(Dispatchers.IO) {
                    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
                        .format(Instant.now().atZone(ZoneId.systemDefault()))
                    val sessionPart = sessionId.takeIf { it.isNotBlank() }?.take(8) ?: "nosession"
                    val dir = File(appContext.cacheDir, "diagnosis-support").apply { mkdirs() }
                    val txtFile = File(dir, "pushgo-diagnosis-$stamp-$sessionPart.txt")
                    val jsonFile = File(dir, "pushgo-diagnosis-$stamp-$sessionPart.json")
                    val failJsonFile = File(dir, "pushgo-diagnosis-$stamp-$sessionPart-fail-only.json")
                    txtFile.writeText(buildReportText(redact = redactSensitiveData), Charsets.UTF_8)
                    jsonFile.writeText(buildReportJsonText(DiagnosisExportScope.ALL, redact = redactSensitiveData), Charsets.UTF_8)
                    failJsonFile.writeText(buildReportJsonText(DiagnosisExportScope.FAIL_ONLY, redact = redactSensitiveData), Charsets.UTF_8)
                    val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
                    listOf(
                        FileProvider.getUriForFile(appContext, authority, txtFile),
                        FileProvider.getUriForFile(appContext, authority, jsonFile),
                        FileProvider.getUriForFile(appContext, authority, failJsonFile),
                    )
                }
            }
            prepared.onSuccess { uris ->
                pendingShareUris = uris
                toastMessage = "支持包已生成，可选择目标应用分享"
            }.onFailure {
                toastMessage = "生成支持包失败: ${it.message?.trim().ifNullOrBlank { it::class.java.simpleName }}"
            }
        }
    }

    override fun onCleared() {
        sessionProgressJob?.cancel()
        continuousChannelProbeJob?.cancel()
        cleanupSessionResources()
        super.onCleared()
    }

    private fun startDiagnosis(forceRestart: Boolean) {
        if (forceRestart) {
            sessionJob?.cancel()
            sessionProgressJob?.cancel()
            cleanupSessionResources()
        }
        ensureContinuousChannelProbeLoop()
        resetSessionState()
        sessionJob = viewModelScope.launch {
            isRunning = true
            isCompleted = false
            sessionId = UUID.randomUUID().toString()
            sessionStartedAtMs = System.currentTimeMillis()
            sessionDurationMs = sessionMode.durationMs
            sessionElapsedMs = 0L
            startSessionProgressTicker()
            statusTitle = "正在诊断"
            statusDetail = "正在持续采集网络、网关和私有连接状态，达到窗口后会自动停止。"
            appendLog(
                "session",
                "开始连接诊断，采集窗口 ${sessionMode.durationMs / 1000} 秒 mode=${sessionMode.wireValue}",
            )
            recordGatewayConfiguration()
            attachAppLogListener()
            attachNetworkCallback()
            val snapshotJob = launch { collectConnectionSnapshots() }
            val networkJob = launch { periodicNetworkSampling(NETWORK_SAMPLE_INTERVAL_MS) }
            val gatewayJob = launch { periodicGatewayProbes(GATEWAY_PROBE_INTERVAL_MS) }
            delay(sessionMode.durationMs)
            snapshotJob.cancel()
            networkJob.cancel()
            gatewayJob.cancel()
            completeDiagnosis()
        }
    }

    private suspend fun recordGatewayConfiguration() {
        val gatewayBaseUrl = settingsRepository.getServerAddress()?.trim()?.ifEmpty { null }
            ?: AppConstants.defaultServerAddress
        val gatewayToken = settingsRepository.getGatewayToken()?.trim()?.ifEmpty { null }
        val gatewayTokenPresent = gatewayToken != null
        val useFcmChannel = settingsRepository.getUseFcmChannel()
        sessionGatewayBaseUrl = gatewayBaseUrl
        sessionGatewayToken = gatewayToken
        sessionUseFcmChannel = useFcmChannel
        appendLog(
            "session",
            "应用版本=${BuildConfig.VERSION_NAME} gateway=$gatewayBaseUrl token_present=$gatewayTokenPresent use_fcm=$useFcmChannel",
        )
        clientCapabilities = buildClientCapabilities(useFcmChannel)
        privateChannelClient.refreshNetworkStateFromSystem()
        appendConnectionSnapshot(privateChannelClient.readConnectionSnapshot(), source = "initial")
        sampleCurrentNetwork(reason = "initial")
        runChannelConnectivityProbe(
            useFcmChannel = useFcmChannel,
            gatewayBaseUrl = gatewayBaseUrl,
            gatewayToken = gatewayToken,
        )
    }

    private suspend fun collectConnectionSnapshots() {
        privateChannelClient.connectionSnapshotFlow.collect { snapshot ->
            appendConnectionSnapshot(snapshot, source = "flow")
        }
    }

    private suspend fun periodicNetworkSampling(intervalMs: Long) {
        while (currentCoroutineContext().isActive) {
            sampleCurrentNetwork(reason = "periodic")
            delay(intervalMs)
        }
    }

    private suspend fun periodicGatewayProbes(intervalMs: Long) {
        while (currentCoroutineContext().isActive) {
            runGatewayProbe()
            delay(intervalMs)
        }
    }

    private suspend fun runGatewayProbe() {
        val probe = fetchGatewayProbe()
        gatewayProbes += probe
        if (probe.success) {
            appendLog(
                "gateway",
                "网关探测成功 latency=${probe.latencyMs}ms observed_ip=${probe.observedClientIp ?: "unknown"} nat_hint=${probe.natHint ?: "unknown"} proxy=${probe.proxyDetected}",
            )
        } else {
            appendLog(
                "gateway",
                "网关探测失败 status=${probe.httpStatus ?: -1} error=${probe.errorMessage ?: "unknown"}",
            )
        }
        refreshSummary()
    }

    private suspend fun runChannelConnectivityProbe(
        useFcmChannel: Boolean,
        gatewayBaseUrl: String,
        gatewayToken: String?,
        emitLog: Boolean = true,
    ) {
        if (emitLog) {
            appendLog(
                "channel-probe",
                "开始独立通道连通性测试 mode=${if (useFcmChannel) "fcm" else "private"}",
            )
        }
        val probeRequestAtMs = if (!useFcmChannel && privateChannelClient.requestInSessionProbe()) {
            System.currentTimeMillis()
        } else {
            null
        }
        val probe = if (useFcmChannel) {
            runFcmChannelProbe(gatewayBaseUrl, gatewayToken)
        } else {
            runPrivateChannelProbe(gatewayBaseUrl, gatewayToken)
        }
        val insight = if (probeRequestAtMs != null) {
            awaitSelectionInsightAfter(probeRequestAtMs)
        } else {
            latestTransportSelectionInsight
        }
        val enrichedProbe = enrichProbeWithSelectionInsight(probe, insight)
        latestChannelProbe = enrichedProbe
        channelProbeHistory += ChannelProbeSnapshot(
            timestampMs = System.currentTimeMillis(),
            mode = enrichedProbe.mode,
            success = enrichedProbe.success,
            profileRttMs = enrichedProbe.profileRttMs,
            legs = enrichedProbe.legs,
        )
        recordChannelProbeLegs(enrichedProbe.legs)
        channelProbeModeLabel = enrichedProbe.mode
        channelProbeProfileRttMs = enrichedProbe.profileRttMs
        if (emitLog) {
            appendLog("channel-probe", enrichedProbe.logLine)
        }
        refreshSummary()
    }

    private suspend fun awaitSelectionInsightAfter(requestAtMs: Long): TransportSelectionInsight? {
        val deadline = SystemClock.elapsedRealtime() + 280L
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = latestTransportSelectionInsight
            val inSessionAt = current?.inSessionProbeAtMs
            if (inSessionAt != null && inSessionAt >= requestAtMs) {
                return current
            }
            delay(20L)
        }
        return latestTransportSelectionInsight
    }

    private fun enrichProbeWithSelectionInsight(
        probe: ChannelProbeResult,
        overrideInsight: TransportSelectionInsight? = null,
    ): ChannelProbeResult {
        if (probe.mode != "private" || probe.legs.isEmpty()) return probe
        val insight = overrideInsight ?: latestTransportSelectionInsight ?: return probe
        val selectedTransport = insight.transport.trim().lowercase(Locale.US)
        if (selectedTransport.isEmpty()) return probe

        val enrichedLegs = probe.legs.map { leg ->
            val noteParts = mutableListOf<String>()
            val isSelected = leg.name.equals(selectedTransport, ignoreCase = true)

            insight.connectBudgetMs?.let { noteParts += "connect_budget=${it}ms" }
            if (leg.name.equals("tcp", ignoreCase = true)) {
                insight.tcpDelayMs?.let { noteParts += "tcp_delay=${it}ms" }
            }
            if (leg.name.equals("wss", ignoreCase = true)) {
                insight.wssDelayMs?.let { noteParts += "wss_delay=${it}ms" }
            }
            if (isSelected) {
                insight.elapsedMs?.let { noteParts += "connect_elapsed=${it}ms" }
                insight.reason?.takeIf { it.isNotBlank() }?.let { noteParts += "selection_reason=$it" }
                insight.inSessionProbeRttMs?.let { noteParts += "in_session_rtt=${it}ms" }
                insight.inSessionProbeSource?.takeIf { it.isNotBlank() }?.let { noteParts += "in_session_source=$it" }
            }
            val mergedNote = (listOfNotNull(leg.note) + noteParts)
                .joinToString("; ")
                .ifBlank { leg.note }
            leg.copy(
                note = mergedNote,
            )
        }
        return probe.copy(legs = enrichedLegs)
    }

    private fun ensureContinuousChannelProbeLoop() {
        if (continuousChannelProbeJob?.isActive == true) return
        continuousChannelProbeJob = viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                var baseUrl = sessionGatewayBaseUrl
                if (baseUrl.isNullOrBlank()) {
                    baseUrl = settingsRepository.getServerAddress()?.trim()?.ifEmpty { null }
                        ?: AppConstants.defaultServerAddress
                    sessionGatewayBaseUrl = baseUrl
                    if (sessionGatewayToken == null) {
                        sessionGatewayToken = settingsRepository.getGatewayToken()?.trim()?.ifEmpty { null }
                    }
                    sessionUseFcmChannel = settingsRepository.getUseFcmChannel()
                }
                if (!baseUrl.isNullOrBlank()) {
                    runCatching {
                        runChannelConnectivityProbe(
                            useFcmChannel = sessionUseFcmChannel,
                            gatewayBaseUrl = baseUrl,
                            gatewayToken = sessionGatewayToken,
                            emitLog = isRunning,
                        )
                    }.onFailure { error ->
                        if (isRunning) {
                            appendLog(
                                "channel-probe",
                                "持续链路探测异常: ${error.message?.trim().ifNullOrBlank { error::class.java.simpleName }}",
                            )
                        }
                    }
                }
                delay(CONTINUOUS_CHANNEL_PROBE_INTERVAL_MS)
            }
        }
    }

    private fun startSessionProgressTicker() {
        sessionProgressJob?.cancel()
        sessionProgressJob = viewModelScope.launch {
            while (currentCoroutineContext().isActive && isRunning) {
                val startedAt = sessionStartedAtMs
                if (startedAt > 0L) {
                    val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                    sessionElapsedMs = elapsed.coerceAtMost(sessionDurationMs)
                }
                delay(1_000L)
            }
        }
    }

    private fun recordChannelProbeLegs(legs: List<ChannelProbeLeg>) {
        val now = System.currentTimeMillis()
        val cutoff = now - CHANNEL_PROBE_ROLLING_WINDOW_MS
        legs.forEach { leg ->
            val key = listOf(
                leg.name,
                leg.protocol,
                leg.host,
                leg.port.toString(),
                leg.path.orEmpty(),
                leg.subprotocol.orEmpty(),
            ).joinToString("|")
            val acc = channelProbeAccumulators.getOrPut(key) { ChannelProbeAccumulator() }
            acc.latest = leg
            acc.lastProbeAtMs = now
            if (leg.result == "OK" || leg.result == "FAIL") {
                acc.samples += ChannelProbeSample(
                    timestampMs = now,
                    latencyMs = leg.latencyMs.takeIf {
                        it > 0L && leg.result == "OK"
                    },
                    isFailure = leg.result == "FAIL",
                )
            }
            acc.samples.removeAll { it.timestampMs < cutoff }
        }
        channelProbeLegs = channelProbeAccumulators.values.mapNotNull { acc ->
            val latest = acc.latest ?: return@mapNotNull null
            val samples = acc.samples
            val failureCount = samples.count { it.isFailure }
            val latencyValues = samples.mapNotNull { it.latencyMs }
            latest.copy(
                averageLatencyMs = if (latencyValues.isNotEmpty()) latencyValues.average().roundToInt().toLong() else null,
                failureRatePercent = if (samples.isNotEmpty()) ((failureCount * 100.0) / samples.size.toDouble()) else null,
                sampleCount = samples.size,
                lastProbeAtMs = acc.lastProbeAtMs,
            )
        }
    }

    private suspend fun sampleCurrentNetwork(reason: String) {
        val sample = withContext(Dispatchers.IO) {
            val cm = connectivityManager
            if (cm == null) {
                NetworkSample(
                    reason = reason,
                    timestampMs = System.currentTimeMillis(),
                    transport = "unknown",
                    validated = false,
                    metered = null,
                    roaming = null,
                    vpn = false,
                    proxy = null,
                    interfaceName = null,
                    localAddresses = emptyList(),
                    dnsServers = emptyList(),
                )
            } else {
                val active = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(active)
                val props = cm.getLinkProperties(active)
                NetworkSample(
                    reason = reason,
                    timestampMs = System.currentTimeMillis(),
                    transport = describeTransport(caps),
                    validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                    metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.not(),
                    roaming = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)?.not(),
                    vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                    proxy = resolveProxySummary(props),
                    interfaceName = props?.interfaceName,
                    localAddresses = props?.linkAddresses
                        ?.mapNotNull { it.address?.hostAddress?.trim()?.ifEmpty { null } }
                        .orEmpty(),
                    dnsServers = props?.dnsServers
                        ?.mapNotNull { it.hostAddress?.trim()?.ifEmpty { null } }
                        .orEmpty(),
                )
            }
        }
        val previous = networkSamples.lastOrNull()
        networkSamples += sample
        if (shouldLogNetworkChange(previous, sample)) {
            appendLog(
                "network",
                "网络采样[$reason] transport=${sample.transport} validated=${sample.validated} vpn=${sample.vpn} metered=${sample.meteredLabel()} roaming=${sample.roamingLabel()} proxy=${sample.proxy ?: "none"} iface=${sample.interfaceName ?: "unknown"}",
            )
        }
        refreshSummary()
    }

    private fun attachAppLogListener() {
        DiagnosticLogStore.snapshot()
            .filter(::shouldCaptureAppLog)
            .takeLast(20)
            .forEach { entry -> appendAppLog(entry, historical = true) }
        appLogListenerId = DiagnosticLogStore.registerListener { entry ->
            if (!shouldCaptureAppLog(entry)) return@registerListener
            viewModelScope.launch {
                appendAppLog(entry, historical = false)
            }
        }
    }

    private fun appendAppLog(entry: DiagnosticLogEntry, historical: Boolean) {
        val prefix = if (historical) "历史应用日志" else "应用日志"
        appendLog(
            "app",
            "$prefix ${entry.tag} ${entry.level.name.lowercase(Locale.US)} ${entry.message}",
            timestampMs = entry.timestampMs,
        )
    }

    private fun shouldCaptureAppLog(entry: DiagnosticLogEntry): Boolean {
        val tag = entry.tag.trim()
        return tag == "PrivateChannelClient"
            || tag == "PushGoApp"
            || tag == "WarpLinkNativeBridge"
            || tag == "PrivateChannelForegroundService"
            || tag == "PushGoMessagingService"
    }

    private fun attachNetworkCallback() {
        val cm = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                viewModelScope.launch { sampleCurrentNetwork(reason = "callback_available") }
            }

            override fun onLost(network: Network) {
                viewModelScope.launch { sampleCurrentNetwork(reason = "callback_lost") }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                viewModelScope.launch { sampleCurrentNetwork(reason = "callback_capabilities") }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                viewModelScope.launch { sampleCurrentNetwork(reason = "callback_link_properties") }
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure {
                appendLog("network", "注册系统网络回调失败: ${it.message ?: it::class.java.simpleName}")
            }
    }

    private fun appendConnectionSnapshot(
        snapshot: PrivateChannelClient.ConnectionSnapshot,
        source: String,
    ) {
        val transportStatus = snapshot.transportStatus
        val fingerprint = listOf(
            source,
            transportStatus.route,
            transportStatus.transport,
            transportStatus.stage,
            transportStatus.detail.orEmpty(),
            snapshot.keepaliveState.name,
            snapshot.networkAvailable,
            snapshot.privateModeEnabled,
            snapshot.selectionInsight?.inSessionProbeRttMs,
            snapshot.selectionInsight?.inSessionProbeSource,
            snapshot.selectionInsight?.inSessionProbeAtMs,
        ).joinToString("|")
        if (fingerprint == lastConnectionFingerprint) {
            return
        }
        lastConnectionFingerprint = fingerprint
        connectionEvents += ConnectionEvent(
            timestampMs = System.currentTimeMillis(),
            route = transportStatus.route,
            transport = transportStatus.transport,
            stage = transportStatus.stage,
            detail = transportStatus.detail,
            keepaliveState = snapshot.keepaliveState,
            networkAvailable = snapshot.networkAvailable,
            privateModeEnabled = snapshot.privateModeEnabled,
            selectionInsight = snapshot.selectionInsight?.let {
                TransportSelectionInsight(
                    transport = it.transport,
                    elapsedMs = it.elapsedMs,
                    reason = it.reason,
                    connectBudgetMs = it.connectBudgetMs,
                    tcpDelayMs = it.tcpDelayMs,
                    wssDelayMs = it.wssDelayMs,
                    inSessionProbeRttMs = it.inSessionProbeRttMs,
                    inSessionProbeSource = it.inSessionProbeSource,
                    inSessionProbeAtMs = it.inSessionProbeAtMs,
                    recordedAtMs = it.recordedAtMs,
                )
            },
        )
        if (snapshot.selectionInsight != null) {
            latestTransportSelectionInsight = connectionEvents.last().selectionInsight
        }
        appendLog(
            "transport",
            "连接状态[$source] route=${transportStatus.route} transport=${transportStatus.transport} stage=${transportStatus.stage} keepalive=${snapshot.keepaliveState.name.lowercase(Locale.US)} network=${snapshot.networkAvailable} detail=${transportStatus.detail ?: "-"}",
        )
        refreshSummary()
    }

    private suspend fun fetchGatewayProbe(): GatewayProbe = withContext(Dispatchers.IO) {
        val baseUrl = settingsRepository.getServerAddress()?.trim()?.ifEmpty { null }
            ?: AppConstants.defaultServerAddress
        val token = settingsRepository.getGatewayToken()?.trim()?.ifEmpty { null }
        val endpoint = "${baseUrl.removeSuffix("/")}/diagnostics/private/network"
        val startedAt = SystemClock.elapsedRealtime()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            defaultUseCaches = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            connectTimeout = GATEWAY_CONNECT_TIMEOUT_MS
            readTimeout = GATEWAY_READ_TIMEOUT_MS
        }
        return@withContext try {
            val httpStatus = connection.responseCode
            val stream = if (httpStatus in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
            val latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            val root = parseJsonObjectOrNull(body)
            if (root == null) {
                GatewayProbe(
                    timestampMs = System.currentTimeMillis(),
                    latencyMs = latencyMs,
                    success = false,
                    httpStatus = httpStatus,
                    observedClientIp = null,
                    natHint = null,
                    observedIpScope = null,
                    proxyDetected = false,
                    errorMessage = buildString {
                        append("status=$httpStatus")
                        sanitizeBodyForError(body)?.let {
                            append(" body=")
                            append(it)
                        }
                    },
                )
            } else if (!root.optBoolean("success", false)) {
                GatewayProbe(
                    timestampMs = System.currentTimeMillis(),
                    latencyMs = latencyMs,
                    success = false,
                    httpStatus = httpStatus,
                    observedClientIp = null,
                    natHint = null,
                    observedIpScope = null,
                    proxyDetected = false,
                    errorMessage = parseJsonErrorMessage(root) ?: "status=$httpStatus",
                )
            } else {
                val data = root.optJSONObject("data") ?: JSONObject()
                GatewayProbe(
                    timestampMs = System.currentTimeMillis(),
                    latencyMs = latencyMs,
                    success = true,
                    httpStatus = httpStatus,
                    observedClientIp = data.optString("observed_client_ip").trim().ifEmpty { null },
                    natHint = data.optString("nat_hint").trim().ifEmpty { null },
                    observedIpScope = data.optString("observed_ip_scope").trim().ifEmpty { null },
                    proxyDetected = data.optBoolean("proxy_detected", false),
                    errorMessage = null,
                )
            }
        } catch (error: Throwable) {
            GatewayProbe(
                timestampMs = System.currentTimeMillis(),
                latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                success = false,
                httpStatus = null,
                observedClientIp = null,
                natHint = null,
                observedIpScope = null,
                proxyDetected = false,
                errorMessage = error.message?.trim().ifNullOrBlank { error::class.java.simpleName },
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun completeDiagnosis(manuallyStopped: Boolean = false) {
        sessionProgressJob?.cancel()
        cleanupSessionResources()
        sessionFinishedAtMs = System.currentTimeMillis()
        if (!manuallyStopped) {
            sessionElapsedMs = sessionDurationMs
        }
        isRunning = false
        isCompleted = true
        if (manuallyStopped) {
            statusTitle = "诊断已停止"
            statusDetail = "已手动停止采集。可以复制或导出日志并附带给排查人员。"
        } else {
            statusTitle = "诊断已完成"
            statusDetail = "采集窗口结束，已停止继续记录。可以复制日志并附带给排查人员。"
        }
        appendLog("session", if (manuallyStopped) "诊断已手动停止，已生成可复制报告" else "诊断完成，已生成可复制报告")
        refreshSummary()
        reportText = buildReportText(redact = redactSensitiveData)
    }

    private fun cleanupSessionResources() {
        appLogListenerId?.let(DiagnosticLogStore::unregisterListener)
        appLogListenerId = null
        val cm = connectivityManager
        val callback = networkCallback
        if (cm != null && callback != null) {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
    }

    private fun resetSessionState() {
        sessionProgressJob?.cancel()
        cleanupSessionResources()
        sessionStartedAtMs = 0L
        sessionFinishedAtMs = 0L
        sessionDurationMs = sessionMode.durationMs
        sessionElapsedMs = 0L
        lastConnectionFingerprint = null
        networkSamples.clear()
        gatewayProbes.clear()
        connectionEvents.clear()
        channelProbeHistory.clear()
        sessionGatewayBaseUrl = null
        sessionGatewayToken = null
        sessionUseFcmChannel = false
        latestTransportSelectionInsight = null
        channelProbeAccumulators.clear()
        isRunning = false
        isCompleted = false
        statusTitle = "准备开始"
        statusDetail = "正在初始化诊断环境。"
        networkSummary = "尚未采集"
        natSummary = "尚未采集"
        proxySummary = "尚未采集"
        gatewaySummary = "尚未采集"
        channelProbeSummary = "尚未采集"
        transportSummary = "尚未采集"
        recommendation = "尚未形成诊断结论"
        logLines = emptyList()
        reportText = ""
        toastMessage = null
        latestChannelProbe = null
        channelProbeLegs = emptyList()
        channelProbeModeLabel = "unknown"
        channelProbeProfileRttMs = null
        protocolSelectionHistory = emptyList()
        gatewayAggregate = GatewayAggregate(
            total = 0,
            success = 0,
            failure = 0,
            avgLatencyMs = null,
            minLatencyMs = null,
            maxLatencyMs = null,
            latestError = null,
        )
        sessionId = ""
        clientCapabilities = ClientCapabilities(
            supportsGatewayHttpProbe = true,
            supportsPrivateTransportProfile = true,
            supportsQuicProbe = true,
            supportsTcpTlsProbe = true,
            supportsWssTlsProbe = true,
            supportsProviderProbe = true,
            supportsLogExportJson = true,
            supportsLogExportText = true,
        )
    }

    private fun refreshSummary() {
        networkSummary = buildNetworkSummary()
        natSummary = buildNatSummary()
        proxySummary = buildProxySummary()
        gatewaySummary = buildGatewaySummary()
        gatewayAggregate = buildGatewayAggregate()
        protocolSelectionHistory = buildProtocolSelectionHistory()
        channelProbeSummary = buildChannelProbeSummary()
        transportSummary = buildTransportSummary()
        recommendation = buildRecommendation()
        reportText = currentReportText()
    }

    private fun buildProtocolSelectionHistory(): List<ProtocolSelectionHistoryItem> {
        if (connectionEvents.isEmpty()) return emptyList()
        val records = mutableListOf<ProtocolSelectionHistoryItem>()
        var lastFingerprint: String? = null
        connectionEvents.forEach { event ->
            val insight = event.selectionInsight ?: return@forEach
            val transport = insight.transport.trim().ifEmpty { event.transport }.lowercase(Locale.US)
            if (transport == "none" || transport == "fcm") return@forEach
            val reason = insight.reason?.trim().takeUnless { it.isNullOrEmpty() }
                ?: event.detail?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "unknown"
            val record = ProtocolSelectionHistoryItem(
                timestampMs = maxOf(event.timestampMs, insight.recordedAtMs),
                transport = transport,
                reason = reason,
                elapsedMs = insight.elapsedMs,
                connectBudgetMs = insight.connectBudgetMs,
                tcpDelayMs = insight.tcpDelayMs,
                wssDelayMs = insight.wssDelayMs,
                inSessionProbeRttMs = insight.inSessionProbeRttMs,
                inSessionProbeSource = insight.inSessionProbeSource,
            )
            val fingerprint = buildString {
                append(record.transport)
                append('|')
                append(record.reason)
                append('|')
                append(record.elapsedMs ?: -1L)
                append('|')
                append(record.connectBudgetMs ?: -1L)
                append('|')
                append(record.tcpDelayMs ?: -1L)
                append('|')
                append(record.wssDelayMs ?: -1L)
            }
            if (fingerprint != lastFingerprint) {
                records += record
                lastFingerprint = fingerprint
            } else if (records.isNotEmpty()) {
                records[records.lastIndex] = record
            }
        }
        return records
    }

    private fun buildChannelProbeSummary(): String {
        val probe = latestChannelProbe ?: return "尚未探测"
        return probe.prettySummary
    }

    private fun buildNetworkSummary(): String {
        if (networkSamples.isEmpty()) return "尚未采集"
        val transports = networkSamples.map { it.transport }.distinct()
        val validatedCount = networkSamples.count { it.validated }
        val vpnSeen = networkSamples.any { it.vpn }
        return buildString {
            append(transports.joinToString(" -> "))
            append(" · 已验证 $validatedCount/${networkSamples.size}")
            if (vpnSeen) append(" · 期间检测到 VPN")
        }
    }

    private fun buildNatSummary(): String {
        val hints = gatewayProbes.mapNotNull { it.natHint }.distinct()
        val scopes = gatewayProbes.mapNotNull { it.observedIpScope }.distinct()
        if (hints.isEmpty() && scopes.isEmpty()) return "尚未拿到网关出口判断"
        return buildString {
            if (hints.isNotEmpty()) {
                append(hints.joinToString(" / "))
            }
            if (scopes.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append("ip_scope=")
                append(scopes.joinToString("/"))
            }
            append(" · 该结果基于网关反射，只能作为 NAT 提示，不等于完整 STUN 分类")
        }
    }

    private fun buildProxySummary(): String {
        val proxies = networkSamples.mapNotNull { it.proxy }.distinct()
        val gatewayProxyDetected = gatewayProbes.any { it.proxyDetected }
        return when {
            proxies.isEmpty() && !gatewayProxyDetected -> "未发现系统代理或网关代理迹象"
            proxies.isNotEmpty() && gatewayProxyDetected -> "系统代理=${proxies.joinToString()} · 网关侧也看到了代理头"
            proxies.isNotEmpty() -> "系统代理=${proxies.joinToString()}"
            else -> "本机未见系统代理，但网关侧看到了代理头"
        }
    }

    private fun buildGatewaySummary(): String {
        if (gatewayProbes.isEmpty()) return "尚未探测"
        val success = gatewayProbes.filter { it.success }
        if (success.isEmpty()) {
            val latest = gatewayProbes.last()
            return "连续失败 ${gatewayProbes.size} 次 · 最近错误=${latest.errorMessage ?: "unknown"}"
        }
        val avgLatency = success.map { it.latencyMs }.average().roundToInt()
        val minLatency = success.minOf { it.latencyMs }
        val maxLatency = success.maxOf { it.latencyMs }
        val failures = gatewayProbes.size - success.size
        return buildString {
            append("成功 ${success.size}/${gatewayProbes.size}")
            append(" · RTT ${minLatency}-${maxLatency}ms")
            append(" · 平均 ${avgLatency}ms")
            if (failures > 0) append(" · 失败 $failures 次")
        }
    }

    private fun buildGatewayAggregate(): GatewayAggregate {
        if (gatewayProbes.isEmpty()) {
            return GatewayAggregate(
                total = 0,
                success = 0,
                failure = 0,
                avgLatencyMs = null,
                minLatencyMs = null,
                maxLatencyMs = null,
                latestError = null,
            )
        }
        val success = gatewayProbes.filter { it.success }
        val latestError = gatewayProbes.lastOrNull { !it.success }?.errorMessage
        return GatewayAggregate(
            total = gatewayProbes.size,
            success = success.size,
            failure = gatewayProbes.size - success.size,
            avgLatencyMs = success.takeIf { it.isNotEmpty() }?.map { it.latencyMs }?.average()?.roundToInt(),
            minLatencyMs = success.takeIf { it.isNotEmpty() }?.minOf { it.latencyMs },
            maxLatencyMs = success.takeIf { it.isNotEmpty() }?.maxOf { it.latencyMs },
            latestError = latestError,
        )
    }

    private fun buildTransportSummary(): String {
        if (connectionEvents.isEmpty()) return "尚未采集"
        val privateModeEnabled = connectionEvents.any { it.privateModeEnabled }
        if (!privateModeEnabled) {
            return buildString {
                append("当前仍处于 FCM 模式，私有链路日志有限")
                latestChannelProbe?.let { probe ->
                    append(" · 独立测试=")
                    append(probe.mode)
                    append("/")
                    append(if (probe.success) "ok" else "fail")
                }
            }
        }
        val transports = connectionEvents
            .map { it.transport }
            .filter { it != "none" && it != "fcm" }
            .distinct()
        val reconnectCount = connectionEvents.count {
            it.stage in setOf("reconnecting", "backoff", "recovering", "closed", "goaway", "fgs_lost")
        }
        val connectedCount = connectionEvents.count { it.stage == "connected" }
        val last = connectionEvents.last()
        val latestSelection = connectionEvents
            .asReversed()
            .firstOrNull { it.selectionInsight != null }
            ?.selectionInsight
        return buildString {
            append("最近状态=${last.stage}/${last.transport}")
            if (transports.isNotEmpty()) {
                append(" · transport=")
                append(transports.joinToString("/"))
            }
            append(" · connected=$connectedCount")
            append(" · 重连/断链=$reconnectCount")
            append(" · keepalive=${last.keepaliveState.name.lowercase(Locale.US)}")
            latestChannelProbe?.let { probe ->
                append(" · probe=")
                append(probe.mode)
                append("/")
                append(if (probe.success) "ok" else "fail")
            }
            if (latestSelection != null) {
                append(" · selection=${latestSelection.transport}")
                latestSelection.elapsedMs?.let { append("@${it}ms") }
                latestSelection.reason?.takeIf { it.isNotBlank() }?.let {
                    append(" · reason=")
                    append(it)
                }
            }
        }
    }

    private fun buildRecommendation(): String {
        if (gatewayProbes.isEmpty()) {
            return "继续等待诊断窗口结束，以便形成更稳定的结论"
        }
        if (connectionEvents.isEmpty()) {
            return "继续等待诊断窗口结束，以便形成更稳定的结论"
        }
        val gatewayFailureRate = gatewayProbes.count { !it.success }.toDouble() / gatewayProbes.size.toDouble()
        val reconnectCount = connectionEvents.count {
            it.stage in setOf("reconnecting", "backoff", "recovering", "closed", "goaway", "fgs_lost")
        }
        val proxySeen = networkSamples.any { it.proxy != null } || gatewayProbes.any { it.proxyDetected }
        val networkChanged = networkSamples.map { it.transport }.distinct().size > 1
        return when {
            gatewayFailureRate >= 0.5 -> "网关探测失败占比过高，先排查当前网络到网关的基础可达性、DNS 与 TLS。"
            proxySeen -> "本次采集看到了代理迹象，优先排查代理、企业网关或系统 VPN 对长连接和 UDP 的影响。"
            reconnectCount >= 4 && gatewayFailureRate < 0.25 -> "HTTP 反射基本正常，但私有链路仍频繁回退，问题更像是 QUIC/TCP 长连接稳定性或系统后台策略。"
            networkChanged -> "诊断期间网络制式发生切换，断链可能与 Wi-Fi/蜂窝切换或验证状态抖动有关。"
            else -> "当前更像是单一网络环境下的私有通道稳定性问题，建议结合复制出的日志继续对照 gateway 侧连接记录。"
        }
    }

    private fun appendLog(category: String, message: String, timestampMs: Long = System.currentTimeMillis()) {
        val entry = ConnectionDiagnosisLogLine(
            timestampMs = timestampMs,
            category = category,
            message = message.trim(),
        )
        val next = (logLines + entry).takeLast(MAX_UI_LOGS)
        logLines = next
    }

    private fun currentReportText(): String {
        return if (sessionStartedAtMs <= 0L) {
            reportText
        } else {
            buildReportText(redact = redactSensitiveData)
        }
    }

    private fun buildReportText(redact: Boolean): String {
        val startedAt = if (sessionStartedAtMs > 0L) formatTimestamp(sessionStartedAtMs) else "-"
        val finishedAt = if (sessionFinishedAtMs > 0L) formatTimestamp(sessionFinishedAtMs) else "-"
        return buildString {
            appendLine("PushGo Android Connection Diagnosis")
            appendLine("schema_version=$DIAG_SCHEMA_VERSION")
            appendLine("session_id=${sessionId.ifBlank { "-" }}")
            appendLine("session_mode=${sessionMode.wireValue}")
            appendLine("diagnosis_profile=${diagnosisProfile.wireValue}")
            appendLine("started_at=$startedAt")
            appendLine("finished_at=$finishedAt")
            appendLine("status=$statusTitle")
            appendLine("status_detail=$statusDetail")
            appendLine("redact_sensitive_data=$redact")
            appendLine("network_summary=$networkSummary")
            appendLine("nat_summary=$natSummary")
            appendLine("proxy_summary=$proxySummary")
            appendLine("gateway_summary=$gatewaySummary")
            appendLine("channel_probe_summary=$channelProbeSummary")
            appendLine("transport_summary=$transportSummary")
            appendLine("recommendation=$recommendation")
            appendLine()
            appendLine("[channel_probe_history]")
            if (channelProbeHistory.isEmpty()) {
                appendLine("(empty)")
            } else {
                channelProbeHistory.forEach { probe ->
                    appendLine(
                        "${formatTimestamp(probe.timestampMs)} mode=${probe.mode} success=${probe.success} profile_rtt_ms=${probe.profileRttMs ?: -1}",
                    )
                    probe.legs.forEach { leg ->
                        appendLine(
                            "  leg=${leg.name} protocol=${leg.protocol} enabled=${leg.enabled} host=${redactHostField(leg.host, redact)} port=${leg.port} result=${leg.result} latency_ms=${leg.latencyMs} error_code=${leg.errorCode ?: "-"} note=${sanitizeText(leg.note ?: "-", redact)}",
                        )
                    }
                }
            }
            appendLine()
            appendLine("[selection_reason_history]")
            if (protocolSelectionHistory.isEmpty()) {
                appendLine("(empty)")
            } else {
                protocolSelectionHistory.forEach { record ->
                    appendLine(
                        buildString {
                            append(formatTimestamp(record.timestampMs))
                            append(" transport=")
                            append(record.transport)
                            append(" reason=")
                            append(sanitizeText(record.reason, redact))
                            record.elapsedMs?.let { append(" elapsed_ms=$it") }
                            record.connectBudgetMs?.let { append(" connect_budget_ms=$it") }
                            record.tcpDelayMs?.let { append(" tcp_delay_ms=$it") }
                            record.wssDelayMs?.let { append(" wss_delay_ms=$it") }
                            record.inSessionProbeRttMs?.let { append(" in_session_rtt_ms=$it") }
                            record.inSessionProbeSource?.takeIf { it.isNotBlank() }?.let {
                                append(" in_session_source=")
                                append(it)
                            }
                        },
                    )
                }
            }
            appendLine()
            appendLine("[logs]")
            logLines.forEach { line ->
                appendLine("${formatTimestamp(line.timestampMs)} [${line.category}] ${sanitizeText(line.message, redact)}")
            }
        }.trim()
    }

    private fun buildReportJsonText(scope: DiagnosisExportScope, redact: Boolean): String {
        val scopedLegs = when (scope) {
            DiagnosisExportScope.ALL -> latestChannelProbe?.legs.orEmpty()
            DiagnosisExportScope.FAIL_ONLY -> latestChannelProbe?.legs.orEmpty().filter { it.result != "OK" }
        }
        val root = JSONObject().apply {
            put("schema_version", DIAG_SCHEMA_VERSION)
            put("name", "PushGo Android Connection Diagnosis")
            put("session_id", sessionId.ifBlank { JSONObject.NULL })
            put("started_at", if (sessionStartedAtMs > 0L) formatTimestamp(sessionStartedAtMs) else "-")
            put("finished_at", if (sessionFinishedAtMs > 0L) formatTimestamp(sessionFinishedAtMs) else "-")
            put("status", statusTitle)
            put("status_detail", statusDetail)
            put("session_mode", sessionMode.wireValue)
            put("diagnosis_profile", diagnosisProfile.wireValue)
            put("export_scope", scope.wireValue)
            put("redact_sensitive_data", redact)
            put(
                "client_capabilities",
                JSONObject().apply {
                    put("supports_gateway_http_probe", clientCapabilities.supportsGatewayHttpProbe)
                    put("supports_private_transport_profile", clientCapabilities.supportsPrivateTransportProfile)
                    put("supports_quic_probe", clientCapabilities.supportsQuicProbe)
                    put("supports_tcp_tls_probe", clientCapabilities.supportsTcpTlsProbe)
                    put("supports_wss_tls_probe", clientCapabilities.supportsWssTlsProbe)
                    put("supports_provider_probe", clientCapabilities.supportsProviderProbe)
                    put("supports_log_export_json", clientCapabilities.supportsLogExportJson)
                    put("supports_log_export_text", clientCapabilities.supportsLogExportText)
                },
            )
            put(
                "summary",
                JSONObject().apply {
                    put("network", networkSummary)
                    put("nat", natSummary)
                    put("proxy", proxySummary)
                    put("gateway", sanitizeText(gatewaySummary, redact))
                    put("channel_probe", channelProbeSummary)
                    put("transport", transportSummary)
                    put("recommendation", recommendation)
                },
            )
            put(
                "channel_probe",
                latestChannelProbe?.let { probe ->
                    JSONObject().apply {
                        put("mode", probe.mode)
                        put("success", probe.success)
                        put("profile_rtt_ms", probe.profileRttMs ?: JSONObject.NULL)
                        put("summary", probe.summary)
                        put("pretty_summary", probe.prettySummary)
                        put(
                            "legs",
                            JSONArray().apply {
                                scopedLegs.forEach { leg ->
                                    put(
                                        JSONObject().apply {
                                            put("name", leg.name)
                                            put("protocol", leg.protocol)
                                            put("enabled", leg.enabled)
                                            put("host", redactHostField(leg.host, redact))
                                            put("port", leg.port)
                                            put("path", leg.path ?: JSONObject.NULL)
                                            put("subprotocol", leg.subprotocol ?: JSONObject.NULL)
                                            put("result", leg.result)
                                            put("latency_ms", leg.latencyMs)
                                            put("error_code", leg.errorCode ?: JSONObject.NULL)
                                            put("error_detail", leg.errorDetail?.let { sanitizeText(it, redact) } ?: JSONObject.NULL)
                                            put("note", leg.note ?: JSONObject.NULL)
                                        },
                                    )
                                }
                            },
                        )
                    }
                } ?: JSONObject.NULL,
            )
            put(
                "channel_probe_history",
                JSONArray().apply {
                    channelProbeHistory.forEach { probe ->
                        put(
                            JSONObject().apply {
                                put("timestamp", formatTimestamp(probe.timestampMs))
                                put("timestamp_ms", probe.timestampMs)
                                put("mode", probe.mode)
                                put("success", probe.success)
                                put("profile_rtt_ms", probe.profileRttMs ?: JSONObject.NULL)
                                put(
                                    "legs",
                                    JSONArray().apply {
                                        probe.legs.forEach { leg ->
                                            put(
                                                JSONObject().apply {
                                                    put("name", leg.name)
                                                    put("protocol", leg.protocol)
                                                    put("enabled", leg.enabled)
                                                    put("host", redactHostField(leg.host, redact))
                                                    put("port", leg.port)
                                                    put("path", leg.path ?: JSONObject.NULL)
                                                    put("subprotocol", leg.subprotocol ?: JSONObject.NULL)
                                                    put("result", leg.result)
                                                    put("latency_ms", leg.latencyMs)
                                                    put("error_code", leg.errorCode ?: JSONObject.NULL)
                                                    put("error_detail", leg.errorDetail?.let { sanitizeText(it, redact) } ?: JSONObject.NULL)
                                                    put("note", leg.note?.let { sanitizeText(it, redact) } ?: JSONObject.NULL)
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put(
                "selection_reason_history",
                JSONArray().apply {
                    protocolSelectionHistory.forEach { record ->
                        put(
                            JSONObject().apply {
                                put("timestamp", formatTimestamp(record.timestampMs))
                                put("timestamp_ms", record.timestampMs)
                                put("transport", record.transport)
                                put("reason", sanitizeText(record.reason, redact))
                                put("elapsed_ms", record.elapsedMs ?: JSONObject.NULL)
                                put("connect_budget_ms", record.connectBudgetMs ?: JSONObject.NULL)
                                put("tcp_delay_ms", record.tcpDelayMs ?: JSONObject.NULL)
                                put("wss_delay_ms", record.wssDelayMs ?: JSONObject.NULL)
                                put("in_session_rtt_ms", record.inSessionProbeRttMs ?: JSONObject.NULL)
                                put("in_session_source", record.inSessionProbeSource ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
            put(
                "logs",
                JSONArray().apply {
                    logLines.forEach { line ->
                        put(
                            JSONObject().apply {
                                put("timestamp", formatTimestamp(line.timestampMs))
                                put("timestamp_ms", line.timestampMs)
                                put("category", line.category)
                                put("message", sanitizeText(line.message, redact))
                            },
                        )
                    }
                },
            )
        }
        return root.toString(2)
    }

    private fun buildClientCapabilities(useFcmChannel: Boolean): ClientCapabilities {
        return ClientCapabilities(
            supportsGatewayHttpProbe = true,
            supportsPrivateTransportProfile = true,
            supportsQuicProbe = true,
            supportsTcpTlsProbe = true,
            supportsWssTlsProbe = true,
            supportsProviderProbe = useFcmChannel,
            supportsLogExportJson = true,
            supportsLogExportText = true,
        )
    }

    private fun sanitizeText(input: String, redact: Boolean): String {
        if (!redact) return input
        val ipRedacted = input
            .replace(Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b"""), "***.***.***.***")
            .replace(Regex("""\b(?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F]{1,4}\b"""), "[ipv6-redacted]")
        return ipRedacted
            .replace(Regex("""(?i)(authorization=)([^ \n]+)"""), "$1[redacted]")
            .replace(Regex("""(?i)(\btoken=)([^ \n]+)"""), "$1[redacted]")
            .replace(Regex("""(?i)(device_key=)([^ \n]+)"""), "$1[redacted]")
    }

    private fun redactHostField(host: String, redact: Boolean): String {
        if (!redact) return host
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return host
        return when {
            IPV4_REGEX.matches(trimmed) -> "***.***.***.***"
            IPV6_REGEX.containsMatchIn(trimmed) -> "[ipv6-redacted]"
            else -> trimmed
        }
    }

    private fun describeTransport(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "unknown"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }
        return transports.joinToString("+").ifBlank { "unknown" }
    }

    private fun resolveProxySummary(linkProperties: LinkProperties?): String? {
        val proxy = linkProperties?.httpProxy
        val fromNetwork = if (proxy != null && proxy.host != null && proxy.port > 0) {
            "${proxy.host}:${proxy.port}"
        } else {
            null
        }
        if (!fromNetwork.isNullOrBlank()) {
            return fromNetwork
        }
        val systemProxyHost = System.getProperty("http.proxyHost")?.trim().takeUnless { it.isNullOrBlank() }
        val systemProxyPort = System.getProperty("http.proxyPort")?.trim().takeUnless { it.isNullOrBlank() }
        return when {
            systemProxyHost != null && systemProxyPort != null -> "$systemProxyHost:$systemProxyPort"
            systemProxyHost != null -> systemProxyHost
            else -> null
        }
    }

    private fun shouldLogNetworkChange(previous: NetworkSample?, current: NetworkSample): Boolean {
        if (previous == null) return true
        return previous.transport != current.transport
            || previous.validated != current.validated
            || previous.vpn != current.vpn
            || previous.proxy != current.proxy
            || previous.interfaceName != current.interfaceName
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .format(TIME_FORMATTER)
    }

    private suspend fun runFcmChannelProbe(
        gatewayBaseUrl: String,
        gatewayToken: String?,
    ): ChannelProbeResult = withContext(Dispatchers.IO) {
        val fcmToken = settingsRepository.getFcmToken()?.trim()?.ifEmpty { null }
        val tlsProbe = probeTlsReachability(FCM_PROBE_HOST, FCM_PROBE_PORT)
        val success = tlsProbe.outcome.startsWith("ok")
        val tokenPresent = fcmToken != null
        ChannelProbeResult(
            mode = "fcm",
            success = success,
            profileRttMs = null,
            summary = "fcm · ${if (success) "ok" else "fail"} · host=$FCM_PROBE_HOST port=$FCM_PROBE_PORT token_present=$tokenPresent tls=${tlsProbe.outcome}/${tlsProbe.latencyMs}ms",
            prettySummary = buildString {
                append("[FCM] ${if (success) "OK" else "FAIL"}\n")
                append("  host=$FCM_PROBE_HOST port=$FCM_PROBE_PORT token_present=$tokenPresent\n")
                append("  tls=${tlsProbe.outcome} latency=${tlsProbe.latencyMs}ms")
                tlsProbe.errorCode?.let { append(" code=$it") }
            },
            legs = listOf(
                ChannelProbeLeg(
                    name = "fcm",
                    protocol = "tls",
                    enabled = true,
                    host = FCM_PROBE_HOST,
                    port = FCM_PROBE_PORT,
                    path = null,
                    subprotocol = null,
                    result = tlsProbe.resultLabel,
                    latencyMs = tlsProbe.latencyMs,
                    errorCode = tlsProbe.errorCode,
                    errorDetail = tlsProbe.errorDetail,
                    note = if (tokenPresent) "token_present=true" else "token_present=false",
                ),
            ),
            logLine = "FCM TLS 链路探测 gateway=$gatewayBaseUrl auth_token_present=${gatewayToken != null} host=$FCM_PROBE_HOST port=$FCM_PROBE_PORT token_present=$tokenPresent result=${tlsProbe.outcome} latency=${tlsProbe.latencyMs}ms",
        )
    }

    private suspend fun runPrivateChannelProbe(
        gatewayBaseUrl: String,
        gatewayToken: String?,
    ): ChannelProbeResult = withContext(Dispatchers.IO) {
        val profileEndpoint = "${gatewayBaseUrl.removeSuffix("/")}/gateway/profile"
        val profileResult = executeJsonRequest(
            endpoint = profileEndpoint,
            token = gatewayToken,
            method = "GET",
            payload = null,
        )
        if (!profileResult.success || profileResult.body == null) {
            return@withContext ChannelProbeResult(
                mode = "private",
                success = false,
                profileRttMs = profileResult.latencyMs,
                summary = "private · fail · /gateway/profile 不可用 · rtt=${profileResult.latencyMs}ms",
                prettySummary = "[PRIVATE] FAIL\n  /gateway/profile status=${profileResult.httpStatus ?: -1} latency=${profileResult.latencyMs}ms code=${profileResult.errorCode ?: "HTTP_FAIL"} error=${profileResult.error ?: "unknown"}",
                legs = emptyList(),
                logLine = "私有通道测试失败：/gateway/profile status=${profileResult.httpStatus ?: -1} rtt=${profileResult.latencyMs}ms error=${profileResult.error ?: "unknown"}",
            )
        }

        val root = profileResult.body
        val data = root.optJSONObject("data") ?: JSONObject()
        val privateEnabled = data.optBoolean(
            "private_channel_enabled",
            data.optBoolean("private_enabled", false),
        )
        if (!privateEnabled) {
            return@withContext ChannelProbeResult(
                mode = "private",
                success = false,
                profileRttMs = profileResult.latencyMs,
                summary = "private · fail · gateway private channel disabled · rtt=${profileResult.latencyMs}ms",
                prettySummary = "[PRIVATE] FAIL\n  gateway private channel disabled\n  /gateway/profile latency=${profileResult.latencyMs}ms",
                legs = emptyList(),
                logLine = "私有通道测试失败：gateway private channel disabled rtt=${profileResult.latencyMs}ms",
            )
        }
        val transport = data.optJSONObject("transport") ?: JSONObject()
        val quicEnabled = transport.optBoolean("quic_enabled", true)
        val quicPort = transport.optInt("quic_port", 443).coerceAtLeast(1)
        val tcpPort = transport.optInt("tcp_port", 5223).coerceAtLeast(1)
        val wssPort = transport.optInt("wss_port", 443).coerceAtLeast(1)

        val host = runCatching {
            val uri = URI(gatewayBaseUrl.trim())
            uri.host?.trim()?.ifEmpty { null }
        }.getOrNull()
        if (host.isNullOrBlank()) {
            return@withContext ChannelProbeResult(
                mode = "private",
                success = false,
                profileRttMs = profileResult.latencyMs,
                summary = "private · fail · 网关主机名无效",
                prettySummary = "[PRIVATE] FAIL\n  网关主机名无效 gateway=$gatewayBaseUrl",
                legs = emptyList(),
                logLine = "私有通道测试失败：无法从 gateway 解析主机名 gateway=$gatewayBaseUrl",
            )
        }

        val quicProbe = if (quicEnabled) probeUdpReachability(host, quicPort) else ChannelLegProbe("skipped(disabled)", 0L)
        val tcpProbe = probeTlsReachability(host, tcpPort)
        val wssProbe = if (transport.optBoolean("wss_enabled", true)) {
            probeTlsReachability(host, wssPort)
        } else {
            ChannelLegProbe("skipped(disabled)", 0L)
        }
        val success = listOf(quicProbe, tcpProbe, wssProbe).any { it.outcome.startsWith("ok") }
        val summary = buildString {
            append("private · ${if (success) "ok" else "fail"}")
            append(" · profile_rtt=${profileResult.latencyMs}ms")
            append(" · quic[enabled=$quicEnabled host=$host port=$quicPort result=${quicProbe.outcome} latency=${quicProbe.latencyMs}ms]")
            append(" · tcp[enabled=${transport.optBoolean("tcp_enabled", true)} host=$host port=$tcpPort result=${tcpProbe.outcome} latency=${tcpProbe.latencyMs}ms]")
            append(" · wss[enabled=${transport.optBoolean("wss_enabled", true)} host=$host port=$wssPort path=${transport.optString("wss_path").trim().ifEmpty { "-" }} subprotocol=${transport.optString("ws_subprotocol").trim().ifEmpty { "-" }} result=${wssProbe.outcome} latency=${wssProbe.latencyMs}ms]")
        }
        val prettySummary = buildString {
            append("[PRIVATE] ${if (success) "OK" else "FAIL"}  profile_rtt=${profileResult.latencyMs}ms\n")
            append("  [QUIC] enabled=$quicEnabled host=$host port=$quicPort result=${quicProbe.outcome} latency=${quicProbe.latencyMs}ms")
            quicProbe.errorCode?.let { append(" code=$it") }
            append("\n")
            append("  [TCP ] enabled=${transport.optBoolean("tcp_enabled", true)} host=$host port=$tcpPort result=${tcpProbe.outcome} latency=${tcpProbe.latencyMs}ms")
            tcpProbe.errorCode?.let { append(" code=$it") }
            append("\n")
            append("  [WSS ] enabled=${transport.optBoolean("wss_enabled", true)} host=$host port=$wssPort path=${transport.optString("wss_path").trim().ifEmpty { "-" }} subprotocol=${transport.optString("ws_subprotocol").trim().ifEmpty { "-" }} result=${wssProbe.outcome} latency=${wssProbe.latencyMs}ms")
            wssProbe.errorCode?.let { append(" code=$it") }
        }
        ChannelProbeResult(
            mode = "private",
            success = success,
            profileRttMs = profileResult.latencyMs,
            summary = summary,
            prettySummary = prettySummary,
            legs = listOf(
                ChannelProbeLeg(
                    name = "quic",
                    protocol = "udp",
                    enabled = quicEnabled,
                    host = host,
                    port = quicPort,
                    path = null,
                    subprotocol = null,
                    result = quicProbe.resultLabel,
                    latencyMs = quicProbe.latencyMs,
                    errorCode = quicProbe.errorCode,
                    errorDetail = quicProbe.errorDetail,
                    note = "udp send-only probe; not round-trip latency",
                ),
                ChannelProbeLeg(
                    name = "tcp",
                    protocol = "tls",
                    enabled = transport.optBoolean("tcp_enabled", true),
                    host = host,
                    port = tcpPort,
                    path = null,
                    subprotocol = null,
                    result = tcpProbe.resultLabel,
                    latencyMs = tcpProbe.latencyMs,
                    errorCode = tcpProbe.errorCode,
                    errorDetail = tcpProbe.errorDetail,
                    note = null,
                ),
                ChannelProbeLeg(
                    name = "wss",
                    protocol = "tls",
                    enabled = transport.optBoolean("wss_enabled", true),
                    host = host,
                    port = wssPort,
                    path = transport.optString("wss_path").trim().ifEmpty { null },
                    subprotocol = transport.optString("ws_subprotocol").trim().ifEmpty { null },
                    result = wssProbe.resultLabel,
                    latencyMs = wssProbe.latencyMs,
                    errorCode = wssProbe.errorCode,
                    errorDetail = wssProbe.errorDetail,
                    note = null,
                ),
            ),
            logLine = summary,
        )
    }

    private fun probeUdpReachability(host: String, port: Int): ChannelLegProbe {
        var outcome = "ok(send)"
        var errorCode: String? = null
        var errorDetail: String? = null
        val elapsedMs = measureTimeMillis {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = CHANNEL_PROBE_CONNECT_TIMEOUT_MS
                    socket.connect(InetSocketAddress(host, port))
                    val probe = "pushgo-diag".toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(probe, probe.size))
                }
            }.onFailure {
                val normalized = normalizeNetworkFailure(it, protocol = "udp")
                outcome = "fail(${normalized.code})"
                errorCode = normalized.code
                errorDetail = normalized.detail
            }
        }.coerceAtLeast(0L)
        return ChannelLegProbe(
            outcome = outcome,
            latencyMs = elapsedMs,
            errorCode = errorCode,
            errorDetail = errorDetail,
        )
    }

    private fun probeTlsReachability(host: String, port: Int): ChannelLegProbe {
        var outcome = "ok(tls)"
        var errorCode: String? = null
        var errorDetail: String? = null
        val elapsedMs = measureTimeMillis {
            runCatching {
                val factory = createFreshSslSocketFactory()
                factory.createSocket().use { raw ->
                    raw.connect(InetSocketAddress(host, port), CHANNEL_PROBE_CONNECT_TIMEOUT_MS)
                    val socket = raw as javax.net.ssl.SSLSocket
                    socket.soTimeout = CHANNEL_PROBE_CONNECT_TIMEOUT_MS
                    socket.startHandshake()
                }
            }.onFailure {
                val normalized = normalizeNetworkFailure(it, protocol = "tls")
                outcome = "fail(${normalized.code})"
                errorCode = normalized.code
                errorDetail = normalized.detail
            }
        }.coerceAtLeast(0L)
        return ChannelLegProbe(
            outcome = outcome,
            latencyMs = elapsedMs,
            errorCode = errorCode,
            errorDetail = errorDetail,
        )
    }

    private fun executeJsonRequest(
        endpoint: String,
        token: String?,
        method: String,
        payload: JSONObject?,
    ): JsonRequestResult {
        val startedAt = SystemClock.elapsedRealtime()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            defaultUseCaches = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
            if (payload != null) {
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            connectTimeout = GATEWAY_CONNECT_TIMEOUT_MS
            readTimeout = GATEWAY_READ_TIMEOUT_MS
        }
        return try {
            val bytes = payload?.toString()?.toByteArray(Charsets.UTF_8)
            connection.doOutput = bytes != null
            if (bytes != null) {
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { output -> output.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bodyText = stream?.use { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
            val bodyJson = parseJsonObjectOrNull(bodyText)
            val success = if (bodyJson != null) {
                bodyJson.optBoolean("success", false) && status in 200..299
            } else {
                status in 200..299
            }
            val error = if (success) {
                null
            } else {
                bodyJson?.let(::parseJsonErrorMessage)
                    ?: buildString {
                        append("status=$status")
                        sanitizeBodyForError(bodyText)?.let {
                            append(" body=")
                            append(it)
                        }
                    }
            }
            val errorCode = if (success) null else deriveHttpErrorCode(status)
            JsonRequestResult(
                success = success,
                httpStatus = status,
                body = bodyJson,
                error = error,
                errorCode = errorCode,
                latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
            )
        } catch (error: Throwable) {
            val normalized = normalizeNetworkFailure(error, protocol = "http")
            JsonRequestResult(
                success = false,
                httpStatus = null,
                body = null,
                error = normalized.detail,
                errorCode = normalized.code,
                latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonObjectOrNull(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private fun sanitizeBodyForError(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.replace('\n', ' ').take(80).ifEmpty { null }
    }

    private fun parseJsonErrorMessage(root: JSONObject): String? {
        val value = root.opt("error")
        return when (value) {
            null, JSONObject.NULL -> null
            is String -> value.trim().ifEmpty { null }
            is JSONObject, is JSONArray -> value.toString()
            else -> value.toString().trim().ifEmpty { null }
        }
    }

    private fun deriveHttpErrorCode(status: Int): String {
        return when (status) {
            401 -> "HTTP_401"
            403 -> "HTTP_403"
            404 -> "HTTP_404"
            429 -> "HTTP_429"
            in 500..599 -> "HTTP_5XX"
            else -> "HTTP_${status.coerceAtLeast(0)}"
        }
    }

    private fun normalizeNetworkFailure(error: Throwable, protocol: String): NormalizedProbeError {
        val detail = error.message?.trim().ifNullOrBlank { error::class.java.simpleName }.take(120)
        val code = when (error) {
            is UnknownHostException -> "DNS_FAIL"
            is SocketTimeoutException -> when (protocol) {
                "tls" -> "TLS_TIMEOUT"
                "udp" -> "UDP_TIMEOUT"
                "http" -> "HTTP_TIMEOUT"
                else -> "TIMEOUT"
            }
            is ConnectException -> "TCP_CONNECT_FAIL"
            is SSLHandshakeException -> "TLS_HANDSHAKE_FAIL"
            is SSLException -> "TLS_FAIL"
            else -> when (protocol) {
                "tls" -> "TLS_FAIL"
                "udp" -> "UDP_FAIL"
                "http" -> "HTTP_FAIL"
                else -> "NETWORK_FAIL"
            }
        }
        return NormalizedProbeError(code = code, detail = detail)
    }

    private fun createFreshSslSocketFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, null, null)
        return context.socketFactory
    }

    private fun runEnhancedProbeInternal(leg: ChannelProbeLeg): EnhancedProbeReport {
        val startedAtMs = System.currentTimeMillis()
        val stages = mutableListOf<EnhancedProbeStage>()
        val traceNote = "Android 常规权限下无法稳定获取逐跳路由（traceroute/ICMP），本结果为分阶段定位。"

        val dnsStart = SystemClock.elapsedRealtime()
        val dnsResult = runCatching {
            InetAddress.getAllByName(leg.host).mapNotNull { it.hostAddress?.trim()?.ifEmpty { null } }
        }
        val dnsElapsed = (SystemClock.elapsedRealtime() - dnsStart).coerceAtLeast(0L)
        val resolvedIps = dnsResult.getOrNull().orEmpty()
        val dnsStage = if (dnsResult.isSuccess && resolvedIps.isNotEmpty()) {
            EnhancedProbeStage(
                name = "dns_resolve",
                result = "OK",
                latencyMs = dnsElapsed,
                detail = resolvedIps.joinToString(", "),
            )
        } else {
            val normalized = normalizeNetworkFailure(
                dnsResult.exceptionOrNull() ?: UnknownHostException("dns lookup empty"),
                protocol = "dns",
            )
            EnhancedProbeStage(
                name = "dns_resolve",
                result = "FAIL",
                latencyMs = dnsElapsed,
                detail = "${normalized.code}: ${normalized.detail}",
            )
        }
        stages += dnsStage
        if (dnsStage.result == "FAIL") {
            return EnhancedProbeReport(
                leg = leg,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                traceNote = traceNote,
                stages = stages,
                failedAt = dnsStage.name,
            )
        }

        if (leg.protocol.equals("udp", ignoreCase = true)) {
            val udpStart = SystemClock.elapsedRealtime()
            val udpStage = runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = CHANNEL_PROBE_CONNECT_TIMEOUT_MS
                    socket.connect(InetSocketAddress(leg.host, leg.port))
                    val payload = "pushgo-enhanced-diag".toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(payload, payload.size))
                }
                EnhancedProbeStage(
                    name = "udp_send",
                    result = "OK",
                    latencyMs = (SystemClock.elapsedRealtime() - udpStart).coerceAtLeast(0L),
                    detail = "udp packet sent to ${leg.host}:${leg.port}",
                )
            }.getOrElse { error ->
                val normalized = normalizeNetworkFailure(error, protocol = "udp")
                EnhancedProbeStage(
                    name = "udp_send",
                    result = "FAIL",
                    latencyMs = (SystemClock.elapsedRealtime() - udpStart).coerceAtLeast(0L),
                    detail = "${normalized.code}: ${normalized.detail}",
                )
            }
            stages += udpStage
            return EnhancedProbeReport(
                leg = leg,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                traceNote = traceNote,
                stages = stages,
                failedAt = stages.firstOrNull { it.result == "FAIL" }?.name,
            )
        }

        val tcpStart = SystemClock.elapsedRealtime()
        val tcpStage = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(leg.host, leg.port), CHANNEL_PROBE_CONNECT_TIMEOUT_MS)
            }
            EnhancedProbeStage(
                name = "tcp_connect",
                result = "OK",
                latencyMs = (SystemClock.elapsedRealtime() - tcpStart).coerceAtLeast(0L),
                detail = "connected ${leg.host}:${leg.port}",
            )
        }.getOrElse { error ->
            val normalized = normalizeNetworkFailure(error, protocol = "tcp")
            EnhancedProbeStage(
                name = "tcp_connect",
                result = "FAIL",
                latencyMs = (SystemClock.elapsedRealtime() - tcpStart).coerceAtLeast(0L),
                detail = "${normalized.code}: ${normalized.detail}",
            )
        }
        stages += tcpStage
        if (tcpStage.result == "FAIL") {
            return EnhancedProbeReport(
                leg = leg,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                traceNote = traceNote,
                stages = stages,
                failedAt = tcpStage.name,
            )
        }

        val tlsStart = SystemClock.elapsedRealtime()
        val tlsStage = runCatching {
            val factory = createFreshSslSocketFactory()
            factory.createSocket().use { raw ->
                raw.connect(InetSocketAddress(leg.host, leg.port), CHANNEL_PROBE_CONNECT_TIMEOUT_MS)
                val socket = raw as javax.net.ssl.SSLSocket
                socket.soTimeout = CHANNEL_PROBE_CONNECT_TIMEOUT_MS
                socket.startHandshake()
            }
            EnhancedProbeStage(
                name = "tls_handshake",
                result = "OK",
                latencyMs = (SystemClock.elapsedRealtime() - tlsStart).coerceAtLeast(0L),
                detail = "tls handshake success",
            )
        }.getOrElse { error ->
            val normalized = normalizeNetworkFailure(error, protocol = "tls")
            EnhancedProbeStage(
                name = "tls_handshake",
                result = "FAIL",
                latencyMs = (SystemClock.elapsedRealtime() - tlsStart).coerceAtLeast(0L),
                detail = "${normalized.code}: ${normalized.detail}",
            )
        }
        stages += tlsStage

        return EnhancedProbeReport(
            leg = leg,
            startedAtMs = startedAtMs,
            finishedAtMs = System.currentTimeMillis(),
            traceNote = traceNote,
            stages = stages,
            failedAt = stages.firstOrNull { it.result == "FAIL" }?.name,
        )
    }
}

data class ConnectionDiagnosisLogLine(
    val timestampMs: Long,
    val category: String,
    val message: String,
)

private data class NetworkSample(
    val reason: String,
    val timestampMs: Long,
    val transport: String,
    val validated: Boolean,
    val metered: Boolean?,
    val roaming: Boolean?,
    val vpn: Boolean,
    val proxy: String?,
    val interfaceName: String?,
    val localAddresses: List<String>,
    val dnsServers: List<String>,
) {
    fun meteredLabel(): String = when (metered) {
        true -> "yes"
        false -> "no"
        null -> "unknown"
    }

    fun roamingLabel(): String = when (roaming) {
        true -> "yes"
        false -> "no"
        null -> "unknown"
    }
}

private data class GatewayProbe(
    val timestampMs: Long,
    val latencyMs: Long,
    val success: Boolean,
    val httpStatus: Int?,
    val observedClientIp: String?,
    val natHint: String?,
    val observedIpScope: String?,
    val proxyDetected: Boolean,
    val errorMessage: String?,
)

private data class ConnectionEvent(
    val timestampMs: Long,
    val route: String,
    val transport: String,
    val stage: String,
    val detail: String?,
    val keepaliveState: KeepaliveState,
    val networkAvailable: Boolean,
    val privateModeEnabled: Boolean,
    val selectionInsight: TransportSelectionInsight?,
)

private data class TransportSelectionInsight(
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

private data class ChannelProbeResult(
    val mode: String,
    val success: Boolean,
    val profileRttMs: Long?,
    val summary: String,
    val prettySummary: String,
    val legs: List<ChannelProbeLeg>,
    val logLine: String,
)

private data class ChannelProbeSnapshot(
    val timestampMs: Long,
    val mode: String,
    val success: Boolean,
    val profileRttMs: Long?,
    val legs: List<ChannelProbeLeg>,
)

private data class JsonRequestResult(
    val success: Boolean,
    val httpStatus: Int?,
    val body: JSONObject?,
    val error: String?,
    val errorCode: String?,
    val latencyMs: Long,
)

private data class ChannelLegProbe(
    val outcome: String,
    val latencyMs: Long,
    val errorCode: String? = null,
    val errorDetail: String? = null,
) {
    val resultLabel: String
        get() = when {
            outcome.startsWith("ok") -> "OK"
            outcome.startsWith("skipped") -> "SKIPPED"
            else -> "FAIL"
        }
}

private data class NormalizedProbeError(
    val code: String,
    val detail: String,
)

enum class DiagnosisExportFormat {
    TEXT,
    JSON,
}

enum class DiagnosisExportScope(val wireValue: String) {
    ALL("all"),
    FAIL_ONLY("fail_only"),
}

enum class DiagnosisSessionMode(
    val wireValue: String,
    val durationMs: Long,
    val label: String,
) {
    QUICK("quick", 30_000L, "快速"),
    FULL("full", 75_000L, "完整"),
}

enum class DiagnosisProfile(
    val wireValue: String,
    val label: String,
    val collectTransportSnapshots: Boolean,
    val periodicChannelProbe: Boolean,
    val networkSampleIntervalMs: Long,
    val gatewayProbeIntervalMs: Long,
) {
    BALANCED(
        wireValue = "balanced",
        label = "平衡诊断",
        collectTransportSnapshots = true,
        periodicChannelProbe = false,
        networkSampleIntervalMs = 5_000L,
        gatewayProbeIntervalMs = 12_000L,
    ),
    GATEWAY_REACHABILITY(
        wireValue = "gateway_reachability",
        label = "网关可达性",
        collectTransportSnapshots = false,
        periodicChannelProbe = false,
        networkSampleIntervalMs = 4_000L,
        gatewayProbeIntervalMs = 8_000L,
    ),
    LINK_STABILITY(
        wireValue = "link_stability",
        label = "链路稳定性",
        collectTransportSnapshots = true,
        periodicChannelProbe = true,
        networkSampleIntervalMs = 5_000L,
        gatewayProbeIntervalMs = 12_000L,
    ),
}

data class ChannelProbeLeg(
    val name: String,
    val protocol: String,
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val path: String?,
    val subprotocol: String?,
    val result: String,
    val latencyMs: Long,
    val errorCode: String?,
    val errorDetail: String?,
    val note: String?,
    val averageLatencyMs: Long? = null,
    val failureRatePercent: Double? = null,
    val sampleCount: Int = 0,
    val lastProbeAtMs: Long? = null,
)

data class EnhancedProbeStage(
    val name: String,
    val result: String,
    val latencyMs: Long,
    val detail: String,
)

data class EnhancedProbeReport(
    val leg: ChannelProbeLeg,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val traceNote: String,
    val stages: List<EnhancedProbeStage>,
    val failedAt: String?,
)

sealed interface EnhancedProbeState {
    data object Idle : EnhancedProbeState
    data class Running(val leg: ChannelProbeLeg, val startedAtMs: Long) : EnhancedProbeState
    data class Result(val report: EnhancedProbeReport) : EnhancedProbeState
}

data class GatewayAggregate(
    val total: Int,
    val success: Int,
    val failure: Int,
    val avgLatencyMs: Int?,
    val minLatencyMs: Long?,
    val maxLatencyMs: Long?,
    val latestError: String?,
)

data class ProtocolSelectionHistoryItem(
    val timestampMs: Long,
    val transport: String,
    val reason: String,
    val elapsedMs: Long?,
    val connectBudgetMs: Long?,
    val tcpDelayMs: Long?,
    val wssDelayMs: Long?,
    val inSessionProbeRttMs: Long?,
    val inSessionProbeSource: String?,
)

data class ClientCapabilities(
    val supportsGatewayHttpProbe: Boolean,
    val supportsPrivateTransportProfile: Boolean,
    val supportsQuicProbe: Boolean,
    val supportsTcpTlsProbe: Boolean,
    val supportsWssTlsProbe: Boolean,
    val supportsProviderProbe: Boolean,
    val supportsLogExportJson: Boolean,
    val supportsLogExportText: Boolean,
)

private data class ChannelProbeAccumulator(
    var latest: ChannelProbeLeg? = null,
    val samples: MutableList<ChannelProbeSample> = mutableListOf(),
    var lastProbeAtMs: Long = 0L,
)

private data class ChannelProbeSample(
    val timestampMs: Long,
    val latencyMs: Long?,
    val isFailure: Boolean,
)

private fun String?.ifNullOrBlank(defaultValue: () -> String): String {
    return this?.takeUnless { it.isBlank() } ?: defaultValue()
}
