package io.ethan.pushgo.ui.screens

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.EntityProjectionCursor
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.ForegroundNotificationPresentationState
import io.ethan.pushgo.notifications.ForegroundNotificationTopMetrics
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.rememberBottomBarNestedScrollConnection
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor
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
    var searchQuery by remember { mutableStateOf("") }
    var channelFilter by remember { mutableStateOf<String?>(null) }
    var showOnlyOpen by remember { mutableStateOf(false) }
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

    fun reloadEvents() {
        scope.launch {
            if (isLoadingMoreEvents) return@launch
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
    }

    fun loadMoreEventsIfNeeded() {
        if (isLoadingMoreEvents || !hasMoreEvents) return
        scope.launch {
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

    val channelOptions = remember(allEvents) { 
        allEvents.mapNotNull { it.channelId?.trim()?.takeIf { v -> v.isNotEmpty() } }.distinct().sorted() 
    }

    if (selectedEvent != null && !isSelectionMode) {
        PushGoModalBottomSheet(
            onDismissRequest = { selectedEvent = null; onEventDetailClosed() },
        ) {
            Text(
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = bottomGestureInset + 24.dp),
                text = selectedEvent!!.title,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
                .nestedScroll(bottomBarNestedScrollConnection)
                .onGloballyPositioned { listTopInWindow = it.positionInWindow().y },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                itemsIndexed(filteredEvents, key = { _, item -> item.eventId }) { index, event ->
                    if (index >= filteredEvents.lastIndex - 6) { LaunchedEffect(event.eventId) { loadMoreEventsIfNeeded() } }
                    EventListRowItem(modifier = Modifier.animateItem(), event = event, onClick = { if (isSelectionMode) toggleSelection(event.eventId) else { selectedEvent = event; onEventDetailOpened(event.eventId) } }, selectionMode = isSelectionMode, selected = selectedEventIds.contains(event.eventId), onToggleSelection = { toggleSelection(event.eventId) })
                }
            }
        }
    }
}

@Composable
fun EventListRowItem(event: EventCardModel, onClick: () -> Unit, selectionMode: Boolean, selected: Boolean, onToggleSelection: () -> Unit, modifier: Modifier = Modifier, showDivider: Boolean = true) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    val isClosed = event.state == EventLifecycleState.Closed
    Column(modifier = modifier.fillMaxWidth().background(if (selected) uiColors.selectedRowFill else uiColors.surfaceBase).combinedClickable(onClick = onClick, onLongClick = { if (!selectionMode) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggleSelection() } }).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selectionMode) { PushGoSelectionIndicator(selected = selected, onClick = onToggleSelection) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), color = if (isClosed) uiColors.iconMuted else uiColors.textPrimary)
                    Text(text = formatLocalRelativeTime(context, event.updatedAt), style = MaterialTheme.typography.labelSmall, color = uiColors.textSecondary)
                }
                EventStatusBadge(normalizedEventStatus(event.status) ?: stringResource(R.string.event_status_created_default), event.state, event.severity)
            }
        }
    }
    if (showDivider) PushGoDividerStrong()
}

@Composable
private fun EventStatusBadge(statusText: String, state: EventLifecycleState, severity: EventSeverity?) {
    val palette = eventSeverityPaletteInternal(severity) ?: eventStatePaletteInternal(state)
    Surface(color = palette.background, shape = MaterialTheme.shapes.small) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PushGoStatusDot(color = palette.foreground, modifier = Modifier.size(6.dp))
            Text(text = statusText, style = MaterialTheme.typography.labelSmall, color = palette.foreground)
        }
    }
}

private fun formatLocalRelativeTime(context: Context, instant: Instant): String {
    return DateUtils.getRelativeTimeSpanString(instant.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL).toString()
}

private fun buildEventCardsInternal(messages: List<PushMessage>): List<EventCardModel> {
    return messages.filter { it.entityType == "event" }.map { m ->
        EventCardModel(
            eventId = m.eventId ?: m.id,
            title = m.title,
            summary = m.bodyPreview,
            status = m.eventState,
            message = m.body,
            imageUrl = m.imageUrl,
            severity = m.severity?.let { EventSeverity.fromRaw(it.name) },
            tags = m.tags,
            state = EventLifecycleState.fromRaw(m.eventState),
            thingId = m.thingId,
            channelId = m.channel,
            attachmentUrls = m.imageUrls,
            attrsJson = m.rawPayloadJson,
            updatedAt = m.receivedAt,
            timeline = emptyList()
        )
    }
}

private fun mergeEventCardsInternal(existing: List<EventCardModel>, incoming: List<EventCardModel>): List<EventCardModel> {
    val map = existing.associateBy { it.eventId }.toMutableMap()
    incoming.forEach { map[it.eventId] = it }
    return map.values.sortedByDescending { it.updatedAt }
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
private fun normalizedEventStatus(raw: String?): String? = raw?.trim()?.ifEmpty { null }
