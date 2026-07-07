package io.ethan.pushgo.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
import io.ethan.pushgo.R
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.data.model.MessageSeverity
import io.ethan.pushgo.notifications.ForegroundNotificationPresentationState
import io.ethan.pushgo.notifications.ForegroundNotificationTopMetrics
import io.ethan.pushgo.notifications.ProviderIngressCoordinator
import io.ethan.pushgo.markdown.MessageBodyResolver
import io.ethan.pushgo.ui.viewmodel.toUserFacingText
import io.ethan.pushgo.ui.PendingLocalDeletionCoordinator
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.accessibility.joinAccessibilitySummary
import io.ethan.pushgo.ui.accessibility.messageReadStateDescription
import io.ethan.pushgo.ui.accessibility.pushGoMergedActionSemantics
import io.ethan.pushgo.ui.accessibility.selectionStateDescription
import io.ethan.pushgo.ui.rememberBottomBarNestedScrollConnection
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import io.ethan.pushgo.ui.viewmodel.MessageListViewModel
import io.ethan.pushgo.ui.viewmodel.MessageSearchViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessageListScreen(
    navController: NavHostController,
    container: AppContainer,
    factory: PushGoViewModelFactory,
    onMessageClick: (PushMessage) -> Unit,
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
    val currentScopeUnreadCount by viewModel.currentScopeUnreadCount.collectAsStateWithLifecycle()
    val facetChannelCounts by viewModel.facetChannelCounts.collectAsStateWithLifecycle()
    val facetTagCounts by viewModel.facetTagCounts.collectAsStateWithLifecycle()
    val query by searchViewModel.queryState.collectAsStateWithLifecycle()
    val searchResults by searchViewModel.results.collectAsStateWithLifecycle()
    val pendingLocalDeletion by container.pendingLocalDeletionCoordinator.pendingDeletion.collectAsStateWithLifecycle()
    val effectivePendingScope by container.pendingLocalDeletionCoordinator.effectiveScope.collectAsStateWithLifecycle()
    
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val bottomGestureInset = rememberBottomGestureInset()
    val bottomBarNestedScrollConnection = rememberBottomBarNestedScrollConnection(onBottomBarVisibilityChanged)
    val selectionModeEnteredLabel = stringResource(R.string.a11y_selection_mode_entered)
    val selectionModeExitedLabel = stringResource(R.string.a11y_selection_mode_exited)
    var channelNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var lastSelectionMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf(emptySet<String>()) }
    var initialSelectionStateForDrag by remember { mutableStateOf<Boolean?>(null) }
    var isPullRefreshing by remember { mutableStateOf(false) }
    var listTopInWindow by remember { mutableFloatStateOf(0f) }
    var selectionRailTopInWindow by remember { mutableFloatStateOf(0f) }
    val messagesTabLabel = stringResource(R.string.tab_messages)

    fun isPendingLocalDeletion(message: PushMessage): Boolean {
        return effectivePendingScope.suppressesMessage(
            id = message.id,
            channelId = message.channel,
        )
    }

    fun normalizedChannel(channel: String?): String {
        return channel?.trim().orEmpty()
    }

    fun normalizedTags(message: PushMessage): Set<String> {
        return message.tags
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun matchesChannelSelection(message: PushMessage, selectedChannels: Set<String>): Boolean {
        if (selectedChannels.isEmpty()) return true
        return selectedChannels.contains(normalizedChannel(message.channel))
    }

    fun matchesTagSelection(message: PushMessage, selectedTags: Set<String>): Boolean {
        if (selectedTags.isEmpty()) return true
        val tags = normalizedTags(message)
        return selectedTags.any(tags::contains)
    }

    fun matchesFacetFilter(message: PushMessage, selectedChannels: Set<String>, selectedTags: Set<String>): Boolean {
        return matchesChannelSelection(message, selectedChannels) && matchesTagSelection(message, selectedTags)
    }

    val visibleSearchResults = remember(searchResults, effectivePendingScope) {
        searchResults.filterNot(::isPendingLocalDeletion)
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedMessageIds = emptySet()
        initialSelectionStateForDrag = null
    }

    suspend fun exitSelectionModeAfterFlushingPendingDeletion() {
        container.pendingLocalDeletionCoordinator.commitCurrentIfNeeded()
        exitSelectionMode()
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
            visibleSearchResults.getOrNull(rowIndex)?.id
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

    LaunchedEffect(isSelectionMode) {
        if (isSelectionMode && !lastSelectionMode) {
            announceForAccessibility(context, selectionModeEnteredLabel)
        } else if (!isSelectionMode && lastSelectionMode) {
            announceForAccessibility(context, selectionModeExitedLabel)
        }
        lastSelectionMode = isSelectionMode
    }

    suspend fun scheduleDeletion(targetMessages: List<PushMessage>) {
        val uniqueMessages = targetMessages
            .associateBy { it.id }
            .values
            .toList()
        if (uniqueMessages.isEmpty()) return

        val summary = if (uniqueMessages.size == 1) {
            uniqueMessages.first().title.trim().ifEmpty { messagesTabLabel }
        } else {
            "${uniqueMessages.size} × $messagesTabLabel"
        }
        val messageIds = uniqueMessages.map { it.id }.toSet()

        container.pendingLocalDeletionCoordinator.schedule(
            summary = summary,
            scope = PendingLocalDeletionCoordinator.Scope(messageIds = messageIds),
            onCommit = {
                container.messageStateCoordinator.deleteMessages(uniqueMessages.map { it.id })
            },
            onCompletion = { result ->
                val error = result.exceptionOrNull()
                if (error != null) {
                    val appContext = context.applicationContext
                    ContextCompat.getMainExecutor(appContext).execute {
                        Toast.makeText(
                            appContext,
                            error.toUserFacingText(appContext, R.string.error_request_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )

        selectedMessageIds = selectedMessageIds - messageIds
    }

    suspend fun deleteSelectedMessages() {
        val selectedMessages = if (query.isBlank()) {
            messages.itemSnapshotList.items.filter { selectedMessageIds.contains(it.id) }
        } else {
            visibleSearchResults
                .filter { selectedMessageIds.contains(it.id) }
                .filter { message ->
                    matchesFacetFilter(
                        message = message,
                        selectedChannels = filterState.channels,
                        selectedTags = filterState.tags,
                    )
                }
        }
        scheduleDeletion(selectedMessages)
    }

    suspend fun markSelectedMessagesRead() {
        val unreadIds = selectedMessageIds.filter { id ->
            val msg = if (query.isBlank()) {
                messages.itemSnapshotList.items.firstOrNull { it.id == id }
            } else {
                visibleSearchResults.firstOrNull { message ->
                    message.id == id && matchesFacetFilter(
                        message = message,
                        selectedChannels = filterState.channels,
                        selectedTags = filterState.tags,
                    )
                }
            }
            msg?.isRead == false
        }
        if (unreadIds.isEmpty()) return
        container.messageStateCoordinator.markRead(unreadIds)
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

    BackHandler(enabled = isSelectionMode) {
        scope.launch { exitSelectionModeAfterFlushingPendingDeletion() }
    }
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

    LaunchedEffect(effectivePendingScope) {
        val suppressedIds = effectivePendingScope.messageIds
        viewModel.setLocallySuppressedMessageIds(suppressedIds)
        searchViewModel.setLocallySuppressedMessageIds(suppressedIds)
        if (suppressedIds.isEmpty()) return@LaunchedEffect
        selectedMessageIds = selectedMessageIds.filterNot(effectivePendingScope::suppressesMessageId).toSet()
    }

    LaunchedEffect(filterState.unreadOnly) {
        searchViewModel.setUnreadOnlyFilter(filterState.unreadOnly)
    }

    val selectedChannels = filterState.channels
    val selectedTags = filterState.tags
    val visiblePagedItems = remember(messages.itemSnapshotList.items, effectivePendingScope) {
        messages.itemSnapshotList.items.filterNot(::isPendingLocalDeletion)
    }
    val filteredPagedItems = remember(visiblePagedItems, selectedChannels, selectedTags) {
        visiblePagedItems.filter { message ->
            matchesFacetFilter(
                message = message,
                selectedChannels = selectedChannels,
                selectedTags = selectedTags,
            )
        }
    }
    val filteredSearchResults = remember(visibleSearchResults, selectedChannels, selectedTags) {
        visibleSearchResults.filter { message ->
            matchesFacetFilter(
                message = message,
                selectedChannels = selectedChannels,
                selectedTags = selectedTags,
            )
        }
    }

    val channelOptions = remember(
        facetChannelCounts,
        selectedChannels,
        channelNameMap,
    ) {
        val globalCounts = linkedMapOf<String, Int>()
        facetChannelCounts.forEach { row ->
            val normalized = row.value.trim()
            globalCounts[normalized] = row.count
        }
        selectedChannels.forEach { channel -> globalCounts.putIfAbsent(channel, 0) }
        globalCounts
            .toList()
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenBy { channelNameMap[it.first] ?: it.first },
            )
    }

    val tagOptions = remember(
        facetTagCounts,
        selectedTags,
    ) {
        val globalCounts = linkedMapOf<String, Int>()
        facetTagCounts.forEach { row ->
            val normalized = row.value.trim().lowercase()
            if (normalized.isNotEmpty()) {
                globalCounts[normalized] = row.count
            }
        }
        selectedTags.forEach { tag -> globalCounts.putIfAbsent(tag, 0) }
        globalCounts
            .toList()
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenBy { it.first },
            )
    }
    val selectableMessageIds = remember(
        query,
        filteredPagedItems,
        filteredSearchResults,
        effectivePendingScope,
    ) {
        val source = if (query.isBlank()) {
            filteredPagedItems
        } else {
            filteredSearchResults
        }
        source.mapTo(mutableSetOf()) { it.id }
    }
    val areAllSelectableMessagesSelected = selectableMessageIds.isNotEmpty() &&
        selectedMessageIds.containsAll(selectableMessageIds)
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
                                    IconButton(
                                        onClick = {
                                            selectedMessageIds = if (areAllSelectableMessagesSelected) {
                                                emptySet()
                                            } else {
                                                selectableMessageIds
                                            }
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.Checklist,
                                            stringResource(R.string.action_batch_select),
                                            tint = if (areAllSelectableMessagesSelected) uiColors.accentPrimary else uiColors.iconMuted,
                                        )
                                    }
                                    Text(text = pluralStringResource(R.plurals.label_selected_count, selectedCount, selectedCount), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { scope.launch { markSelectedMessagesRead() } }) {
                                        Icon(Icons.Outlined.MarkEmailRead, stringResource(R.string.action_mark_read))
                                    }
                                    IconButton(onClick = { scope.launch { deleteSelectedMessages() } }) {
                                        Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete))
                                    }
                                    IconButton(onClick = { scope.launch { exitSelectionModeAfterFlushingPendingDeletion() } }) {
                                        SelectionDoneIcon(contentDescription = stringResource(R.string.label_confirm), accentColor = uiColors.accentPrimary)
                                    }
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
                                        val hasActiveFilter = filterState.channels.isNotEmpty()
                                            || filterState.tags.isNotEmpty()
                                            || filterState.unreadOnly
                                        val toolbarIconTint = if (hasActiveFilter) uiColors.accentPrimary else uiColors.iconMuted
                                        Box {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (query.isBlank() && currentScopeUnreadCount > 0) {
                                                    IconButton(
                                                        onClick = {
                                                            scope.launch {
                                                                val changed = viewModel.markCurrentScopeRead()
                                                                if (changed <= 0) return@launch
                                                                val localizedToastText = resources.getQuantityString(R.plurals.message_marked_read_selected_count, changed, changed)
                                                                Toast.makeText(
                                                                    context,
                                                                    localizedToastText,
                                                                    Toast.LENGTH_SHORT,
                                                                ).show()
                                                                announceForAccessibility(context, localizedToastText)
                                                            }
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.DoneAll,
                                                            contentDescription = stringResource(R.string.action_mark_all_read),
                                                            tint = toolbarIconTint,
                                                        )
                                                    }
                                                }
                                                IconButton(onClick = { searchMenuExpanded = true }) {
                                                    FilterMenuIcon(
                                                        active = hasActiveFilter,
                                                        inactiveTint = uiColors.iconMuted,
                                                        contentDescription = stringResource(R.string.label_channel_id),
                                                    )
                                                }
                                            }
                                            DropdownMenu(expanded = searchMenuExpanded, onDismissRequest = { searchMenuExpanded = false }) {
                                                DropdownMenuItem(
                                                    text = { Text(text = "选择", style = MaterialTheme.typography.bodyLarge) },
                                                    leadingIcon = { Icon(Icons.Outlined.Checklist, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                    onClick = {
                                                        isSelectionMode = true
                                                        selectedMessageIds = emptySet()
                                                        searchMenuExpanded = false
                                                    },
                                                )

                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.message_show_unread_only)) },
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.Outlined.MarkEmailUnread,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    },
                                                    onClick = {
                                                        viewModel.toggleUnreadOnlyFilter()
                                                    },
                                                    trailingIcon = {
                                                        if (filterState.unreadOnly) {
                                                            Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp))
                                                        }
                                                    }
                                                )

                                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                                if (channelOptions.isNotEmpty()) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                text = "Channels",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = uiColors.textSecondary,
                                                            )
                                                        },
                                                        onClick = {},
                                                        enabled = false,
                                                    )
                                                }
                                                if (channelOptions.isNotEmpty()) {
                                                    channelOptions.forEach { (channel, _) ->
                                                        DropdownMenuItem(
                                                            text = {
                                                                val baseName = if (channel.isBlank()) {
                                                                    stringResource(R.string.label_group_ungrouped)
                                                                } else {
                                                                    channelNameMap[channel] ?: channel
                                                                }
                                                                Text(
                                                                    text = baseName,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                )
                                                            },
                                                            onClick = { viewModel.toggleChannel(channel) },
                                                            trailingIcon = {
                                                                if (filterState.channels.contains(channel)) {
                                                                    Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp))
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                                if (tagOptions.isNotEmpty()) {
                                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                                    Column(
                                                        modifier = Modifier
                                                            .widthIn(max = 320.dp)
                                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        Text(
                                                            text = "Tags",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = uiColors.textSecondary,
                                                        )
                                                        FlowRow(
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        tagOptions.forEach { (tag, _) ->
                                                            val selected = filterState.tags.contains(tag)
                                                            FilterChip(
                                                                    selected = selected,
                                                                    onClick = {
                                                                        viewModel.toggleTag(tag)
                                                                    },
                                                                    label = { Text(tag) },
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
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
                    if (filteredPagedItems.isEmpty() && messages.loadState.refresh is LoadState.NotLoading) {
                        item { AppEmptyState(icon = Icons.Outlined.Email, title = stringResource(R.string.message_list_empty_title), description = stringResource(R.string.message_list_empty_hint)) }
                    } else {
                        items(
                            count = messages.itemCount,
                            key = messages.itemKey { it.id },
                            contentType = messages.itemContentType { "message" }
                        ) { index ->
                            val message = messages[index]
                            if (
                                message != null &&
                                !isPendingLocalDeletion(message) &&
                                matchesFacetFilter(
                                    message = message,
                                    selectedChannels = selectedChannels,
                                    selectedTags = selectedTags,
                                )
                            ) {
                                val listImageModels = remember(message.rawPayloadJson) {
                                    container.messageImageStore.resolveListImageModels(message.rawPayloadJson, MessageListImagePreviewMaxItems)
                                }
                                MessageRow(
                                    modifier = Modifier.animateItem().testTag("message.row.${message.id}"),
                                    message = message,
                                    imageModels = listImageModels,
                                    channelDisplayName = resolveChannelDisplayName(
                                        rawChannelId = message.channel,
                                        channelNameMap = channelNameMap,
                                    ),
                                    onClick = {
                                        if (isSelectionMode) {
                                            toggleSelection(message.id)
                                        } else {
                                            scope.launch {
                                                val body = MessageBodyResolver.resolve(message.rawPayloadJson, message.body).rawText
                                                container.messageImageStore.preheatDetailAssets(message.rawPayloadJson, body)
                                            }
                                            onMessageClick(message)
                                        }
                                    },
                                    onMarkRead = { viewModel.markRead(message.id) },
                                    onDelete = { scope.launch { scheduleDeletion(listOf(message)) } },
                                    selectionMode = isSelectionMode,
                                    selected = selectedMessageIds.contains(message.id),
                                    onToggleSelection = { toggleSelection(message.id) },
                                )
                            }
                        }
                    }
                } else {
                    if (filteredSearchResults.isEmpty()) {
                        item { AppEmptyState(icon = Icons.Default.Search, title = stringResource(R.string.label_no_search_results), description = stringResource(R.string.message_list_empty_hint)) }
                    } else {
                        items(items = filteredSearchResults, key = { it.id }) { message ->
                            val listImageModels = remember(message.rawPayloadJson) {
                                container.messageImageStore.resolveListImageModels(message.rawPayloadJson, MessageListImagePreviewMaxItems)
                            }
                            MessageRow(
                                modifier = Modifier.animateItem().testTag("message.row.${message.id}"),
                                message = message,
                                imageModels = listImageModels,
                                channelDisplayName = resolveChannelDisplayName(
                                    rawChannelId = message.channel,
                                    channelNameMap = channelNameMap,
                                ),
                                onClick = {
                                    if (isSelectionMode) {
                                        toggleSelection(message.id)
                                    } else {
                                        scope.launch {
                                            val body = MessageBodyResolver.resolve(message.rawPayloadJson, message.body).rawText
                                            container.messageImageStore.preheatDetailAssets(message.rawPayloadJson, body)
                                        }
                                        onMessageClick(message)
                                    }
                                },
                                onMarkRead = { viewModel.markRead(message.id) },
                                onDelete = { scope.launch { scheduleDeletion(listOf(message)) } },
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
                            .pointerInput(query, visibleSearchResults.size, messages.itemCount, listTopInWindow, selectionRailTopInWindow) {
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

}

@Composable
private fun SelectionDoneIcon(contentDescription: String, accentColor: Color) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(accentColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun FilterMenuIcon(
    active: Boolean,
    inactiveTint: Color,
    contentDescription: String,
) {
    Box(
        modifier = Modifier
            .size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.FilterList,
            contentDescription = contentDescription,
            tint = if (active) PushGoThemeExtras.colors.accentPrimary else inactiveTint,
        )
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageRow(
    modifier: Modifier = Modifier,
    message: PushMessage,
    imageModels: List<Any>,
    channelDisplayName: String?,
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
    val severityLabel = when (message.severity) {
        MessageSeverity.LOW -> stringResource(R.string.message_severity_low)
        MessageSeverity.MEDIUM -> stringResource(R.string.message_severity_medium)
        MessageSeverity.HIGH -> stringResource(R.string.message_severity_high)
        MessageSeverity.CRITICAL -> stringResource(R.string.message_severity_critical)
        null -> null
    }
    val rowSummary = joinAccessibilitySummary(
        message.title.ifBlank { stringResource(R.string.app_name) },
        timeText,
        channelDisplayName,
        severityLabel,
        bodyPreview.takeIf { it.isNotBlank() },
        if (imageModels.isNotEmpty()) "${imageModels.size} image attachments" else null,
    )
    val rowStateDescription = joinAccessibilitySummary(
        messageReadStateDescription(message.isRead),
        if (selectionMode) selectionStateDescription(selected) else null,
    ).takeIf { it.isNotBlank() }
    val markMessageReadActionLabel = stringResource(R.string.a11y_action_mark_message_read)
    val deleteMessageActionLabel = stringResource(R.string.a11y_action_delete_message)

    val uiColors = PushGoThemeExtras.colors
    Box(modifier = modifier.fillMaxWidth().background(uiColors.fieldContainer)) {
        if (!selectionMode) {
            Row(modifier = Modifier.matchParentSize().padding(end = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (hasMarkReadAction) {
                    PushGoCircularActionIconButton(
                        imageVector = Icons.Outlined.MarkEmailRead,
                        accessibilityLabel = stringResource(R.string.a11y_action_mark_message_read),
                        onClick = { offsetX = 0f; onMarkRead() },
                        containerColor = uiColors.stateInfo.background,
                        contentColor = uiColors.stateInfo.foreground,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                PushGoCircularActionIconButton(
                    imageVector = Icons.Outlined.Delete,
                    accessibilityLabel = stringResource(R.string.a11y_action_delete_message),
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
                .pushGoMergedActionSemantics(
                    summary = rowSummary,
                    stateDescription = rowStateDescription,
                    selectedState = if (selectionMode) selected else null,
                    onClickLabel = if (selectionMode) {
                        stringResource(R.string.a11y_action_toggle_selection)
                    } else {
                        stringResource(R.string.a11y_action_open_message)
                    },
                    onClickAction = { if (selectionMode) onToggleSelection() else onClick() },
                    onLongClickLabel = if (selectionMode) null else stringResource(R.string.a11y_action_enter_selection_mode),
                    onLongClickAction = if (selectionMode) {
                        null
                    } else {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleSelection()
                        }
                    },
                    customActions = if (selectionMode) {
                        emptyList()
                    } else {
                        buildList {
                            if (hasMarkReadAction) {
                                add(
                                    CustomAccessibilityAction(
                                        label = markMessageReadActionLabel,
                                        action = {
                                            offsetX = 0f
                                            onMarkRead()
                                            true
                                        },
                                    )
                                )
                            }
                            add(
                                CustomAccessibilityAction(
                                    label = deleteMessageActionLabel,
                                    action = {
                                        offsetX = 0f
                                        onDelete()
                                        true
                                    },
                                )
                            )
                        }
                    },
                )
                .padding(horizontal = ScreenHorizontalPadding, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectionMode) {
                    PushGoSelectionIndicator(selected = selected, onClick = onToggleSelection)
                }
                Column(modifier = Modifier.weight(1f)) {
                    MessageRowContent(
                        message = message,
                        imageModels = imageModels,
                        appName = stringResource(R.string.app_name),
                        timeText = timeText,
                        bodyPreview = bodyPreview,
                        channelDisplayName = channelDisplayName,
                    )
                }
            }
        }
    }
    PushGoDividerSubtle(
        thickness = 0.5.dp,
        color = uiColors.dividerSubtle.copy(alpha = 0.55f),
    )
}

@Composable
fun MessageRowContent(
    message: PushMessage,
    imageModels: List<Any>,
    appName: String,
    timeText: String,
    bodyPreview: String,
    channelDisplayName: String? = null,
) {
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
    val hasMetaChips = !channelDisplayName.isNullOrBlank() || message.decryptionState != null
    if (hasMetaChips || bodyPreview.isNotBlank() || imageModels.isNotEmpty() || message.tags.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (hasMetaChips) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    channelDisplayName?.trim()?.takeIf { it.isNotEmpty() }?.let { displayName ->
                        PushGoChannelMetaChip(channelDisplayName = displayName)
                    }
                    message.decryptionState?.let { state ->
                        PushGoDecryptionMetaChip(decryptionState = state)
                    }
                }
            }
            if (bodyPreview.isNotBlank()) Text(text = bodyPreview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = uiColors.textSecondary)
            if (message.tags.isNotEmpty()) Text(text = message.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = PushGoThemeExtras.colors.stateInfo.foreground)
            if (imageModels.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { imageModels.forEach { PushGoAsyncImage(model = it, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) } }
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

private fun resolveChannelDisplayName(
    rawChannelId: String?,
    channelNameMap: Map<String, String>,
): String? {
    val channelId = rawChannelId?.trim().orEmpty()
    if (channelId.isEmpty()) {
        return null
    }
    return channelNameMap[channelId] ?: channelId
}

internal fun formatMessageTime(context: Context, receivedAt: Instant, zoneId: ZoneId, nowInstant: Instant = Instant.now()): String {
    val millis = receivedAt.toEpochMilli()
    return DateUtils.getRelativeTimeSpanString(millis, nowInstant.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL).toString()
}
