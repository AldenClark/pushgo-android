package io.ethan.pushgo.ui.screens

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.EntityProjectionCursor
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.ForegroundNotificationPresentationState
import io.ethan.pushgo.notifications.ForegroundNotificationTopMetrics
import io.ethan.pushgo.notifications.ProviderIngressCoordinator
import io.ethan.pushgo.ui.rememberBottomBarNestedScrollConnection
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.theme.PushGoStateColors
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable
data class EventTimelineRow(
    val messageId: String,
    val title: String,
    val displayTitle: String?,
    val summary: String?,
    val displaySummary: String?,
    val status: String?,
    val message: String?,
    val severity: EventSeverity?,
    val tags: List<String>,
    val state: EventLifecycleState,
    val thingId: String?,
    val channelId: String?,
    val imageUrl: String?,
    val attachmentUrls: List<String>,
    val attrsJson: String?,
    @Serializable(with = InstantSerializer::class)
    val happenedAt: Instant,
)

@Serializable
data class EventCardModel(
    val eventId: String,
    val title: String,
    val summary: String?,
    val status: String?,
    val message: String?,
    val imageUrl: String?,
    val severity: EventSeverity?,
    val tags: List<String>,
    val state: EventLifecycleState,
    val thingId: String?,
    val channelId: String?,
    val attachmentUrls: List<String>,
    val attrsJson: String?,
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
    val timeline: List<EventTimelineRow>,
)

enum class EventSeverity(val wireValue: String) {
    Critical("critical"),
    High("high"),
    Normal("normal"),
    Low("low");

    companion object {
        fun fromRaw(raw: String?): EventSeverity? {
            return when (raw?.trim()?.lowercase()) {
                Critical.wireValue -> Critical
                High.wireValue -> High
                Normal.wireValue -> Normal
                Low.wireValue -> Low
                else -> null
            }
        }
    }
}

enum class EventLifecycleState(val token: String) {
    Ongoing("ongoing"),
    Closed("closed"),
    Unknown("unknown");

    companion object {
        fun fromRaw(raw: String?): EventLifecycleState {
            return when (raw?.trim()?.uppercase()) {
                "ONGOING" -> Ongoing
                "CLOSED" -> Closed
                else -> Unknown
            }
        }
    }
}

