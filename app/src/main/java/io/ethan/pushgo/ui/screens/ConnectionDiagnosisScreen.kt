package io.ethan.pushgo.ui.screens

import android.content.Intent
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import io.ethan.pushgo.ui.viewmodel.ChannelProbeLeg
import io.ethan.pushgo.ui.viewmodel.ConnectionDiagnosisLogLine
import io.ethan.pushgo.ui.viewmodel.DiagnosisExportFormat
import io.ethan.pushgo.ui.viewmodel.DiagnosisExportScope
import io.ethan.pushgo.ui.viewmodel.EnhancedProbeState
import io.ethan.pushgo.ui.viewmodel.GatewayAggregate
import io.ethan.pushgo.ui.viewmodel.ConnectionDiagnosisViewModel
import io.ethan.pushgo.ui.viewmodel.ProtocolSelectionHistoryItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayList

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ConnectionDiagnosisScreen(
    navController: NavController,
    factory: PushGoViewModelFactory,
) {
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    val shareBundleChooserLabel = stringResource(R.string.label_connection_diagnosis_share_bundle)
    val openShareFailedPrefix = stringResource(R.string.error_connection_diagnosis_open_share_failed_prefix)
    val viewModel: ConnectionDiagnosisViewModel = viewModel(factory = factory)
    val bottomGestureInset = rememberBottomGestureInset()
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
            context.startActivity(
                Intent.createChooser(
                    shareIntent,
                    shareBundleChooserLabel,
                ),
            )
        }.onFailure {
            val errorText = openShareFailedPrefix + (it.message ?: "unknown")
            Toast.makeText(
                context,
                errorText,
                Toast.LENGTH_SHORT,
            ).show()
        }
        viewModel.consumePendingShareUris()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen.settings.connection_diagnosis"),
        topBar = {
            Column(modifier = Modifier.background(uiColors.surfaceBase)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.label_back),
                            tint = uiColors.textPrimary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.label_connection_diagnosis),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        color = uiColors.textPrimary,
                    )
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
                            contentDescription = stringResource(R.string.label_connection_diagnosis_share_bundle),
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
                            contentDescription = stringResource(R.string.label_connection_diagnosis_export_json),
                        )
                    }
                }
                PushGoDividerSubtle()
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(uiColors.surfaceBase)
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = bottomGestureInset + 16.dp,
            ),
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
                    selectionHistory = viewModel.protocolSelectionHistory,
                    onEnhancedProbeClick = viewModel::startEnhancedProbe,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.label_connection_diagnosis_logs),
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = uiColors.accentPrimary,
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
    val uiColors = PushGoThemeExtras.colors
    val statusColor = if (isRunning) {
        uiColors.stateInfo.foreground
    } else if (isCompleted) {
        uiColors.stateSuccess.foreground
    } else {
        uiColors.stateNeutral.foreground
    }
    val containerColor = if (isRunning) {
        uiColors.stateInfo.background
    } else if (isCompleted) {
        uiColors.stateSuccess.background
    } else {
        uiColors.surfaceSunken
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
                            stringResource(
                                R.string.label_connection_diagnosis_collecting_progress,
                                progressPercent,
                                elapsedSeconds,
                                totalSeconds,
                            )
                        } else {
                            stringResource(R.string.label_connection_diagnosis_background_probe)
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = uiColors.textSecondary,
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
                    label = {
                        Text(
                            if (isRunning) {
                                stringResource(R.string.action_connection_diagnosis_stop)
                            } else {
                                stringResource(R.string.label_connection_diagnosis_restart)
                            },
                        )
                    },
                )
                AssistChip(
                    onClick = { onRedactToggle(!redactEnabled) },
                    label = {
                        Text(
                            if (redactEnabled) {
                                stringResource(R.string.label_connection_diagnosis_redact_on)
                            } else {
                                stringResource(R.string.label_connection_diagnosis_redact_off)
                            },
                        )
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (isRunning) {
                        stringResource(R.string.label_connection_diagnosis_live_mode)
                    } else {
                        stringResource(R.string.label_connection_diagnosis_background_mode)
                    },
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
    selectionHistory: List<ProtocolSelectionHistoryItem>,
    onEnhancedProbeClick: (ChannelProbeLeg) -> Unit,
) {
    val uiColors = PushGoThemeExtras.colors
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
                text = stringResource(R.string.label_connection_diagnosis_link_matrix),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = uiColors.accentPrimary,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = buildString {
                    append("mode=$modeLabel")
                    profileRttMs?.let { append(" · profile_rtt=${it}ms") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = uiColors.textSecondary,
                fontFamily = FontFamily.Monospace,
            )
            if (sortedLegs.isEmpty()) {
                Text(
                    text = stringResource(R.string.label_connection_diagnosis_link_matrix_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = uiColors.textSecondary,
                )
            } else {
                sortedLegs.forEach { leg ->
                    HorizontalDivider(color = PushGoThemeExtras.colors.dividerStrong)
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
                                "OK" -> uiColors.accentPrimary
                                "SKIPPED" -> uiColors.textSecondary
                                else -> uiColors.stateDanger.foreground
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
                        if (!leg.path.isNullOrBlank()) {
                            Text(
                                text = "path=${leg.path}",
                                style = MaterialTheme.typography.bodySmall,
                                color = uiColors.textSecondary,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (!leg.subprotocol.isNullOrBlank()) {
                            Text(
                                text = "subprotocol=${leg.subprotocol}",
                                style = MaterialTheme.typography.bodySmall,
                                color = uiColors.textSecondary,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        formatLegNoteRows(leg.note).forEach { row ->
                            Text(
                                text = "note.${row.key}=${row.value}",
                                style = MaterialTheme.typography.bodySmall,
                                color = uiColors.textSecondary,
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
                                color = uiColors.stateDanger.foreground,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (leg.result.equals("FAIL", ignoreCase = true)) {
                            Text(
                                text = stringResource(R.string.label_connection_diagnosis_failure_reason, buildFailureReason(leg)),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = uiColors.stateDanger.foreground,
                            )
                        }
                        AssistChip(
                            onClick = { onEnhancedProbeClick(leg) },
                            label = { Text(stringResource(R.string.action_connection_diagnosis_enhanced_probe)) },
                        )
                        leg.lastProbeAtMs?.let { last ->
                            Text(
                                text = "last_probe=${formatDiagnosisTime(last)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = uiColors.textSecondary,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                if (selectionHistory.isNotEmpty()) {
                    HorizontalDivider(color = PushGoThemeExtras.colors.dividerStrong)
                    Text(
                        text = stringResource(R.string.label_connection_diagnosis_selection_history),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = uiColors.accentPrimary,
                    )
                    selectionHistory.takeLast(8).asReversed().forEach { item ->
                        Text(
                            text = buildString {
                                append("${formatDiagnosisTime(item.timestampMs)} ${item.transport}")
                                append(" reason=${item.reason}")
                                item.elapsedMs?.let { append(" elapsed=${it}ms") }
                                item.inSessionProbeRttMs?.let { append(" in_session_rtt=${it}ms") }
                                item.inSessionProbeSource?.takeIf { it.isNotBlank() }?.let {
                                    append(" source=")
                                    append(it)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = uiColors.textSecondary,
                            fontFamily = FontFamily.Monospace,
                        )
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
    val uiColors = PushGoThemeExtras.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bottomGestureInset = rememberBottomGestureInset()
    PushGoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = bottomGestureInset + 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state) {
                is EnhancedProbeState.Running -> {
                    Text(
                        text = stringResource(
                            R.string.label_connection_diagnosis_enhanced_running,
                            state.leg.name.uppercase(),
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = "${state.leg.protocol.uppercase()} ${state.leg.host}:${state.leg.port}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.label_connection_diagnosis_enhanced_running_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = uiColors.textSecondary,
                    )
                }
                is EnhancedProbeState.Result -> {
                    val report = state.report
                    Text(
                        text = stringResource(
                            R.string.label_connection_diagnosis_enhanced_result,
                            report.leg.name.uppercase(),
                        ),
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
                        color = uiColors.textSecondary,
                    )
                    report.failedAt?.let {
                        Text(
                            text = stringResource(R.string.label_connection_diagnosis_failed_stage, it),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = uiColors.stateDanger.foreground,
                        )
                    }
                    report.stages.forEach { stage ->
                        HorizontalDivider(color = PushGoThemeExtras.colors.dividerStrong)
                        Text(
                            text = "${stage.name} · ${stage.result} · ${stage.latencyMs}ms",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (stage.result == "FAIL") uiColors.stateDanger.foreground else uiColors.accentPrimary,
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
    val uiColors = PushGoThemeExtras.colors
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_network), value = networkSummary)
            HorizontalDivider(color = PushGoThemeExtras.colors.dividerStrong)
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_nat), value = natSummary)
            HorizontalDivider(color = PushGoThemeExtras.colors.dividerStrong)
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_proxy), value = proxySummary)
            HorizontalDivider(color = PushGoThemeExtras.colors.dividerStrong)
            GatewaySummaryTable(
                label = stringResource(R.string.label_connection_diagnosis_gateway),
                aggregate = gatewayAggregate,
                fallback = gatewaySummary,
            )
            HorizontalDivider(color = PushGoThemeExtras.colors.dividerStrong)
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_transport), value = transportSummary)
            HorizontalDivider(color = PushGoThemeExtras.colors.dividerStrong)
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
    val uiColors = PushGoThemeExtras.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = uiColors.accentPrimary,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (aggregate.total <= 0) {
            Text(
                text = fallback,
                style = MaterialTheme.typography.bodyMedium,
                color = uiColors.textPrimary,
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
                color = uiColors.stateDanger.foreground,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    val uiColors = PushGoThemeExtras.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = uiColors.accentPrimary,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = uiColors.textPrimary,
        )
    }
}

@Composable
private fun DiagnosisLogCard(line: ConnectionDiagnosisLogLine) {
    val uiColors = PushGoThemeExtras.colors
    Card(
        colors = CardDefaults.cardColors(
            containerColor = PushGoThemeExtras.colors.fieldContainer,
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
                color = uiColors.textSecondary,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = line.message,
                style = MaterialTheme.typography.bodySmall,
                color = uiColors.textPrimary,
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
        else -> "Network probe failed (no detailed error)"
    }
}

private data class LegNoteRow(
    val key: String,
    val value: String,
)

private fun formatLegNoteRows(note: String?): List<LegNoteRow> {
    if (note.isNullOrBlank()) return emptyList()
    return note.split(Regex("""\s*[;|]\s*"""))
        .mapNotNull { part ->
            val cleaned = part.trim()
            if (cleaned.isEmpty()) return@mapNotNull null
            val idx = cleaned.indexOf('=')
            if (idx <= 0 || idx >= cleaned.length - 1) {
                LegNoteRow("detail", cleaned)
            } else {
                LegNoteRow(cleaned.substring(0, idx).trim(), cleaned.substring(idx + 1).trim())
            }
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
