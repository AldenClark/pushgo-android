package io.ethan.pushgo.ui.screens

import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.SelectAll
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FilterList as FilledFilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList as OutlinedFilterList
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.ethan.pushgo.R
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.model.MessageChannelCount
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.data.model.MessageSeverity
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.viewmodel.MessageListViewModel
import io.ethan.pushgo.ui.viewmodel.MessageSearchViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.CheckCircle
import io.ethan.pushgo.data.model.ReadFilter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton

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
    val filterState by viewModel.filterState.collectAsState()
    val channelCounts by viewModel.channelCounts.collectAsState()
    val query by searchViewModel.queryState.collectAsState()
    val searchResults by searchViewModel.results.collectAsState()
    val context = LocalContext.current
    val appContext = context.applicationContext
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
        selectedMessageIds = emptySet<String>()
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

    fun selectIfNeeded(messageId: String) {
        if (!selectedMessageIds.contains(messageId)) {
            selectedMessageIds = selectedMessageIds + messageId
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    fun messageById(messageId: String): PushMessage? {
        return if (query.isBlank()) {
            messages.itemSnapshotList.items.firstOrNull { it.id == messageId }
        } else {
            searchResults.firstOrNull { it.id == messageId }
        }
    }

    fun messageIdForVisibleItemIndex(itemIndex: Int): String? {
        val rowIndex = itemIndex - 1
        if (rowIndex < 0) return null
        return if (query.isBlank()) {
            if (rowIndex < messages.itemCount) {
                messages.peek(rowIndex)?.id
            } else {
                null
            }
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
                selectedMessageIds = if (targetState) {
                    selectedMessageIds + messageId
                } else {
                    selectedMessageIds - messageId
                }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    suspend fun deleteSelectedMessages() {
        val ids = selectedMessageIds.toList()
        if (ids.isEmpty()) return
        ids.forEach { messageId ->
            container.messageStateCoordinator.deleteMessage(messageId)
        }
        announceForAccessibility(
            context,
            appContext.getString(R.string.message_deleted_selected_count, ids.size),
        )
        exitSelectionMode()
    }

    suspend fun markSelectedMessagesRead() {
        val unreadIds = selectedMessageIds.filter { id ->
            messageById(id)?.isRead == false
        }
        if (unreadIds.isEmpty()) return
        unreadIds.forEach { messageId ->
            viewModel.markRead(messageId)
        }
        announceForAccessibility(
            context,
            appContext.getString(R.string.message_marked_read_selected_count, unreadIds.size),
        )
        exitSelectionMode()
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

    LaunchedEffect(Unit) {
        channelNameMap = container.channelRepository.loadSubscriptionLookup(includeDeleted = true)
        delay(180)
        viewModel.enableChannelCounts()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    channelNameMap = container.channelRepository.loadSubscriptionLookup(includeDeleted = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val unreadOnlySelected = filterState.readFilter == ReadFilter.UNREAD
    val selectedHasUnread = selectedMessageIds.any { id -> messageById(id)?.isRead == false }
    val channelOptions = remember(channelCounts) {
        channelCounts.map { it.channel.trim() }.distinct()
    }

    LaunchedEffect(query, searchResults.size) {
        PushGoAutomation.writeEvent(
            type = "search.results_updated",
            command = null,
            details = org.json.JSONObject()
                .put("search_query", query)
                .put("result_count", searchResults.size),
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .onGloballyPositioned { coordinates ->
                    listTopInWindow = coordinates.positionInWindow().y
                },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isSelectionMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ScreenHorizontalPadding, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.label_selected_count, selectedMessageIds.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            enabled = selectedHasUnread,
                            onClick = {
                                scope.launch { markSelectedMessagesRead() }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MarkEmailRead,
                                contentDescription = stringResource(R.string.action_mark_read),
                            )
                        }
                        IconButton(
                            enabled = selectedMessageIds.isNotEmpty(),
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
                        var searchMenuExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Spacer(modifier = Modifier.width(16.dp))
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            TextField(
                                value = query,
                                onValueChange = searchViewModel::updateQuery,
                                placeholder = {
                                    Text(
                                        stringResource(R.string.label_search),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("field.message.search"),
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
                                IconButton(
                                    modifier = Modifier.testTag("action.message.list.channel_filter"),
                                    onClick = { searchMenuExpanded = true },
                                ) {
                                    val hasActiveFilter = filterState.channel != null || filterState.readFilter == ReadFilter.UNREAD
                                    Icon(
                                        imageVector = if (!hasActiveFilter) Icons.Outlined.OutlinedFilterList else Icons.Filled.FilledFilterList,
                                        contentDescription = stringResource(R.string.label_channel_id),
                                        tint = if (hasActiveFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }

                                DropdownMenu(
                                    expanded = searchMenuExpanded,
                                    onDismissRequest = { searchMenuExpanded = false },
                                    modifier = Modifier.width(200.dp)
                                ) {
                                    // Toggle Status: Unread
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.filter_unread)) },
                                        onClick = {
                                            val newFilter = if (filterState.readFilter == ReadFilter.UNREAD) ReadFilter.ALL else ReadFilter.UNREAD
                                            viewModel.setReadFilter(newFilter)
                                            searchMenuExpanded = false
                                        },
                                        trailingIcon = {
                                            if (filterState.readFilter == ReadFilter.UNREAD) {
                                                Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    )

                                    if (channelOptions.isNotEmpty()) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                        val ungroupedLabel = stringResource(R.string.label_group_ungrouped)
                                        channelOptions.forEach { channel ->
                                            val displayName = if (channel.isBlank()) {
                                                ungroupedLabel
                                            } else {
                                                channelNameMap[channel] ?: channel
                                            }
                                            DropdownMenuItem(
                                                text = { Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                onClick = {
                                                    val newChannel = if (filterState.channel == channel) null else channel
                                                    viewModel.setChannel(newChannel)
                                                    searchMenuExpanded = false
                                                },
                                                trailingIcon = {
                                                    if (filterState.channel == channel) {
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
                                    selectedMessageIds = emptySet()
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
                    text = stringResource(R.string.tab_messages),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .padding(start = ScreenHorizontalPadding, top = 8.dp, bottom = 12.dp)
                        .semantics { heading() },
                )

            }
        }

        if (query.isBlank() && messages.itemCount == 0 || query.isNotBlank() && searchResults.isEmpty()) {
            item {
                if (query.isBlank()) {
                    AppEmptyState(
                        icon = Icons.Outlined.Email,
                        title = stringResource(R.string.message_list_empty_title),
                        description = stringResource(R.string.message_list_empty_hint),
                    )
                } else {
                    AppEmptyState(
                        icon = Icons.Default.Search,
                        title = stringResource(R.string.label_no_search_results),
                        description = stringResource(R.string.message_list_empty_hint),
                    )
                }
            }
        } else {
            if (query.isBlank()) {
                items(count = messages.itemCount, key = { index -> 
                    val item = messages.peek(index)
                    item?.id ?: index
                }) { index ->
                    val message = messages[index]
                    if (message != null) {
                        val listImageModels = remember(message.rawPayloadJson) {
                            container.messageImageStore.resolveListImageModels(
                                rawPayloadJson = message.rawPayloadJson,
                                maxItems = MessageListImagePreviewMaxItems,
                            )
                        }
                        MessageRow(
                            modifier = Modifier.testTag("message.row.${message.id}"),
                            message = message,
                            imageModels = listImageModels,
                            onClick = {
                                if (isSelectionMode) {
                                    toggleSelection(message.id)
                                } else {
                                    onMessageClick(message.id)
                                }
                            },
                            onMarkRead = { viewModel.markRead(message.id) },
                            onDelete = { viewModel.deleteMessage(message.id) },
                            selectionMode = isSelectionMode,
                            selected = selectedMessageIds.contains(message.id),
                            onToggleSelection = { toggleSelection(message.id) },
                        )
                    }
                }
            } else {
                items(searchResults, key = { it.id }) { message ->
                    val listImageModels = remember(message.rawPayloadJson) {
                        container.messageImageStore.resolveListImageModels(
                            rawPayloadJson = message.rawPayloadJson,
                            maxItems = MessageListImagePreviewMaxItems,
                        )
                    }
                    MessageRow(
                        modifier = Modifier.testTag("message.row.${message.id}"),
                        message = message,
                        imageModels = listImageModels,
                        onClick = {
                            if (isSelectionMode) {
                                toggleSelection(message.id)
                            } else {
                                onMessageClick(message.id)
                            }
                        },
                        onMarkRead = { viewModel.markRead(message.id) },
                        onDelete = { viewModel.deleteMessage(message.id) },
                        selectionMode = isSelectionMode,
                        selected = selectedMessageIds.contains(message.id),
                        onToggleSelection = { toggleSelection(message.id) },
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
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
                    .pointerInput(query, searchResults.size, messages.itemCount, listTopInWindow, selectionRailTopInWindow) {
                        detectDragGestures(
                            onDragStart = { point ->
                                val listLocalY = point.y + (selectionRailTopInWindow - listTopInWindow)
                                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    listLocalY in item.offset.toFloat()..(item.offset + item.size).toFloat()
                                }
                                val rowIndex = target?.index
                                val messageId = rowIndex?.let { index -> messageIdForVisibleItemIndex(index) }
                                if (messageId != null) {
                                    val isSelected = selectedMessageIds.contains(messageId)
                                    initialSelectionStateForDrag = !isSelected
                                    updateSelectionAtRailY(point.y, !isSelected)
                                }
                            },
                            onDrag = { change, _ ->
                                initialSelectionStateForDrag?.let { targetState ->
                                    updateSelectionAtRailY(change.position.y, targetState)
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
            text = { Text(text = stringResource(R.string.confirm_delete_selected_messages, selectedMessageIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchDeleteConfirmation = false
                        scope.launch { deleteSelectedMessages() }
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

@Composable
private fun TechFilterChip(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .clickable(onClick = onClick)
            .then(
                if (!selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.0f))
                    .background(Color.Transparent)
                else Modifier
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
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
private fun MessageSelectionToggle(
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
    val haptic = LocalHapticFeedback.current
    val hasMarkReadAction = !message.isRead
    val actionWidth = if (hasMarkReadAction) 140.dp else 72.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }

    val appName = stringResource(R.string.app_name)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val timeText = remember(message.receivedAt, configuration) {
        formatMessageTime(context, message.receivedAt, ZoneId.systemDefault())
    }
    
    val bodyPreview = remember(message.bodyPreview) {
        message.bodyPreview?.trim().orEmpty()
    }
    val unreadStateLabel = stringResource(R.string.filter_unread)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        if (!selectionMode) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(end = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasMarkReadAction) {
                    Box(
                        modifier = Modifier
                            .testTag("action.message.row.${message.id}.mark_read")
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable {
                                offsetX = 0f
                                onMarkRead()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MarkEmailRead,
                            contentDescription = stringResource(R.string.action_mark_read),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Box(
                    modifier = Modifier
                        .testTag("action.message.row.${message.id}.delete")
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable {
                            offsetX = 0f
                            onDelete()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                )
                .testTag("message.row.content.${message.id}")
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
                .semantics(mergeDescendants = true) {
                    if (!message.isRead) {
                        stateDescription = unreadStateLabel
                    }
                }
                .then(
                    if (selectionMode) {
                        Modifier
                    } else {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                offsetX = (offsetX + delta).coerceIn(-actionWidthPx, 0f)
                            },
                            onDragStopped = {
                                offsetX = if (offsetX < -actionWidthPx / 2) -actionWidthPx else 0f
                            }
                        )
                    }
                )
                .padding(horizontal = ScreenHorizontalPadding, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectionMode) {
                    MessageSelectionToggle(
                        selected = selected,
                        onToggle = onToggleSelection,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    MessageRowContent(
                        message = message,
                        imageModels = imageModels,
                        appName = appName,
                        timeText = timeText,
                        bodyPreview = bodyPreview,
                    )
                }
            }
        }
    }

    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
fun MessageRowContent(
    message: PushMessage,
    imageModels: List<Any>,
    appName: String,
    timeText: String,
    bodyPreview: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = message.title.ifBlank { appName },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (message.isRead) FontWeight.SemiBold else FontWeight.ExtraBold
                    ),
                    color = if (message.isRead) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
                MessageSeverityListBadge(severity = message.severity)
            }
        }

        Spacer(modifier = Modifier.width(8.dp))
        if (!message.isRead) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val hasBodyText = bodyPreview.isNotBlank()
    val hasImages = imageModels.isNotEmpty()
    val hasTags = message.tags.isNotEmpty()
    if (hasBodyText || hasImages || hasTags) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
        ) {
            if (hasBodyText) {
                Text(
                    text = bodyPreview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Clip,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hasTags) {
                Text(
                    text = message.tags.joinToString(separator = " · "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hasImages) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    imageModels.forEach { imageModel ->
                        AsyncImage(
                            model = imageModel,
                            contentDescription = stringResource(R.string.label_image_attachment),
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageSeverityListBadge(severity: MessageSeverity?) {
    val label = when (severity) {
        MessageSeverity.HIGH -> stringResource(R.string.message_severity_high_compact)
        MessageSeverity.CRITICAL -> stringResource(R.string.message_severity_critical_compact)
        else -> return
    }
    val bgColor = when (severity) {
        MessageSeverity.HIGH -> Color(0xFFF59E0B).copy(alpha = 0.14f)
        MessageSeverity.CRITICAL -> Color(0xFFDC2626).copy(alpha = 0.16f)
    }
    val fgColor = when (severity) {
        MessageSeverity.HIGH -> Color(0xFFB45309)
        MessageSeverity.CRITICAL -> Color(0xFFB91C1C)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = fgColor,
            maxLines = 1,
        )
    }
}

internal fun formatMessageTime(
    context: Context,
    receivedAt: Instant,
    zoneId: ZoneId,
    nowInstant: Instant = Instant.now(),
): String {
    val received = receivedAt.atZone(zoneId)
    val now = nowInstant.atZone(zoneId)
    val dayDelta = ChronoUnit.DAYS.between(received.toLocalDate(), now.toLocalDate())
    val locale = localeFrom(context)

    if (dayDelta == 0L) {
        val formatter = DateFormat.getTimeFormat(context)
        return formatter.format(java.util.Date.from(receivedAt))
    }
    if (dayDelta == 1L || dayDelta == 2L) {
        return DateUtils.getRelativeTimeSpanString(
            receivedAt.toEpochMilli(),
            nowInstant.toEpochMilli(),
            DateUtils.DAY_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }
    if (dayDelta in 3L..6L) {
        return DateTimeFormatter
            .ofPattern(DateFormat.getBestDateTimePattern(locale, "EEE"), locale)
            .format(received)
    }
    if (received.year == now.year) {
        return DateTimeFormatter
            .ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
            .format(received)
    }
    return DateTimeFormatter
        .ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale)
        .format(received)
}

private fun localeFrom(context: Context): Locale {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.resources.configuration.locales.get(0)
    } else {
        @Suppress("DEPRECATION")
        context.resources.configuration.locale
    }
}
