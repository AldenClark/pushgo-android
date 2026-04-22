package io.ethan.pushgo.ui.screens

import android.widget.Toast
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList as FilledFilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.EntityProjectionCursor
import io.ethan.pushgo.data.model.*
import io.ethan.pushgo.data.parseThingProfile
import io.ethan.pushgo.data.parseEventProfile
import io.ethan.pushgo.notifications.ForegroundNotificationPresentationState
import io.ethan.pushgo.notifications.ForegroundNotificationTopMetrics
import io.ethan.pushgo.notifications.ProviderIngressCoordinator
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.rememberBottomBarNestedScrollConnection
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import io.ethan.pushgo.ui.markdown.FullMarkdownRenderer
import io.ethan.pushgo.util.normalizeExternalImageUrl
import io.ethan.pushgo.util.openExternalUrl
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.Serializable
import java.time.temporal.ChronoUnit
import androidx.compose.foundation.lazy.items
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import android.content.Context

private const val THING_PAGE_SIZE = 40
private val ScreenHorizontalPadding = 12.dp

private object ThingInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable
data class ThingCardModel(
    val thingId: String,
    val title: String,
    val summary: String?,
    val state: String?,
    val channelId: String?,
    val imageUrl: String?,
    val tags: List<String>,
    @Serializable(with = ThingInstantSerializer::class)
    val createdAt: Instant?,
    @Serializable(with = ThingInstantSerializer::class)
    val updatedAt: Instant,
    val imageUrls: List<String>,
    val attrsJson: String?,
    val metadataJson: String?,
    val relatedEvents: List<EventCardModel>,
    val relatedMessages: List<ThingRelatedMessage>,
    val relatedUpdates: List<ThingRelatedUpdate>,
)

@Serializable
data class ThingRelatedMessage(
    val message: PushMessage,
    @Serializable(with = ThingInstantSerializer::class)
    val happenedAt: Instant,
)

@Serializable
data class ThingRelatedUpdate(
    val updateId: String,
    val title: String,
    val summary: String?,
    val state: String?,
    @Serializable(with = ThingInstantSerializer::class)
    val happenedAt: Instant,
    val attrsJson: String?,
)

object ThingTimeFormatter {
    private val fullFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    fun format(instant: Instant): String = fullFormatter.format(instant)
}

enum class ThingLifecycleState(val raw: String) {
    Active("active"),
    Archived("archived"),
    Deleted("deleted"),
    Unknown("unknown");
    companion object {
        fun fromRaw(raw: String?): ThingLifecycleState = entries.find { it.raw == raw?.lowercase() } ?: Unknown
    }
}