private val EventTimeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
private val ScreenHorizontalPadding = 12.dp
private const val EventProjectionPageSize = 240

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EventListScreen(
    container: AppContainer,
    refreshToken: Long,
    openEventId: String?,
    onOpenEventHandled: () -> Unit,
    onEventDetailOpened: (String) -> Unit,
    onEventDetailClosed: () -> Unit,
    onBatchModeChanged: (Boolean) -> Unit,
    onBottomBarVisibilityChanged: (Boolean) -> Unit,
    scrollToTopToken: Long,
) {
    val uiColors = PushGoThemeExtras.colors
    var allEvents by remember { mutableStateOf<List<EventCardModel>>(emptyList()) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var eventCursor by remember { mutableStateOf<EntityProjectionCursor?>(null) }
    var hasMoreEvents by remember { mutableStateOf(true) }
    var isLoadingMoreEvents by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<EventCardModel?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedEventIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirmation by remember { mutableStateOf(false) }
    var isPullRefreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var channelFilter by remember { mutableStateOf<String?>(null) }
    var showOnlyOpen by remember { mutableStateOf(false) }
    var pendingCloseEvent by remember { mutableStateOf<EventCardModel?>(null) }
    var pendingDeleteEventId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val closeEventFailedMessage = stringResource(R.string.error_event_close_failed)
    val closeEventStatusDefault = stringResource(R.string.event_status_closed_default)
    val closeEventBodyDefault = stringResource(R.string.event_message_closed_default)
    val closeEventSuccessMessage = stringResource(R.string.message_event_closed)
    val singleDeleteToast = stringResource(R.string.message_deleted_selected_count, 1)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val bottomGestureInset = rememberBottomGestureInset()
    val bottomBarNestedScrollConnection = rememberBottomBarNestedScrollConnection(onBottomBarVisibilityChanged)
    var listTopInWindow by remember { mutableFloatStateOf(0f) }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedEventIds = emptySet()
    }

    fun toggleSelection(eventId: String) {
        selectedEventIds = if (selectedEventIds.contains(eventId)) {
            selectedEventIds - eventId
        } else {
            selectedEventIds + eventId
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    suspend fun reloadEventsInternal() {
        if (isLoadingMoreEvents) return
        isLoadingMoreEvents = true
        try {
            eventCursor = null
            hasMoreEvents = true
            val firstPage = container.entityRepository.getEventProjectionMessagesPage(
                before = null,
                limit = EventProjectionPageSize,
            )
            allEvents = withContext(Dispatchers.Default) { buildEventCardsInternal(firstPage) }
            val last = firstPage.lastOrNull()
            eventCursor = last?.let { EntityProjectionCursor(receivedAt = it.receivedAt.toEpochMilli(), id = it.id) }
            hasMoreEvents = firstPage.size >= EventProjectionPageSize
            hasLoadedOnce = true
        } finally { isLoadingMoreEvents = false }
    }

    fun reloadEvents() {
        scope.launch {
            reloadEventsInternal()
        }
    }

    suspend fun closeEvent(event: EventCardModel) {
        val channelId = event.channelId.orEmpty().trim()
        if (channelId.isEmpty()) {
            showToast("$closeEventFailedMessage: missing channel_id")
            return
        }
        runCatching {
            container.channelRepository.closeEvent(
                rawEventId = event.eventId,
                rawThingId = event.thingId,
                rawChannelId = channelId,
                rawStatus = closeEventStatusDefault,
                rawMessage = closeEventBodyDefault,
                rawSeverity = event.severity?.wireValue,
            )
        }.onSuccess {
            showToast(closeEventSuccessMessage)
            if (selectedEvent?.eventId == event.eventId) {
                selectedEvent = null
                onEventDetailClosed()
            }
            reloadEventsInternal()
        }.onFailure { error ->
            showToast("$closeEventFailedMessage: ${error.message.orEmpty()}")
        }
    }

    suspend fun deleteEvent(eventId: String) {
        val deleted = container.entityRepository.deleteEvent(eventId)
        if (selectedEvent?.eventId == eventId) {
            selectedEvent = null
            onEventDetailClosed()
        }
        if (deleted > 0) {
            showToast(singleDeleteToast)
        }
        reloadEventsInternal()
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
                reloadEventsInternal()
            }.onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(
                    "EventListScreen",
                    "provider ingress refresh failed",
                    error,
                )
            }
            isPullRefreshing = false
        }
    }

    suspend fun loadMoreEventsInternal() {
        if (isLoadingMoreEvents || !hasMoreEvents) return
        isLoadingMoreEvents = true
        try {
            val page = container.entityRepository.getEventProjectionMessagesPage(before = eventCursor, limit = EventProjectionPageSize)
            if (page.isNotEmpty()) {
                val merged = withContext(Dispatchers.Default) { mergeEventCardsInternal(allEvents, buildEventCardsInternal(page)) }
                allEvents = merged
                val last = page.last()
                eventCursor = EntityProjectionCursor(receivedAt = last.receivedAt.toEpochMilli(), id = last.id)
            }
            hasMoreEvents = page.size >= EventProjectionPageSize
        } finally { isLoadingMoreEvents = false }
    }

    fun loadMoreEventsIfNeeded() {
        if (isLoadingMoreEvents || !hasMoreEvents) return
        scope.launch { loadMoreEventsInternal() }
    }

    LaunchedEffect(refreshToken) { reloadEvents() }
    LaunchedEffect(isSelectionMode) { onBatchModeChanged(isSelectionMode) }
    DisposableEffect(Unit) {
        onDispose {
            onBatchModeChanged(false)
            onBottomBarVisibilityChanged(true)
            ForegroundNotificationPresentationState.clearEvent()
        }
    }
    BackHandler(enabled = isSelectionMode) { exitSelectionMode() }

    val suppressForegroundNotificationAtTop = selectedEvent == null

    LaunchedEffect(suppressForegroundNotificationAtTop) {
        if (!suppressForegroundNotificationAtTop) {
            ForegroundNotificationPresentationState.reportEvent(
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
                ForegroundNotificationPresentationState.reportEvent(
                    isAtTop = isAtTop,
                    suppressionEligible = suppressForegroundNotificationAtTop,
                )
            }
    }

    LaunchedEffect(scrollToTopToken) {
        if (scrollToTopToken == 0L) return@LaunchedEffect
        listState.animateScrollToItem(0)
    }

    val filteredEvents = remember(allEvents, searchQuery, channelFilter, showOnlyOpen) {
        val query = searchQuery.trim().lowercase()
        allEvents.filter { event ->
            (channelFilter == null || event.channelId == channelFilter) &&
            (!showOnlyOpen || event.state == EventLifecycleState.Ongoing) &&
            (query.isEmpty() || event.title.lowercase().contains(query) || event.summary?.lowercase()?.contains(query) == true)
        }
    }

    LaunchedEffect(listState, filteredEvents.size, hasMoreEvents) {
        snapshotFlow {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex to totalItems
        }
            .distinctUntilChanged()
            .collect { (lastVisibleIndex, totalItems) ->
                if (!hasMoreEvents || isLoadingMoreEvents || totalItems <= 0) return@collect
                if (lastVisibleIndex >= totalItems - 7) {
                    loadMoreEventsIfNeeded()
                }
            }
    }

    val channelOptions = remember(allEvents) { 
        allEvents.mapNotNull { it.channelId?.trim()?.takeIf { v -> v.isNotEmpty() } }.distinct().sorted() 
    }

    LaunchedEffect(openEventId, allEvents, hasMoreEvents, isLoadingMoreEvents) {
        val target = openEventId?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val matched = allEvents.firstOrNull { it.eventId == target }
        if (matched != null) {
            selectedEvent = matched
            onEventDetailOpened(matched.eventId)
            onOpenEventHandled()
            return@LaunchedEffect
        }
        if (hasMoreEvents && !isLoadingMoreEvents) {
            loadMoreEventsIfNeeded()
        }
    }

    LaunchedEffect(allEvents, selectedEvent?.eventId) {
        val selectedId = selectedEvent?.eventId ?: return@LaunchedEffect
        val latest = allEvents.firstOrNull { it.eventId == selectedId }
        if (latest == null) {
            selectedEvent = null
            onEventDetailClosed()
        } else if (latest != selectedEvent) {
            selectedEvent = latest
        }
    }

    if (selectedEvent != null && !isSelectionMode) {
        PushGoModalBottomSheet(
            onDismissRequest = { selectedEvent = null; onEventDetailClosed() },
        ) {
            EventDetailSheet(
                event = selectedEvent!!,
                bottomGestureInset = bottomGestureInset,
                onCloseEvent = { pendingCloseEvent = selectedEvent },
                onDeleteEvent = { pendingDeleteEventId = selectedEvent?.eventId },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = { refreshProviderIngressFromPullDown() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
                    .nestedScroll(bottomBarNestedScrollConnection)
                    .onGloballyPositioned { listTopInWindow = it.positionInWindow().y },
                state = listState,
                verticalArrangement = Arrangement.Top,
                contentPadding = PaddingValues(bottom = bottomGestureInset + 24.dp),
            ) {
            item {
                Column(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), contentAlignment = Alignment.Center) {
                        if (isSelectionMode) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(text = stringResource(R.string.label_selected_count, selectedEventIds.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { showBatchDeleteConfirmation = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                                TextButton(onClick = { exitSelectionMode() }) { Text(stringResource(R.string.label_cancel)) }
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding), verticalAlignment = Alignment.CenterVertically) {
                                PushGoSearchBar(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholderText = stringResource(R.string.label_search_events),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box {
                                        var menuExpanded by remember { mutableStateOf(false) }
                                        IconButton(onClick = { menuExpanded = true }) {
                                            val active = channelFilter != null || showOnlyOpen
                                            Icon(
                                                imageVector = if (active) Icons.Filled.FilledFilterList else Icons.Outlined.OutlinedFilterList,
                                                contentDescription = null,
                                                tint = if (active) uiColors.accentPrimary else uiColors.iconMuted
                                            )
                                        }
                                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.filter_open_events)) },
                                                onClick = { showOnlyOpen = !showOnlyOpen; menuExpanded = false },
                                                trailingIcon = { if (showOnlyOpen) Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp)) }
                                            )
                                            if (channelOptions.isNotEmpty()) {
                                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                                channelOptions.forEach { channel ->
                                                    DropdownMenuItem(
                                                        text = { Text(channel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                        onClick = { channelFilter = if (channelFilter == channel) null else channel; menuExpanded = false },
                                                        trailingIcon = { if (channelFilter == channel) Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp)) }
                                                    )
                                                }
                                            } else {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.label_channel_all)) },
                                                    onClick = { menuExpanded = false },
                                                    enabled = false
                                                )
                                            }
                                        }
                                    }
                                    IconButton(onClick = { isSelectionMode = true; selectedEventIds = emptySet() }) { Icon(Icons.Outlined.Checklist, stringResource(R.string.action_batch_select), tint = uiColors.iconMuted) }
                                }
                            }
                        }
                    }
                    Text(text = stringResource(R.string.label_send_type_event), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp), color = uiColors.textPrimary, modifier = Modifier.padding(start = ScreenHorizontalPadding, top = 8.dp, bottom = 12.dp).semantics { heading() })
                }
            }
            if (filteredEvents.isEmpty()) {
                item {
                    AppEmptyState(
                        icon = if (searchQuery.isNotEmpty() || channelFilter != null || showOnlyOpen) Icons.Default.Search else Icons.AutoMirrored.Outlined.EventNote,
                        title = if (searchQuery.isNotEmpty() || channelFilter != null || showOnlyOpen) stringResource(R.string.label_no_search_results) else stringResource(R.string.label_no_events_title),
                        description = if (searchQuery.isNotEmpty() || channelFilter != null || showOnlyOpen) stringResource(R.string.message_list_empty_hint) else stringResource(R.string.label_no_events_hint),
                    )
                }
            } else {
                itemsIndexed(filteredEvents, key = { _, item -> item.eventId }) { _, event ->
                    EventListRowItem(
                        modifier = Modifier.animateItem(),
                        event = event,
                        onClick = {
                            if (isSelectionMode) {
                                toggleSelection(event.eventId)
                            } else {
                                selectedEvent = event
                                onEventDetailOpened(event.eventId)
                            }
                        },
                        selectionMode = isSelectionMode,
                        selected = selectedEventIds.contains(event.eventId),
                        onToggleSelection = { toggleSelection(event.eventId) },
                    )
                }
            }
        }
        }
    }

    if (showBatchDeleteConfirmation) {
        val batchDeleteToast = stringResource(R.string.message_deleted_selected_count, selectedEventIds.size)
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirmation = false },
            title = { Text(text = stringResource(R.string.action_delete)) },
            text = { Text(text = stringResource(R.string.confirm_delete_selected_events, selectedEventIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchDeleteConfirmation = false
                        val targets = selectedEventIds.toList()
                        scope.launch {
                            targets.forEach { eventId ->
                                container.entityRepository.deleteEvent(eventId)
                            }
                            showToast(batchDeleteToast)
                            reloadEventsInternal()
                            exitSelectionMode()
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.label_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirmation = false }) {
                    Text(text = stringResource(R.string.label_cancel))
                }
            },
        )
    }

    pendingCloseEvent?.let { targetEvent ->
        AlertDialog(
            onDismissRequest = { pendingCloseEvent = null },
            title = { Text(text = stringResource(R.string.action_close_event)) },
            text = { Text(text = targetEvent.title) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCloseEvent = null
                        scope.launch { closeEvent(targetEvent) }
                    },
                ) {
                    Text(text = stringResource(R.string.label_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloseEvent = null }) {
                    Text(text = stringResource(R.string.label_cancel))
                }
            },
        )
    }

    pendingDeleteEventId?.let { targetEventId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteEventId = null },
            title = { Text(text = stringResource(R.string.action_delete)) },
            text = { Text(text = stringResource(R.string.confirm_delete_selected_events, 1)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteEventId = null
                        scope.launch { deleteEvent(targetEventId) }
                    },
                ) {
                    Text(text = stringResource(R.string.label_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEventId = null }) {
                    Text(text = stringResource(R.string.label_cancel))
                }
            },
        )
    }
}

