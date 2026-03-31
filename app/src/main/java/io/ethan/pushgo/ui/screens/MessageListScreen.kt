package io.ethan.pushgo.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.FilterList as FilledFilterList
import androidx.compose.material.icons.outlined.FilterList as OutlinedFilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.compose.itemContentType
import coil.compose.AsyncImage
import io.ethan.pushgo.R
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.data.model.MessageSeverity
import io.ethan.pushgo.data.model.ReadFilter
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.viewmodel.MessageListViewModel
import io.ethan.pushgo.ui.viewmodel.MessageSearchViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.items

private val ScreenHorizontalPadding = 12.dp
private const val MessageListImagePreviewMaxItems = 3

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MessageListScreen(
    navController: NavHostController,
    container: AppContainer,
    factory: PushGoViewModelFactory,
    onMessageClick: (String) -> Unit,
    onBatchModeChanged: (Boolean) -> Unit,
) {
    val viewModel: MessageListViewModel = viewModel(factory = factory)
    val searchViewModel: MessageSearchViewModel = viewModel(factory = factory)
    val messages = viewModel.messages.collectAsLazyPagingItems()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val channelCounts by viewModel.channelCounts.collectAsStateWithLifecycle()
    val query by searchViewModel.queryState.collectAsStateWithLifecycle()
    val searchResults by searchViewModel.results.collectAsStateWithLifecycle()
    
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    
    var channelNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(emptySet<String>()) }
    var initialSelectionStateForDrag by remember { mutableStateOf<Boolean?>(null) }
    var showBatchDeleteConfirmation by remember { mutableStateOf(false) }
    var listTopInWindow by remember { mutableFloatStateOf(0f) }
    var selectionRailTopInWindow by remember { mutableFloatStateOf(0f) }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedMessageIds = emptySet()
        initialSelectionStateForDrag = null
    }

    fun toggleSelection(messageId: String) {
        selectedMessageIds = if (selectedMessageIds.contains(messageId)) {
            selectedMessageIds - messageId
        } else {
            selectedMessageIds + messageId
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun messageIdForVisibleItemIndex(itemIndex: Int): String? {
        val rowIndex = itemIndex - 1
        if (rowIndex < 0) return null
        return if (query.isBlank()) {
            if (rowIndex < messages.itemCount) messages.peek(rowIndex)?.id else null
        } else {
            searchResults.getOrNull(rowIndex)?.id
        }
    }

    fun updateSelectionAtRailY(railLocalY: Float, targetState: Boolean) {
        val listLocalY = railLocalY + (selectionRailTopInWindow - listTopInWindow)
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            listLocalY in item.offset.toFloat()..(item.offset + item.size).toFloat()
        }
        val messageId = target?.index?.let { index -> messageIdForVisibleItemIndex(index) }
        if (messageId != null) {
            val isSelected = selectedMessageIds.contains(messageId)
            if (isSelected != targetState) {
                selectedMessageIds = if (targetState) selectedMessageIds + messageId else selectedMessageIds - messageId
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    suspend fun deleteSelectedMessages() {
        val ids = selectedMessageIds.toList()
        if (ids.isEmpty()) return
        ids.forEach { container.messageStateCoordinator.deleteMessage(it) }
        exitSelectionMode()
    }

    suspend fun markSelectedMessagesRead() {
        val unreadIds = selectedMessageIds.filter { id ->
            val msg = if (query.isBlank()) {
                messages.itemSnapshotList.items.firstOrNull { it.id == id }
            } else {
                searchResults.firstOrNull { it.id == id }
            }
            msg?.isRead == false
        }
        if (unreadIds.isEmpty()) return
        unreadIds.forEach { viewModel.markRead(it) }
        exitSelectionMode()
    }

    BackHandler(enabled = isSelectionMode) { exitSelectionMode() }
    LaunchedEffect(isSelectionMode) { onBatchModeChanged(isSelectionMode) }
    DisposableEffect(Unit) { onDispose { onBatchModeChanged(false) } }

    LaunchedEffect(Unit) {
        channelNameMap = container.channelRepository.loadSubscriptionLookup(includeDeleted = true)
        delay(180)
        viewModel.enableChannelCounts()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { channelNameMap = container.channelRepository.loadSubscriptionLookup(includeDeleted = true) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val channelOptions = remember(channelCounts) { channelCounts.map { it.channel.trim() }.distinct() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .onGloballyPositioned { listTopInWindow = it.positionInWindow().y },
            state = listState,
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), contentAlignment = Alignment.Center) {
                        if (isSelectionMode) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val selectedCount = selectedMessageIds.size
                                Text(text = stringResource(R.string.label_selected_count, selectedCount), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { scope.launch { markSelectedMessagesRead() } }) {
                                    Icon(Icons.Outlined.MarkEmailRead, stringResource(R.string.action_mark_read))
                                }
                                IconButton(onClick = { showBatchDeleteConfirmation = true }) {
                                    Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete))
                                }
                                TextButton(onClick = { exitSelectionMode() }) { Text(stringResource(R.string.label_cancel)) }
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding), verticalAlignment = Alignment.CenterVertically) {
                                var searchMenuExpanded by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                    TextField(
                                        value = query,
                                        onValueChange = searchViewModel::updateQuery,
                                        placeholder = { Text(stringResource(R.string.label_search), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                                        modifier = Modifier.weight(1f).testTag("field.message.search"),
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                        ),
                                        singleLine = true,
                                    )
                                    Box {
                                        IconButton(onClick = { searchMenuExpanded = true }) {
                                            val hasActiveFilter = filterState.channel != null || filterState.readFilter == ReadFilter.UNREAD
                                            Icon(
                                                imageVector = if (!hasActiveFilter) Icons.Outlined.OutlinedFilterList else Icons.Filled.FilledFilterList,
                                                contentDescription = stringResource(R.string.label_channel_id),
                                                tint = if (hasActiveFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            )
                                        }
                                        DropdownMenu(expanded = searchMenuExpanded, onDismissRequest = { searchMenuExpanded = false }) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.filter_unread)) },
                                                onClick = {
                                                    viewModel.setReadFilter(if (filterState.readFilter == ReadFilter.UNREAD) ReadFilter.ALL else ReadFilter.UNREAD)
                                                    searchMenuExpanded = false
                                                },
                                                trailingIcon = { if (filterState.readFilter == ReadFilter.UNREAD) Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp)) }
                                            )
                                            if (channelOptions.isNotEmpty()) {
                                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                                channelOptions.forEach { channel ->
                                                    DropdownMenuItem(
                                                        text = { Text(if (channel.isBlank()) stringResource(R.string.label_group_ungrouped) else channelNameMap[channel] ?: channel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                        onClick = { viewModel.setChannel(if (filterState.channel == channel) null else channel); searchMenuExpanded = false },
                                                        trailingIcon = { if (filterState.channel == channel) Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp)) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    IconButton(onClick = { isSelectionMode = true; selectedMessageIds = emptySet() }) {
                                        Icon(Icons.Outlined.Checklist, stringResource(R.string.action_batch_select), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.tab_messages),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = ScreenHorizontalPadding, top = 8.dp, bottom = 12.dp).semantics { heading() },
                    )
                }
            }

            if (messages.loadState.refresh is LoadState.Loading && messages.itemCount == 0) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            } else {
                if (query.isBlank()) {
                    if (messages.itemCount == 0 && messages.loadState.refresh is LoadState.NotLoading) {
                        item { AppEmptyState(icon = Icons.Outlined.Email, title = stringResource(R.string.message_list_empty_title), description = stringResource(R.string.message_list_empty_hint)) }
                    } else {
                        items(
                            count = messages.itemCount,
                            key = messages.itemKey { it.id },
                            contentType = messages.itemContentType { "message" }
                        ) { index ->
                            val message = messages[index]
                            if (message != null) {
                                val listImageModels = remember(message.rawPayloadJson) {
                                    container.messageImageStore.resolveListImageModels(message.rawPayloadJson, MessageListImagePreviewMaxItems)
                                }
                                MessageRow(
                                    modifier = Modifier.animateItem().testTag("message.row.${message.id}"),
                                    message = message,
                                    imageModels = listImageModels,
                                    onClick = { if (isSelectionMode) toggleSelection(message.id) else onMessageClick(message.id) },
                                    onMarkRead = { viewModel.markRead(message.id) },
                                    onDelete = { viewModel.deleteMessage(message.id) },
                                    selectionMode = isSelectionMode,
                                    selected = selectedMessageIds.contains(message.id),
                                    onToggleSelection = { toggleSelection(message.id) },
                                )
                            }
                        }
                    }
                } else {
                    if (searchResults.isEmpty()) {
                        item { AppEmptyState(icon = Icons.Default.Search, title = stringResource(R.string.label_no_search_results), description = stringResource(R.string.message_list_empty_hint)) }
                    } else {
                        items(items = searchResults, key = { it.id }) { message ->
                            val listImageModels = remember(message.rawPayloadJson) {
                                container.messageImageStore.resolveListImageModels(message.rawPayloadJson, MessageListImagePreviewMaxItems)
                            }
                            MessageRow(
                                modifier = Modifier.animateItem().testTag("message.row.${message.id}"),
                                message = message,
                                imageModels = listImageModels,
                                onClick = { if (isSelectionMode) toggleSelection(message.id) else onMessageClick(message.id) },
                                onMarkRead = { viewModel.markRead(message.id) },
                                onDelete = { viewModel.deleteMessage(message.id) },
                                selectionMode = isSelectionMode,
                                selected = selectedMessageIds.contains(message.id),
                                onToggleSelection = { toggleSelection(message.id) },
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }

        if (isSelectionMode) {
            Box(
                modifier = Modifier.align(Alignment.TopStart).fillMaxHeight().width(72.dp)
                    .onGloballyPositioned { selectionRailTopInWindow = it.positionInWindow().y }
                    .pointerInput(query, searchResults.size, messages.itemCount, listTopInWindow, selectionRailTopInWindow) {
                        detectDragGestures(
                            onDragStart = { point ->
                                val listLocalY = point.y + (selectionRailTopInWindow - listTopInWindow)
                                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    listLocalY in item.offset.toFloat()..(item.offset + item.size).toFloat()
                                }
                                val messageId = target?.index?.let { index -> messageIdForVisibleItemIndex(index) }
                                if (messageId != null) {
                                    initialSelectionStateForDrag = !selectedMessageIds.contains(messageId)
                                    updateSelectionAtRailY(point.y, !selectedMessageIds.contains(messageId))
                                }
                            },
                            onDrag = { change, _ -> initialSelectionStateForDrag?.let { updateSelectionAtRailY(change.position.y, it) } },
                            onDragEnd = { initialSelectionStateForDrag = null },
                            onDragCancel = { initialSelectionStateForDrag = null }
                        )
                    },
            )
        }
    }

    if (showBatchDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirmation = false },
            title = { Text(text = stringResource(R.string.action_delete)) },
            text = { Text(text = stringResource(R.string.confirm_delete_selected_messages, selectedMessageIds.size)) },
            confirmButton = { TextButton(onClick = { showBatchDeleteConfirmation = false; scope.launch { deleteSelectedMessages() } }) { Text(stringResource(R.string.label_confirm)) } },
            dismissButton = { TextButton(onClick = { showBatchDeleteConfirmation = false }) { Text(stringResource(R.string.label_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    modifier: Modifier = Modifier,
    message: PushMessage,
    imageModels: List<Any>,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val hasMarkReadAction = !message.isRead
    val actionWidth = if (hasMarkReadAction) 140.dp else 72.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val timeText = remember(message.receivedAt) { formatMessageTime(context, message.receivedAt, ZoneId.systemDefault()) }
    val bodyPreview = remember(message.bodyPreview) { message.bodyPreview?.trim().orEmpty() }

    Box(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
        if (!selectionMode) {
            Row(modifier = Modifier.matchParentSize().padding(end = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (hasMarkReadAction) {
                    IconButton(onClick = { offsetX = 0f; onMarkRead() }, modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)) {
                        Icon(Icons.Outlined.MarkEmailRead, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                IconButton(onClick = { offsetX = 0f; onDelete() }, modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer)) {
                    Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                }
            }
        }
        Column(
            modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }.fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface)
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelection() else onClick() },
                    onLongClick = { if (!selectionMode) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggleSelection() } }
                )
                .then(
                    if (selectionMode) Modifier 
                    else Modifier.draggable(
                        state = rememberDraggableState { delta -> offsetX = (offsetX + delta).coerceIn(-actionWidthPx, 0f) },
                        orientation = Orientation.Horizontal,
                        onDragStopped = { offsetX = if (offsetX < -actionWidthPx / 2) -actionWidthPx else 0f }
                    )
                )
                .padding(horizontal = ScreenHorizontalPadding, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectionMode) {
                    Icon(imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp).padding(top = 2.dp).clickable { onToggleSelection() })
                }
                Column(modifier = Modifier.weight(1f)) {
                    MessageRowContent(message, imageModels, stringResource(R.string.app_name), timeText, bodyPreview)
                }
            }
        }
    }
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
}

