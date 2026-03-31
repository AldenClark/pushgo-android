package io.ethan.pushgo.ui.screens

import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList as FilledFilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList as OutlinedFilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import coil.compose.AsyncImage
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.EntityProjectionCursor
import io.ethan.pushgo.data.parseEventProfile
import io.ethan.pushgo.data.parseThingProfile
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.MessageSeverity
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessageBodyResolver
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.markdown.FullMarkdownRenderer
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor
import io.ethan.pushgo.util.normalizeExternalImageUrl
import io.ethan.pushgo.util.openExternalUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.material3.Checkbox

private data class ThingRelatedMessage(
    val message: PushMessage,
    val happenedAt: Instant,
)

private data class ThingRelatedUpdate(
    val updateId: String,
    val title: String,
    val summary: String?,
    val state: String?,
    val attrsJson: String?,
    val happenedAt: Instant,
)

private data class ThingCardModel(
    val thingId: String,
    val title: String,
    val state: String?,
    val summary: String?,
    val tags: List<String>,
    val createdAt: Instant?,
    val imageUrl: String?,
    val imageUrls: List<String>,
    val channelId: String?,
    val attrsJson: String?,
    val attrsCount: Int,
    val metadataJson: String?,
    val metadataCount: Int,
    val updatedAt: Instant,
    val relatedEvents: List<EventCardModel>,
    val relatedMessages: List<ThingRelatedMessage>,
    val relatedUpdates: List<ThingRelatedUpdate>,
)

private val ThingTimeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
private val ScreenHorizontalPadding = 12.dp
private const val ThingProjectionPageSize = 300

private enum class ThingLifecycleState {
    Active,
    Archived,
    Deleted,
    Unknown;

    companion object {
        fun fromRaw(raw: String?): ThingLifecycleState {
            return when (raw?.trim()?.lowercase()) {
                "active" -> Active
                "inactive", "archived" -> Archived
                "deleted", "decommissioned" -> Deleted
                else -> Unknown
            }
        }
    }
}

private fun normalizedThingState(state: String?): String {
    return when (ThingLifecycleState.fromRaw(state)) {
        ThingLifecycleState.Active -> "ACTIVE"
        ThingLifecycleState.Archived -> "ARCHIVED"
        ThingLifecycleState.Deleted -> "DELETED"
        ThingLifecycleState.Unknown -> "UNKNOWN"
    }
}

