package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList as FilledFilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList as OutlinedFilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import io.ethan.pushgo.markdown.MessagePreviewExtractor
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
import io.ethan.pushgo.data.model.ReadFilter

private val ScreenHorizontalPadding = 12.dp
private const val MessageListImagePreviewMaxItems = 3

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
                                Icon(
                                    imageVector = if (filterState.channel == null) Icons.Outlined.OutlinedFilterList else Icons.Filled.FilledFilterList,
                                    contentDescription = stringResource(R.string.label_channel_id),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                            DropdownMenu(
                                expanded = searchMenuExpanded,
                                onDismissRequest = { searchMenuExpanded = false }
                            ) {
                                ChannelFilterMenuOption(
                                    title = stringResource(R.string.label_channel_all),
                                    isSelected = filterState.channel == null,
                                    modifier = Modifier.testTag("filter.message.channel.all"),
                                ) {
                                        viewModel.setChannel(null)
                                        searchMenuExpanded = false
                                    }
                                val ungroupedLabel = stringResource(R.string.label_group_ungrouped)
                                channelOptions.forEach { channel ->
                                    val displayName = if (channel.isBlank()) {
                                        ungroupedLabel
                                    } else {
                                        channelNameMap[channel] ?: channel
                                    }
                                    ChannelFilterMenuOption(
                                        title = displayName,
                                        isSelected = filterState.channel == channel,
                                        modifier = Modifier.testTag(
                                            "filter.message.channel.${channel.ifBlank { "ungrouped" }}"
                                        ),
                                    ) {
                                            viewModel.setChannel(channel)
                                            searchMenuExpanded = false
                                        }
                                }
                            }
                        }
                        IconButton(
                            modifier = Modifier.testTag("action.message.list.unread_only"),
                            onClick = {
                                viewModel.setReadFilter(
                                    if (unreadOnlySelected) ReadFilter.ALL else ReadFilter.UNREAD
                                )
                            },
                        ) {
                            Icon(
                                imageVector = if (unreadOnlySelected) {
                                    Icons.Filled.MarkEmailUnread
                                } else {
                                    Icons.Outlined.MarkEmailUnread
                                },
                                contentDescription = stringResource(R.string.filter_unread),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
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
                        icon = Icons.Outlined.MarkEmailUnread,
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
                            onClick = { onMessageClick(message.id) },
                            onMarkRead = { viewModel.markRead(message.id) },
                            onDelete = { viewModel.deleteMessage(message.id) },
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
                        onClick = { onMessageClick(message.id) },
                        onMarkRead = { viewModel.markRead(message.id) },
                        onDelete = { viewModel.deleteMessage(message.id) },
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
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
private fun MessageRow(
    modifier: Modifier = Modifier,
    message: PushMessage,
    imageModels: List<Any>,
    onClick: () -> Unit,
    onMarkRead: () -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
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
    
    val bodyPreview = remember(message.bodyPreview, message.body) {
        message.bodyPreview?.trim()?.takeIf { it.isNotEmpty() }
            ?: MessagePreviewExtractor.listPreview(message.body)
    }
    val unreadStateLabel = stringResource(R.string.filter_unread)

    Box(
        modifier = modifier
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
        Column(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .testTag("message.row.content.${message.id}")
                .clickable { onClick() }
                .semantics(mergeDescendants = true) {
                    if (!message.isRead) {
                        stateDescription = unreadStateLabel
                    }
                }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        offsetX = (offsetX + delta).coerceIn(-actionWidthPx, 0f)
                    },
                    onDragStopped = {
                        offsetX = if (offsetX < -actionWidthPx / 2) -actionWidthPx else 0f
                    }
                )
                .padding(horizontal = ScreenHorizontalPadding, vertical = 12.dp)
        ) {
            MessageRowContent(
                message = message,
                imageModels = imageModels,
                appName = appName,
                timeText = timeText,
                bodyPreview = bodyPreview,
            )
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
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
                    overflow = TextOverflow.Ellipsis
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
    if (hasBodyText || hasImages) {
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
            if (message.tags.isNotEmpty()) {
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