private data class EventDisplayAttribute(
    val key: String,
    val label: String,
    val value: String,
) {
    val displayLabel: String
        get() = label.trim().ifEmpty { key }
}

@Composable
fun EventListRowItem(
    event: EventCardModel,
    onClick: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    val imageAttachments = event.attachmentUrls.filter(::isImageAttachmentUrl)
    val isClosed = event.state == EventLifecycleState.Closed
    val mutedTextColor = uiColors.textSecondary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(uiColors.fieldContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) uiColors.selectedRowFill else uiColors.surfaceBase)
                .combinedClickable(
                    onClick = {
                        if (selectionMode) {
                            onToggleSelection()
                        } else {
                            onClick()
                        }
                    },
                    onLongClick = {
                        if (!selectionMode) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleSelection()
                        }
                    },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selectionMode) {
                    PushGoSelectionIndicator(selected = selected, onClick = onToggleSelection)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            color = if (isClosed) mutedTextColor else uiColors.textPrimary,
                        )
                        Text(
                            text = formatLocalRelativeTime(context, event.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = uiColors.textSecondary,
                        )
                    }
                    EventStatusBadge(
                        statusText = normalizedEventStatus(event.status) ?: stringResource(R.string.event_status_created_default),
                        state = event.state,
                        severity = event.severity,
                    )
                    if (!event.summary.isNullOrBlank()) {
                        Text(
                            text = event.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isClosed) mutedTextColor else uiColors.textSecondary,
                            maxLines = 3,
                        )
                    }
                    if (!event.message.isNullOrBlank()) {
                        EventInlineAlert(
                            text = event.message.orEmpty(),
                            severity = event.severity,
                            muted = isClosed,
                        )
                    }
                    if (event.tags.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.event_meta_tags_count, event.tags.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = uiColors.textSecondary,
                        )
                    }
                }
            }
            if (imageAttachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    imageAttachments.take(3).forEach { url ->
                        PushGoAsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .height(44.dp)
                                .width(44.dp)
                                .clip(MaterialTheme.shapes.small),
                        )
                    }
                }
            }
        }
    }
    if (showDivider) {
        PushGoDividerSubtle(
            thickness = 0.5.dp,
            color = uiColors.dividerSubtle.copy(alpha = 0.55f),
        )
    }
}

