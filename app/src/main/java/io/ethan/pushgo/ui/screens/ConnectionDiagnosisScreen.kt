package io.ethan.pushgo.ui.screens

import android.content.Intent
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.viewmodel.ChannelProbeLeg
import io.ethan.pushgo.ui.viewmodel.ConnectionDiagnosisLogLine
import io.ethan.pushgo.ui.viewmodel.DiagnosisExportFormat
import io.ethan.pushgo.ui.viewmodel.DiagnosisExportScope
import io.ethan.pushgo.ui.viewmodel.EnhancedProbeState
import io.ethan.pushgo.ui.viewmodel.GatewayAggregate
import io.ethan.pushgo.ui.viewmodel.ConnectionDiagnosisViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ConnectionDiagnosisScreen(
    navController: NavController,
    factory: PushGoViewModelFactory,
) {
    val context = LocalContext.current
    val viewModel: ConnectionDiagnosisViewModel = viewModel(factory = factory)
    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportReportToUri(uri, DiagnosisExportFormat.JSON, DiagnosisExportScope.ALL)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startDiagnosisIfIdle()
    }
    LaunchedEffect(viewModel.toastMessage) {
        val message = viewModel.toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        announceForAccessibility(context, message)
        viewModel.consumeToastMessage()
    }
    LaunchedEffect(viewModel.pendingShareUris) {
        val uris = viewModel.pendingShareUris
        if (uris.isEmpty()) return@LaunchedEffect
        runCatching {
            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra(Intent.EXTRA_SUBJECT, "PushGo Diagnosis Support Bundle")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享诊断支持包"))
        }.onFailure {
            Toast.makeText(context, "无法打开分享面板: ${it.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
        }
        viewModel.consumePendingShareUris()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen.settings.connection_diagnosis"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.label_connection_diagnosis),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.label_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ),
                actions = {
                    IconButton(
                        onClick = viewModel::copyReportToClipboard,
                        modifier = Modifier.testTag("action.connection_diagnosis.copy_toolbar"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.label_connection_diagnosis_copy),
                        )
                    }
                    IconButton(
                        onClick = viewModel::prepareSupportBundleForShare,
                        modifier = Modifier.testTag("action.connection_diagnosis.share_bundle"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "分享支持包",
                        )
                    }
                    IconButton(
                        onClick = {
                            exportJsonLauncher.launch(defaultExportFileName("json"))
                        },
                        modifier = Modifier.testTag("action.connection_diagnosis.export_json"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = "导出 JSON",
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DiagnosisStatusCard(
                    title = viewModel.statusTitle,
                    isRunning = viewModel.isRunning,
                    isCompleted = viewModel.isCompleted,
                    progressPercent = viewModel.sessionProgressPercent,
                    elapsedSeconds = viewModel.sessionElapsedSeconds,
                    totalSeconds = viewModel.sessionDurationSeconds,
                    redactEnabled = viewModel.redactSensitiveData,
                    onRedactToggle = viewModel::updateRedactSensitiveData,
                    onStopOrRestart = {
                        if (viewModel.isRunning) {
                            viewModel.stopDiagnosis()
                        } else {
                            viewModel.restartDiagnosis()
                        }
                    },
                )
            }
            item {
                DiagnosisSummaryCard(
                    networkSummary = viewModel.networkSummary,
                    natSummary = viewModel.natSummary,
                    proxySummary = viewModel.proxySummary,
                    gatewaySummary = viewModel.gatewaySummary,
                    gatewayAggregate = viewModel.gatewayAggregate,
                    transportSummary = viewModel.transportSummary,
                    recommendation = viewModel.recommendation,
                )
            }
            item {
                LinkMatrixCard(
                    modeLabel = viewModel.channelProbeModeLabel,
                    profileRttMs = viewModel.channelProbeProfileRttMs,
                    legs = viewModel.channelProbeLegs,
                    onEnhancedProbeClick = viewModel::startEnhancedProbe,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.label_connection_diagnosis_logs),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    ),
                )
            }
            items(viewModel.logLines, key = { line -> "${line.timestampMs}-${line.category}-${line.message.hashCode()}" }) { line ->
                DiagnosisLogCard(line = line)
            }
        }
    }

    when (val enhancedState = viewModel.enhancedProbeState) {
        is EnhancedProbeState.Idle -> Unit
        is EnhancedProbeState.Running -> {
            EnhancedProbeSheet(
                state = enhancedState,
                onDismiss = viewModel::clearEnhancedProbe,
            )
        }
        is EnhancedProbeState.Result -> {
            EnhancedProbeSheet(
                state = enhancedState,
                onDismiss = viewModel::clearEnhancedProbe,
            )
        }
    }
}