private data class ThingDisplayAttribute(
    val key: String,
    val label: String,
    val value: String,
) {
    val displayLabel: String
        get() = label.trim().ifEmpty { key }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThingListScreen(
    container: AppContainer,
    refreshToken: Long,
    openThingId: String?,
    onOpenThingHandled: () -> Unit,
    onThingDetailOpened: (String) -> Unit,
    onThingDetailClosed: () -> Unit,
    onBatchModeChanged: (Boolean) -> Unit,
    onBottomBarVisibilityChanged: (Boolean) -> Unit,
    scrollToTopToken: Long,
) {
    val uiColors = PushGoThemeExtras.colors
    var allThings by remember { mutableStateOf<List<ThingCardModel>>(emptyList()) }
    var hasLoadedOnce by remember { mutableStateOf(false) }
    var thingCursor by remember { mutableStateOf<EntityProjectionCursor?>(null) }
    var hasMoreThings by remember { mutableStateOf(true) }
    var isLoadingMoreThings by remember { mutableStateOf(false) }
    var selectedThing by remember { mutableStateOf<ThingCardModel?>(null) }
    var selectedRelatedMessage by remember { mutableStateOf<ThingRelatedMessage?>(null) }
    var selectedRelatedEvent by remember { mutableStateOf<EventCardModel?>(null) }
    var selectedRelatedUpdate by remember { mutableStateOf<ThingRelatedUpdate?>(null) }
    
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedThingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initialSelectionStateForDrag by remember { mutableStateOf<Boolean?>(null) }
    var showBatchDeleteConfirmation by remember { mutableStateOf(false) }
    var isPullRefreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var channelFilter by remember { mutableStateOf<String?>(null) }
    var showOnlyActive by remember { mutableStateOf(false) }
    var pendingDeleteThingId by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
    val singleDeleteToast = stringResource(R.string.message_deleted_selected_count, 1)
    val closeEventFailedMessage = stringResource(R.string.error_event_close_failed)
    val closeEventStatusDefault = stringResource(R.string.event_status_closed_default)
    val closeEventBodyDefault = stringResource(R.string.event_message_closed_default)
    val closeEventSuccessMessage = stringResource(R.string.message_event_closed)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val bottomGestureInset = rememberBottomGestureInset()
    val bottomBarNestedScrollConnection = rememberBottomBarNestedScrollConnection(onBottomBarVisibilityChanged)
    var listTopInWindow by remember { mutableFloatStateOf(0f) }
    var selectionRailTopInWindow by remember { mutableFloatStateOf(0f) }
    
    var channelNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

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

    fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
                selectedThingIds = if (targetState) selectedThingIds + thingId else selectedThingIds - thingId
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    BackHandler(enabled = isSelectionMode) { exitSelectionMode() }
    LaunchedEffect(isSelectionMode) { onBatchModeChanged(isSelectionMode) }
    DisposableEffect(Unit) {
        onDispose {
            onBatchModeChanged(false)
            onBottomBarVisibilityChanged(true)
            ForegroundNotificationPresentationState.clearThing()
        }
    }

    val suppressForegroundNotificationAtTop = selectedThing == null &&
        selectedRelatedMessage == null &&
        selectedRelatedEvent == null &&
        selectedRelatedUpdate == null

    LaunchedEffect(suppressForegroundNotificationAtTop) {
        if (!suppressForegroundNotificationAtTop) {
            ForegroundNotificationPresentationState.reportThing(
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
                ForegroundNotificationPresentationState.reportThing(
                    isAtTop = isAtTop,
                    suppressionEligible = suppressForegroundNotificationAtTop,
                )
            }
    }

    suspend fun reloadThingsInternal() {
        if (isLoadingMoreThings) return
        isLoadingMoreThings = true
        try {
            thingCursor = null
            val firstPage = container.entityRepository.getThingProjectionMessagesPage(before = null, limit = THING_PAGE_SIZE)
            allThings = withContext(Dispatchers.Default) { buildThingCardsInternal(firstPage) }
            val last = firstPage.lastOrNull()
            thingCursor = last?.let { EntityProjectionCursor(receivedAt = it.receivedAt.toEpochMilli(), id = it.id) }
            hasMoreThings = firstPage.size >= THING_PAGE_SIZE
            hasLoadedOnce = true
        } finally { isLoadingMoreThings = false }
    }

    fun reloadThings() {
        scope.launch {
            reloadThingsInternal()
        }
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
                reloadThingsInternal()
            }.onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(
                    "ThingListScreen",
                    "provider ingress refresh failed",
                    error,
                )
            }
            isPullRefreshing = false
        }
    }

    suspend fun loadMoreThingsInternal() {
        if (isLoadingMoreThings || !hasMoreThings) return
        isLoadingMoreThings = true
        try {
            val page = container.entityRepository.getThingProjectionMessagesPage(before = thingCursor, limit = THING_PAGE_SIZE)
            if (page.isNotEmpty()) {
                val merged = withContext(Dispatchers.Default) { mergeThingCardsInternal(allThings, buildThingCardsInternal(page)) }
                allThings = merged
                val last = page.last()
                thingCursor = EntityProjectionCursor(receivedAt = last.receivedAt.toEpochMilli(), id = last.id)
            }
            hasMoreThings = page.size >= THING_PAGE_SIZE
        } finally { isLoadingMoreThings = false }
    }

    fun loadMoreThingsIfNeeded() {
        if (isLoadingMoreThings || !hasMoreThings) return
        scope.launch { loadMoreThingsInternal() }
    }

    suspend fun deleteThing(thingId: String) {
        val deleted = container.entityRepository.deleteThing(thingId)
        if (selectedThing?.thingId == thingId) {
            selectedThing = null
            onThingDetailClosed()
        }
        if (deleted > 0) {
            showToast(singleDeleteToast)
        }
        reloadThingsInternal()
    }

    LaunchedEffect(refreshToken) { reloadThings() }
    LaunchedEffect(Unit) { channelNameMap = container.channelRepository.loadSubscriptionLookup(includeDeleted = true) }

    LaunchedEffect(scrollToTopToken) {
        if (scrollToTopToken == 0L) return@LaunchedEffect
        listState.animateScrollToItem(0)
    }

    val filteredThings = remember(allThings, searchQuery, channelFilter, showOnlyActive) {
        val query = searchQuery.trim().lowercase()
        allThings.filter { thing ->
            (searchQuery.isBlank() || thing.title.lowercase().contains(query)) &&
            (channelFilter == null || thing.channelId == channelFilter) &&
            (!showOnlyActive || (thing.state != "archived" && thing.state != "deleted"))
        }
    }

    val channelOptions = remember(allThings) {
        allThings.mapNotNull { it.channelId?.trim()?.takeIf { v -> v.isNotEmpty() } }.distinct().sorted()
    }

    LaunchedEffect(listState, filteredThings.size, hasMoreThings) {
        snapshotFlow {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex to totalItems
        }
            .distinctUntilChanged()
            .collect { (lastVisibleIndex, totalItems) ->
                if (!hasMoreThings || isLoadingMoreThings || totalItems <= 0) return@collect
                if (lastVisibleIndex >= totalItems - 7) {
                    loadMoreThingsIfNeeded()
                }
            }
    }

    LaunchedEffect(openThingId, allThings, hasMoreThings, isLoadingMoreThings) {
        val target = openThingId?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val matched = allThings.firstOrNull { it.thingId == target }
        if (matched != null) {
            selectedThing = matched
            onThingDetailOpened(matched.thingId)
            onOpenThingHandled()
            return@LaunchedEffect
        }
        if (hasMoreThings && !isLoadingMoreThings) {
            loadMoreThingsIfNeeded()
        }
    }

    LaunchedEffect(allThings, selectedThing?.thingId) {
        val selectedId = selectedThing?.thingId ?: return@LaunchedEffect
        val latest = allThings.firstOrNull { it.thingId == selectedId }
        if (latest == null) {
            selectedThing = null
            onThingDetailClosed()
        } else if (latest != selectedThing) {
            selectedThing = latest
        }
    }

    if (selectedThing != null && !isSelectionMode) {
        PushGoModalBottomSheet(
            onDismissRequest = {
                selectedThing = null
                selectedRelatedMessage = null
                selectedRelatedEvent = null
                selectedRelatedUpdate = null
                onThingDetailClosed()
            },
        ) {
            ThingDetailSheet(
                thing = selectedThing!!,
                channelNameMap = channelNameMap,
                bottomGestureInset = bottomGestureInset,
                onOpenRelatedEvent = { selectedRelatedEvent = it },
                onOpenRelatedMessage = { selectedRelatedMessage = it },
                onOpenRelatedUpdate = { selectedRelatedUpdate = it },
                onDelete = { pendingDeleteThingId = selectedThing?.thingId },
            )
        }
    }

    if (selectedRelatedEvent != null) {
        PushGoModalBottomSheet(
            onDismissRequest = { selectedRelatedEvent = null },
        ) {
            EventDetailSheet(
                event = selectedRelatedEvent!!,
                bottomGestureInset = bottomGestureInset,
                onCloseEvent = {
                    val event = selectedRelatedEvent ?: return@EventDetailSheet
                    scope.launch {
                        val channelId = event.channelId.orEmpty().trim()
                        if (channelId.isEmpty()) {
                            showToast("$closeEventFailedMessage: missing channel_id")
                            return@launch
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
                            selectedRelatedEvent = null
                            reloadThingsInternal()
                        }.onFailure { error ->
                            showToast("$closeEventFailedMessage: ${error.message.orEmpty()}")
                        }
                    }
                },
                onDeleteEvent = {
                    val eventId = selectedRelatedEvent?.eventId ?: return@EventDetailSheet
                    scope.launch {
                        container.entityRepository.deleteEvent(eventId)
                        selectedRelatedEvent = null
                        reloadThingsInternal()
                    }
                },
            )
        }
    }

    if (selectedRelatedMessage != null) {
        PushGoModalBottomSheet(
            onDismissRequest = { selectedRelatedMessage = null },
        ) {
            ThingRelatedMessageDetailSheet(message = selectedRelatedMessage!!)
        }
    }

    if (selectedRelatedUpdate != null) {
        PushGoModalBottomSheet(
            onDismissRequest = { selectedRelatedUpdate = null },
        ) {
            ThingUpdateDetailSheet(update = selectedRelatedUpdate!!)
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
                                Text(text = stringResource(R.string.label_selected_count, selectedThingIds.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = { showBatchDeleteConfirmation = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
                                TextButton(onClick = { exitSelectionMode() }) { Text(stringResource(R.string.label_cancel)) }
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding), verticalAlignment = Alignment.CenterVertically) {
                                PushGoSearchBar(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholderText = stringResource(R.string.label_search_things),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box {
                                        var menuExpanded by remember { mutableStateOf(false) }
                                        IconButton(onClick = { menuExpanded = true }) {
                                            val active = channelFilter != null || showOnlyActive
                                            Icon(
                                                imageVector = if (active) Icons.Filled.FilledFilterList else Icons.Outlined.OutlinedFilterList,
                                                contentDescription = null,
                                                tint = if (active) uiColors.accentPrimary else uiColors.iconMuted
                                            )
                                        }
                                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.filter_active_things)) },
                                                onClick = { showOnlyActive = !showOnlyActive; menuExpanded = false },
                                                trailingIcon = { if (showOnlyActive) Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp)) }
                                            )
                                            if (channelOptions.isNotEmpty()) {
                                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                                channelOptions.forEach { channel ->
                                                    val displayName = channelNameMap[channel] ?: channel
                                                    DropdownMenuItem(
                                                        text = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                                    IconButton(onClick = { isSelectionMode = true; selectedThingIds = emptySet() }) { Icon(Icons.Outlined.Checklist, stringResource(R.string.action_batch_select), tint = uiColors.iconMuted) }
                                }
                            }
                        }
                    }
                    Text(text = stringResource(R.string.label_send_type_thing), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp), color = uiColors.textPrimary, modifier = Modifier.padding(start = ScreenHorizontalPadding, top = 8.dp, bottom = 12.dp).semantics { heading() })
                }
            }
            if (filteredThings.isEmpty()) {
                item {
                    AppEmptyState(
                        icon = if (searchQuery.isNotEmpty() || channelFilter != null || showOnlyActive) Icons.Default.Search else Icons.Outlined.Memory,
                        title = if (searchQuery.isNotEmpty() || channelFilter != null || showOnlyActive) stringResource(R.string.label_no_search_results) else stringResource(R.string.label_no_things_title),
                        description = if (searchQuery.isNotEmpty() || channelFilter != null || showOnlyActive) stringResource(R.string.message_list_empty_hint) else stringResource(R.string.label_no_things_hint),
                    )
                }
            } else {
                itemsIndexed(filteredThings, key = { _, item -> item.thingId }) { _, thing ->
                    ThingRow(
                        thing = thing,
                        onClick = {
                            if (isSelectionMode) {
                                toggleSelection(thing.thingId)
                            } else {
                                selectedThing = thing
                                onThingDetailOpened(thing.thingId)
                            }
                        },
                        selectionMode = isSelectionMode,
                        selected = selectedThingIds.contains(thing.thingId),
                        onToggleSelection = { toggleSelection(thing.thingId) },
                    )
                }
            }
            }
        }
    }

    if (showBatchDeleteConfirmation) {
        val batchDeleteToast = stringResource(R.string.message_deleted_selected_count, selectedThingIds.size)
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
                            showToast(batchDeleteToast)
                            reloadThingsInternal()
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

    pendingDeleteThingId?.let { targetThingId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteThingId = null },
            title = { Text(text = stringResource(R.string.action_delete)) },
            text = { Text(text = stringResource(R.string.confirm_delete_selected_things, 1)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteThingId = null
                        scope.launch { deleteThing(targetThingId) }
                    },
                ) {
                    Text(text = stringResource(R.string.label_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteThingId = null }) {
                    Text(text = stringResource(R.string.label_cancel))
                }
            },
        )
    }
}

