package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ethan.pushgo.R
import java.util.Calendar
import kotlinx.coroutines.launch

private enum class MessageHistoryCleanupRange(
    val labelRes: Int,
) {
    ALL(R.string.history_cleanup_all),
    SEVEN_DAYS(R.string.history_cleanup_7_days),
    THIRTY_DAYS(R.string.history_cleanup_30_days),
    THREE_MONTHS(R.string.history_cleanup_3_months),
    SIX_MONTHS(R.string.history_cleanup_6_months),
    ONE_YEAR(R.string.history_cleanup_1_year),
    ;

    val icon: ImageVector
        get() = when (this) {
            ALL -> Icons.Outlined.DeleteSweep
            SEVEN_DAYS -> Icons.Outlined.Schedule
            THIRTY_DAYS -> Icons.Outlined.CalendarMonth
            THREE_MONTHS -> Icons.Outlined.History
            SIX_MONTHS, ONE_YEAR -> Icons.Outlined.Inventory2
        }

    val isDestructive: Boolean get() = this == ALL

    fun cutoffEpochMillis(now: Long = System.currentTimeMillis()): Long? {
        if (this == ALL) return null
        return Calendar.getInstance().apply {
            timeInMillis = now
            when (this@MessageHistoryCleanupRange) {
                SEVEN_DAYS -> add(Calendar.DAY_OF_YEAR, -7)
                THIRTY_DAYS -> add(Calendar.DAY_OF_YEAR, -30)
                THREE_MONTHS -> add(Calendar.MONTH, -3)
                SIX_MONTHS -> add(Calendar.MONTH, -6)
                ONE_YEAR -> add(Calendar.YEAR, -1)
                ALL -> Unit
            }
        }.timeInMillis
    }
}

private sealed interface MessageHistoryCleanupPhase {
    data object Confirmation : MessageHistoryCleanupPhase
    data object Cleaning : MessageHistoryCleanupPhase
    data class Success(val count: Int) : MessageHistoryCleanupPhase
    data class Failure(val message: String) : MessageHistoryCleanupPhase
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageHistoryCleanupFlow(
    showRangeSheet: Boolean,
    onDismissRangeSheet: () -> Unit,
    onCleanup: suspend (Long?) -> Int,
) {
    var selectedRange by remember { mutableStateOf<MessageHistoryCleanupRange?>(null) }
    var phase by remember { mutableStateOf<MessageHistoryCleanupPhase>(MessageHistoryCleanupPhase.Confirmation) }
    val coroutineScope = rememberCoroutineScope()

    if (showRangeSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                if (phase !is MessageHistoryCleanupPhase.Cleaning) {
                    selectedRange = null
                    phase = MessageHistoryCleanupPhase.Confirmation
                    onDismissRangeSheet()
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    IconButton(
                        onClick = onDismissRangeSheet,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.label_cancel))
                    }
                }

                MessageHistoryCleanupSheetHeader()
                Spacer(modifier = Modifier.height(24.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MessageHistoryCleanupRange.entries.forEach { range ->
                        MessageHistoryCleanupRangeCard(
                            range = range,
                            onClick = {
                                phase = MessageHistoryCleanupPhase.Confirmation
                                selectedRange = range
                            },
                        )
                    }
                }
            }
        }
    }

    selectedRange?.let { range ->
        MessageHistoryCleanupStatusDialog(
            range = range,
            phase = phase,
            onDismiss = {
                if (phase !is MessageHistoryCleanupPhase.Cleaning) {
                    selectedRange = null
                    phase = MessageHistoryCleanupPhase.Confirmation
                }
            },
            onConfirm = {
                if (phase is MessageHistoryCleanupPhase.Confirmation) {
                    phase = MessageHistoryCleanupPhase.Cleaning
                    coroutineScope.launch {
                        phase = runCatching { onCleanup(range.cutoffEpochMillis()) }
                            .fold(
                                onSuccess = MessageHistoryCleanupPhase::Success,
                                onFailure = { MessageHistoryCleanupPhase.Failure(it.localizedMessage ?: it.toString()) },
                            )
                    }
                }
            },
            onClose = {
                selectedRange = null
                phase = MessageHistoryCleanupPhase.Confirmation
                onDismissRangeSheet()
            },
        )
    }
}