@Composable
fun MessageRowContent(message: PushMessage, imageModels: List<Any>, appName: String, timeText: String, bodyPreview: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(modifier = Modifier.weight(1f), text = message.title.ifBlank { appName }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (message.isRead) FontWeight.SemiBold else FontWeight.ExtraBold), maxLines = 1)
                MessageSeverityListBadge(message.severity)
            }
        }
        if (!message.isRead) { Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)); Spacer(modifier = Modifier.width(6.dp)) }
        Text(text = timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (bodyPreview.isNotBlank() || imageModels.isNotEmpty() || message.tags.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (bodyPreview.isNotBlank()) Text(text = bodyPreview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.tags.isNotEmpty()) Text(text = message.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            if (imageModels.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { imageModels.forEach { AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) } }
        }
    }
}

@Composable
private fun MessageSeverityListBadge(severity: MessageSeverity?) {
    val (label, color) = when (severity) {
        MessageSeverity.HIGH -> stringResource(R.string.message_severity_high_compact) to Color(0xFFB45309)
        MessageSeverity.CRITICAL -> stringResource(R.string.message_severity_critical_compact) to Color(0xFFB91C1C)
        else -> return
    }
    Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = color)
    }
}

internal fun formatMessageTime(context: Context, receivedAt: Instant, zoneId: ZoneId, nowInstant: Instant = Instant.now()): String {
    val millis = receivedAt.toEpochMilli()
    return DateUtils.getRelativeTimeSpanString(millis, nowInstant.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL).toString()
}