@Composable
private fun ThingRow(
    thing: ThingCardModel,
    onClick: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    val metaSummary = remember(thing.attrsJson, thing.summary, thing.thingId) {
        val attrs = parseThingDisplayAttributes(thing.attrsJson)
        when {
            attrs.isNotEmpty() -> {
                val preview = attrs.take(3).joinToString(" · ") { "${it.displayLabel}: ${it.value}" }
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
    Box(
        modifier = Modifier
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
                            text = formatLocalRelativeTimeV2(context, thing.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = uiColors.textSecondary,
                        )
                    }
                    Text(
                        text = metaSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = uiColors.textSecondary,
                        maxLines = 2,
                    )
                }
            }
            if (thing.tags.isNotEmpty()) {
                Text(
                    text = thing.tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = uiColors.textSecondary,
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
                            color = uiColors.textSecondary,
                            modifier = Modifier.padding(start = 2.dp, top = 12.dp),
                        )
                    }
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
private fun ThingImageThumb(
    url: String?,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    onClick: ((String) -> Unit)? = null,
) {
    val uiColors = PushGoThemeExtras.colors
    val normalized = url?.trim().takeUnless { it.isNullOrEmpty() }?.takeIf(::isThingImageAttachmentUrl)
    if (normalized.isNullOrEmpty()) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = uiColors.fieldContainer,
            modifier = Modifier
                .height(size)
                .width(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = uiColors.iconMuted,
                    modifier = Modifier.size(size * 0.45f),
                )
            }
        }
        return
    }
    PushGoAsyncImage(
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
                },
            ),
    )
}

