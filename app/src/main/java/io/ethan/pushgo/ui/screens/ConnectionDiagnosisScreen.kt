package io.ethan.pushgo.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor
import io.ethan.pushgo.ui.viewmodel.ConnectionDiagnosisLogLine
import io.ethan.pushgo.ui.viewmodel.ConnectionDiagnosisViewModel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ConnectionDiagnosisScreen(
    navController: NavController,
    factory: PushGoViewModelFactory,
) {
    val context = LocalContext.current
    val viewModel: ConnectionDiagnosisViewModel = viewModel(factory = factory)

    LaunchedEffect(Unit) {
        viewModel.startDiagnosisIfIdle()
    }
    LaunchedEffect(viewModel.toastMessage) {
        val message = viewModel.toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        announceForAccessibility(context, message)
        viewModel.consumeToastMessage()
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
                    containerColor = PushGoSheetContainerColor(),
                ),
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
                    detail = viewModel.statusDetail,
                    isRunning = viewModel.isRunning,
                )
            }
            item {
                DiagnosisActionRow(
                    isRunning = viewModel.isRunning,
                    onCopy = viewModel::copyReportToClipboard,
                    onRestart = viewModel::restartDiagnosis,
                )
            }
            item {
                DiagnosisSummaryCard(
                    networkSummary = viewModel.networkSummary,
                    natSummary = viewModel.natSummary,
                    proxySummary = viewModel.proxySummary,
                    gatewaySummary = viewModel.gatewaySummary,
                    transportSummary = viewModel.transportSummary,
                    recommendation = viewModel.recommendation,
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
}

@Composable
private fun DiagnosisStatusCard(
    title: String,
    detail: String,
    isRunning: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(
                            if (isRunning) {
                                stringResource(R.string.label_connection_diagnosis_running)
                            } else {
                                stringResource(R.string.label_connection_diagnosis_finished)
                            }
                        )
                    },
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosisActionRow(
    isRunning: Boolean,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onCopy,
            modifier = Modifier
                .weight(1f)
                .testTag("action.connection_diagnosis.copy"),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.label_connection_diagnosis_copy),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Button(
            onClick = onRestart,
            modifier = Modifier
                .weight(1f)
                .testTag("action.connection_diagnosis.restart"),
            enabled = !isRunning,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.label_connection_diagnosis_restart),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun DiagnosisSummaryCard(
    networkSummary: String,
    natSummary: String,
    proxySummary: String,
    gatewaySummary: String,
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
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_gateway), value = gatewaySummary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_transport), value = transportSummary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            SummaryLine(label = stringResource(R.string.label_connection_diagnosis_recommendation), value = recommendation)
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
