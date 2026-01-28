package io.ethan.pushgo.ui.screens

import androidx.compose.animation.core.Animatable
import android.animation.ValueAnimator
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.data.model.MessageChannelCount
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MarkdownRenderPayloadSizing
import io.ethan.pushgo.markdown.MessageBodyResolver
import io.ethan.pushgo.markdown.extractMarkdownRenderPayload
import io.ethan.pushgo.ui.MessagePayloadUtils
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.export.ExportUtils
import io.ethan.pushgo.ui.markdown.MarkdownRenderPayloadText
import io.ethan.pushgo.ui.viewmodel.MessageListViewModel
import io.ethan.pushgo.ui.viewmodel.MessageSearchViewModel
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils

@Composable
fun MessageListScreen(
    navController: NavHostController,
    container: AppContainer,
    factory: PushGoViewModelFactory,
    onMessageClick: (String) -> Unit,
) {
    val viewModel: MessageListViewModel = viewModel(factory = factory)
    val searchViewModel: MessageSearchViewModel = viewModel(factory = factory)
    val messages = viewModel.messages.collectAsLazyPagingItems()
    val filterState by viewModel.filterState.collectAsState()
    val channelCounts by viewModel.channelCounts.collectAsState()
    val query by searchViewModel.queryState.collectAsState()
    val searchResults by searchViewModel.results.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var channelNameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val noMessagesExportText = stringResource(R.string.no_messages_available_to_export)
    val exportSuccessText = stringResource(R.string.message_json_exported_successfully)
    var showChannelCleanupConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        channelNameMap = container.channelRepository.loadSubscriptionLookup(includeDeleted = true)
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

    val totalUnread = remember(channelCounts) { channelCounts.sumOf { it.unreadCount } }
    val totalCount = remember(channelCounts) { channelCounts.sumOf { it.totalCount } }
    val hasChannels = remember(channelCounts) { channelCounts.any { it.channel.isNotBlank() } }
    var showFilters by remember { mutableStateOf(true) }
    val canExport = if (query.isBlank()) {
        messages.itemSnapshotList.items.isNotEmpty()
    } else {
        searchResults.isNotEmpty()
    }
    val hasListContent = if (query.isBlank()) {
        messages.itemSnapshotList.items.isNotEmpty()
    } else {
        searchResults.isNotEmpty()
    }
    val readCount = remember(channelCounts, totalCount, totalUnread, filterState.channel) {
        val channel = filterState.channel
        if (channel == null) {
            (totalCount - totalUnread).coerceAtLeast(0)
        } else {
            val normalized = channel.trim()
            val count = channelCounts.firstOrNull { it.channel == normalized }
            val total = count?.totalCount ?: 0
            val unread = count?.unreadCount ?: 0
            (total - unread).coerceAtLeast(0)
        }
    }
    val canCleanRead = hasListContent && readCount > 0

    val exportLauncher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.CreateDocument("application/json") {
            override fun createIntent(context: Context, input: String): Intent {
                val intent = super.createIntent(context, input)
                return Intent.createChooser(intent, context.getString(R.string.export_messages))
            }
        },
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val listToExport = if (query.isBlank()) {
                messages.itemSnapshotList.items
            } else {
                searchResults
            }
            if (listToExport.isEmpty()) {
                Toast.makeText(context, noMessagesExportText, Toast.LENGTH_SHORT).show()
                announceForAccessibility(context, noMessagesExportText)
                return@launch
            }
            ExportUtils.writeMessagesJson(context, uri, listToExport)
            Toast.makeText(context, exportSuccessText, Toast.LENGTH_SHORT).show()
            announceForAccessibility(context, exportSuccessText)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    var searchMenuExpanded by remember { mutableStateOf(false) }
                    
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
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
                            modifier = Modifier.weight(1f),
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
                            IconButton(onClick = { searchMenuExpanded = true }) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.label_more_actions),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            DropdownMenu(
                                expanded = searchMenuExpanded,
                                onDismissRequest = { searchMenuExpanded = false }
                            ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_mark_all_read)) },
                                leadingIcon = { Icon(Icons.Outlined.MarkEmailRead, null) },
                                onClick = {
                                    viewModel.markAllRead()
                                    searchMenuExpanded = false
                                },
                                enabled = totalUnread > 0
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clean_channel_messages)) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                                onClick = {
                                    showChannelCleanupConfirmation = true
                                    searchMenuExpanded = false
                                },
                                enabled = canCleanRead
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_messages)) },
                                leadingIcon = { Icon(Icons.Outlined.IosShare, null) },
                                onClick = {
                                    exportLauncher.launch(exportFilename(filterState.channel))
                                    searchMenuExpanded = false
                                },
                                enabled = canExport
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
                        .padding(start = 20.dp, top = 8.dp, bottom = 12.dp)
                        .semantics { heading() },
                )

                if (hasChannels && showFilters) {
                    ChannelFilterRow(
                        selectedChannel = filterState.channel,
                        channelCounts = channelCounts,
                        channelNameMap = channelNameMap,
                        totalUnread = totalUnread,
                        totalCount = totalCount,
                        onChannelChange = viewModel::setChannel,
                    )
                }
            }
        }

        if (query.isBlank() && messages.itemCount == 0 || query.isNotBlank() && searchResults.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (query.isBlank()) {
                        Icon(
                            imageVector = Icons.Outlined.MarkEmailUnread,
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .padding(bottom = 16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = stringResource(R.string.message_list_empty_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.message_list_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.label_no_search_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                        val normalizedChannel = message.channel?.trim()
                        val channelDisplayName = normalizedChannel?.let { channelNameMap[it] ?: it }
                        MessageRow(
                            message = message,
                            channelDisplayName = channelDisplayName,
                            onClick = { onMessageClick(message.id) },
                            onMarkRead = {
                                viewModel.markRead(message.id)
                            },
                            onDelete = {
                                viewModel.deleteMessage(message.id)
                            },
                        )
                    }
                }
            } else {
                items(searchResults, key = { it.id }) { message ->
                    val normalizedChannel = message.channel?.trim()
                    val channelDisplayName = normalizedChannel?.let { channelNameMap[it] ?: it }
                    MessageRow(
                        message = message,
                        channelDisplayName = channelDisplayName,
                        onClick = { onMessageClick(message.id) },
                        onMarkRead = {
                            viewModel.markRead(message.id)
                        },
                        onDelete = {
                            viewModel.deleteMessage(message.id)
                        },
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    if (showChannelCleanupConfirmation) {
        val confirmTextRes = if (filterState.channel == null) {
            R.string.confirm_cleanup_read_messages_all
        } else {
            R.string.confirm_cleanup_read_messages_channel
        }
        AlertDialog(
            onDismissRequest = { showChannelCleanupConfirmation = false },
            title = { Text(stringResource(R.string.confirm_cleanup_messages)) },
            text = { Text(stringResource(confirmTextRes)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cleanupReadMessagesForCurrentFilter()
                    showChannelCleanupConfirmation = false
                }) {
                    Text(stringResource(R.string.label_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showChannelCleanupConfirmation = false
                }) {
                    Text(stringResource(R.string.label_cancel))
                }
            },
        )
    }
}

@Composable
private fun ChannelFilterRow(
    selectedChannel: String?,
    channelCounts: List<MessageChannelCount>,
    channelNameMap: Map<String, String>,
    totalUnread: Int,
    totalCount: Int,
    onChannelChange: (String?) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
        ) {
            item {
                TechFilterChip(
                    selected = selectedChannel == null,
                    onClick = { onChannelChange(null) },
                    label = "All"
                )
            }
            items(channelCounts) { channelCount ->
                val channel = channelCount.channel.trim()
                val displayName = if (channel.isBlank()) {
                    stringResource(R.string.label_group_ungrouped)
                } else {
                    channelNameMap[channel] ?: channel
                }
                TechFilterChip(
                    selected = selectedChannel == channel,
                    onClick = { onChannelChange(channel) },
                    label = displayName
                )
            }
        }
    }
}

@Composable
private fun TechFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer 
                else MaterialTheme.colorScheme.background
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
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MessageRow(
    message: PushMessage,
    channelDisplayName: String?,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    val hasMarkReadAction = !message.isRead
    val actionWidth = if (hasMarkReadAction) 140.dp else 72.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val offsetX = remember { Animatable(0f) }
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val scope = rememberCoroutineScope()

    val appName = stringResource(R.string.app_name)
    val context = LocalContext.current
    val timeText = remember(message.receivedAt, context.resources.configuration) {
        formatMessageTime(context, message.receivedAt, ZoneId.systemDefault())
    }
    
    val resolvedBody = remember(message.rawPayloadJson, message.body) {
        MessageBodyResolver.resolve(message.rawPayloadJson, message.body)
    }
        val renderPayload = remember(message.rawPayloadJson, resolvedBody.rawText, resolvedBody.isMarkdown) {
            extractMarkdownRenderPayload(message.rawPayloadJson)
                ?: MarkdownRenderPayloadSizing.listPayload(resolvedBody.rawText, resolvedBody.isMarkdown)
        }
        val imageUrl = remember(message.rawPayloadJson) {
            MessagePayloadUtils.extractImageUrl(message.rawPayloadJson)
        }
        val iconUrl = remember(message.rawPayloadJson) {
            MessagePayloadUtils.extractIconUrl(message.rawPayloadJson)
        }
        val markActionLabel = stringResource(R.string.action_mark_read)
        val deleteActionLabel = stringResource(R.string.action_delete)
        val unreadStateLabel = stringResource(R.string.filter_unread)
    
        val iconStyle = resolveMessageIconStyle(
            message = message,
            channelDisplayName = channelDisplayName,
            colorScheme = MaterialTheme.colorScheme,
        )
    
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
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
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable {
                                scope.launch {
                                    if (animationsEnabled) {
                                        offsetX.animateTo(0f)
                                    } else {
                                        offsetX.snapTo(0f)
                                    }
                                }
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
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable {
                            scope.launch {
                                if (animationsEnabled) {
                                    offsetX.animateTo(0f)
                                } else {
                                    offsetX.snapTo(0f)
                                }
                            }
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
            Column(
                modifier = Modifier
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onClick() }
                    .semantics(mergeDescendants = true) {
                        if (!message.isRead) {
                            stateDescription = unreadStateLabel
                        }
                        customActions = buildList {
                            if (hasMarkReadAction) {
                                add(CustomAccessibilityAction(markActionLabel) {
                                    scope.launch {
                                        if (animationsEnabled) {
                                            offsetX.animateTo(0f)
                                        } else {
                                            offsetX.snapTo(0f)
                                        }
                                    }
                                    onMarkRead()
                                    true
                                })
                            }
                            add(CustomAccessibilityAction(deleteActionLabel) {
                                scope.launch {
                                    if (animationsEnabled) {
                                        offsetX.animateTo(0f)
                                    } else {
                                        offsetX.snapTo(0f)
                                    }
                                }
                                onDelete()
                                true
                            })
                        }
                    }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                val newOffset = (offsetX.value + delta).coerceIn(-actionWidthPx, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        },
                        onDragStopped = {
                            val targetOffset = if (offsetX.value < -actionWidthPx / 2) -actionWidthPx else 0f
                            scope.launch {
                                if (animationsEnabled) {
                                    offsetX.animateTo(targetOffset)
                                } else {
                                    offsetX.snapTo(targetOffset)
                                }
                            }
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (!iconUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = iconUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = message.title.ifBlank { appName },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = if (message.isRead) FontWeight.SemiBold else FontWeight.ExtraBold
                            ),
                            color = if (message.isRead) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!channelDisplayName.isNullOrBlank()) {
                            Text(
                                text = "#$channelDisplayName",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.isRead) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
            ) {
                 if (renderPayload != null) {
                    MarkdownRenderPayloadText(
                        payload = renderPayload,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 8,
                        overflow = TextOverflow.Clip,
                    )
                } else {
                    Text(
                        text = resolvedBody.rawText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 8,
                        overflow = TextOverflow.Clip,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                
                if (!imageUrl.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = stringResource(R.string.label_image_attachment),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
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

private fun exportFilename(channel: String?): String {
    val base = "pushgo-messages"
    val suffix = when {
        channel == null -> null
        channel.isBlank() -> "no-channel"
        else -> channel.trim()
            .lowercase()
            .replace("\\s+".toRegex(), "-")
            .replace("[^a-z0-9_-]".toRegex(), "")
    }
    return if (suffix.isNullOrBlank()) {
        "$base-all.json"
    } else {
        "$base-$suffix.json"
    }
}

private fun formatChannelLabel(name: String, unreadCount: Int, totalCount: Int): String {
    return name
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

private data class MessageIconStyle(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: Color,
    val background: Color,
)

@Composable
private fun resolveMessageIconStyle(
    message: PushMessage,
    channelDisplayName: String?,
    colorScheme: androidx.compose.material3.ColorScheme,
): MessageIconStyle {
    val isWarning = message.status == MessageStatus.MISSING
        || message.status == MessageStatus.PARTIALLY_DECRYPTED
        || message.decryptionState == DecryptionState.ALG_MISMATCH
        || message.decryptionState == DecryptionState.DECRYPT_FAILED
    val isVerified = message.status == MessageStatus.DECRYPTED
        || message.decryptionState == DecryptionState.DECRYPT_OK
    return when {
        isWarning -> MessageIconStyle(
            icon = Icons.Outlined.Warning,
            tint = colorScheme.error,
            background = Color.Transparent,
        )
        isVerified -> MessageIconStyle(
            icon = Icons.Outlined.Verified,
            tint = colorScheme.primary,
            background = Color.Transparent,
        )
        !channelDisplayName.isNullOrBlank() -> MessageIconStyle(
            icon = Icons.Outlined.Group,
            tint = colorScheme.secondary,
            background = Color.Transparent,
        )
        else -> MessageIconStyle(
            icon = Icons.Outlined.Person,
            tint = colorScheme.primary,
            background = Color.Transparent,
        )
    }
}