private fun formatLocalRelativeTimeV2(context: Context, instant: Instant): String {
    return DateUtils.getRelativeTimeSpanString(
        instant.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_ALL,
    ).toString()
}

private fun buildThingCardsInternal(messages: List<PushMessage>): List<ThingCardModel> {
    val grouped = messages
        .asSequence()
        .mapNotNull { message ->
            val thingId = message.thingId?.trim().takeIf { !it.isNullOrBlank() } ?: return@mapNotNull null
            thingId to message
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })

    return grouped.map { (thingId, thingMessagesRaw) ->
        val thingMessages = thingMessagesRaw.sortedBy { message ->
            happenedAtFromPayload(
                payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull(),
                fallback = message.receivedAt,
            )
        }
        val latestThingMessage = thingMessages.lastOrNull { it.entityType == "thing" } ?: thingMessages.last()
        val latestPayload = runCatching { JSONObject(latestThingMessage.rawPayloadJson) }.getOrNull()
        val profile = parseThingProfile(latestPayload?.optString("thing_profile_json"))
        val title = profile?.title ?: latestThingMessage.title.ifBlank { thingId }
        val summary = profile?.description ?: latestThingMessage.bodyPreview
        val state = profile?.state
            ?: profile?.status
            ?: latestPayload?.optString("thing_state")?.trim()?.takeIf { it.isNotEmpty() }
            ?: latestPayload?.optString("state")?.trim()?.takeIf { it.isNotEmpty() }
            ?: latestThingMessage.eventState
        val createdAt = profile?.createdAt?.takeIf { it > 0L }?.let(::epochToInstant)
        val imageUrl = profile?.imageUrl ?: latestThingMessage.imageUrl
        val imageUrls = linkedSetOf<String>().apply {
            if (!imageUrl.isNullOrBlank()) add(imageUrl)
            profile?.imageUrls?.forEach { if (it.isNotBlank()) add(it) }
            latestThingMessage.imageUrls.forEach { if (it.isNotBlank()) add(it) }
        }.toList()
        val tags = if (profile?.tags?.isNotEmpty() == true) profile.tags else latestThingMessage.tags

        val attrs = linkedMapOf<String, Any?>()
        thingMessages.forEach { message ->
            val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull() ?: return@forEach
            payload.optString("thing_attrs_json")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { raw ->
                    val parsed = parseJsonObjectOrNull(raw)
                    if (parsed != null) {
                        attrs.clear()
                        parsed.forEach { (key, value) ->
                            if (value != null) attrs[key] = value
                        }
                    }
                }
            payload.optString("event_attrs_json")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { raw ->
                    parseJsonObjectOrNull(raw)?.forEach { (key, value) ->
                        if (value == null) {
                            attrs.remove(key)
                        } else {
                            attrs[key] = value
                        }
                    }
                }
        }
        val attrsJson = if (attrs.isEmpty()) null else JSONObject(attrs as Map<*, *>).toString(2)

        val metadata = linkedMapOf<String, String>()
        thingMessages.forEach { message ->
            message.metadata.forEach { (key, value) ->
                val normalizedKey = key.trim()
                val normalizedValue = value.trim()
                if (normalizedKey.isNotEmpty() && normalizedValue.isNotEmpty()) {
                    metadata[normalizedKey] = normalizedValue
                }
            }
        }
        val metadataJson = if (metadata.isEmpty()) null else JSONObject(metadata as Map<*, *>).toString(2)

        var channelId: String? = null
        thingMessages.forEach { message ->
            val value = message.channel?.trim()?.takeIf { it.isNotEmpty() }
            if (value != null) channelId = value
        }
        val updatedAt = thingMessages.lastOrNull()?.receivedAt ?: Instant.EPOCH

        ThingCardModel(
            thingId = thingId,
            title = title,
            summary = summary,
            state = state,
            channelId = channelId,
            imageUrl = imageUrl,
            tags = tags,
            createdAt = createdAt,
            updatedAt = updatedAt,
            imageUrls = imageUrls,
            attrsJson = attrsJson,
            metadataJson = metadataJson,
            relatedEvents = buildThingRelatedEventCards(thingMessages, thingId),
            relatedMessages = thingMessages
                .asSequence()
                .filter { it.entityType == "message" }
                .map { message ->
                    ThingRelatedMessage(
                        message = message,
                        happenedAt = happenedAtFromPayload(
                            payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull(),
                            fallback = message.receivedAt,
                        ),
                    )
                }
                .distinctBy { it.message.messageId ?: it.message.id }
                .sortedByDescending { it.happenedAt }
                .toList(),
            relatedUpdates = thingMessages
                .asSequence()
                .filter { it.entityType == "thing" }
                .map { message ->
                    val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
                    ThingRelatedUpdate(
                        updateId = message.id.trim().ifEmpty { "${thingId}:${message.receivedAt.toEpochMilli()}" },
                        title = message.title.ifBlank { thingId },
                        summary = message.body.trim().takeIf { it.isNotEmpty() },
                        state = payload?.optString("thing_state")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: payload?.optString("state")?.trim()?.takeIf { it.isNotEmpty() },
                        happenedAt = happenedAtFromPayload(payload = payload, fallback = message.receivedAt),
                        attrsJson = payload?.optString("thing_attrs_json")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: payload?.optString("event_attrs_json")
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() },
                    )
                }
                .distinctBy { it.updateId }
                .sortedByDescending { it.happenedAt }
                .toList(),
        )
    }.sortedByDescending { it.updatedAt }
}