@Composable
private fun DiagnosisStatusCard(
    title: String,
    isRunning: Boolean,
    isCompleted: Boolean,
    progressPercent: Int,
    elapsedSeconds: Long,
    totalSeconds: Long,
    redactEnabled: Boolean,
    onRedactToggle: (Boolean) -> Unit,
    onStopOrRestart: () -> Unit,
) {
    val statusColor = if (isRunning) {
        MaterialTheme.colorScheme.primary
    } else if (isCompleted) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val containerColor = if (isRunning) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else if (isCompleted) {
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = if (isRunning) {
                            "采集中 ${progressPercent}% · ${elapsedSeconds}s/${totalSeconds}s"
                        } else {
                            "链路矩阵保持活跃探测"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isRunning) {
                LinearProgressIndicator(
                    progress = { progressPercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = onStopOrRestart,
                    label = { Text(if (isRunning) "停止采集" else "重新诊断") },
                )
                AssistChip(
                    onClick = { onRedactToggle(!redactEnabled) },
                    label = { Text(if (redactEnabled) "脱敏: 开" else "脱敏: 关") },
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (isRunning) "实时更新" else "后台持续",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun LinkMatrixCard(
    modeLabel: String,
    profileRttMs: Long?,
    legs: List<ChannelProbeLeg>,
    onEnhancedProbeClick: (ChannelProbeLeg) -> Unit,
) {
    val sortedLegs = remember(legs) {
        legs.sortedWith(
            compareBy<ChannelProbeLeg> {
                when (it.result.uppercase()) {
                    "FAIL" -> 0
                    "SKIPPED" -> 1
                    else -> 2
                }
            }.thenBy { it.name },
        )
    }
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "链路矩阵",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = buildString {
                    append("mode=$modeLabel")
                    profileRttMs?.let { append(" · profile_rtt=${it}ms") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            if (sortedLegs.isEmpty()) {
                Text(
                    text = "尚未产生链路探测数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                sortedLegs.forEach { leg ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "[${leg.name.uppercase()}] ${leg.result.uppercase()} · ${leg.protocol.uppercase()} · last=${formatLegLastLatency(leg)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            fontFamily = FontFamily.Monospace,
                            color = when (leg.result.uppercase()) {
                                "OK" -> MaterialTheme.colorScheme.primary
                                "SKIPPED" -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            text = buildString {
                                append("enabled=${leg.enabled} host=${leg.host} port=${leg.port}")
                                leg.averageLatencyMs?.let { append(" · avg=${it}ms") }
                                leg.failureRatePercent?.let { append(" · fail=${"%.1f".format(it)}%") }
                                if (leg.sampleCount > 0) append(" · samples=${leg.sampleCount}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (!leg.path.isNullOrBlank() || !leg.subprotocol.isNullOrBlank() || !leg.note.isNullOrBlank()) {
                            Text(
                                text = buildString {
                                    if (!leg.path.isNullOrBlank()) append("path=${leg.path} ")
                                    if (!leg.subprotocol.isNullOrBlank()) append("subprotocol=${leg.subprotocol} ")
                                    if (!leg.note.isNullOrBlank()) append("note=${leg.note}")
                                }.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (!leg.errorCode.isNullOrBlank() || !leg.errorDetail.isNullOrBlank()) {
                            Text(
                                text = buildString {
                                    if (!leg.errorCode.isNullOrBlank()) append("error_code=${leg.errorCode} ")
                                    if (!leg.errorDetail.isNullOrBlank()) append("error=${leg.errorDetail}")
                                }.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (leg.result.equals("FAIL", ignoreCase = true)) {
                            Text(
                                text = "失败原因: ${buildFailureReason(leg)}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        AssistChip(
                            onClick = { onEnhancedProbeClick(leg) },
                            label = { Text("增强探测") },
                        )
                        leg.lastProbeAtMs?.let { last ->
                            Text(
                                text = "last_probe=${formatDiagnosisTime(last)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnhancedProbeSheet(
    state: EnhancedProbeState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state) {
                is EnhancedProbeState.Running -> {
                    Text(
                        text = "增强探测中 · ${state.leg.name.uppercase()}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = "${state.leg.protocol.uppercase()} ${state.leg.host}:${state.leg.port}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "正在执行分阶段探测（DNS/连接/TLS或UDP）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is EnhancedProbeState.Result -> {
                    val report = state.report
                    Text(
                        text = "增强探测结果 · ${report.leg.name.uppercase()}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = "${report.leg.protocol.uppercase()} ${report.leg.host}:${report.leg.port}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = report.traceNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    report.failedAt?.let {
                        Text(
                            text = "失败阶段: $it",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    report.stages.forEach { stage ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        Text(
                            text = "${stage.name} · ${stage.result} · ${stage.latencyMs}ms",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (stage.result == "FAIL") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = stage.detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                is EnhancedProbeState.Idle -> Unit
            }
            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}

private fun defaultExportFileName(ext: String): String {
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .format(Instant.now().atZone(ZoneId.systemDefault()))
    return "pushgo-connection-diagnosis-$stamp.$ext"
}

@Composable
private fun DiagnosisSummaryCard(
    networkSummary: String,
    natSummary: String,
    proxySummary: String,
    gatewaySummary: String,
    gatewayAggregate: GatewayAggregate,
    transportSummary: String,
    recommendation: String,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_network), value = networkSummary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_nat), value = natSummary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_proxy), value = proxySummary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            GatewaySummaryTable(
                label = stringResource(R.string.label_connection_diagnosis_gateway),
                aggregate = gatewayAggregate,
                fallback = gatewaySummary,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_transport), value = transportSummary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_recommendation), value = recommendation)
        }
    }
}

@Composable
private fun GatewaySummaryTable(
    label: String,
    aggregate: GatewayAggregate,
    fallback: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (aggregate.total <= 0) {
            Text(
                text = fallback,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            return
        }
        Text(
            text = "total=${aggregate.total} success=${aggregate.success} failure=${aggregate.failure}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = buildString {
                append("latency_avg=${aggregate.avgLatencyMs ?: "-"}ms ")
                append("min=${aggregate.minLatencyMs ?: "-"}ms ")
                append("max=${aggregate.maxLatencyMs ?: "-"}ms")
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        aggregate.latestError?.let {
            Text(
                text = "latest_error=$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DiagnosisLogCard(line: ConnectionDiagnosisLogLine) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${formatDiagnosisTime(line.timestampMs)} · ${line.category}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = line.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun formatDiagnosisTime(timestampMs: Long): String {
    return java.time.Instant.ofEpochMilli(timestampMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
}

private fun buildFailureReason(leg: ChannelProbeLeg): String {
    return when {
        !leg.errorCode.isNullOrBlank() && !leg.errorDetail.isNullOrBlank() ->
            "${leg.errorCode}: ${leg.errorDetail}"
        !leg.errorCode.isNullOrBlank() -> leg.errorCode
        !leg.errorDetail.isNullOrBlank() -> leg.errorDetail
        !leg.note.isNullOrBlank() -> leg.note
        else -> "网络层探测失败（无详细错误）"
    }
}

private fun formatLegLastLatency(leg: ChannelProbeLeg): String {
    val hasMeasuredLatency = leg.result.equals("OK", ignoreCase = true) && leg.latencyMs > 0L
    if (!hasMeasuredLatency) return "n/a"
    return if (leg.protocol.equals("udp", ignoreCase = true)) {
        "${leg.latencyMs}ms (udp)"
    } else {
        "${leg.latencyMs}ms"
    }
}

@Suppress("DEPRECATION")
private fun announceForAccessibility(context: Context, message: String) {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (manager == null || !manager.isEnabled) return
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    event.text.add(message)
    event.packageName = context.packageName
    event.className = context.javaClass.name
    manager.sendAccessibilityEvent(event)
}
