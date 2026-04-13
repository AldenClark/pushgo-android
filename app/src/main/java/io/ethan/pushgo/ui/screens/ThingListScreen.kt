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
import coil.compose.AsyncImage
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

data class ThingDisplayAttribute(val label: String, val value: String)

private fun parseThingDisplayAttributes(json: String?): List<ThingDisplayAttribute> {
    if (json.isNullOrBlank()) return emptyList()
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    return obj.keys().asSequence().map { key -> ThingDisplayAttribute(key, obj.optString(key)) }.toList()
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
    
    val context = LocalContext.current
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
            (channelFilter == null || thing.relatedMessages.any { it.message.channel == channelFilter }) &&
            (!showOnlyActive || (thing.state != "archived" && thing.state != "deleted"))
        }
    }

    val channelOptions = remember(allThings) {
        allThings.flatMap { it.relatedMessages.mapNotNull { m -> m.message.channel } }
            .distinct()
            .sorted()
    }

    if (selectedThing != null && !isSelectionMode) {
        PushGoModalBottomSheet(
            onDismissRequest = { selectedThing = null; onThingDetailClosed() },
        ) {
            ThingDetailSheet(
                thing = selectedThing!!,
                channelNameMap = channelNameMap,
                bottomGestureInset = bottomGestureInset,
                onOpenRelatedEvent = { selectedRelatedEvent = it },
                onOpenRelatedMessage = { selectedRelatedMessage = it },
                onOpenRelatedUpdate = { selectedRelatedUpdate = it },
                onDelete = { /* TODO */ }
            )
        }
    }

    if (selectedRelatedEvent != null) {
        PushGoModalBottomSheet(
            onDismissRequest = { selectedRelatedEvent = null },
        ) {
            Text(
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = bottomGestureInset + 24.dp),
                text = selectedRelatedEvent!!.title,
                style = MaterialTheme.typography.headlineSmall,
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                items(items = filteredThings, key = { it.thingId }) { thing ->
                    ThingRow(thing = thing, onClick = { if (isSelectionMode) toggleSelection(thing.thingId) else { selectedThing = thing; onThingDetailOpened(thing.thingId) } }, selectionMode = isSelectionMode, selected = selectedThingIds.contains(thing.thingId), onToggleSelection = { toggleSelection(thing.thingId) })
                }
            }
            }
        }
    }
}

@Composable
private fun ThingRow(thing: ThingCardModel, onClick: () -> Unit, selectionMode: Boolean, selected: Boolean, onToggleSelection: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    Row(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { if (!selectionMode) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggleSelection() } }).background(if (selected) uiColors.selectedRowFill else uiColors.surfaceBase).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selectionMode) { PushGoSelectionIndicator(selected = selected, onClick = onToggleSelection) }
        ThingImageThumb(url = thing.imageUrl, size = 52.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = thing.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(text = formatLocalRelativeTimeV2(context, thing.updatedAt), style = MaterialTheme.typography.labelSmall, color = uiColors.textSecondary)
            }
            if (!thing.summary.isNullOrBlank()) Text(text = thing.summary, style = MaterialTheme.typography.bodySmall, color = uiColors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ThingImageThumb(url: String?, size: androidx.compose.ui.unit.Dp, onClick: ((String) -> Unit)? = null) {
    val uiColors = PushGoThemeExtras.colors
    val m = Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(uiColors.fieldContainer).then(if (url != null && onClick != null) Modifier.clickable { onClick(url) } else Modifier)
    if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = m)
    else Box(modifier = m, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Memory, null, tint = uiColors.iconMuted, modifier = Modifier.size(size * 0.5f)) }
}

private fun formatLocalRelativeTimeV2(context: Context, instant: Instant): String {
    return DateUtils.getRelativeTimeSpanString(instant.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL).toString()
}

private fun buildThingCardsInternal(messages: List<PushMessage>): List<ThingCardModel> {
    return messages.filter { it.entityType == "thing" }.map { m ->
        val profile = io.ethan.pushgo.data.parseThingProfile(m.rawPayloadJson)
        ThingCardModel(
            thingId = m.thingId ?: m.id,
            title = profile?.title ?: m.title,
            summary = profile?.description ?: m.bodyPreview,
            state = profile?.state ?: m.eventState,
            imageUrl = profile?.imageUrl ?: m.imageUrl,
            tags = profile?.tags ?: m.tags,
            createdAt = profile?.createdAt?.let { Instant.ofEpochMilli(it) },
            updatedAt = m.receivedAt,
            imageUrls = profile?.imageUrls ?: m.imageUrls,
            attrsJson = m.rawPayloadJson,
            metadataJson = null,
            relatedEvents = emptyList(),
            relatedMessages = emptyList(),
            relatedUpdates = emptyList()
        )
    }
}

@Composable
private fun EntityKeyValueRows(entries: List<ThingDisplayAttribute>) {
    val uiColors = PushGoThemeExtras.colors
    Surface(modifier = Modifier.fillMaxWidth(), color = uiColors.surfaceSunken, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.5.dp, uiColors.dividerStrong)) {
        Column {
            entries.forEachIndexed { index, entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    Text(text = entry.label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp), color = uiColors.stateInfo.foreground, modifier = Modifier.width(100.dp))
                    Text(text = entry.value, style = MaterialTheme.typography.bodyMedium, color = uiColors.textPrimary, modifier = Modifier.weight(1f))
                }
                if (index < entries.lastIndex) PushGoDividerSubtle(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp)
            }
        }
    }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = bottomGestureInset + 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val uiColors = PushGoThemeExtras.colors
        Text(text = thing.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        val attrs = remember(thing.attrsJson) { parseThingDisplayAttributes(thing.attrsJson) }
        if (attrs.isNotEmpty()) EntityKeyValueRows(entries = attrs)
        Text(text = "Updated: ${ThingTimeFormatter.format(thing.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = uiColors.textSecondary)
    }
}

@Composable
private fun ThingRelatedMessageDetailSheet(message: ThingRelatedMessage) { /* detail */ }
@Composable
private fun ThingUpdateDetailSheet(update: ThingRelatedUpdate) { /* detail */ }

private enum class ThingDetailTab(val labelRes: Int) {
    Events(R.string.thing_detail_tab_events),
    Messages(R.string.thing_detail_tab_messages),
    Updates(R.string.thing_detail_tab_updates),
}

@Composable
private fun ThingStateBadge(state: String?) { /* badge */ }
