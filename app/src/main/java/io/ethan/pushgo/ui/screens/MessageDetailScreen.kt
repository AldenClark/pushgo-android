package io.ethan.pushgo.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import io.ethan.pushgo.R
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.MessageImageStore
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.data.model.MessageSeverity
import io.ethan.pushgo.markdown.MessageBodyResolver
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.ui.MessageDetailViewModelFactory
import io.ethan.pushgo.ui.markdown.FullMarkdownRenderer
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor
import io.ethan.pushgo.ui.viewmodel.MessageDetailViewModel
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.os.Build
import android.text.format.DateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    messageId: String,
    repository: MessageRepository,
    stateCoordinator: MessageStateCoordinator,
    channelRepository: ChannelSubscriptionRepository,
    imageStore: MessageImageStore,
    onDismiss: () -> Unit,
) {
    val initialRenderStartedAtMs = remember(messageId) { SystemClock.elapsedRealtime() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val viewModel: MessageDetailViewModel = viewModel(
        key = messageId,
        factory = MessageDetailViewModelFactory(repository, stateCoordinator, messageId),
    )
    val message by viewModel.message.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(messageId) {
        viewModel.load()
    }

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.label_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        return
    }

    val current = message
    if (current == null) {
        AppEmptyState(
            icon = Icons.Outlined.MarkEmailRead,
            title = stringResource(R.string.label_no_messages),
            description = stringResource(R.string.message_list_empty_hint),
            modifier = Modifier.fillMaxWidth(),
            topPadding = 32.dp,
            iconSize = 44.dp,
        )
        return
    }

    LaunchedEffect(isLoading, current.id) {
        if (!isLoading) {
            val elapsedMs = SystemClock.elapsedRealtime() - initialRenderStartedAtMs
            io.ethan.pushgo.util.SilentSink.i(
                "PushGoPerf",
                "android_detail_visible message_id=${current.id} elapsed_ms=$elapsedMs"
            )
        }
    }

    val configuration = LocalConfiguration.current
    val timeText = remember(current.receivedAt, configuration) {
        formatDetailTime(context, current.receivedAt, ZoneId.systemDefault())
    }
    val severity = current.severity
    val imageModels = remember(current.rawPayloadJson) {
        imageStore.resolveDetailImageModels(current.rawPayloadJson)
    }
    var previewImageModel by remember(current.id) { mutableStateOf<Any?>(null) }
    val resolvedBody = remember(current.rawPayloadJson, current.body) {
        MessageBodyResolver.resolve(current.rawPayloadJson, current.body)
    }
    ModalBottomSheet(
        modifier = Modifier.testTag("sheet.message.detail"),
        onDismissRequest = { onDismiss() },
        sheetState = sheetState,
        containerColor = PushGoSheetContainerColor(),
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        MessageDetailCoreContent(
            message = current,
            timeText = timeText,
            imageModels = imageModels,
            resolvedBodyText = resolvedBody.rawText,
            onDelete = {
                viewModel.delete()
                onDismiss()
            },
            onOpenImage = { model -> previewImageModel = model },
            onOpenUrl = { url ->
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                context.startActivity(intent)
            },
        )
    }
    if (previewImageModel != null) {
        ZoomableImagePreviewDialog(
            model = previewImageModel,
            onDismiss = { previewImageModel = null },
        )
    }
}

@Composable
internal fun MessageDetailCoreContent(
    message: PushMessage,
    timeText: String,
    imageModels: List<Any>,
    resolvedBodyText: String,
    onDelete: (() -> Unit)?,
    onOpenImage: (Any) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onDelete != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    modifier = Modifier.testTag("action.message.delete"),
                    onClick = onDelete,
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("panel.message.detail"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(
                        text = message.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MessageSeverityDetailBadge(severity = message.severity)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                if (message.tags.isNotEmpty()) {
                    Text(
                        text = message.tags.joinToString(separator = " · "),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (message.metadata.isNotEmpty()) {
                    MetadataSection(items = message.metadata)
                }
                if (imageModels.isNotEmpty()) {
                    if (imageModels.size == 1) {
                        val model = imageModels.first()
                        AsyncImage(
                            model = model,
                            contentDescription = stringResource(R.string.label_image_attachment),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenImage(model) },
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            imageModels.forEach { model ->
                                AsyncImage(
                                    model = model,
                                    contentDescription = stringResource(R.string.label_image_attachment),
                                    modifier = Modifier
                                        .size(88.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onOpenImage(model) },
                                )
                            }
                        }
                    }
                }
                if (message.severity == MessageSeverity.CRITICAL) {
                    CriticalSeverityHintCard()
                }
                FullMarkdownRenderer(
                    text = resolvedBodyText,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!message.url.isNullOrBlank()) {
                    Button(
                        onClick = { onOpenUrl(message.url.orEmpty()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("action.message.open_url"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.label_open_url))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun MetadataSection(items: Map<String, String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("section.message.metadata"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageSeverityDetailBadge(severity: MessageSeverity?) {
    val resolved = severity ?: return
    val text = when (resolved) {
        MessageSeverity.LOW -> stringResource(R.string.message_severity_low)
        MessageSeverity.MEDIUM -> stringResource(R.string.message_severity_medium)
        MessageSeverity.HIGH -> stringResource(R.string.message_severity_high)
        MessageSeverity.CRITICAL -> stringResource(R.string.message_severity_critical)
    }
    val bgColor = when (resolved) {
        MessageSeverity.LOW -> Color(0xFF93C5FD).copy(alpha = 0.18f)
        MessageSeverity.MEDIUM -> Color(0xFFFDE68A).copy(alpha = 0.20f)
        MessageSeverity.HIGH -> Color(0xFFF59E0B).copy(alpha = 0.18f)
        MessageSeverity.CRITICAL -> Color(0xFFDC2626).copy(alpha = 0.18f)
    }
    val fgColor = when (resolved) {
        MessageSeverity.LOW -> Color(0xFF1D4ED8)
        MessageSeverity.MEDIUM -> Color(0xFFB45309)
        MessageSeverity.HIGH -> Color(0xFFB45309)
        MessageSeverity.CRITICAL -> Color(0xFFB91C1C)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fgColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun CriticalSeverityHintCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("banner.message.severity.critical"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFEE2E2).copy(alpha = 0.6f),
        ),
        border = BorderStroke(1.dp, Color(0xFFFCA5A5).copy(alpha = 0.6f)),
    ) {
        Text(
            text = stringResource(R.string.message_severity_critical_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF991B1B),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

private fun formatDetailTime(
    context: Context,
    receivedAt: java.time.Instant,
    zoneId: ZoneId,
): String {
    val locale = localeFrom(context)
    val is24Hour = DateFormat.is24HourFormat(context)
    val skeleton = if (is24Hour) "yMMMdHms" else "yMMMd hms"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    val formatter = DateTimeFormatter.ofPattern(pattern, locale)
    return formatter.format(receivedAt.atZone(zoneId))
}

private fun localeFrom(context: Context): Locale {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.resources.configuration.locales.get(0)
    } else {
        @Suppress("DEPRECATION")
        context.resources.configuration.locale
    }
}