private fun thingStateTint(state: String?): Color {
    return when (ThingLifecycleState.fromRaw(state)) {
        ThingLifecycleState.Active -> Color(0xFF16A34A)
        ThingLifecycleState.Archived -> Color(0xFFD97706)
        ThingLifecycleState.Deleted -> Color(0xFFDC2626)
        ThingLifecycleState.Unknown -> Color(0xFF6B7280)
    }
}
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ThingListScreen(
    container: AppContainer,
    refreshToken: Long,
    openThingId: String?,
    onOpenThingHandled: () -> Unit,
    onThingDetailOpened: (String) -> Unit,
    onThingDetailClosed: () -> Unit,
    onBatchModeChanged: (Boolean) -> Unit,
) {
    var allThings by remember { mutableStateOf<List<ThingCardModel>>(emptyList()) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var thingCursor by remember { mutableStateOf<EntityProjectionCursor?>(null) }
    var hasMoreThings by remember { mutableStateOf(true) }
    var isLoadingMoreThings by remember { mutableStateOf(false) }
    var selectedThing by remember { mutableStateOf<ThingCardModel?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedThingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initialSelectionStateForDrag by remember { mutableStateOf<Boolean?>(null) }
    var showBatchDeleteConfirmation by remember { mutableStateOf(false) }
    var selectedRelatedEvent by remember { mutableStateOf<EventCardModel?>(null) }
    var selectedRelatedMessage by remember { mutableStateOf<ThingRelatedMessage?>(null) }
    var selectedRelatedUpdate by remember { mutableStateOf<ThingRelatedUpdate?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var channelFilter by remember { mutableStateOf<String?>(null) }
    var showOnlyActive by remember { mutableStateOf(false) }
    var channelNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = container.appContext
    val noThingsText = stringResource(R.string.label_no_things_title)
    val listState = rememberLazyListState()
    var listTopInWindow by remember { mutableFloatStateOf(0f) }
    var selectionRailTopInWindow by remember { mutableFloatStateOf(0f) }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedThingIds = emptySet()
        initialSelectionStateForDrag = null
    }

    fun toggleSelection(thingId: String) {
        selectedThingIds = if (selectedThingIds.contains(thingId)) {
            selectedThingIds - thingId
        } else {
            selectedThingIds + thingId
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun selectIfNeeded(thingId: String) {
        if (!selectedThingIds.contains(thingId)) {
            selectedThingIds = selectedThingIds + thingId
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    fun updateSelectionAtRailY(railLocalY: Float, targetState: Boolean, filteredThings: List<ThingCardModel>) {
        val listLocalY = railLocalY + (selectionRailTopInWindow - listTopInWindow)
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            listLocalY in item.offset.toFloat()..(item.offset + item.size).toFloat()
        }
        val rowIndex = (target?.index ?: -1) - 1
        val thingId = filteredThings.getOrNull(rowIndex)?.thingId
        if (thingId != null) {
            val isSelected = selectedThingIds.contains(thingId)
            if (isSelected != targetState) {
                selectedThingIds = if (targetState) {
                    selectedThingIds + thingId
                } else {
                    selectedThingIds - thingId
                }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    BackHandler(enabled = isSelectionMode) {
        exitSelectionMode()
    }

    LaunchedEffect(isSelectionMode) {
        onBatchModeChanged(isSelectionMode)
    }

    DisposableEffect(Unit) {
        onDispose { onBatchModeChanged(false) }
    }

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun reloadThings() {
        scope.launch {
            if (isLoadingMoreThings) return@launch
            isLoadingMoreThings = true
            try {
                thingCursor = null
                hasMoreThings = true
                val firstPage = container.entityRepository.getThingProjectionMessagesPage(
                    before = null,
                    limit = ThingProjectionPageSize,
                )
                allThings = withContext(Dispatchers.Default) {
                    buildThingCards(firstPage)
                }
                val last = firstPage.lastOrNull()
                thingCursor = last?.let {
                    EntityProjectionCursor(
                        receivedAt = it.receivedAt.toEpochMilli(),
                        id = it.id,
                    )
                }
                hasMoreThings = firstPage.size >= ThingProjectionPageSize
            } finally {
                isLoadingMoreThings = false
                hasLoadedOnce = true
            }
        }
    }

    fun loadMoreThingsIfNeeded() {
        if (isLoadingMoreThings || !hasMoreThings) return
        scope.launch {
            isLoadingMoreThings = true
            try {
                val page = container.entityRepository.getThingProjectionMessagesPage(
                    before = thingCursor,
                    limit = ThingProjectionPageSize,
                )
                if (page.isNotEmpty()) {
                    val existing = allThings
                    allThings = withContext(Dispatchers.Default) {
                        mergeThingCards(existing, buildThingCards(page))
                    }
                    val last = page.last()
                    thingCursor = EntityProjectionCursor(
                        receivedAt = last.receivedAt.toEpochMilli(),
                        id = last.id,
                    )
                }
                hasMoreThings = page.size >= ThingProjectionPageSize
            } finally {
                isLoadingMoreThings = false
            }
        }
    }

    LaunchedEffect(refreshToken) {
        reloadThings()
        channelNameMap = container.channelRepository.loadSubscriptionLookup(includeDeleted = true)
    }

    val filteredThings = remember(allThings, searchQuery, channelFilter, showOnlyActive) {
        val query = searchQuery.trim().lowercase()
        allThings
            .asSequence()
            .filter { thing ->
                channelFilter == null || thing.channelId == channelFilter
            }
            .filter { thing ->
                !showOnlyActive || ThingLifecycleState.fromRaw(thing.state) == ThingLifecycleState.Active
            }
            .filter { thing ->
                if (query.isEmpty()) {
                    true
                } else {
                    thing.title.lowercase().contains(query) ||
                        thing.summary?.lowercase()?.contains(query) == true ||
                        thing.tags.any { it.lowercase().contains(query) } ||
                        thing.relatedMessages.any { related ->
                            related.message.title.lowercase().contains(query) ||
                                related.message.bodyPreview?.lowercase()?.contains(query) == true ||
                                related.message.body.lowercase().contains(query)
                        } ||
                        thing.thingId.lowercase().contains(query)
                }
            }
            .toList()
    }

    LaunchedEffect(
        filteredThings.size,
        searchQuery,
        channelFilter,
        hasMoreThings,
        isLoadingMoreThings,
    ) {
        val query = searchQuery.trim()
        val hasActiveFilter = query.isNotEmpty() || channelFilter != null
        if (hasActiveFilter && filteredThings.isEmpty() && hasMoreThings && !isLoadingMoreThings) {
            loadMoreThingsIfNeeded()
        }
    }

    val channelOptions = remember(allThings) {
        allThings
            .mapNotNull { it.channelId?.trim()?.takeIf { value -> value.isNotEmpty() } }
            .distinct()
            .sorted()
    }
    LaunchedEffect(openThingId, allThings) {
        val target = openThingId?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val matched = allThings.firstOrNull { it.thingId == target } ?: return@LaunchedEffect
        selectedThing = matched
        onThingDetailOpened(matched.thingId)
        onOpenThingHandled()
    }

    LaunchedEffect(allThings, selectedThing?.thingId) {
        val selectedId = selectedThing?.thingId ?: return@LaunchedEffect
        val latest = allThings.firstOrNull { it.thingId == selectedId } ?: return@LaunchedEffect
        if (latest != selectedThing) {
            selectedThing = latest
        }
    }

    if (selectedThing != null && !isSelectionMode) {
        val thingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            sheetState = thingSheetState,
            containerColor = PushGoSheetContainerColor(),
            tonalElevation = 0.dp,
            onDismissRequest = {
                selectedThing = null
                selectedRelatedMessage = null
                selectedRelatedEvent = null
                selectedRelatedUpdate = null
                onThingDetailClosed()
            }
        ) {
            ThingDetailSheet(
                thing = selectedThing!!,
                channelNameMap = channelNameMap,
                onOpenRelatedEvent = { event ->
                    selectedRelatedEvent = event
                },
                onOpenRelatedMessage = { message ->
                    selectedRelatedMessage = message
                },
                onOpenRelatedUpdate = { update ->
                    selectedRelatedUpdate = update
                },
                onDelete = {
                    val thingId = selectedThing?.thingId ?: return@ThingDetailSheet
                    scope.launch {
                        container.entityRepository.deleteThing(thingId)
                        selectedThing = null
                        onThingDetailClosed()
                        reloadThings()
                    }
                },
            )
        }
    }

    val relatedEvent = selectedRelatedEvent
    if (relatedEvent != null) {
        val relatedEventSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedRelatedEvent = null },
            sheetState = relatedEventSheetState,
            containerColor = PushGoSheetContainerColor(),
            tonalElevation = 0.dp,
        ) {
            EventDetailSheet(
                event = relatedEvent,
                onCloseEvent = {
                    scope.launch {
                        try {
                            val channelId = relatedEvent.channelId.orEmpty().trim()
                            if (channelId.isEmpty()) {
                                showToast(context.getString(R.string.error_event_close_failed) + ": missing channel_id")
                                return@launch
                            }
                            container.channelRepository.closeEvent(
                                rawEventId = relatedEvent.eventId,
                                rawThingId = relatedEvent.thingId,
                                rawChannelId = channelId,
                                rawStatus = context.getString(R.string.event_status_closed_default),
                                rawMessage = context.getString(R.string.event_message_closed_default),
                                rawSeverity = relatedEvent.severity?.wireValue,
                            )
                            showToast(context.getString(R.string.message_event_closed))
                            selectedRelatedEvent = null
                            reloadThings()
                        } catch (error: Exception) {
                            showToast(context.getString(R.string.error_event_close_failed) + ": ${error.message.orEmpty()}")
                        }
                    }
                },
                onDelete = {
                    val eventId = relatedEvent.eventId
                    scope.launch {
                        container.entityRepository.deleteEvent(eventId)
                        selectedRelatedEvent = null
                        reloadThings()
                    }
                },
            )
        }
    }

    val relatedUpdate = selectedRelatedUpdate
    if (relatedUpdate != null) {
        val relatedUpdateSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedRelatedUpdate = null },
            sheetState = relatedUpdateSheetState,
            containerColor = PushGoSheetContainerColor(),
            tonalElevation = 0.dp,
        ) {
            ThingUpdateDetailSheet(update = relatedUpdate)
        }
    }

    val relatedMessage = selectedRelatedMessage
    if (relatedMessage != null) {
        val relatedMessageSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedRelatedMessage = null },
            sheetState = relatedMessageSheetState,
            containerColor = PushGoSheetContainerColor(),
            tonalElevation = 0.dp,
        ) {
            ThingRelatedMessageDetailSheet(message = relatedMessage)
        }
    }

    if (!hasLoadedOnce && allThings.isEmpty()) {
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

    if (allThings.isEmpty()) {
        AppEmptyState(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.label_no_things_title),
            description = stringResource(R.string.label_no_things_hint),
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    listTopInWindow = coordinates.positionInWindow().y
                },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                if (isSelectionMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.label_selected_count, selectedThingIds.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            enabled = selectedThingIds.isNotEmpty(),
                            onClick = { showBatchDeleteConfirmation = true },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                        TextButton(onClick = { exitSelectionMode() }) {
                            Text(text = stringResource(R.string.label_cancel))
                        }
                    }
                } else {
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
                                        stringResource(R.string.label_search_things),
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

                            Box {
                                var channelMenuExpanded by remember { mutableStateOf(false) }
                                IconButton(onClick = { channelMenuExpanded = true }) {
                                    val hasActiveFilter = channelFilter != null || showOnlyActive
                                    Icon(
                                        imageVector = if (!hasActiveFilter) Icons.Outlined.OutlinedFilterList else Icons.Filled.FilledFilterList,
                                        contentDescription = stringResource(R.string.label_channel_id),
                                        tint = if (hasActiveFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }

                                DropdownMenu(
                                    expanded = channelMenuExpanded,
                                    onDismissRequest = { channelMenuExpanded = false },
                                    modifier = Modifier.width(200.dp)
                                ) {
                                    // Toggle Status: Active things
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.filter_active_things)) },
                                        onClick = {
                                            showOnlyActive = !showOnlyActive
                                            channelMenuExpanded = false
                                        },
                                        trailingIcon = {
                                            if (showOnlyActive) {
                                                Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    )

                                    if (channelOptions.isNotEmpty()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                        channelOptions.forEach { channelId ->
                                            val displayName = channelNameMap[channelId] ?: channelId
                                            DropdownMenuItem(
                                                text = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                onClick = {
                                                    val newChannel = if (channelFilter == channelId) null else channelId
                                                    channelFilter = newChannel
                                                    channelMenuExpanded = false
                                                },
                                                trailingIcon = {
                                                    if (channelFilter == channelId) {
                                                        Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            IconButton(
                                onClick = {
                                    isSelectionMode = true
                                    selectedThing = null
                                    selectedThingIds = emptySet()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Checklist,
                                    contentDescription = stringResource(R.string.action_batch_select),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.label_send_type_thing),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .padding(start = ScreenHorizontalPadding, top = 8.dp, bottom = 12.dp)
                        .semantics { heading() },
                )
            }
        }

        if (filteredThings.isEmpty()) {
            item {
                AppEmptyState(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.label_no_search_results),
                    description = stringResource(R.string.label_no_things_hint),
                )
            }
        } else {
            itemsIndexed(filteredThings, key = { _, item -> item.thingId }) { index, thing ->
                if (index >= filteredThings.lastIndex - 6) {
                    LaunchedEffect(thing.thingId, hasMoreThings, isLoadingMoreThings) {
                        loadMoreThingsIfNeeded()
                    }
                }
                ThingListRowItem(
                    thing = thing,
                    onClick = {
                        if (isSelectionMode) {
                            toggleSelection(thing.thingId)
                        } else {
                            selectedThing = thing
                        }
                    },
                    selectionMode = isSelectionMode,
                    selected = selectedThingIds.contains(thing.thingId),
                    onToggleSelection = { toggleSelection(thing.thingId) },
                )
            }
            if (isLoadingMoreThings) {
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

        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
                    .width(72.dp)
                    .onGloballyPositioned { coordinates ->
                        selectionRailTopInWindow = coordinates.positionInWindow().y
                    }
                    .pointerInput(filteredThings.size, listTopInWindow, selectionRailTopInWindow, showOnlyActive) {
                        detectDragGestures(
                            onDragStart = { point ->
                                val listLocalY = point.y + (selectionRailTopInWindow - listTopInWindow)
                                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    listLocalY in item.offset.toFloat()..(item.offset + item.size).toFloat()
                                }
                                val rowIndex = (target?.index ?: -1) - 1
                                val thingId = filteredThings.getOrNull(rowIndex)?.thingId
                                if (thingId != null) {
                                    val isSelected = selectedThingIds.contains(thingId)
                                    initialSelectionStateForDrag = !isSelected
                                    updateSelectionAtRailY(point.y, !isSelected, filteredThings)
                                }
                            },
                            onDrag = { change, _ ->
                                initialSelectionStateForDrag?.let { targetState ->
                                    updateSelectionAtRailY(change.position.y, targetState, filteredThings)
                                }
                            },
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
            text = { Text(text = stringResource(R.string.confirm_delete_selected_things, selectedThingIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchDeleteConfirmation = false
                        val targets = selectedThingIds.toList()
                        scope.launch {
                            targets.forEach { thingId ->
                                container.entityRepository.deleteThing(thingId)
                            }
                            showToast(context.getString(R.string.message_deleted_selected_count, targets.size))
                            reloadThings()
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
private fun ThingStateBadge(state: String?) {
    val tint = thingStateTint(state)
    Surface(
        color = tint.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = normalizedThingState(state),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private enum class ThingDetailTab {
    Events,
    Messages,
    Updates,
}

@Composable
private fun ThingSelectionToggle(
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(bounded = false, radius = 20.dp),
                onClick = onToggle
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun FilterOptionItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThingListRowItem(
    thing: ThingCardModel,
    onClick: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val metaSummary = remember(thing.attrsJson, thing.summary, thing.thingId) {
        val attrs = parseThingDisplayAttributes(thing.attrsJson)
        when {
            attrs.isNotEmpty() -> {
                val preview = attrs.take(3).joinToString(" · ") { "${it.label}: ${it.value}" }
                val overflow = attrs.size - 3
                if (overflow > 0) "$preview · +$overflow" else preview
            }
            !thing.summary.isNullOrBlank() -> thing.summary
            else -> thing.thingId
        }
    }
    val attachmentPreviewUrls = remember(thing.imageUrl, thing.imageUrls) {
        val primary = thing.imageUrl?.trim().orEmpty()
        thing.imageUrls
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && isThingImageAttachmentUrl(it) }
            .filter { url -> primary.isEmpty() || url != primary }
            .distinct()
            .toList()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    Color.Transparent
                }
            )
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
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selectionMode) {
                ThingSelectionToggle(
                    selected = selected,
                    onToggle = onToggleSelection,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            ThingImageThumb(url = thing.imageUrl)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = thing.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        text = ThingTimeFormatter.format(thing.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = metaSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        if (thing.tags.isNotEmpty()) {
            Text(
                text = thing.tags.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (attachmentPreviewUrls.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attachmentPreviewUrls.take(4).forEach { url ->
                    ThingImageThumb(url = url, size = 42.dp)
                }
                val overflow = attachmentPreviewUrls.size - 4
                if (overflow > 0) {
                    Text(
                        text = "+$overflow",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, top = 12.dp),
                    )
                }
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        thickness = 1.dp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThingDetailSheet(
    thing: ThingCardModel,
    channelNameMap: Map<String, String>,
    onOpenRelatedEvent: (EventCardModel) -> Unit,
    onOpenRelatedMessage: (ThingRelatedMessage) -> Unit,
    onOpenRelatedUpdate: (ThingRelatedUpdate) -> Unit,
    onDelete: () -> Unit,
) {
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    var showMetadataSheet by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(ThingDetailTab.Events) }
    val attrsEntries = remember(thing.attrsJson) { parseThingDisplayAttributes(thing.attrsJson) }
    val metadataEntries = remember(thing.metadataJson) { parseThingDisplayAttributes(thing.metadataJson) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                ThingImageThumb(
                    url = thing.imageUrl,
                    size = 80.dp,
                    onClick = { url -> previewImageUrl = url }
                )
                IconButton(
                    modifier = Modifier.size(32.dp),
                    onClick = onDelete
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = thing.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                    )
                    val lifecycleState = ThingLifecycleState.fromRaw(thing.state)
                    if (lifecycleState == ThingLifecycleState.Archived || lifecycleState == ThingLifecycleState.Deleted) {
                        ThingStateBadge(state = thing.state)
                    }
                }
                
                if (metadataEntries.isNotEmpty()) {
                    TextButton(
                        onClick = { showMetadataSheet = true },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.thing_detail_metadata_button),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        if (attrsEntries.isNotEmpty()) {
            EntityKeyValueRows(entries = attrsEntries)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(
                    R.string.thing_detail_created_updated,
                    thing.createdAt?.let(ThingTimeFormatter::format) ?: "-",
                    ThingTimeFormatter.format(thing.updatedAt),
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )

            if (thing.tags.isNotEmpty()) {
                Text(
                    text = thing.tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                )
            }

            if (!thing.summary.isNullOrBlank()) {
                Text(
                    text = thing.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }

        if (thing.imageUrls.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                thing.imageUrls.drop(1).forEach { url ->
                    ThingImageThumb(url = url, size = 100.dp, onClick = { previewImageUrl = it })
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selectedTab == ThingDetailTab.Events,
                onClick = { selectedTab = ThingDetailTab.Events },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                modifier = Modifier.weight(1f),
                icon = {},
            ) { Text(stringResource(R.string.thing_detail_tab_events)) }
            SegmentedButton(
                selected = selectedTab == ThingDetailTab.Messages,
                onClick = { selectedTab = ThingDetailTab.Messages },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                modifier = Modifier.weight(1f),
                icon = {},
            ) { Text(stringResource(R.string.thing_detail_tab_messages)) }
            SegmentedButton(
                selected = selectedTab == ThingDetailTab.Updates,
                onClick = { selectedTab = ThingDetailTab.Updates },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                modifier = Modifier.weight(1f),
                icon = {},
            ) { Text(stringResource(R.string.thing_detail_tab_updates)) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (selectedTab) {
                ThingDetailTab.Events -> {
                    if (thing.relatedEvents.isEmpty()) {
                        AppEmptyState(
                            icon = Icons.Outlined.Info,
                            title = stringResource(R.string.thing_detail_no_related_events),
                            description = stringResource(R.string.label_no_events_hint),
                            topPadding = 12.dp,
                            horizontalPadding = 0.dp,
                            iconSize = 44.dp,
                        )
                    } else {
                        thing.relatedEvents.sortedByDescending { it.updatedAt }.forEach { event ->
                            EventListRowItem(
                                event = event,
                                onClick = { onOpenRelatedEvent(event) },
                                selectionMode = false,
                                selected = false,
                                onToggleSelection = {},
                                showDivider = true,
                            )
                        }
                    }
                }
                ThingDetailTab.Messages -> {
                    if (thing.relatedMessages.isEmpty()) {
                        AppEmptyState(
                            icon = Icons.Outlined.Info,
                            title = stringResource(R.string.thing_detail_no_related_messages),
                            description = stringResource(R.string.message_list_empty_hint),
                            topPadding = 12.dp,
                            horizontalPadding = 0.dp,
                            iconSize = 44.dp,
                        )
                    } else {
                        thing.relatedMessages
                            .sortedByDescending { it.happenedAt }
                            .forEach { message ->
                            val nowInstant = Instant.now()
                            val rowMessage = message.message
                            val bodyPreview = rowMessage.bodyPreview?.trim().orEmpty()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenRelatedMessage(message) }
                                    .padding(vertical = 10.dp),
                            ) {
                                MessageRowContent(
                                    message = rowMessage,
                                    imageModels = rowMessage.imageUrls.take(3).map { it as Any },
                                    appName = stringResource(R.string.app_name),
                                    timeText = formatMessageTime(
                                        context = LocalContext.current,
                                        receivedAt = rowMessage.receivedAt,
                                        zoneId = ZoneId.systemDefault(),
                                        nowInstant = nowInstant,
                                    ),
                                    bodyPreview = bodyPreview,
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(top = 10.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
                ThingDetailTab.Updates -> {
                    if (thing.relatedUpdates.isEmpty()) {
                        AppEmptyState(
                            icon = Icons.Outlined.Info,
                            title = stringResource(R.string.thing_detail_no_related_updates),
                            description = stringResource(R.string.label_no_things_hint),
                            topPadding = 12.dp,
                            horizontalPadding = 0.dp,
                            iconSize = 44.dp,
                        )
                    } else {
                        thing.relatedUpdates.sortedByDescending { it.happenedAt }.forEach { update ->
                            val updateMessage = PushMessage(
                                id = update.updateId,
                                messageId = update.updateId,
                                title = update.title,
                                body = update.summary ?: "",
                                channel = null,
                                url = null,
                                isRead = true,
                                receivedAt = update.happenedAt,
                                rawPayloadJson = "{}",
                                status = MessageStatus.NORMAL,
                                decryptionState = null,
                                notificationId = null,
                                serverId = null,
                                bodyPreview = update.summary,
                            )
                            val nowInstant = Instant.now()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenRelatedUpdate(update) }
                                    .padding(vertical = 8.dp),
                            ) {
                                MessageRowContent(
                                    message = updateMessage,
                                    imageModels = emptyList(),
                                    appName = stringResource(R.string.app_name),
                                    timeText = formatMessageTime(
                                        context = LocalContext.current,
                                        receivedAt = updateMessage.receivedAt,
                                        zoneId = ZoneId.systemDefault(),
                                        nowInstant = nowInstant,
                                    ),
                                    bodyPreview = update.summary.orEmpty(),
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(top = 8.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }

    if (previewImageUrl != null) {
        ZoomableImagePreviewDialog(
            model = previewImageUrl,
            onDismiss = { previewImageUrl = null }
        )
    }
    if (showMetadataSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMetadataSheet = false },
            containerColor = PushGoSheetContainerColor(),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.thing_detail_metadata_button),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                if (metadataEntries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.thing_detail_no_metadata),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                } else {
                    EntityKeyValueRows(entries = metadataEntries)
                }
            }
        }
    }
}

@Composable
private fun ThingRelatedMessageDetailSheet(message: ThingRelatedMessage) {
    val current = message.message
    val context = LocalContext.current
    val timeText = remember(current.receivedAt) {
        ThingTimeFormatter.format(current.receivedAt)
    }
    val resolvedBody = remember(current.rawPayloadJson, current.body) {
        MessageBodyResolver.resolve(current.rawPayloadJson, current.body)
    }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    MessageDetailCoreContent(
        message = current,
        timeText = timeText,
        imageModels = current.imageUrls.map { it as Any },
        resolvedBodyText = resolvedBody.rawText,
        onDelete = null,
        onOpenImage = { model ->
            val raw = model as? String ?: return@MessageDetailCoreContent
            val safeImage = normalizeExternalImageUrl(raw) ?: return@MessageDetailCoreContent
            previewImageUrl = safeImage
        },
        onOpenUrl = { url -> context.openExternalUrl(url) },
    )
    if (previewImageUrl != null) {
        ZoomableImagePreviewDialog(
            model = previewImageUrl,
            onDismiss = { previewImageUrl = null },
        )
    }
}

@Composable
private fun ThingUpdateDetailSheet(update: ThingRelatedUpdate) {
    val attrsEntries = remember(update.attrsJson) { parseThingDisplayAttributes(update.attrsJson) }
    val timeText = remember(update.happenedAt) { ThingTimeFormatter.format(update.happenedAt) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = update.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                if (!update.state.isNullOrBlank()) {
                    ThingStateBadge(state = update.state)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (!update.summary.isNullOrBlank()) {
            Text(
                text = update.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 22.sp
            )
        }

        if (attrsEntries.isNotEmpty()) {
            EntityKeyValueRows(entries = attrsEntries)
        }
    }
}

@Composable
private fun EntityKeyValueRows(entries: List<ThingDisplayAttribute>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column {
            entries.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.2.sp
                        ),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        modifier = Modifier.width(100.dp)
                    )
                    Text(
                        text = entry.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (index < entries.lastIndex) {
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

private fun buildThingCards(messages: List<PushMessage>): List<ThingCardModel> {
    val projections = linkedMapOf<String, MutableMap<String, Any?>>()
    val updatedAtMap = linkedMapOf<String, Instant>()
    val titleMap = linkedMapOf<String, String>()
    val summaryMap = linkedMapOf<String, String>()
    val stateMap = linkedMapOf<String, String>()
    val tagsMap = linkedMapOf<String, List<String>>()
    val createdAtMap = linkedMapOf<String, Instant>()
    val imageMap = linkedMapOf<String, String>()
    val imageListMap = linkedMapOf<String, List<String>>()
    val channelMap = linkedMapOf<String, String>()

    messages.sortedBy {
        happenedAtFromPayload(
            payload = runCatching { JSONObject(it.rawPayloadJson) }.getOrNull(),
            fallback = it.receivedAt,
        )
    }.forEach { message ->
        val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull() ?: return@forEach
        val happenedAt = happenedAtFromPayload(payload = payload, fallback = message.receivedAt)
        val thingId = message.thingId?.trim().takeIf { !it.isNullOrBlank() } ?: return@forEach
        val attrs = projections.getOrPut(thingId) { linkedMapOf() }
        val profile = parseThingProfile(payload.optString("thing_profile_json"))
        titleMap[thingId] = profile?.title
            ?: message.title.ifBlank { thingId }
        val summary = profile?.description
        if (!summary.isNullOrBlank()) {
            summaryMap[thingId] = summary
        }
        val thingState = profile?.state
            ?: profile?.status
            ?: payload.optString("thing_state").trim().takeIf { it.isNotEmpty() }
            ?: payload.optString("state").trim().takeIf { it.isNotEmpty() }
        if (!thingState.isNullOrBlank()) {
            stateMap[thingId] = thingState
        }
        profile?.imageUrl?.let { imageMap[thingId] = it }
        profile?.imageUrls?.takeIf { it.isNotEmpty() }?.let { imageListMap[thingId] = it }
        val tags = profile?.tags ?: emptyList()
        if (tags.isNotEmpty()) {
            tagsMap[thingId] = tags
        }
        profile?.createdAt
            ?.takeIf { it > 0L }
            ?.let { createdAtMap[thingId] = Instant.ofEpochSecond(it) }
        message.channel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { channelMap[thingId] = it }
        updatedAtMap[thingId] = happenedAt

        payload.optString("thing_attrs_json")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { raw ->
                val parsed = parseJsonObjectOrNull(raw)
                if (parsed != null) {
                    attrs.clear()
                    parsed.forEach { (key, value) ->
                        if (value != JSONObject.NULL) {
                            attrs[key] = value
                        }
                    }
                }
            }

        payload.optString("event_attrs_json")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { raw ->
                parseJsonObjectOrNull(raw)?.forEach { (key, value) ->
                    if (value == JSONObject.NULL) {
                        attrs.remove(key)
                    } else {
                        attrs[key] = value
                    }
                }
            }
    }

    return projections.map { (thingId, attrs) ->
        val relatedEvents = buildThingRelatedEventCards(
            messages = messages,
            thingId = thingId,
        )
        val relatedMessages = messages
            .asSequence()
            .filter { it.thingId?.trim() == thingId && it.entityType == "message" }
            .map {
                val payload = runCatching { JSONObject(it.rawPayloadJson) }.getOrNull()
                val messageId = it.messageId?.trim()?.takeIf { value -> value.isNotEmpty() } ?: it.id
                ThingRelatedMessage(
                    message = it.copy(
                        messageId = messageId,
                        title = it.title.ifBlank { messageId },
                    ),
                    happenedAt = happenedAtFromPayload(payload = payload, fallback = it.receivedAt),
                )
            }
            .groupBy { it.message.messageId ?: it.message.id }
            .map { (_, values) -> values.maxBy { it.happenedAt } }
            .sortedByDescending { it.happenedAt }
            .toList()
        val relatedUpdates = messages
            .asSequence()
            .filter { it.thingId?.trim() == thingId && it.entityType == "thing" }
            .map { message ->
                val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
                val updateId = message.id.trim().ifEmpty { "${thingId}:${message.receivedAt.toEpochMilli()}" }
                val attrsJson = payload?.optString("thing_attrs_json")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: payload?.optString("event_attrs_json")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                ThingRelatedUpdate(
                    updateId = updateId,
                    title = message.title.ifBlank { thingId },
                    summary = message.body.trim().takeIf { it.isNotEmpty() },
                    state = payload?.optString("thing_state")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: payload?.optString("state")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                    attrsJson = attrsJson,
                    happenedAt = happenedAtFromPayload(payload = payload, fallback = message.receivedAt),
                )
            }
            .distinctBy { it.updateId }
            .sortedByDescending { it.happenedAt }
            .toList()

        val attrsJson = if (attrs.isEmpty()) {
            null
        } else {
            JSONObject(attrs).toString(2)
        }
        val metadataMap = linkedMapOf<String, String>()
        messages
            .asSequence()
            .filter { it.thingId?.trim() == thingId }
            .forEach { message ->
                val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull() ?: return@forEach
                val rawMetadata = payload.opt("metadata")
                val metadataObject = when (rawMetadata) {
                    is JSONObject -> rawMetadata
                    is String -> runCatching { JSONObject(rawMetadata) }.getOrNull()
                    else -> null
                } ?: return@forEach
                val keys = metadataObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = metadataObject.optString(key).trim()
                    if (value.isNotEmpty()) {
                        metadataMap[key] = value
                    }
                }
            }
        val metadataJson = if (metadataMap.isEmpty()) null else JSONObject(metadataMap as Map<*, *>).toString(2)
        ThingCardModel(
            thingId = thingId,
            title = titleMap[thingId] ?: thingId,
            state = stateMap[thingId],
            summary = summaryMap[thingId],
            tags = tagsMap[thingId] ?: emptyList(),
            createdAt = createdAtMap[thingId],
            imageUrl = imageMap[thingId],
            imageUrls = imageListMap[thingId] ?: imageMap[thingId]?.let { listOf(it) } ?: emptyList(),
            channelId = channelMap[thingId],
            attrsJson = attrsJson,
            attrsCount = attrs.size,
            metadataJson = metadataJson,
            metadataCount = metadataMap.size,
            updatedAt = updatedAtMap[thingId] ?: Instant.EPOCH,
            relatedEvents = relatedEvents,
            relatedMessages = relatedMessages,
            relatedUpdates = relatedUpdates,
        )
    }.sortedByDescending { it.updatedAt }
}

private fun buildThingRelatedEventCards(
    messages: List<PushMessage>,
    thingId: String,
): List<EventCardModel> {
    val grouped = messages
        .asSequence()
        .filter { message ->
            message.thingId?.trim() == thingId &&
                (message.entityType == "event" || !message.eventId.isNullOrBlank())
        }
        .mapNotNull { message ->
            val eventId = message.eventId?.trim().orEmpty()
            if (eventId.isEmpty()) return@mapNotNull null
            val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
            val eventTime = happenedAtFromPayload(payload = payload, fallback = message.receivedAt)
            val profileRaw = payload?.optString("event_profile_json")
            val profile = parseEventProfile(profileRaw)
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
            val payloadTags = payload
                ?.optString("tags", "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { raw ->
                    runCatching { JSONArray(raw) }.getOrNull()
                        ?.let { array ->
                            buildList {
                                for (index in 0 until array.length()) {
                                    val tag = array.optString(index).trim()
                                    if (tag.isNotEmpty()) add(tag)
                                }
                            }
                        }
                }
            eventId to EventTimelineRow(
                messageId = message.id,
                title = profileTitle ?: message.title.ifBlank { eventId },
                displayTitle = operationTitle,
                summary = profileDescription,
                displaySummary = operationDescription,
                status = profile?.status ?: payloadStatus,
                message = profile?.message ?: payloadMessage,
                severity = EventSeverity.fromRaw(profile?.severity ?: payloadSeverity),
                tags = if (profile?.tags.isNullOrEmpty()) payloadTags ?: emptyList() else profile.tags,
                state = EventLifecycleState.fromRaw(message.eventState),
                thingId = message.thingId,
                channelId = message.channel?.trim()?.ifEmpty { null },
                imageUrl = profile?.imageUrl,
                attachmentUrls = (parseEventImageUrls(profileRaw) + message.imageUrls).distinct(),
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
            val orderedTimeline = timeline.sortedByDescending { it.happenedAt }
            val latest = orderedTimeline.first()
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
                attachmentUrls = orderedTimeline.flatMap { it.attachmentUrls }.distinct(),
                attrsJson = mergeThingEventAttrsJson(orderedTimeline),
                updatedAt = latest.happenedAt,
                timeline = orderedTimeline,
            )
        }
        .sortedByDescending { it.updatedAt }
}

private fun parseEventImageUrls(profileRaw: String?): List<String> {
    val text = profileRaw?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
    val profile = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
    val urls = linkedSetOf<String>()
    val array = profile.optJSONArray("images") ?: return emptyList()
    for (index in 0 until array.length()) {
        val url = array.optString(index, "").trim()
        if (url.isNotEmpty()) {
            urls += url
        }
    }
    return urls.toList()
}

private fun mergeThingEventAttrsJson(timeline: List<EventTimelineRow>): String? {
    val merged = JSONObject()
    timeline
        .sortedBy { it.happenedAt }
        .forEach { row ->
            val patch = parseJsonObjectOrNull(row.attrsJson ?: return@forEach) ?: return@forEach
            patch.forEach { (key, value) ->
                if (value == JSONObject.NULL) {
                    merged.remove(key)
                } else {
                    merged.put(key, value)
                }
            }
        }
    return if (merged.length() == 0) null else merged.toString(2)
}

private fun mergeThingCards(
    existing: List<ThingCardModel>,
    incoming: List<ThingCardModel>,
): List<ThingCardModel> {
    if (incoming.isEmpty()) return existing
    if (existing.isEmpty()) return incoming
    val byThingId = LinkedHashMap<String, ThingCardModel>(existing.size + incoming.size)
    for (thing in existing) {
        byThingId[thing.thingId] = thing
    }
    for (thing in incoming) {
        val current = byThingId[thing.thingId]
        byThingId[thing.thingId] = if (current == null) thing else mergeThingCard(current, thing)
    }
    return byThingId.values.sortedByDescending { it.updatedAt }
}

private fun mergeThingCard(
    current: ThingCardModel,
    incoming: ThingCardModel,
): ThingCardModel {
    val preferred = if (incoming.updatedAt >= current.updatedAt) incoming else current
    val fallback = if (preferred === incoming) current else incoming
    val mergedRelatedEvents = (current.relatedEvents + incoming.relatedEvents)
        .distinctBy { it.eventId }
        .sortedByDescending { it.updatedAt }
    val mergedRelatedMessages = (current.relatedMessages + incoming.relatedMessages)
        .distinctBy { it.message.messageId ?: it.message.id }
        .sortedByDescending { it.happenedAt }
    val mergedRelatedUpdates = (current.relatedUpdates + incoming.relatedUpdates)
        .distinctBy { it.updateId }
        .sortedByDescending { it.happenedAt }
    val mergedImageUrls = (preferred.imageUrls + fallback.imageUrls)
        .filter { it.isNotBlank() }
        .distinct()

    return ThingCardModel(
        thingId = current.thingId,
        title = preferred.title.ifBlank { fallback.title },
        state = preferred.state ?: fallback.state,
        summary = preferred.summary ?: fallback.summary,
        tags = preferred.tags.ifEmpty { fallback.tags },
        createdAt = preferred.createdAt ?: fallback.createdAt,
        imageUrl = preferred.imageUrl ?: fallback.imageUrl,
        imageUrls = mergedImageUrls.ifEmpty {
            listOfNotNull(preferred.imageUrl, fallback.imageUrl).distinct()
        },
        channelId = preferred.channelId ?: fallback.channelId,
        attrsJson = preferred.attrsJson ?: fallback.attrsJson,
        attrsCount = maxOf(preferred.attrsCount, fallback.attrsCount),
        metadataJson = preferred.metadataJson ?: fallback.metadataJson,
        metadataCount = maxOf(preferred.metadataCount, fallback.metadataCount),
        updatedAt = maxOf(current.updatedAt, incoming.updatedAt),
        relatedEvents = mergedRelatedEvents,
        relatedMessages = mergedRelatedMessages,
        relatedUpdates = mergedRelatedUpdates,
    )
}

@Composable
private fun ThingImageThumb(
    url: String?,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    onClick: ((String) -> Unit)? = null,
) {
    val normalized = url?.trim().takeUnless { it.isNullOrEmpty() }
    if (normalized.isNullOrEmpty()) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .height(size)
                .width(size)
        ) {}
        return
    }
    AsyncImage(
        model = normalized,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .height(size)
            .width(size)
            .clip(MaterialTheme.shapes.small)
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick(normalized) }
                } else {
                    Modifier
                }
            ),
    )
}

private fun parseJsonObjectOrNull(raw: String): Map<String, Any?>? {
    val objectValue = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val result = linkedMapOf<String, Any?>()
    val iterator = objectValue.keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        result[key] = objectValue.opt(key)
    }
    return result
}

private data class ThingDisplayAttribute(
    val key: String,
    val label: String,
    val value: String,
)

private fun parseThingDisplayAttributes(attrsJson: String?): List<ThingDisplayAttribute> {
    if (attrsJson.isNullOrBlank()) return emptyList()
    val parsed = runCatching { JSONObject(attrsJson) }.getOrNull() ?: return emptyList()
    val entries = mutableListOf<ThingDisplayAttribute>()
    val keys = parsed.keys().asSequence().toList().sorted()
    for (key in keys) {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) continue
        val rawValue = parsed.opt(key)
        if (rawValue == null || rawValue == JSONObject.NULL) continue
        val value = rawValue.toString().trim()
        if (value.isEmpty()) continue
        entries += ThingDisplayAttribute(
            key = normalizedKey,
            label = normalizedKey,
            value = value,
        )
    }
    return entries
}

private fun isThingImageAttachmentUrl(raw: String): Boolean {
    val value = raw.trim().lowercase()
    return value.endsWith(".png")
        || value.endsWith(".jpg")
        || value.endsWith(".jpeg")
        || value.endsWith(".webp")
        || value.endsWith(".gif")
        || value.endsWith(".bmp")
        || value.endsWith(".heic")
        || value.contains("format=png")
        || value.contains("format=jpg")
        || value.contains("format=jpeg")
        || value.contains("format=webp")
}

private fun happenedAtFromPayload(payload: JSONObject?, fallback: Instant): Instant {
    val epochSeconds = payload?.optLong("observed_at")?.takeIf { it > 0L }
    return epochSeconds?.let { Instant.ofEpochSecond(it) } ?: fallback
}