@Composable
fun EventDetailSheet(
    event: EventCardModel,
    bottomGestureInset: Dp,
    onCloseEvent: () -> Unit,
    onDeleteEvent: () -> Unit,
) {
    var showCloseConfirmation by remember { mutableStateOf(false) }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    val uiColors = PushGoThemeExtras.colors
    val imageAttachments = event.attachmentUrls.filter(::isImageAttachmentUrl)
    val timelineDescending = remember(event.timeline) { event.timeline.sortedByDescending { it.happenedAt } }
    val createdAt = remember(event.timeline) { event.timeline.minByOrNull { it.happenedAt }?.happenedAt }
    val isEnded = event.state == EventLifecycleState.Closed
    val attrsEntries = remember(event.attrsJson) { parseEventDisplayAttributes(event.attrsJson) }
    val hasOverviewContent = !event.summary.isNullOrBlank() || !event.message.isNullOrBlank() || event.tags.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomGestureInset + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = uiColors.surfaceBase,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(0.8.dp, uiColors.dividerStrong.copy(alpha = 0.42f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    Row {
                        if (!isEnded) {
                            IconButton(
                                modifier = Modifier.size(32.dp),
                                onClick = { showCloseConfirmation = true },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = stringResource(R.string.action_close_event),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        IconButton(
                            modifier = Modifier.size(32.dp),
                            onClick = onDeleteEvent,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    EventStatusBadge(
                        statusText = normalizedEventStatus(event.status) ?: stringResource(R.string.event_status_created_default),
                        state = event.state,
                        severity = event.severity,
                    )
                    createdAt?.let { created ->
                        Text(
                            text = EventTimeFormatter.format(created),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = uiColors.textSecondary,
                        )
                    }
                }

                if (hasOverviewContent) {
                    HorizontalDivider(
                        color = uiColors.dividerSubtle.copy(alpha = 0.7f),
                        thickness = 0.6.dp,
                    )
                    if (!event.summary.isNullOrBlank()) {
                        Text(
                            text = event.summary,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (!event.message.isNullOrBlank()) {
                        EventInlineAlert(
                            text = event.message.orEmpty(),
                            severity = event.severity,
                        )
                    }
                    if (event.tags.isNotEmpty()) {
                        Text(
                            text = event.tags.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = uiColors.textSecondary,
                        )
                    }
                    event.thingId?.trim()?.takeIf { it.isNotEmpty() }?.let { thingId ->
                        Text(
                            text = "Object $thingId",
                            style = MaterialTheme.typography.labelSmall,
                            color = uiColors.textSecondary,
                        )
                    }
                }

                if (attrsEntries.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.event_detail_attributes),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = uiColors.textSecondary,
                    )
                    EventAttributeRows(
                        entries = attrsEntries,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (imageAttachments.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.event_detail_attachments),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = uiColors.textSecondary,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        imageAttachments.forEach { url ->
                            PushGoAsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(92.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { previewImageUrl = url },
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.event_detail_history_timeline),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        if (timelineDescending.isEmpty()) {
            AppEmptyState(
                icon = Icons.Outlined.Info,
                title = "No history records.",
                description = "Updates will appear here when this event receives new actions.",
                topPadding = 8.dp,
                horizontalPadding = 0.dp,
                iconSize = 40.dp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                timelineDescending.forEach { row ->
                    val pointAttrs = parseEventDisplayAttributes(row.attrsJson)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = uiColors.fieldContainer.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.8.dp, uiColors.dividerSubtle.copy(alpha = 0.85f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = EventTimeFormatter.format(row.happenedAt),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                    color = uiColors.textSecondary,
                                )
                                normalizedEventStatus(row.status)?.let { rowStatus ->
                                    EventStatusBadge(
                                        statusText = rowStatus,
                                        state = row.state,
                                        severity = row.severity,
                                    )
                                }
                            }
                            if (!row.displayTitle.isNullOrBlank()) {
                                Text(
                                    text = row.displayTitle,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                )
                            }
                            if (!row.displaySummary.isNullOrBlank()) {
                                Text(
                                    text = row.displaySummary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = uiColors.textSecondary,
                                )
                            }
                            if (!row.message.isNullOrBlank()) {
                                EventInlineAlert(text = row.message.orEmpty(), severity = row.severity)
                            }
                            if (pointAttrs.isNotEmpty()) {
                                EventAttributeRows(
                                    entries = pointAttrs,
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCloseConfirmation) {
        AlertDialog(
            onDismissRequest = { showCloseConfirmation = false },
            title = { Text(text = stringResource(R.string.action_close_event)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCloseConfirmation = false
                        onCloseEvent()
                    },
                ) {
                    Text(text = stringResource(R.string.action_close_event))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirmation = false }) {
                    Text(text = stringResource(R.string.label_cancel))
                }
            },
        )
    }

    ZoomableImagePreviewDialog(
        model = previewImageUrl,
        onDismiss = { previewImageUrl = null },
    )
}

@Composable
private fun EventStatusBadge(statusText: String, state: EventLifecycleState, severity: EventSeverity?) {
    val palette = eventSeverityPaletteInternal(severity) ?: eventStatePaletteInternal(state)
    Surface(color = palette.background, shape = MaterialTheme.shapes.small) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PushGoStatusDot(color = palette.foreground, modifier = Modifier.size(6.dp))
            Text(text = statusText, style = MaterialTheme.typography.labelSmall, color = palette.foreground)
        }
    }
}

@Composable
private fun EventInlineAlert(
    text: String,
    severity: EventSeverity?,
    muted: Boolean = false,
) {
    val baseTint = eventSeverityTint(severity) ?: MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint = if (muted) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.56f)
    } else {
        baseTint.copy(alpha = 0.72f)
    }
    val textColor = if (muted) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = eventSeverityIcon(severity),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .height(13.dp)
                .width(13.dp)
                .padding(top = 1.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            modifier = Modifier.weight(1f),
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun EventAttributeRows(
    entries: List<EventDisplayAttribute>,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val uiColors = PushGoThemeExtras.colors
    Surface(
        modifier = modifier,
        color = uiColors.fieldContainer.copy(alpha = 0.95f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.7.dp, uiColors.dividerSubtle.copy(alpha = 0.9f)),
    ) {
        Column {
            entries.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = entry.displayLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = color,
                        modifier = Modifier.widthIn(min = 72.dp, max = 120.dp),
                    )
                    Text(
                        text = entry.value,
                        style = textStyle,
                        color = color,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (index < entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        color = uiColors.dividerSubtle.copy(alpha = 0.7f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}

private fun formatLocalRelativeTime(context: Context, instant: Instant): String {
    return DateUtils.getRelativeTimeSpanString(
        instant.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_ALL,
    ).toString()
}

private fun buildEventCardsInternal(messages: List<PushMessage>): List<EventCardModel> {
    val grouped = messages
        .asSequence()
        .filter { it.entityType == "event" || !it.eventId.isNullOrBlank() }
        .mapNotNull { message ->
            val eventId = message.eventId?.trim().orEmpty()
            if (eventId.isEmpty()) return@mapNotNull null
            val payload = runCatching { org.json.JSONObject(message.rawPayloadJson) }.getOrNull()
            val eventTime = payload
                ?.optLong("event_time")
                ?.takeIf { it > 0 }
                ?.let(Instant::ofEpochSecond)
                ?: message.receivedAt
            val profileRaw = payload?.optString("event_profile_json")
            val profile = io.ethan.pushgo.data.parseEventProfile(profileRaw)
            val attachmentUrls = linkedSetOf<String>().apply {
                addAll(parseImageUrls(profileRaw))
                addAll(message.imageUrls)
            }.toList()
            val imageUrl = profile?.imageUrl ?: message.imageUrl
            val profileTitle = profile?.title?.trim()?.ifEmpty { null }
            val profileDescription = profile?.description?.trim()?.ifEmpty { null }
            val operationTitle = payload
                ?.optString("event_title", "")
                ?.trim()
                ?.ifEmpty { null }
            val operationDescription = payload
                ?.optString("event_description", "")
                ?.trim()
                ?.ifEmpty { null }
            val payloadStatus = payload
                ?.optString("status", "")
                ?.trim()
                ?.ifEmpty { null }
            val payloadMessage = payload
                ?.optString("message", "")
                ?.trim()
                ?.ifEmpty { null }
            val payloadSeverity = payload
                ?.optString("severity", "")
                ?.trim()
                ?.ifEmpty { null }
            eventId to EventTimelineRow(
                messageId = message.id,
                title = profileTitle ?: message.title.ifBlank { eventId },
                displayTitle = operationTitle,
                summary = profileDescription ?: message.bodyPreview,
                displaySummary = operationDescription,
                status = profile?.status ?: payloadStatus ?: message.eventState,
                message = profile?.message ?: payloadMessage ?: message.body.takeIf { it.isNotBlank() },
                severity = EventSeverity.fromRaw(profile?.severity ?: payloadSeverity ?: message.severity?.name),
                tags = if (profile?.tags?.isNotEmpty() == true) profile.tags else message.tags,
                state = EventLifecycleState.fromRaw(message.eventState),
                thingId = message.thingId,
                channelId = message.channel?.trim()?.ifEmpty { null },
                imageUrl = imageUrl,
                attachmentUrls = attachmentUrls,
                attrsJson = payload
                    ?.optString("event_attrs_json")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                happenedAt = eventTime,
            )
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })

    return grouped.entries
        .map { (eventId, timeline) ->
            val latest = timeline.maxByOrNull { it.happenedAt }!!
            val orderedTimeline = timeline.sortedByDescending { it.happenedAt }
            val attachmentUrls = orderedTimeline
                .flatMap { it.attachmentUrls }
                .distinct()
            EventCardModel(
                eventId = eventId,
                title = latest.title,
                summary = latest.summary,
                status = latest.status,
                message = latest.message,
                imageUrl = latest.imageUrl,
                severity = latest.severity,
                tags = latest.tags,
                state = latest.state,
                thingId = latest.thingId,
                channelId = latest.channelId,
                attachmentUrls = attachmentUrls,
                attrsJson = mergedEventAttrsJson(orderedTimeline),
                updatedAt = latest.happenedAt,
                timeline = orderedTimeline,
            )
        }
        .sortedWith(compareBy<EventCardModel> { stateSortPriority(it.state) }.thenByDescending { it.updatedAt })
}

private fun mergeEventCardsInternal(existing: List<EventCardModel>, incoming: List<EventCardModel>): List<EventCardModel> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming
    val byEventId = LinkedHashMap<String, EventCardModel>(existing.size + incoming.size)
    for (event in existing) {
        byEventId[event.eventId] = event
    }
    for (event in incoming) {
        val current = byEventId[event.eventId]
        byEventId[event.eventId] = if (current == null) event else mergeEventCardInternal(current, event)
    }
    return byEventId.values.sortedWith(compareBy<EventCardModel> { stateSortPriority(it.state) }.thenByDescending { it.updatedAt })
}

private fun mergeEventCardInternal(current: EventCardModel, incoming: EventCardModel): EventCardModel {
    val mergedTimeline = (current.timeline + incoming.timeline)
        .distinctBy { it.messageId }
        .sortedByDescending { it.happenedAt }
    if (mergedTimeline.isEmpty()) {
        return if (incoming.updatedAt >= current.updatedAt) incoming else current
    }
    val latest = mergedTimeline.first()
    val attachmentUrls = mergedTimeline
        .flatMap { it.attachmentUrls }
        .distinct()
    return EventCardModel(
        eventId = current.eventId,
        title = latest.title,
        summary = latest.summary,
        status = latest.status,
        message = latest.message,
        imageUrl = latest.imageUrl,
        severity = latest.severity,
        tags = latest.tags,
        state = latest.state,
        thingId = latest.thingId,
        channelId = latest.channelId,
        attachmentUrls = attachmentUrls,
        attrsJson = mergedEventAttrsJson(mergedTimeline),
        updatedAt = latest.happenedAt,
        timeline = mergedTimeline,
    )
}

@Composable
private fun eventStatePaletteInternal(state: EventLifecycleState): PushGoStateColors {
    val colors = PushGoThemeExtras.colors
    return if (state == EventLifecycleState.Ongoing) colors.stateInfo else colors.stateNeutral
}

@Composable
private fun eventSeverityPaletteInternal(severity: EventSeverity?): PushGoStateColors? {
    val colors = PushGoThemeExtras.colors
    return when (severity) {
        EventSeverity.Critical -> colors.stateDanger
        EventSeverity.High -> colors.stateWarning
        EventSeverity.Normal, EventSeverity.Low -> colors.stateInfo
        null -> null
    }
}

private fun stateSortPriority(state: EventLifecycleState): Int {
    return when (state) {
        EventLifecycleState.Ongoing -> 0
        EventLifecycleState.Closed -> 1
        EventLifecycleState.Unknown -> 2
    }
}

private fun eventSeverityTint(severity: EventSeverity?): Color? {
    return when (severity) {
        EventSeverity.Critical -> Color(0xFFB91C1C)
        EventSeverity.High -> Color(0xFFD97706)
        EventSeverity.Normal -> Color(0xFF2563EB)
        EventSeverity.Low -> Color(0xFF6366F1)
        null -> null
    }
}

private fun eventSeverityIcon(severity: EventSeverity?): androidx.compose.ui.graphics.vector.ImageVector {
    return when (severity) {
        EventSeverity.Critical -> Icons.Outlined.WarningAmber
        EventSeverity.High -> Icons.Outlined.NotificationsActive
        EventSeverity.Normal -> Icons.Outlined.Info
        EventSeverity.Low -> Icons.Outlined.ArrowDownward
        null -> Icons.Outlined.Info
    }
}

private fun isImageAttachmentUrl(raw: String): Boolean {
    val path = raw.substringBefore("?").lowercase()
    if (
        path.endsWith(".png") ||
        path.endsWith(".jpg") ||
        path.endsWith(".jpeg") ||
        path.endsWith(".gif") ||
        path.endsWith(".webp") ||
        path.endsWith(".bmp") ||
        path.endsWith(".heic") ||
        path.endsWith(".heif")
    ) {
        return true
    }
    val tail = path.substringAfterLast("/")
    return !tail.contains(".")
}

private fun parseImageUrls(profileRaw: String?): List<String> {
    val text = profileRaw?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
    val profile = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return emptyList()
    val urls = linkedSetOf<String>()
    val images = profile.optJSONArray("images") ?: return emptyList()
    for (index in 0 until images.length()) {
        val url = images.optString(index, "").trim()
        if (url.isNotEmpty()) {
            urls += url
        }
    }
    return urls.toList()
}

private fun mergedEventAttrsJson(timeline: List<EventTimelineRow>): String? {
    val merged = org.json.JSONObject()
    timeline
        .sortedBy { it.happenedAt }
        .forEach { row ->
            val patch = parseJsonObjectOrNull(row.attrsJson ?: return@forEach) ?: return@forEach
            val iterator = patch.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                val value = patch.opt(key)
                if (value == org.json.JSONObject.NULL) {
                    merged.remove(key)
                } else {
                    merged.put(key, value)
                }
            }
        }
    return if (merged.length() == 0) null else merged.toString(2)
}

private fun parseJsonObjectOrNull(raw: String): org.json.JSONObject? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return runCatching { org.json.JSONObject(text) }.getOrNull()
}

private fun parseEventDisplayAttributes(raw: String?): List<EventDisplayAttribute> {
    val text = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
    val parsedObject = runCatching { org.json.JSONObject(text) }.getOrNull()
    if (parsedObject != null) {
        val keys = parsedObject.keys().asSequence().toList().sorted()
        return keys.map { key ->
            val rawValue = parsedObject.opt(key)
            toEventDisplayAttribute(key = key, rawValue = rawValue)
        }
    }

    val parsedArray = runCatching { org.json.JSONArray(text) }.getOrNull() ?: return emptyList()
    val entries = mutableListOf<EventDisplayAttribute>()
    for (index in 0 until parsedArray.length()) {
        val item = parsedArray.opt(index)
        if (item is org.json.JSONObject) {
            val fallbackKey = item.optString("key").trim().ifEmpty { "item_${index + 1}" }
            val label = item.optString("label").trim().ifEmpty { fallbackKey }
            val value = if (item.has("value")) {
                jsonValueToText(item.opt("value"))
            } else {
                item.toString()
            }
            entries += EventDisplayAttribute(key = fallbackKey, label = label, value = value)
        } else {
            val fallbackKey = "item_${index + 1}"
            entries += EventDisplayAttribute(
                key = fallbackKey,
                label = fallbackKey,
                value = jsonValueToText(item),
            )
        }
    }
    return entries
}

private fun toEventDisplayAttribute(key: String, rawValue: Any?): EventDisplayAttribute {
    if (rawValue is org.json.JSONObject && rawValue.has("value")) {
        val label = rawValue.optString("label").trim().ifEmpty { key }
        return EventDisplayAttribute(
            key = key,
            label = label,
            value = jsonValueToText(rawValue.opt("value")),
        )
    }
    return EventDisplayAttribute(
        key = key,
        label = key,
        value = jsonValueToText(rawValue),
    )
}

private fun jsonValueToText(value: Any?): String {
    return when (value) {
        null, org.json.JSONObject.NULL -> "null"
        is String -> value
        is Number, is Boolean -> value.toString()
        is org.json.JSONObject -> value.toString()
        is org.json.JSONArray -> value.toString()
        else -> value.toString()
    }
}

private fun normalizedEventStatus(raw: String?): String? = raw?.trim()?.ifEmpty { null }
