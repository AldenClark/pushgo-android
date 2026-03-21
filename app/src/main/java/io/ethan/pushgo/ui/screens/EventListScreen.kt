package io.ethan.pushgo.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList as FilledFilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList as OutlinedFilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.ethan.pushgo.R
import io.ethan.pushgo.data.OpaqueId
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.EntityProjectionCursor
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.parseEventProfile
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor
import org.json.JSONArray
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val happenedAt: Instant,
)

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

private data class EventDisplayAttribute(
    val key: String,
    val label: String,
    val value: String,
) {
    val displayLabel: String
        get() = label.trim().ifEmpty { key }
}

private val EventTimeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
private val ScreenHorizontalPadding = 12.dp
private const val EventProjectionPageSize = 240
private const val EventExportPageSize = 600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventListScreen(
    container: AppContainer,
    refreshToken: Long,
    openEventId: String?,
    onOpenEventHandled: () -> Unit,
    onEventDetailOpened: (String) -> Unit,
    onEventDetailClosed: () -> Unit,
) {
    var allEvents by remember { mutableStateOf<List<EventCardModel>>(emptyList()) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var eventCursor by remember { mutableStateOf<EntityProjectionCursor?>(null) }
    var hasMoreEvents by remember { mutableStateOf(true) }
    var isLoadingMoreEvents by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<EventCardModel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var channelFilter by remember { mutableStateOf<String?>(null) }
    val closeEventSuccessMessage = stringResource(R.string.message_event_closed)
    val closeEventFailedMessage = stringResource(R.string.error_event_close_failed)
    val scope = rememberCoroutineScope()

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
                allEvents = withContext(Dispatchers.Default) {
                    buildEventCards(firstPage)
                }
                val last = firstPage.lastOrNull()
                eventCursor = last?.let {
                    EntityProjectionCursor(
                        receivedAt = it.receivedAt.toEpochMilli(),
                        id = it.id,
                    )
                }
                hasMoreEvents = firstPage.size >= EventProjectionPageSize
            } finally {
                isLoadingMoreEvents = false
                hasLoadedOnce = true
            }
        }
    }

    fun loadMoreEventsIfNeeded() {
        if (isLoadingMoreEvents || !hasMoreEvents) return
        scope.launch {
            isLoadingMoreEvents = true
            try {
                val page = container.entityRepository.getEventProjectionMessagesPage(
                    before = eventCursor,
                    limit = EventProjectionPageSize,
                )
                if (page.isNotEmpty()) {
                    val existing = allEvents
                    val merged = withContext(Dispatchers.Default) {
                        mergeEventCards(existing, buildEventCards(page))
                    }
                    allEvents = merged
                    val last = page.last()
                    eventCursor = EntityProjectionCursor(
                        receivedAt = last.receivedAt.toEpochMilli(),
                        id = last.id,
                    )
                }
                hasMoreEvents = page.size >= EventProjectionPageSize
            } finally {
                isLoadingMoreEvents = false
            }
        }
    }

    fun showToast(message: String) {
        Toast.makeText(container.appContext, message, Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(refreshToken) {
        reloadEvents()
    }

    val filteredEvents = remember(allEvents, searchQuery, channelFilter) {
        val query = searchQuery.trim().lowercase()
        allEvents
            .asSequence()
            .filter { event ->
                channelFilter == null || event.channelId == channelFilter
            }
            .filter { event ->
                if (query.isEmpty()) {
                    true
                } else {
                    event.title.lowercase().contains(query) ||
                        event.summary?.lowercase()?.contains(query) == true ||
                        event.severity?.wireValue?.contains(query) == true ||
                        event.status?.lowercase()?.contains(query) == true ||
                        event.message?.lowercase()?.contains(query) == true ||
                        event.tags.any { it.lowercase().contains(query) } ||
                        event.eventId.lowercase().contains(query) ||
                        event.thingId?.lowercase()?.contains(query) == true ||
                        event.state.token.contains(query)
                }
            }
            .toList()
    }

    LaunchedEffect(
        filteredEvents.size,
        searchQuery,
        channelFilter,
        hasMoreEvents,
        isLoadingMoreEvents,
    ) {
        val query = searchQuery.trim()
        val hasActiveFilter = query.isNotEmpty() || channelFilter != null
        if (hasActiveFilter && filteredEvents.isEmpty() && hasMoreEvents && !isLoadingMoreEvents) {
            loadMoreEventsIfNeeded()
        }
    }

    val channelOptions = remember(allEvents) {
        allEvents
            .mapNotNull { it.channelId?.trim()?.takeIf { value -> value.isNotEmpty() } }
            .distinct()
            .sorted()
    }
    LaunchedEffect(openEventId, allEvents) {
        val target = openEventId?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val matched = allEvents.firstOrNull { it.eventId == target } ?: return@LaunchedEffect
        selectedEvent = matched
        onEventDetailOpened(matched.eventId)
        onOpenEventHandled()
    }

    LaunchedEffect(allEvents, selectedEvent?.eventId) {
        val selectedId = selectedEvent?.eventId ?: return@LaunchedEffect
        val latest = allEvents.firstOrNull { it.eventId == selectedId } ?: return@LaunchedEffect
        if (latest != selectedEvent) {
            selectedEvent = latest
        }
    }

    if (selectedEvent != null) {
        val useFullHeightSheet = selectedEvent!!.timeline.size >= 8
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = useFullHeightSheet)
        ModalBottomSheet(
            modifier = if (useFullHeightSheet) Modifier.fillMaxHeight(0.98f) else Modifier,
            sheetState = sheetState,
            containerColor = PushGoSheetContainerColor(),
            tonalElevation = 0.dp,
            onDismissRequest = {
            selectedEvent = null
            onEventDetailClosed()
        }) {
            EventDetailSheet(
                event = selectedEvent!!,
                onCloseEvent = {
                    val event = selectedEvent ?: return@EventDetailSheet
                    scope.launch {
                        try {
                            val channelId = event.channelId.orEmpty().trim()
                            if (channelId.isEmpty()) {
                                showToast("$closeEventFailedMessage: missing channel_id")
                                return@launch
                            }
                            container.channelRepository.closeEvent(
                                rawEventId = event.eventId,
                                rawThingId = event.thingId,
                                rawChannelId = channelId,
                                rawStatus = container.appContext.getString(R.string.event_status_closed_default),
                                rawMessage = container.appContext.getString(R.string.event_message_closed_default),
                                rawSeverity = event.severity?.wireValue,
                            )
                            showToast(closeEventSuccessMessage)
                            selectedEvent = null
                            onEventDetailClosed()
                            reloadEvents()
                        } catch (error: Exception) {
                            showToast("$closeEventFailedMessage: ${error.message.orEmpty()}")
                        }
                    }
                },
                onDelete = {
                    val eventId = selectedEvent?.eventId ?: return@EventDetailSheet
                    scope.launch {
                        container.entityRepository.deleteEvent(eventId)
                        selectedEvent = null
                        onEventDetailClosed()
                        reloadEvents()
                    }
                },
            )
        }
    }

    if (!hasLoadedOnce && allEvents.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .height(22.dp)
                    .width(22.dp),
                strokeWidth = 2.dp,
            )
        }
        return
    }

    if (allEvents.isEmpty()) {
        AppEmptyState(
            icon = Icons.Outlined.NotificationsActive,
            title = stringResource(R.string.label_no_events_title),
            description = stringResource(R.string.label_no_events_hint),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Spacer(modifier = Modifier.width(16.dp))
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    stringResource(R.string.label_search_events),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            singleLine = true,
                        )

                        var channelMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { channelMenuExpanded = true }) {
                                Icon(
                                    imageVector = if (channelFilter == null) Icons.Outlined.OutlinedFilterList else Icons.Filled.FilledFilterList,
                                    contentDescription = stringResource(R.string.label_channel_id),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                            DropdownMenu(
                                expanded = channelMenuExpanded,
                                onDismissRequest = { channelMenuExpanded = false }
                            ) {
                                ChannelFilterMenuOption(
                                    title = stringResource(R.string.label_channel_all),
                                    isSelected = channelFilter == null,
                                ) {
                                        channelFilter = null
                                        channelMenuExpanded = false
                                }
                                channelOptions.forEach { channelId ->
                                    ChannelFilterMenuOption(
                                        title = channelId,
                                        isSelected = channelFilter == channelId,
                                    ) {
                                            channelFilter = channelId
                                            channelMenuExpanded = false
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (filteredEvents.isEmpty()) {
            item {
                AppEmptyState(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.label_no_search_results),
                    description = stringResource(R.string.label_no_events_hint),
                )
            }
        } else {
            itemsIndexed(filteredEvents, key = { _, item -> item.eventId }) { index, event ->
                if (index >= filteredEvents.lastIndex - 6) {
                    LaunchedEffect(event.eventId, hasMoreEvents, isLoadingMoreEvents) {
                        loadMoreEventsIfNeeded()
                    }
                }
                EventListRowItem(
                    event = event,
                    onClick = { selectedEvent = event },
                )
            }
            if (isLoadingMoreEvents) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .height(20.dp)
                                .width(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
fun EventListRowItem(
    event: EventCardModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    val imageAttachments = event.attachmentUrls.filter(::isImageAttachmentUrl)
    val isClosed = event.state == EventLifecycleState.Closed
    val mutedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
                        color = if (isClosed) mutedTextColor else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = EventTimeFormatter.format(event.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EventStatusBadge(
                    statusText = normalizedEventStatus(event.status)
                        ?: stringResource(R.string.event_status_created_default),
                    state = event.state,
                    severity = event.severity,
                )
                if (!event.summary.isNullOrBlank()) {
                    Text(
                        text = event.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isClosed) mutedTextColor else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (imageAttachments.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                imageAttachments.take(3).forEach { url ->
                    AsyncImage(
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
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
fun EventDetailSheet(
    event: EventCardModel,
    onCloseEvent: () -> Unit,
    onDelete: () -> Unit,
) {
    var showCloseConfirmation by remember { mutableStateOf(false) }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    val imageAttachments = event.attachmentUrls.filter(::isImageAttachmentUrl)
    val linkAttachments = event.attachmentUrls.filterNot(::isImageAttachmentUrl)
    val timelineDescending = remember(event.timeline) { event.timeline.sortedByDescending { it.happenedAt } }
    val createdAt = remember(event.timeline) { event.timeline.minByOrNull { it.happenedAt }?.happenedAt }
    val latestAt = timelineDescending.firstOrNull()?.happenedAt ?: event.updatedAt
    val isEnded = event.state == EventLifecycleState.Closed
    val attrsEntries = remember(event.attrsJson) { parseEventDisplayAttributes(event.attrsJson) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            if (event.state != EventLifecycleState.Closed) {
                IconButton(onClick = { showCloseConfirmation = true }) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = stringResource(R.string.action_close_event),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            EventStatusBadge(
                statusText = normalizedEventStatus(event.status)
                    ?: stringResource(R.string.event_status_created_default),
                state = event.state,
                severity = event.severity,
            )
        }
        if (!event.summary.isNullOrBlank()) {
            Text(
                text = event.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!event.message.isNullOrBlank()) {
            EventInlineAlert(
                text = event.message.orEmpty(),
                severity = event.severity,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            createdAt?.let { created ->
                EventTimeMetaChip(
                    icon = Icons.Outlined.Info,
                    text = EventTimeFormatter.format(created),
                )
            }
            EventTimeMetaChip(
                icon = if (isEnded) Icons.Outlined.CheckCircle else Icons.Outlined.NotificationsActive,
                text = EventTimeFormatter.format(latestAt),
            )
        }
        if (event.tags.isNotEmpty()) {
            Text(
                text = stringResource(R.string.event_meta_tags_count, event.tags.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (attrsEntries.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.medium
            ) {
                EventAttributeRows(
                    entries = attrsEntries,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        if (imageAttachments.isNotEmpty() || linkAttachments.isNotEmpty()) {
            if (imageAttachments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    imageAttachments.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .height(72.dp)
                                        .width(72.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { previewImageUrl = url },
                                )
                            }
                        }
                    }
                }
            }
            linkAttachments.forEach { url ->
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (timelineDescending.isEmpty()) {
            AppEmptyState(
                icon = Icons.Outlined.Info,
                title = "No history records.",
                description = "Updates will appear here when this event receives new actions.",
                topPadding = 12.dp,
                horizontalPadding = 0.dp,
                iconSize = 44.dp,
            )
        } else {
            timelineDescending.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .background(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = MaterialTheme.shapes.small
                            )
                            .height(8.dp)
                            .fillMaxWidth(0.02f)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = EventTimeFormatter.format(row.happenedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val rowStatus = normalizedEventStatus(row.status)
                            if (rowStatus != null) {
                                EventStatusBadge(
                                    statusText = rowStatus,
                                    state = row.state,
                                    severity = row.severity,
                                )
                            }
                        }
                        if (!row.displayTitle.isNullOrBlank()) {
                            Text(text = row.displayTitle, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (!row.displaySummary.isNullOrBlank()) {
                            Text(
                                text = row.displaySummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!row.message.isNullOrBlank()) {
                            EventInlineAlert(
                                text = row.message.orEmpty(),
                                severity = row.severity,
                            )
                        }
                        if (row.tags.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.event_meta_tags_count, row.tags.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val pointAttrs = parseEventDisplayAttributes(row.attrsJson)
                        if (pointAttrs.isNotEmpty()) {
                            EventAttributeRows(
                                entries = pointAttrs,
                                textStyle = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
    if (showCloseConfirmation) {
        AlertDialog(
            onDismissRequest = { showCloseConfirmation = false },
            title = {
                Text(text = stringResource(R.string.action_close_event))
            },
            confirmButton = {
                TextButton(onClick = {
                    showCloseConfirmation = false
                    onCloseEvent()
                }) {
                    Text(text = stringResource(R.string.action_close_event))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirmation = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            }
        )
    }
    ZoomableImagePreviewDialog(
        model = previewImageUrl,
        onDismiss = { previewImageUrl = null },
    )
}

@Composable
private fun ChannelFilterMenuOption(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        modifier = modifier
            .fillMaxWidth(),
        text = { Text(text = title) },
        leadingIcon = {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun EventAttributeRows(
    entries: List<EventDisplayAttribute>,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        entries.forEach { entry ->
            Text(
                text = "${entry.displayLabel} - ${entry.value}",
                style = textStyle,
                color = color,
            )
        }
    }
}

@Composable
private fun EventStatusBadge(
    statusText: String,
    state: EventLifecycleState,
    severity: EventSeverity?,
) {
    val tint = eventSeverityTint(severity) ?: eventStateTint(state)
    Surface(
        color = tint.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(tint)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
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
private fun EventSeverityIndicator(severity: EventSeverity?) {
    val resolved = severity ?: return
    val tint = eventSeverityTint(resolved) ?: return
    Icon(
        imageVector = eventSeverityIcon(resolved),
        contentDescription = resolved.wireValue,
        tint = tint,
        modifier = Modifier
            .height(16.dp)
            .width(16.dp),
    )
}

@Composable
private fun EventTimeMetaChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .height(14.dp)
                .width(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntityImageThumb(url: String?) {
    val normalized = url?.trim().takeUnless { it.isNullOrEmpty() }
    if (normalized.isNullOrEmpty()) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .height(44.dp)
                .width(44.dp)
        ) {}
        return
    }
    AsyncImage(
        model = normalized,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .height(44.dp)
            .width(44.dp)
            .clip(MaterialTheme.shapes.small),
    )
}

private fun buildEventCards(messages: List<PushMessage>): List<EventCardModel> {
    val grouped = messages
        .asSequence()
        .filter { it.entityType == "event" || !it.eventId.isNullOrBlank() }
        .mapNotNull { message ->
            val eventId = message.eventId?.trim().orEmpty()
            if (eventId.isEmpty()) return@mapNotNull null
            val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
            val eventTime = payload
                ?.optLong("event_time")
                ?.takeIf { it > 0 }
                ?.let(Instant::ofEpochSecond)
                ?: message.receivedAt
            val profileRaw = payload?.optString("event_profile_json")
            val profile = parseEventProfile(profileRaw)
            val attachmentUrls = parseImageUrls(profileRaw)
            val imageUrl = profile?.imageUrl
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
            eventId to EventTimelineRow(
                messageId = message.id,
                title = profileTitle
                    ?: message.title.ifBlank { eventId },
                displayTitle = operationTitle,
                summary = profileDescription,
                displaySummary = operationDescription,
                status = profile?.status,
                message = profile?.message,
                severity = EventSeverity.fromRaw(profile?.severity),
                tags = profile?.tags ?: emptyList(),
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
        .sortedWith(
            compareBy<EventCardModel> { stateSortPriority(it.state) }
                .thenByDescending { it.updatedAt }
        )
}

private fun mergeEventCards(
    existing: List<EventCardModel>,
    incoming: List<EventCardModel>,
): List<EventCardModel> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming
    val byEventId = LinkedHashMap<String, EventCardModel>(existing.size + incoming.size)
    for (event in existing) {
        byEventId[event.eventId] = event
    }
    for (event in incoming) {
        val current = byEventId[event.eventId]
        byEventId[event.eventId] = if (current == null) event else mergeEventCard(current, event)
    }
    return byEventId.values
        .sortedWith(compareBy<EventCardModel> { stateSortPriority(it.state) }.thenByDescending { it.updatedAt })
}

private fun mergeEventCard(
    current: EventCardModel,
    incoming: EventCardModel,
): EventCardModel {
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

private fun stateSortPriority(state: EventLifecycleState): Int {
    return when (state) {
        EventLifecycleState.Ongoing -> 0
        EventLifecycleState.Closed -> 1
        EventLifecycleState.Unknown -> 2
    }
}

private fun eventStateTint(state: EventLifecycleState): Color {
    return when (state) {
        EventLifecycleState.Ongoing -> Color(0xFF2563EB)
        EventLifecycleState.Closed -> Color(0xFF6B7280)
        EventLifecycleState.Unknown -> Color(0xFF6B7280)
    }
}

private fun isImageAttachmentUrl(raw: String): Boolean {
    val path = raw.substringBefore("?").lowercase()
    if (path.endsWith(".png") ||
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
    val profile = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
    val urls = linkedSetOf<String>()

    fun appendArray(key: String) {
        val array = profile.optJSONArray(key) ?: return
        for (index in 0 until array.length()) {
            val url = array.optString(index, "").trim()
            if (url.isNotEmpty()) {
                urls += url
            }
        }
    }

    appendArray("images")
    return urls.toList()
}

private fun mergedEventAttrsJson(timeline: List<EventTimelineRow>): String? {
    val merged = JSONObject()
    timeline
        .sortedBy { it.happenedAt }
        .forEach { row ->
            val patch = parseJsonObjectOrNull(row.attrsJson ?: return@forEach) ?: return@forEach
            val iterator = patch.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                val value = patch.opt(key)
                if (value == JSONObject.NULL) {
                    merged.remove(key)
                } else {
                    merged.put(key, value)
                }
            }
        }
    return if (merged.length() == 0) null else merged.toString(2)
}

private fun parseJsonObjectOrNull(raw: String): JSONObject? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val parsed = runCatching { JSONObject(text) }.getOrNull() ?: return null
    return parsed
}

private fun normalizedEventStatus(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    return trimmed.ifEmpty { null }
}

private fun eventSeverityTint(severity: EventSeverity?): Color? {
    return when (severity) {
        EventSeverity.Critical -> Color(0xFFB91C1C)
        EventSeverity.High -> Color(0xFFD97706)
        EventSeverity.Normal -> Color(0xFF2563EB)
        EventSeverity.Low -> Color(0xFF6366F1)
        else -> null
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

private fun parseEventDisplayAttributes(raw: String?): List<EventDisplayAttribute> {
    val text = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
    val parsedObject = runCatching { JSONObject(text) }.getOrNull()
    if (parsedObject != null) {
        val keys = parsedObject.keys().asSequence().toList().sorted()
        return keys.map { key ->
            val rawValue = parsedObject.opt(key)
            toEventDisplayAttribute(key = key, rawValue = rawValue)
        }
    }

    val parsedArray = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    val entries = mutableListOf<EventDisplayAttribute>()
    for (index in 0 until parsedArray.length()) {
        val item = parsedArray.opt(index)
        if (item is JSONObject) {
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
    if (rawValue is JSONObject && rawValue.has("value")) {
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
        null, JSONObject.NULL -> "null"
        is String -> value
        is Number, is Boolean -> value.toString()
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        else -> value.toString()
    }
}
