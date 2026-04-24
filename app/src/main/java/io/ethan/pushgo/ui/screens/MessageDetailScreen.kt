package io.ethan.pushgo.ui.screens

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import io.ethan.pushgo.R
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.MessageImageStore
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.data.model.MessageSeverity
import io.ethan.pushgo.markdown.MessageBodyResolver
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.ui.MessageDetailViewModelFactory
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.markdown.FullMarkdownRenderer
import io.ethan.pushgo.ui.markdown.MarkdownAnimatedImagePlaybackRegistry
import io.ethan.pushgo.ui.markdown.SelectablePlainTextRenderer
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor
import io.ethan.pushgo.ui.theme.PushGoStateColors
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import io.ethan.pushgo.ui.viewmodel.MessageDetailViewModel
import io.ethan.pushgo.util.normalizeExternalImageUrl
import io.ethan.pushgo.util.openExternalUrl
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val uiColors = PushGoThemeExtras.colors
    val initialRenderStartedAtMs = remember(messageId) { SystemClock.elapsedRealtime() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bottomGestureInset = rememberBottomGestureInset()
    val scope = rememberCoroutineScope()
    val viewModel: MessageDetailViewModel = viewModel(
        key = messageId,
        factory = MessageDetailViewModelFactory(repository, imageStore, stateCoordinator, messageId),
    )
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val copiedMessage = stringResource(R.string.message_text_copied)
    val imageSavedMessage = stringResource(R.string.message_image_saved)
    val imageSaveFailedMessage = stringResource(R.string.error_message_image_save_failed)
    val imageShareFailedMessage = stringResource(R.string.error_message_image_share_failed)
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
    var channelNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var previewImageModel by remember(current?.id) { mutableStateOf<Any?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val resolvedBodyText = remember(current?.rawPayloadJson, current?.body) {
        current?.let { MessageBodyResolver.resolve(it.rawPayloadJson, it.body).rawText }.orEmpty()
    }
    val channelDisplayName = remember(current?.channel, channelNameMap) {
        val channelId = current?.channel?.trim().orEmpty()
        if (channelId.isEmpty()) {
            null
        } else {
            channelNameMap[channelId] ?: channelId
        }
    }

    LaunchedEffect(Unit) {
        channelNameMap = channelRepository.loadSubscriptionLookup(includeDeleted = true)
    }

    PushGoModalBottomSheet(
        modifier = Modifier.testTag("sheet.message.detail"),
        onDismissRequest = { onDismiss() },
        sheetState = sheetState,
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = bottomGestureInset + 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.label_loading),
                        style = MaterialTheme.typography.bodyLarge,
                        color = uiColors.accentPrimary
                    )
                }
            }

            loadError != null -> {
                AppEmptyState(
                    icon = Icons.Outlined.MarkEmailRead,
                    title = stringResource(R.string.error_request_failed),
                    description = loadError.orEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    topPadding = 32.dp,
                    bottomPadding = bottomGestureInset + 24.dp,
                    iconSize = 44.dp,
                )
            }

            current == null -> {
                AppEmptyState(
                    icon = Icons.Outlined.MarkEmailRead,
                    title = stringResource(R.string.label_no_messages),
                    description = stringResource(R.string.message_list_empty_hint),
                    modifier = Modifier.fillMaxWidth(),
                    topPadding = 32.dp,
                    bottomPadding = bottomGestureInset + 24.dp,
                    iconSize = 44.dp,
                )
            }

            else -> {
                MessageDetailCoreContent(
                    message = current,
                    timeText = timeText,
                    imageModels = imageModels,
                    channelDisplayName = channelDisplayName,
                    resolvedBodyText = resolvedBodyText,
                    bottomGestureInset = bottomGestureInset,
                    onDelete = {
                        showDeleteConfirmation = true
                    },
                    onCopyText = { text ->
                        val trimmed = text.trim()
                        if (trimmed.isEmpty()) return@MessageDetailCoreContent
                        scope.launch {
                            clipboard.setText(AnnotatedString(trimmed))
                        }
                        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        announceForAccessibility(context, copiedMessage)
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
                    onOpenUrl = { url -> context.openExternalUrl(url) }
                )
            }
        }
    }

    if (showDeleteConfirmation) {
        PushGoAlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(text = stringResource(R.string.action_delete)) },
            text = { Text(text = stringResource(R.string.confirm_delete_message)) },
            confirmButton = {
                PushGoDestructiveTextButton(
                    text = stringResource(R.string.label_confirm),
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.delete()
                        onDismiss()
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(text = stringResource(R.string.label_cancel))
                }
            }
        )
    }

    if (previewImageModel != null) {
        val targetModel = previewImageModel
        ZoomableImagePreviewDialog(
            model = targetModel,
            onDismiss = { previewImageModel = null },
            onSaveImage = {
                val model = targetModel ?: return@ZoomableImagePreviewDialog
                scope.launch {
                    val saved = saveMessageImageToGallery(
                        context = context,
                        model = model,
                        imageStore = imageStore,
                    )
                    val messageText = if (saved) imageSavedMessage else imageSaveFailedMessage
                    Toast.makeText(context, messageText, Toast.LENGTH_SHORT).show()
                    announceForAccessibility(context, messageText)
                }
            },
            onShareImage = {
                val model = targetModel ?: return@ZoomableImagePreviewDialog
                scope.launch {
                    val shared = shareMessageImage(
                        context = context,
                        model = model,
                        imageStore = imageStore,
                    )
                    if (!shared) {
                        Toast.makeText(context, imageShareFailedMessage, Toast.LENGTH_SHORT).show()
                        announceForAccessibility(context, imageShareFailedMessage)
                    }
                }
            },
        )
    }
}