private fun mergeThingCardsInternal(existing: List<ThingCardModel>, incoming: List<ThingCardModel>): List<ThingCardModel> {
    val map = existing.associateBy { it.thingId }.toMutableMap()
    incoming.forEach { map[it.thingId] = it }
    return map.values.sortedByDescending { it.updatedAt }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThingDetailSheet(
    thing: ThingCardModel,
    channelNameMap: Map<String, String>,
    bottomGestureInset: Dp,
    onOpenRelatedEvent: (EventCardModel) -> Unit,
    onOpenRelatedMessage: (ThingRelatedMessage) -> Unit,
    onOpenRelatedUpdate: (ThingRelatedUpdate) -> Unit,
    onDelete: () -> Unit,
) {
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    var showMetadataSheet by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(ThingDetailTab.Events) }
    val uiColors = PushGoThemeExtras.colors
    val attrsEntries = remember(thing.attrsJson) { parseThingDisplayAttributes(thing.attrsJson) }
    val metadataEntries = remember(thing.metadataJson) { parseThingDisplayAttributes(thing.metadataJson) }

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
                    ThingImageThumb(
                        url = thing.imageUrl,
                        size = 84.dp,
                        onClick = { url -> previewImageUrl = url },
                    )
                    IconButton(
                        modifier = Modifier.size(32.dp),
                        onClick = onDelete,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = thing.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f),
                    )
                    val lifecycleState = ThingLifecycleState.fromRaw(thing.state)
                    if (lifecycleState == ThingLifecycleState.Archived || lifecycleState == ThingLifecycleState.Deleted) {
                        ThingStateBadge(state = thing.state)
                    }
                }

                thing.channelId?.let { channelId ->
                    val displayName = channelNameMap[channelId] ?: channelId
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = uiColors.textSecondary,
                    )
                }

                Text(
                    text = stringResource(
                        R.string.thing_detail_created_updated,
                        thing.createdAt?.let(ThingTimeFormatter::format) ?: "-",
                        ThingTimeFormatter.format(thing.updatedAt),
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = uiColors.textSecondary,
                )

                if (thing.tags.isNotEmpty()) {
                    Text(
                        text = thing.tags.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = uiColors.textSecondary,
                    )
                }

                if (!thing.summary.isNullOrBlank()) {
                    Text(
                        text = thing.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp,
                    )
                }

                if (metadataEntries.isNotEmpty()) {
                    TextButton(
                        onClick = { showMetadataSheet = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .height(32.dp)
                            .background(
                                color = uiColors.fieldContainer.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(18.dp),
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.thing_detail_metadata_button),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                if (attrsEntries.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.thing_detail_attributes),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = uiColors.textSecondary,
                    )
                    EntityKeyValueRows(entries = attrsEntries)
                }
            }
        }

        if (thing.imageUrls.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                thing.imageUrls.drop(1).forEach { url ->
                    ThingImageThumb(url = url, size = 88.dp, onClick = { previewImageUrl = it })
                }
            }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selectedTab == ThingDetailTab.Events,
                onClick = { selectedTab = ThingDetailTab.Events },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                modifier = Modifier.weight(1f),
                icon = {},
            ) {
                Text(stringResource(R.string.thing_detail_tab_events))
            }
            SegmentedButton(
                selected = selectedTab == ThingDetailTab.Messages,
                onClick = { selectedTab = ThingDetailTab.Messages },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                modifier = Modifier.weight(1f),
                icon = {},
            ) {
                Text(stringResource(R.string.thing_detail_tab_messages))
            }
            SegmentedButton(
                selected = selectedTab == ThingDetailTab.Updates,
                onClick = { selectedTab = ThingDetailTab.Updates },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                modifier = Modifier.weight(1f),
                icon = {},
            ) {
                Text(stringResource(R.string.thing_detail_tab_updates))
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = uiColors.surfaceBase,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(0.8.dp, uiColors.dividerStrong.copy(alpha = 0.35f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                                .forEach { related ->
                                    val rowMessage = related.message
                                    val nowInstant = Instant.now()
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenRelatedMessage(related) }
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
                                            bodyPreview = rowMessage.bodyPreview.orEmpty(),
                                        )
                                        PushGoDividerSubtle(
                                            modifier = Modifier.padding(top = 10.dp),
                                            thickness = 0.5.dp,
                                            color = uiColors.dividerSubtle.copy(alpha = 0.7f),
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
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenRelatedUpdate(update) }
                                        .padding(vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Text(
                                        text = update.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!update.summary.isNullOrBlank()) {
                                        Text(
                                            text = update.summary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = ThingTimeFormatter.format(update.happenedAt),
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (!update.state.isNullOrBlank()) {
                                            ThingStateBadge(state = update.state)
                                        }
                                    }
                                    PushGoDividerSubtle(
                                        modifier = Modifier.padding(top = 8.dp),
                                        thickness = 0.5.dp,
                                        color = uiColors.dividerSubtle.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (previewImageUrl != null) {
        ZoomableImagePreviewDialog(
            model = previewImageUrl,
            onDismiss = { previewImageUrl = null },
        )
    }
    if (showMetadataSheet) {
        PushGoModalBottomSheet(onDismissRequest = { showMetadataSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = bottomGestureInset + 24.dp),
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
    val copiedMessage = stringResource(R.string.message_text_copied)
    val bottomGestureInset = rememberBottomGestureInset()
    val timeText = remember(current.receivedAt) {
        ThingTimeFormatter.format(current.receivedAt)
    }
    val resolvedBody = remember(current.rawPayloadJson, current.body) {
        io.ethan.pushgo.markdown.MessageBodyResolver.resolve(current.rawPayloadJson, current.body)
    }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    MessageDetailCoreContent(
        message = current,
        timeText = timeText,
        imageModels = current.imageUrls.map { it as Any },
        resolvedBodyText = resolvedBody.rawText,
        bottomGestureInset = bottomGestureInset,
        onDelete = null,
        onCopyText = { text ->
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        },
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
                lineHeight = 22.sp,
            )
        }

        if (attrsEntries.isNotEmpty()) {
            EntityKeyValueRows(entries = attrsEntries)
        }
    }
}

@Composable
private fun EntityKeyValueRows(entries: List<ThingDisplayAttribute>) {
    val uiColors = PushGoThemeExtras.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = uiColors.fieldContainer.copy(alpha = 0.95f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.8.dp, uiColors.dividerSubtle.copy(alpha = 0.9f)),
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
                        text = entry.displayLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
                        color = uiColors.textSecondary,
                        modifier = Modifier.width(100.dp),
                    )
                    Text(
                        text = entry.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (index < entries.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = uiColors.dividerSubtle.copy(alpha = 0.72f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}

private enum class ThingDetailTab(val labelRes: Int) {
    Events(R.string.thing_detail_tab_events),
    Messages(R.string.thing_detail_tab_messages),
    Updates(R.string.thing_detail_tab_updates),
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

private fun normalizedThingState(raw: String?): String {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isEmpty()) return "unknown"
    return normalized.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
}

private fun thingStateTint(raw: String?): Color {
    return when (ThingLifecycleState.fromRaw(raw)) {
        ThingLifecycleState.Active -> Color(0xFF2563EB)
        ThingLifecycleState.Archived -> Color(0xFF6B7280)
        ThingLifecycleState.Deleted -> Color(0xFFB91C1C)
        ThingLifecycleState.Unknown -> Color(0xFF6B7280)
    }
}

private fun buildThingRelatedEventCards(
    messages: List<PushMessage>,
    thingId: String,
): List<EventCardModel> {
    return messages
        .asSequence()
        .filter { message ->
            message.thingId?.trim() == thingId &&
                (message.entityType == "event" || !message.eventId.isNullOrBlank())
        }
        .mapNotNull { message ->
            val eventId = message.eventId?.trim().orEmpty()
            if (eventId.isEmpty()) return@mapNotNull null
            val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
            val profile = parseEventProfile(payload?.optString("event_profile_json"))
            val happenedAt = happenedAtFromPayload(payload, message.receivedAt)
            EventCardModel(
                eventId = eventId,
                title = profile?.title ?: message.title.ifBlank { eventId },
                summary = profile?.description ?: message.bodyPreview,
                status = profile?.status ?: message.eventState,
                message = profile?.message ?: message.body.takeIf { it.isNotBlank() },
                imageUrl = profile?.imageUrl ?: message.imageUrl,
                severity = EventSeverity.fromRaw(profile?.severity ?: message.severity?.name),
                tags = if (profile?.tags?.isNotEmpty() == true) profile.tags else message.tags,
                state = EventLifecycleState.fromRaw(message.eventState),
                thingId = message.thingId,
                channelId = message.channel,
                attachmentUrls = linkedSetOf<String>().apply {
                    profile?.imageUrls?.forEach { if (it.isNotBlank()) add(it) }
                    message.imageUrls.forEach { if (it.isNotBlank()) add(it) }
                }.toList(),
                attrsJson = payload?.optString("event_attrs_json")?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = happenedAt,
                timeline = emptyList(),
            )
        }
        .groupBy { it.eventId }
        .mapNotNull { (_, versions) -> versions.maxByOrNull { it.updatedAt } }
        .sortedByDescending { it.updatedAt }
        .toList()
}

private fun parseJsonObjectOrNull(raw: String): Map<String, Any?>? {
    val objectValue = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val result = linkedMapOf<String, Any?>()
    val iterator = objectValue.keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        val value = objectValue.opt(key)
        result[key] = if (value == JSONObject.NULL) null else value
    }
    return result
}

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

private fun epochToInstant(raw: Long): Instant {
    return if (raw > 10_000_000_000L) {
        Instant.ofEpochMilli(raw)
    } else {
        Instant.ofEpochSecond(raw)
    }
}
