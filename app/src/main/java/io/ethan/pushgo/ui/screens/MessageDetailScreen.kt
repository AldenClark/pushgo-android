package io.ethan.pushgo.ui.screens

import android.content.Context
import android.os.SystemClock
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
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
import io.ethan.pushgo.ui.markdown.SelectablePlainTextRenderer
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor
import io.ethan.pushgo.util.normalizeExternalImageUrl
import io.ethan.pushgo.ui.viewmodel.MessageDetailViewModel
import io.ethan.pushgo.util.openExternalUrl
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
    LaunchedEffect(messageId) {
        viewModel.load()
    }
    val current = message

    LaunchedEffect(isLoading, current?.id) {
        if (!isLoading && current != null) {
            val elapsedMs = SystemClock.elapsedRealtime() - initialRenderStartedAtMs
            io.ethan.pushgo.util.SilentSink.i(
                "PushGoPerf",
                "android_detail_visible message_id=${current.id} elapsed_ms=$elapsedMs"
            )
        }
    }

    val configuration = LocalConfiguration.current
    val timeText = remember(current?.receivedAt, configuration) {
        current?.let { formatDetailTime(context, it.receivedAt, ZoneId.systemDefault()) }.orEmpty()
    }
    val imageModels = remember(current?.rawPayloadJson) {
        current?.let { imageStore.resolveDetailImageModels(it.rawPayloadJson) }.orEmpty()
    }
    var previewImageModel by remember(current?.id) { mutableStateOf<Any?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val resolvedBodyText = remember(current?.rawPayloadJson, current?.body) {
        current?.let { MessageBodyResolver.resolve(it.rawPayloadJson, it.body).rawText }.orEmpty()
    }

    ModalBottomSheet(
        modifier = Modifier.testTag("sheet.message.detail"),
        onDismissRequest = { onDismiss() },
        sheetState = sheetState,
        containerColor = PushGoSheetContainerColor(),
        tonalElevation = 0.dp,
    ) {
        when {
            isLoading -> {
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
            }

            current == null -> {
                AppEmptyState(
                    icon = Icons.Outlined.MarkEmailRead,
                    title = stringResource(R.string.label_no_messages),
                    description = stringResource(R.string.message_list_empty_hint),
                    modifier = Modifier.fillMaxWidth(),
                    topPadding = 32.dp,
                    iconSize = 44.dp,
                )
            }

            else -> {
                MessageDetailCoreContent(
                    message = current,
                    timeText = timeText,
                    imageModels = imageModels,
                    resolvedBodyText = resolvedBodyText,
                    onDelete = {
                        showDeleteConfirmation = true
                    },
                    onOpenImage = { model ->
                        when (model) {
                            is String -> {
                                val safeImage = normalizeExternalImageUrl(model)
                                if (safeImage != null) {
                                    previewImageModel = safeImage
                                }
                            }
                            else -> previewImageModel = model
                        }
                    },
                    onOpenUrl = { url -> context.openExternalUrl(url) },
                )
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(text = stringResource(R.string.action_delete)) },
            text = { Text(text = stringResource(R.string.confirm_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.delete()
                        onDismiss()
                    }
                ) {
                    Text(text = stringResource(R.string.label_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(text = stringResource(R.string.label_cancel))
                }
            }
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
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                SelectablePlainTextRenderer(
                    text = message.title,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("action.message.copy_title"),
                    typeface = remember { android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) },
                    textSizeSp = MaterialTheme.typography.headlineSmall.fontSize.value,
                    textColorArgb = MaterialTheme.colorScheme.onSurface.toArgb(),
                )
                if (onDelete != null) {
                    IconButton(
                        modifier = Modifier
                            .testTag("action.message.delete")
                            .size(32.dp),
                        onClick = onDelete,
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    if (message.tags.isNotEmpty()) {
                        Text(
                            text = message.tags.joinToString(separator = " · "),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Normal
                            ),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }
                MessageSeverityDetailBadge(severity = message.severity)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (imageModels.isNotEmpty()) {
            if (imageModels.size == 1) {
                val model = imageModels.first()
                AsyncImage(
                    model = model,
                    contentDescription = stringResource(R.string.label_image_attachment),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenImage(model) },
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    imageModels.forEach { model ->
                        AsyncImage(
                            model = model,
                            contentDescription = stringResource(R.string.label_image_attachment),
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpenImage(model) },
                        )
                    }
                }
            }
        }

        if (message.metadata.isNotEmpty()) {
            MetadataSection(items = message.metadata)
        }

        if (message.severity == MessageSeverity.CRITICAL) {
            CriticalSeverityHintCard()
        }

        FullMarkdownRenderer(
            text = resolvedBodyText,
            modifier = Modifier.fillMaxWidth(),
            onOpenLink = onOpenUrl,
            onOpenImage = { imageUrl -> onOpenImage(imageUrl) },
        )

        if (!message.url.isNullOrBlank()) {
            Button(
                onClick = { onOpenUrl(message.url.orEmpty()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("action.message.open_url"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.label_open_url),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun MetadataSection(items: Map<String, String>) {
    val sortedItems = items.toSortedMap(String.CASE_INSENSITIVE_ORDER).toList()
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("section.message.metadata"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column {
            sortedItems.forEachIndexed { index, (key, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.2.sp
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.width(100.dp)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (index < sortedItems.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        thickness = 0.5.dp
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
        MessageSeverity.LOW -> Color(0xFF93C5FD).copy(alpha = 0.15f)
        MessageSeverity.MEDIUM -> Color(0xFFFDE68A).copy(alpha = 0.18f)
        MessageSeverity.HIGH -> Color(0xFFF59E0B).copy(alpha = 0.15f)
        MessageSeverity.CRITICAL -> Color(0xFFDC2626).copy(alpha = 0.15f)
    }
    val fgColor = when (resolved) {
        MessageSeverity.LOW -> Color(0xFF2563EB)
        MessageSeverity.MEDIUM -> Color(0xFFD97706)
        MessageSeverity.HIGH -> Color(0xFFD97706)
        MessageSeverity.CRITICAL -> Color(0xFFDC2626)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            ),
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