@Composable
internal fun MessageDetailCoreContent(
    message: PushMessage,
    timeText: String,
    imageModels: List<Any>,
    channelDisplayName: String?,
    resolvedBodyText: String,
    bottomGestureInset: Dp,
    onDelete: (() -> Unit)?,
    onCopyText: (String) -> Unit,
    onOpenImage: (Any) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val uiColors = PushGoThemeExtras.colors
    val detailScrollState = rememberScrollState()
    var activeAnimatedImageKey by remember(message.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(detailScrollState.isScrollInProgress) {
        if (detailScrollState.isScrollInProgress) {
            activeAnimatedImageKey = null
            MarkdownAnimatedImagePlaybackRegistry.stopAll()
        }
    }
    DisposableEffect(message.id) {
        onDispose {
            MarkdownAnimatedImagePlaybackRegistry.stopAll()
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(detailScrollState)
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp)
            .padding(bottom = bottomGestureInset + 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                SelectablePlainTextRenderer(
                    text = message.title,
                    modifier = Modifier
                        .weight(1f),
                    typeface = remember { android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) },
                    textSizeSp = MaterialTheme.typography.headlineSmall.fontSize.value,
                    textColorArgb = uiColors.textPrimary.toArgb(),
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
                            tint = uiColors.iconMuted,
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
                        color = uiColors.placeholderText
                    )
                    if (!channelDisplayName.isNullOrBlank() || message.decryptionState != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            channelDisplayName?.trim()?.takeIf { it.isNotEmpty() }?.let { displayName ->
                                PushGoChannelMetaChip(channelDisplayName = displayName)
                            }
                            message.decryptionState?.let { state ->
                                PushGoDecryptionMetaChip(decryptionState = state)
                            }
                        }
                    }
                    if (message.tags.isNotEmpty()) {
                        Text(
                            text = message.tags.joinToString(separator = " · "),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Normal
                            ),
                            color = uiColors.stateInfo.foreground
                        )
                    }
                }
                MessageSeverityDetailBadge(severity = message.severity)
            }
        }

        HorizontalDivider(color = uiColors.dividerStrong)

        if (imageModels.isNotEmpty()) {
            if (imageModels.size == 1) {
                val model = imageModels.first()
                val playbackKey = remember(model) { "detail:0:${pushGoImageModelIdentity(model)}" }
                PushGoPlayableImage(
                    model = model,
                    contentDescription = stringResource(R.string.label_image_attachment),
                    shouldPlayAnimated = activeAnimatedImageKey == playbackKey,
                    enableCrossfade = false,
                    onPlayClick = {
                        MarkdownAnimatedImagePlaybackRegistry.stopAll()
                        activeAnimatedImageKey = playbackKey
                    },
                    onPlaybackFinished = {
                        if (activeAnimatedImageKey == playbackKey) {
                            activeAnimatedImageKey = null
                        }
                    },
                    onClick = {
                        activeAnimatedImageKey = null
                        MarkdownAnimatedImagePlaybackRegistry.stopAll()
                        onOpenImage(model)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    imageModels.forEachIndexed { index, model ->
                        val playbackKey = remember(model, index) { "detail:$index:${pushGoImageModelIdentity(model)}" }
                        PushGoPlayableImage(
                            model = model,
                            contentDescription = stringResource(R.string.label_image_attachment),
                            shouldPlayAnimated = activeAnimatedImageKey == playbackKey,
                            enableCrossfade = false,
                            onPlayClick = {
                                MarkdownAnimatedImagePlaybackRegistry.stopAll()
                                activeAnimatedImageKey = playbackKey
                            },
                            onPlaybackFinished = {
                                if (activeAnimatedImageKey == playbackKey) {
                                    activeAnimatedImageKey = null
                                }
                            },
                            onClick = {
                                activeAnimatedImageKey = null
                                MarkdownAnimatedImagePlaybackRegistry.stopAll()
                                onOpenImage(model)
                            },
                            modifier = Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                    }
                }
            }
        }

        if (message.metadata.isNotEmpty()) {
            MetadataSection(
                items = message.metadata,
                onCopyValue = onCopyText,
            )
        }

        if (message.severity == MessageSeverity.CRITICAL) {
            CriticalSeverityHintCard()
        }

        FullMarkdownRenderer(
            text = resolvedBodyText,
            modifier = Modifier.fillMaxWidth(),
            onOpenLink = onOpenUrl,
            onOpenImage = { imageUrl ->
                activeAnimatedImageKey = null
                MarkdownAnimatedImagePlaybackRegistry.stopAll()
                onOpenImage(imageUrl)
            },
            onAnimatedImagePlay = {
                activeAnimatedImageKey = null
            },
        )

        if (!message.url.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { onOpenUrl(message.url.orEmpty()) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("action.message.open_url"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_open_url),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
                IconButton(
                    onClick = { onCopyText(message.url.orEmpty()) },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("action.message.copy_url"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = uiColors.iconMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataSection(
    items: Map<String, String>,
    onCopyValue: (String) -> Unit,
) {
    val uiColors = PushGoThemeExtras.colors
    val sortedItems = items.toSortedMap(String.CASE_INSENSITIVE_ORDER).toList()
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("section.message.metadata"),
        color = uiColors.surfaceSunken,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, uiColors.dividerStrong)
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
                        color = uiColors.stateInfo.foreground,
                        modifier = Modifier.width(100.dp)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = uiColors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onCopyValue(value) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                            tint = uiColors.iconMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (index < sortedItems.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = uiColors.dividerSubtle,
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
    val palette = severityPalette(resolved)
    val text = when (resolved) {
        MessageSeverity.LOW -> stringResource(R.string.message_severity_low)
        MessageSeverity.MEDIUM -> stringResource(R.string.message_severity_medium)
        MessageSeverity.HIGH -> stringResource(R.string.message_severity_high)
        MessageSeverity.CRITICAL -> stringResource(R.string.message_severity_critical)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            ),
            color = palette.foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun CriticalSeverityHintCard() {
    val palette = PushGoThemeExtras.colors.stateDanger
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("banner.message.severity.critical"),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = palette.background,
        ),
        border = BorderStroke(1.dp, palette.background),
    ) {
        Text(
            text = stringResource(R.string.message_severity_critical_hint),
            style = MaterialTheme.typography.bodySmall,
            color = palette.foreground,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun severityPalette(severity: MessageSeverity): PushGoStateColors {
    val colors = PushGoThemeExtras.colors
    return when (severity) {
        MessageSeverity.LOW, MessageSeverity.MEDIUM -> colors.stateInfo
        MessageSeverity.HIGH -> colors.stateWarning
        MessageSeverity.CRITICAL -> colors.stateDanger
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

private suspend fun saveMessageImageToGallery(
    context: Context,
    model: Any,
    imageStore: MessageImageStore,
): Boolean = withContext(Dispatchers.IO) {
    val source = resolveImageFileForAction(model, imageStore) ?: return@withContext false
    val mimeType = inferMimeType(source.name)
    val fileName = "pushgo-${System.currentTimeMillis()}.${source.extension.ifBlank { "jpg" }}"

    val resolver = context.contentResolver
    val contentValues = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PushGo")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val targetUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        ?: return@withContext false
    return@withContext try {
        resolver.openOutputStream(targetUri)?.use { output ->
            FileInputStream(source).use { input ->
                input.copyTo(output)
            }
        } ?: return@withContext false
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(targetUri, contentValues, null, null)
        true
    } catch (_: Exception) {
        resolver.delete(targetUri, null, null)
        false
    }
}

private suspend fun shareMessageImage(
    context: Context,
    model: Any,
    imageStore: MessageImageStore,
): Boolean = withContext(Dispatchers.IO) {
    val source = resolveImageFileForAction(model, imageStore) ?: return@withContext false
    val shareDir = File(context.cacheDir, "shared-images").apply { mkdirs() }
    val extension = source.extension.ifBlank { "jpg" }
    val copied = File(shareDir, "pushgo-share-${System.currentTimeMillis()}.$extension")
    return@withContext try {
        FileInputStream(source).use { input ->
            FileOutputStream(copied).use { output ->
                input.copyTo(output)
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copied)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = inferMimeType(copied.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        copied.delete()
        false
    }
}

private suspend fun resolveImageFileForAction(
    model: Any,
    imageStore: MessageImageStore,
): File? = withContext(Dispatchers.IO) {
    when (model) {
        is File -> if (model.exists() && model.isFile) model else null
        is String -> {
            val normalized = normalizeExternalImageUrl(model) ?: return@withContext null
            val cached = imageStore.ensureCached(normalized) ?: return@withContext null
            val file = File(cached.originalPath)
            if (file.exists() && file.isFile) file else null
        }
        else -> null
    }
}

private fun inferMimeType(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }
}
