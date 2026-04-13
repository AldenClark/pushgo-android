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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import io.ethan.pushgo.data.model.MessageListSortMode
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.data.model.MessageSeverity
import io.ethan.pushgo.notifications.ForegroundNotificationPresentationState
import io.ethan.pushgo.notifications.ForegroundNotificationTopMetrics
import io.ethan.pushgo.notifications.ProviderIngressCoordinator
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.rememberBottomBarNestedScrollConnection
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import io.ethan.pushgo.ui.viewmodel.MessageListViewModel
import io.ethan.pushgo.ui.viewmodel.MessageSearchViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.SolidColor
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
    onBottomBarVisibilityChanged: (Boolean) -> Unit,
    suppressForegroundNotificationAtTop: Boolean,
    scrollToUnreadToken: Long,
    scrollToTopToken: Long,
) {
    val viewModel: MessageListViewModel = viewModel(factory = factory)
    val searchViewModel: MessageSearchViewModel = viewModel(factory = factory)
    val uiColors = PushGoThemeExtras.colors
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
    val bottomGestureInset = rememberBottomGestureInset()
    val bottomBarNestedScrollConnection = rememberBottomBarNestedScrollConnection(onBottomBarVisibilityChanged)
    
    var channelNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(emptySet<String>()) }
    var initialSelectionStateForDrag by remember { mutableStateOf<Boolean?>(null) }
    var showBatchDeleteConfirmation by remember { mutableStateOf(false) }
    var isPullRefreshing by remember { mutableStateOf(false) }
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

    fun refreshProviderIngressFromPullDown() {
        if (isPullRefreshing) return
        scope.launch {
            isPullRefreshing = true
            runCatching {
                ProviderIngressCoordinator.pullPersistAndDrainAcks(
                    context = context,
                    channelRepository = container.channelRepository,
                    messageRepository = container.messageRepository,
                    entityRepository = container.entityRepository,
                    inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
                    settingsRepository = container.settingsRepository,
                )
            }.onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(
                    "MessageListScreen",
                    "provider ingress refresh failed",
                    error,
                )
            }
            channelNameMap = container.channelRepository.loadSubscriptionLookup(includeDeleted = true)
            messages.refresh()
            isPullRefreshing = false
        }
    }

    BackHandler(enabled = isSelectionMode) { exitSelectionMode() }
    LaunchedEffect(isSelectionMode) { onBatchModeChanged(isSelectionMode) }
    DisposableEffect(Unit) {
        onDispose {
            onBatchModeChanged(false)
            onBottomBarVisibilityChanged(true)
            ForegroundNotificationPresentationState.clearMessage()
        }
    }

    LaunchedEffect(suppressForegroundNotificationAtTop) {
        if (!suppressForegroundNotificationAtTop) {
            ForegroundNotificationPresentationState.reportMessage(
                isAtTop = false,
                suppressionEligible = false,
            )
        }
    }

    LaunchedEffect(listState, suppressForegroundNotificationAtTop) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= ForegroundNotificationTopMetrics.topOffsetTolerancePx
        }
            .distinctUntilChanged()
            .collect { isAtTop ->
                ForegroundNotificationPresentationState.reportMessage(
                    isAtTop = isAtTop,
                    suppressionEligible = suppressForegroundNotificationAtTop,
                )
            }
    }

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

    LaunchedEffect(scrollToUnreadToken) {
        if (scrollToUnreadToken == 0L) return@LaunchedEffect
        val unreadRowIndex = if (query.isBlank()) {
            messages.itemSnapshotList.items.indexOfFirst { !it.isRead }
        } else {
            searchResults.indexOfFirst { !it.isRead }
        }
        if (unreadRowIndex >= 0) {
            listState.animateScrollToItem(unreadRowIndex + 1)
        }
    }

    LaunchedEffect(scrollToTopToken) {
        if (scrollToTopToken == 0L) return@LaunchedEffect
        listState.animateScrollToItem(0)
    }

    val channelOptions = remember(channelCounts) { channelCounts.map { it.channel.trim() }.distinct() }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = { refreshProviderIngressFromPullDown() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(uiColors.surfaceBase)
                    .nestedScroll(bottomBarNestedScrollConnection)
                    .onGloballyPositioned { listTopInWindow = it.positionInWindow().y },
                state = listState,
                contentPadding = PaddingValues(bottom = bottomGestureInset + 24.dp),
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
                                    PushGoSearchBar(
                                        value = query,
                                        onValueChange = searchViewModel::updateQuery,
                                        placeholderText = stringResource(R.string.label_search),
                                        modifier = Modifier.weight(1f).testTag("field.message.search")
                                    ) {
                                        Box {
                                            IconButton(onClick = {
                                                val nextSortMode =
                                                    if (filterState.sortMode == MessageListSortMode.UNREAD_FIRST) {
                                                        MessageListSortMode.TIME_DESC
                                                    } else {
                                                        MessageListSortMode.UNREAD_FIRST
                                                    }
                                                viewModel.setSortMode(nextSortMode)
                                                searchViewModel.setSortMode(nextSortMode)
                                            }) {
                                                val unreadFirstEnabled = filterState.sortMode == MessageListSortMode.UNREAD_FIRST
                                                Icon(
                                                    imageVector = Icons.Outlined.SwapVert,
                                                    contentDescription = stringResource(R.string.label_sort),
                                                    tint = if (unreadFirstEnabled) uiColors.accentPrimary else uiColors.iconMuted,
                                                )
                                            }
                                        }
                                        Box {
                                            IconButton(onClick = { searchMenuExpanded = true }) {
                                                val hasActiveFilter = filterState.channel != null
                                                Icon(
                                                    imageVector = if (!hasActiveFilter) Icons.Outlined.OutlinedFilterList else Icons.Filled.FilledFilterList,
                                                    contentDescription = stringResource(R.string.label_channel_id),
                                                    tint = if (hasActiveFilter) uiColors.accentPrimary else uiColors.iconMuted,
                                                )
                                            }
                                            DropdownMenu(expanded = searchMenuExpanded, onDismissRequest = { searchMenuExpanded = false }) {
                                                if (channelOptions.isNotEmpty()) {
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
                                            Icon(Icons.Outlined.Checklist, stringResource(R.string.action_batch_select), tint = uiColors.iconMuted)
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.tab_messages),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
                            color = uiColors.textPrimary,
                            modifier = Modifier.padding(start = ScreenHorizontalPadding, top = 8.dp, bottom = 12.dp).semantics { heading() },
                        )
                    }
                }

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
        }

        if (showBatchDeleteConfirmation) {
            PushGoAlertDialog(
                onDismissRequest = { showBatchDeleteConfirmation = false },
                title = { Text(text = stringResource(R.string.action_delete)) },
                text = { Text(text = stringResource(R.string.confirm_delete_selected_messages, selectedMessageIds.size)) },
                confirmButton = {
                    PushGoDestructiveTextButton(
                        text = stringResource(R.string.label_confirm),
                        onClick = {
                            showBatchDeleteConfirmation = false
                            scope.launch { deleteSelectedMessages() }
                        },
                    )
                },
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

    val uiColors = PushGoThemeExtras.colors
    Box(modifier = modifier.fillMaxWidth().background(uiColors.fieldContainer)) {
        if (!selectionMode) {
            Row(modifier = Modifier.matchParentSize().padding(end = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (hasMarkReadAction) {
                    PushGoCircularActionIconButton(
                        imageVector = Icons.Outlined.MarkEmailRead,
                        contentDescription = null,
                        onClick = { offsetX = 0f; onMarkRead() },
                        containerColor = uiColors.stateInfo.background,
                        contentColor = uiColors.stateInfo.foreground,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                PushGoCircularActionIconButton(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    onClick = { offsetX = 0f; onDelete() },
                    containerColor = uiColors.stateDanger.background,
                    contentColor = uiColors.stateDanger.foreground,
                )
            }
        }
        Column(
            modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }.fillMaxWidth()
                .background(if (selected) uiColors.selectedRowFill else uiColors.surfaceBase)
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
                    PushGoSelectionIndicator(selected = selected, onClick = onToggleSelection)
                }
                Column(modifier = Modifier.weight(1f)) {
                    MessageRowContent(message, imageModels, stringResource(R.string.app_name), timeText, bodyPreview)
                }
            }
        }
    }
    PushGoDividerSubtle(thickness = 1.dp)
}

@Composable
fun MessageRowContent(message: PushMessage, imageModels: List<Any>, appName: String, timeText: String, bodyPreview: String) {
    val uiColors = PushGoThemeExtras.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(modifier = Modifier.weight(1f), text = message.title.ifBlank { appName }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (message.isRead) FontWeight.SemiBold else FontWeight.ExtraBold), maxLines = 1)
                MessageSeverityListBadge(message.severity)
            }
        }
        if (!message.isRead) { PushGoStatusDot(color = uiColors.accentPrimary); Spacer(modifier = Modifier.width(6.dp)) }
        Text(text = timeText, style = MaterialTheme.typography.labelSmall, color = uiColors.textSecondary)
    }
    if (bodyPreview.isNotBlank() || imageModels.isNotEmpty() || message.tags.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (bodyPreview.isNotBlank()) Text(text = bodyPreview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = uiColors.textSecondary)
            if (message.tags.isNotEmpty()) Text(text = message.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = PushGoThemeExtras.colors.stateInfo.foreground)
            if (imageModels.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { imageModels.forEach { AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) } }
        }
    }
}

@Composable
private fun MessageSeverityListBadge(severity: MessageSeverity?) {
    val colors = PushGoThemeExtras.colors
    val (label, palette) = when (severity) {
        MessageSeverity.HIGH -> stringResource(R.string.message_severity_high_compact) to colors.stateWarning
        MessageSeverity.CRITICAL -> stringResource(R.string.message_severity_critical_compact) to colors.stateDanger
        else -> return
    }
    Box(modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(palette.background).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = palette.foreground)
    }
}

internal fun formatMessageTime(context: Context, receivedAt: Instant, zoneId: ZoneId, nowInstant: Instant = Instant.now()): String {
    val millis = receivedAt.toEpochMilli()
    return DateUtils.getRelativeTimeSpanString(millis, nowInstant.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL).toString()
}