@Composable
private fun MessageHistoryCleanupSheetHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                modifier = Modifier.padding(16.dp).size(28.dp),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.history_cleanup_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.history_cleanup_sheet_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MessageHistoryCleanupRangeCard(
    range: MessageHistoryCleanupRange,
    onClick: () -> Unit,
) {
    val accent = if (range.isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = accent.copy(alpha = 0.11f),
                contentColor = accent,
                shape = RoundedCornerShape(13.dp),
            ) {
                Icon(
                    imageVector = range.icon,
                    contentDescription = null,
                    modifier = Modifier.padding(11.dp).size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(range.labelRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (range.isDestructive) accent else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        if (range.isDestructive) R.string.history_cleanup_all_detail
                        else R.string.history_cleanup_range_detail,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageHistoryCleanupStatusDialog(
    range: MessageHistoryCleanupRange,
    phase: MessageHistoryCleanupPhase,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    val title = when (phase) {
        MessageHistoryCleanupPhase.Confirmation -> stringResource(R.string.history_cleanup_confirm_title)
        MessageHistoryCleanupPhase.Cleaning -> stringResource(R.string.history_cleanup_cleaning)
        is MessageHistoryCleanupPhase.Success -> stringResource(R.string.history_cleanup_complete)
        is MessageHistoryCleanupPhase.Failure -> stringResource(R.string.history_cleanup_failed)
    }
    val detail = when (phase) {
        MessageHistoryCleanupPhase.Confirmation -> stringResource(R.string.history_cleanup_confirm_detail)
        MessageHistoryCleanupPhase.Cleaning -> stringResource(R.string.history_cleanup_cleaning_detail)
        is MessageHistoryCleanupPhase.Success -> pluralStringResource(
            R.plurals.history_cleanup_success,
            phase.count,
            phase.count,
        )
        is MessageHistoryCleanupPhase.Failure -> phase.message
    }

    BasicAlertDialog(
        onDismissRequest = {
            if (phase !is MessageHistoryCleanupPhase.Cleaning) onDismiss()
        },
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MessageHistoryCleanupStatusIcon(phase)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (phase is MessageHistoryCleanupPhase.Confirmation) {
                    Spacer(modifier = Modifier.height(20.dp))
                    MessageHistoryCleanupSelectionCard(range)
                }
                MessageHistoryCleanupDialogActions(
                    phase = phase,
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun MessageHistoryCleanupStatusIcon(phase: MessageHistoryCleanupPhase) {
    val accent: Color = when (phase) {
        MessageHistoryCleanupPhase.Cleaning -> MaterialTheme.colorScheme.primary
        is MessageHistoryCleanupPhase.Success -> MaterialTheme.colorScheme.tertiary
        MessageHistoryCleanupPhase.Confirmation,
        is MessageHistoryCleanupPhase.Failure,
        -> MaterialTheme.colorScheme.error
    }
    Surface(
        color = accent.copy(alpha = 0.12f),
        contentColor = accent,
        shape = RoundedCornerShape(20.dp),
    ) {
        Box(
            modifier = Modifier.padding(16.dp).size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                MessageHistoryCleanupPhase.Confirmation -> Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                MessageHistoryCleanupPhase.Cleaning -> CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp,
                )
                is MessageHistoryCleanupPhase.Success -> Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                is MessageHistoryCleanupPhase.Failure -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            }
        }
    }
}

@Composable
private fun MessageHistoryCleanupSelectionCard(range: MessageHistoryCleanupRange) {
    val accent = if (range.isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                text = stringResource(R.string.history_cleanup_selected_range),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = accent.copy(alpha = 0.11f),
                    contentColor = accent,
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Icon(
                        imageVector = range.icon,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(range.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (range.isDestructive) accent else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            if (range.isDestructive) R.string.history_cleanup_all_detail
                            else R.string.history_cleanup_range_detail,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageHistoryCleanupDialogActions(
    phase: MessageHistoryCleanupPhase,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    when (phase) {
        MessageHistoryCleanupPhase.Confirmation -> {
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.label_cancel))
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.history_cleanup_confirm_action))
                }
            }
        }
        MessageHistoryCleanupPhase.Cleaning -> Unit
        is MessageHistoryCleanupPhase.Success,
        is MessageHistoryCleanupPhase.Failure,
        -> {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.history_cleanup_done))
            }
        }
    }
}
