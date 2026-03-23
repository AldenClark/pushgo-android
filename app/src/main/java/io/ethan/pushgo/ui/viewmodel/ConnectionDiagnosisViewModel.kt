package io.ethan.pushgo.ui.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
        private const val GATEWAY_CONNECT_TIMEOUT_MS = 8_000
        private const val GATEWAY_READ_TIMEOUT_MS = 12_000
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

    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var sessionJob: Job? = null
    private var appLogListenerId: Long? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var sessionStartedAtMs: Long = 0L
    private var sessionFinishedAtMs: Long = 0L
    private var lastConnectionFingerprint: String? = null

    private val networkSamples = mutableListOf<NetworkSample>()
    private val gatewayProbes = mutableListOf<GatewayProbe>()
    private val connectionEvents = mutableListOf<ConnectionEvent>()

    fun startDiagnosisIfIdle() {
        if (sessionJob?.isActive == true) return
        startDiagnosis(forceRestart = false)
    }

    fun restartDiagnosis() {
        startDiagnosis(forceRestart = true)
    }

    fun consumeToastMessage() {
        toastMessage = null
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

    override fun onCleared() {
        cleanupSessionResources()
        super.onCleared()
    }

    private fun startDiagnosis(forceRestart: Boolean) {
        if (forceRestart) {
            sessionJob?.cancel()
            cleanupSessionResources()
        }
        resetSessionState()
        sessionJob = viewModelScope.launch {
            isRunning = true
            isCompleted = false
            sessionStartedAtMs = System.currentTimeMillis()
            statusTitle = "正在诊断"
            statusDetail = "正在持续采集网络、网关和私有连接状态，达到窗口后会自动停止。"
            appendLog("session", "开始连接诊断，采集窗口 ${DIAG_DURATION_MS / 1000} 秒")
            recordGatewayConfiguration()
            attachAppLogListener()
            attachNetworkCallback()
            val snapshotJob = launch { collectConnectionSnapshots() }
            val networkJob = launch { periodicNetworkSampling() }
            val gatewayJob = launch { periodicGatewayProbes() }
            delay(DIAG_DURATION_MS)
            snapshotJob.cancel()
            networkJob.cancel()
            gatewayJob.cancel()
            completeDiagnosis()
        }
    }

    private suspend fun recordGatewayConfiguration() {
        val gatewayBaseUrl = settingsRepository.getServerAddress()?.trim()?.ifEmpty { null }
            ?: AppConstants.defaultServerAddress
        val gatewayTokenPresent = settingsRepository.getGatewayToken()?.trim()?.isNotEmpty() == true
        val useFcmChannel = settingsRepository.getUseFcmChannel()
        appendLog(
            "session",
            "应用版本=${BuildConfig.VERSION_NAME} gateway=$gatewayBaseUrl token_present=$gatewayTokenPresent use_fcm=$useFcmChannel",
        )
        privateChannelClient.refreshNetworkStateFromSystem()
        appendConnectionSnapshot(privateChannelClient.readConnectionSnapshot(), source = "initial")
        sampleCurrentNetwork(reason = "initial")
    }

    private suspend fun collectConnectionSnapshots() {
        privateChannelClient.connectionSnapshotFlow.collect { snapshot ->
            appendConnectionSnapshot(snapshot, source = "flow")
        }
    }

    private suspend fun periodicNetworkSampling() {
        while (currentCoroutineContext().isActive) {
            sampleCurrentNetwork(reason = "periodic")
            delay(NETWORK_SAMPLE_INTERVAL_MS)
        }
    }

    private suspend fun periodicGatewayProbes() {
        while (currentCoroutineContext().isActive) {
            runGatewayProbe()
            delay(GATEWAY_PROBE_INTERVAL_MS)
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
        )
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
        val endpoint = "${baseUrl.removeSuffix("/")}/private/diagnostics/network"
        val startedAt = SystemClock.elapsedRealtime()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
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
            val root = JSONObject(body)
            if (!root.optBoolean("success", false)) {
                GatewayProbe(
                    timestampMs = System.currentTimeMillis(),
                    latencyMs = latencyMs,
                    success = false,
                    httpStatus = httpStatus,
                    observedClientIp = null,
                    natHint = null,
                    observedIpScope = null,
                    proxyDetected = false,
                    errorMessage = root.optString("error").trim().ifEmpty { "unknown error" },
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

    private suspend fun completeDiagnosis() {
        cleanupSessionResources()
        sessionFinishedAtMs = System.currentTimeMillis()
        isRunning = false
        isCompleted = true
        statusTitle = "诊断已完成"
        statusDetail = "采集窗口结束，已停止继续记录。可以复制日志并附带给排查人员。"
        refreshSummary()
        reportText = buildReportText()
        appendLog("session", "诊断完成，已生成可复制报告")
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
        cleanupSessionResources()
        sessionStartedAtMs = 0L
        sessionFinishedAtMs = 0L
        lastConnectionFingerprint = null
        networkSamples.clear()
        gatewayProbes.clear()
        connectionEvents.clear()
        isRunning = false
        isCompleted = false
        statusTitle = "准备开始"
        statusDetail = "正在初始化诊断环境。"
        networkSummary = "尚未采集"
        natSummary = "尚未采集"
        proxySummary = "尚未采集"
        gatewaySummary = "尚未采集"
        transportSummary = "尚未采集"
        recommendation = "尚未形成诊断结论"
        logLines = emptyList()
        reportText = ""
        toastMessage = null
    }

    private fun refreshSummary() {
        networkSummary = buildNetworkSummary()
        natSummary = buildNatSummary()
        proxySummary = buildProxySummary()
        gatewaySummary = buildGatewaySummary()
        transportSummary = buildTransportSummary()
        recommendation = buildRecommendation()
        reportText = currentReportText()
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

    private fun buildTransportSummary(): String {
        if (connectionEvents.isEmpty()) return "尚未采集"
        val privateModeEnabled = connectionEvents.any { it.privateModeEnabled }
        if (!privateModeEnabled) {
            return "当前仍处于 FCM 模式，私有链路日志有限"
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
        return buildString {
            append("最近状态=${last.stage}/${last.transport}")
            if (transports.isNotEmpty()) {
                append(" · transport=")
                append(transports.joinToString("/"))
            }
            append(" · connected=$connectedCount")
            append(" · 重连/断链=$reconnectCount")
            append(" · keepalive=${last.keepaliveState.name.lowercase(Locale.US)}")
        }
    }

    private fun buildRecommendation(): String {
        if (gatewayProbes.isEmpty() || connectionEvents.isEmpty()) {
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
            buildReportText()
        }
    }

    private fun buildReportText(): String {
        val startedAt = if (sessionStartedAtMs > 0L) formatTimestamp(sessionStartedAtMs) else "-"
        val finishedAt = if (sessionFinishedAtMs > 0L) formatTimestamp(sessionFinishedAtMs) else "-"
        return buildString {
            appendLine("PushGo Android Connection Diagnosis")
            appendLine("started_at=$startedAt")
            appendLine("finished_at=$finishedAt")
            appendLine("status=$statusTitle")
            appendLine("status_detail=$statusDetail")
            appendLine("network_summary=$networkSummary")
            appendLine("nat_summary=$natSummary")
            appendLine("proxy_summary=$proxySummary")
            appendLine("gateway_summary=$gatewaySummary")
            appendLine("transport_summary=$transportSummary")
            appendLine("recommendation=$recommendation")
            appendLine()
            appendLine("[logs]")
            logLines.forEach { line ->
                appendLine("${formatTimestamp(line.timestampMs)} [${line.category}] ${line.message}")
            }
        }.trim()
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
)

private fun String?.ifNullOrBlank(defaultValue: () -> String): String {
    return this?.takeUnless { it.isBlank() } ?: defaultValue()
}
