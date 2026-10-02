package io.ethan.pushgo.ui.screens

import android.widget.Toast
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.PendingLocalDeletionOperation
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.data.model.MessageChannelCount
import io.ethan.pushgo.testing.QualityRuntime
import io.ethan.pushgo.ui.PendingLocalDeletionCoordinator
import io.ethan.pushgo.ui.accessibility.joinAccessibilitySummary
import io.ethan.pushgo.ui.accessibility.pushGoMergedActionSemantics
import io.ethan.pushgo.ui.rememberBottomBarNestedScrollConnection
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.theme.pushGoOutlinedTextFieldColors
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import io.ethan.pushgo.ui.theme.pushGoPrimaryButtonColors
import io.ethan.pushgo.ui.theme.pushGoSegmentedButtonColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface ChannelActivityState {
    data object Loading : ChannelActivityState
    data object Failed : ChannelActivityState
    data class Loaded(val byIdentifier: Map<String, MessageChannelCount>) : ChannelActivityState
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ChannelListScreen(
    navController: NavController,
    container: AppContainer,
    viewModel: SettingsViewModel,
    onBottomBarVisibilityChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val bottomGestureInset = rememberBottomGestureInset()
    val listState = rememberLazyListState()
    val bottomBarNestedScrollConnection = rememberBottomBarNestedScrollConnection(
        onBottomBarVisibilityChanged,
        canScroll = listState.canScrollBackward || listState.canScrollForward,
    )
    val effectivePendingScope by container.pendingLocalDeletionCoordinator.effectiveScope.collectAsStateWithLifecycle()
    val visibleChannelSubscriptions = viewModel.channelSubscriptions.filterNot {
        effectivePendingScope.suppressesChannel(it.channelId)
    }
    val channelActivityState by produceState<ChannelActivityState>(
        initialValue = ChannelActivityState.Loading,
        key1 = container.messageRepository,
    ) {
        try {
            container.messageRepository.observeChannelCounts().collect { counts ->
                value = ChannelActivityState.Loaded(
                    counts.associateBy { it.channel.trim() }
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            value = ChannelActivityState.Failed
        }
    }

    LaunchedEffect(viewModel.errorMessage) {
        val message = viewModel.errorMessage
        if (message != null) {
            // Keep host-level Toast evidence separate from channel-entry Sheet
            // errors. Quality UI journeys use this counter to prove a channel
            // failure did not escape its owning surface.
            QualityRuntime.recordGlobalErrorPresentation()
            val text = message.resolve(context)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            announceForAccessibility(context, text)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.syncSubscriptionsOnChannelListEntry(context)
    }
    LaunchedEffect(viewModel.successMessage) {
        val message = viewModel.successMessage
        if (message != null) {
            val text = message.resolve(context)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            announceForAccessibility(context, text)
            viewModel.consumeSuccess()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            onBottomBarVisibilityChanged(true)
        }
    }
    var pendingChannelRemoval by remember { mutableStateOf<ChannelSubscription?>(null) }
    var pendingChannelRename by remember { mutableStateOf<ChannelSubscription?>(null) }
    var showChannelEntrySheet by remember { mutableStateOf(false) }
    var channelEntryMode by remember { mutableStateOf(ChannelEntryMode.Create) }
    var createChannelName by remember { mutableStateOf("") }
    var createChannelPassword by remember { mutableStateOf("") }
    var subscribeChannelId by remember { mutableStateOf("") }
    var subscribeChannelPassword by remember { mutableStateOf("") }
    var renameAlias by remember { mutableStateOf("") }

    val canSubmitChannelEntry = !viewModel.isSavingChannel

    val channelIdCopiedText = stringResource(R.string.label_channel_id_copied)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("screen.channels.list")
            .background(uiColors.surfaceBase)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.section_channels),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
                    .semantics { heading() },
                color = uiColors.textPrimary
            )

            IconButton(
                onClick = {
                    viewModel.clearChannelEntryError()
                    channelEntryMode = ChannelEntryMode.Create
                    createChannelName = ""
                    createChannelPassword = ""
                    subscribeChannelId = ""
                    subscribeChannelPassword = ""
                    showChannelEntrySheet = true
                },
                modifier = Modifier.testTag("action.channels.add"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.label_add_channel),
                    tint = uiColors.accentPrimary
                )
            }
            IconButton(
                onClick = {
                    navController.navigate(io.ethan.pushgo.ui.SettingsRoute) {
                        launchSingleTop = true
                    }
                },
                modifier = Modifier.testTag("action.channels.settings"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.tab_settings),
                    tint = uiColors.textSecondary
                )
            }
        }
        
        PushGoDividerSubtle()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(bottomBarNestedScrollConnection),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomGestureInset + 24.dp),
        ) {
            if (visibleChannelSubscriptions.isEmpty()) {
                item {
                    AppEmptyState(
                        icon = Icons.Outlined.Group,
                        title = stringResource(R.string.channel_list_empty_title),
                        description = stringResource(R.string.channel_list_empty_hint),
                    )
                }
            } else {
                items(visibleChannelSubscriptions, key = { it.channelId }) { subscription ->
                    ChannelRow(
                        subscription = subscription,
                        activityText = channelActivityText(
                            context = context,
                            state = channelActivityState,
                            identifier = subscription.channelId,
                        ),
                        onRename = {
                            viewModel.clearChannelRenameError()
                            pendingChannelRename = subscription
                            renameAlias = subscription.displayName
                        },
                        onDelete = { pendingChannelRemoval = subscription },
                        onCopy = {
                            scope.launch {
                                clipboard.setText(AnnotatedString(subscription.channelId))
                            }
                            Toast.makeText(context, channelIdCopiedText, Toast.LENGTH_SHORT).show()
                            announceForAccessibility(context, channelIdCopiedText)
                        },
                    )
                    PushGoDividerSubtle()
                }
            }
        }
    }
    if (pendingChannelRemoval != null) {
        val target = pendingChannelRemoval
        val unsubscribeTitle = stringResource(
            R.string.label_unsubscribe_channel_title,
            target?.displayName ?: "",
        )
        PushGoAlertDialog(
            onDismissRequest = { pendingChannelRemoval = null },
            paneTitle = unsubscribeTitle,
            title = {
                Text(
                    text = unsubscribeTitle
                )
            },
            text = {
                Text(stringResource(R.string.label_unsubscribe_channel_hint))
            },
            confirmButton = {
                Column {
                    PushGoDestructiveTextButton(
                        text = stringResource(R.string.label_unsubscribe_delete_history),
                        onClick = {
                            val removalTarget = target ?: return@PushGoDestructiveTextButton
                            // Claim this dialog action synchronously. Snapshot reads below may
                            // suspend; leaving the dialog actionable would allow a duplicate or
                            // conflicting "keep history" unsubscribe to be queued meanwhile.
                            pendingChannelRemoval = null
                            scope.launch {
                                try {
                                    val channelId = removalTarget.channelId
                                    val summary = removalTarget.displayName.ifBlank { channelId }
                                    val appContext = context.applicationContext
                                    val expectedGateway = container.channelRepository.loadGatewayConfig().first
                                    val expectedUpdatedAt = removalTarget.updatedAt
                                    val expectedUseProvider = viewModel.channelRemovalUsesProvider(appContext)
                                    container.pendingLocalDeletionCoordinator.schedule(
                                        summary = summary,
                                        operation = PendingLocalDeletionOperation.channel(
                                            id = channelId,
                                            expectedGatewayUrl = expectedGateway,
                                            expectedUpdatedAt = expectedUpdatedAt,
                                            expectedUseProvider = expectedUseProvider,
                                        ),
                                        onCompletion = viewModel::handleUnsubscribeAndDeleteHistoryCompletion,
                                    )
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    viewModel.handleUnsubscribeAndDeleteHistoryCompletion(
                                        Result.failure(error)
                                    )
                                }
                            }
                        },
                        enabled = !viewModel.isRemovingChannel,
                        modifier = Modifier.testTag("action.channel.unsubscribe.delete_history"),
                    )
                    TextButton(
                        onClick = {
                            val channelId = target?.channelId ?: return@TextButton
                            scope.launch {
                                viewModel.unsubscribeChannel(context, channelId)
                                pendingChannelRemoval = null
                            }
                        },
                        enabled = !viewModel.isRemovingChannel,
                        modifier = Modifier.testTag("action.channel.unsubscribe.keep_history"),
                    ) {
                        Text(stringResource(R.string.label_unsubscribe_keep_history))
                    }
                }
            }
        )
    }

    if (pendingChannelRename != null) {
        val target = pendingChannelRename
        val renameTitle = stringResource(
            R.string.label_rename_channel_title,
            target?.displayName ?: "",
        )
        PushGoAlertDialog(
            onDismissRequest = {
                viewModel.clearChannelRenameError()
                pendingChannelRename = null
            },
            paneTitle = renameTitle,
            title = {
                Text(
                    text = renameTitle
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameAlias,
                        onValueChange = {
                            renameAlias = it
                            viewModel.clearChannelRenameError()
                        },
                        label = { Text(stringResource(R.string.label_channel_alias)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("field.channel.rename.alias"),
                        singleLine = true,
                        colors = pushGoOutlinedTextFieldColors(),
                    )
                    Text(
                        text = stringResource(R.string.label_rename_channel_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    viewModel.channelRenameErrorMessage?.let { message ->
                        Text(
                            text = message.resolve(context),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("feedback.channel.rename"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val channelId = target?.channelId ?: return@TextButton
                        val alias = renameAlias
                        scope.launch {
                            if (viewModel.renameChannel(channelId, alias)) {
                                pendingChannelRename = null
                            }
                        }
                    },
                    enabled = !viewModel.isRenamingChannel && renameAlias.trim().isNotEmpty(),
                    modifier = Modifier.testTag("action.channel.rename.save"),
                ) {
                    Text(stringResource(R.string.label_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.clearChannelRenameError()
                        pendingChannelRename = null
                    },
                    modifier = Modifier.testTag("action.channel.rename.cancel"),
                ) {
                    Text(stringResource(R.string.label_cancel))
                }
            }
        )
    }

    if (showChannelEntrySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        PushGoModalBottomSheet(
            modifier = Modifier.testTag("sheet.channels.entry"),
            onDismissRequest = {
                viewModel.clearChannelEntryError()
                showChannelEntrySheet = false
            },
            sheetState = sheetState,
            paneTitle = stringResource(R.string.a11y_pane_channel_management),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = bottomGestureInset + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.label_add_channel),
                    style = MaterialTheme.typography.titleLarge,
                )

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ChannelEntryMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = channelEntryMode == mode,
                            onClick = {
                                viewModel.clearChannelEntryError()
                                channelEntryMode = mode
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ChannelEntryMode.entries.size
                            ),
                            colors = pushGoSegmentedButtonColors(),
                            modifier = Modifier.testTag(mode.testTag),
                        ) {
                            Text(stringResource(mode.labelRes))
                        }
                    }
                }

                viewModel.channelEntryErrorMessage?.let { message ->
                    Text(
                        text = message.resolve(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = uiColors.stateDanger.foreground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("feedback.channels.entry"),
                    )
                }

                when (channelEntryMode) {
                    ChannelEntryMode.Create -> {
                        OutlinedTextField(
                            value = createChannelName,
                            onValueChange = {
                                viewModel.clearChannelEntryError()
                                createChannelName = it
                            },
                            label = { Text(stringResource(R.string.label_channel_name)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field.channels.create.name"),
                            singleLine = true,
                            colors = pushGoOutlinedTextFieldColors(),
                        )
                        OutlinedTextField(
                            value = createChannelPassword,
                            onValueChange = {
                                viewModel.clearChannelEntryError()
                                createChannelPassword = it
                            },
                            label = { Text(stringResource(R.string.label_channel_password)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field.channels.create.password"),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = pushGoOutlinedTextFieldColors(),
                        )
                    }

                    ChannelEntryMode.Subscribe -> {
                        OutlinedTextField(
                            value = subscribeChannelId,
                            onValueChange = {
                                viewModel.clearChannelEntryError()
                                subscribeChannelId = it
                            },
                            label = { Text(stringResource(R.string.label_channel_id)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field.channels.subscribe.id"),
                            singleLine = true,
                            colors = pushGoOutlinedTextFieldColors(),
                        )
                        OutlinedTextField(
                            value = subscribeChannelPassword,
                            onValueChange = {
                                viewModel.clearChannelEntryError()
                                subscribeChannelPassword = it
                            },
                            label = { Text(stringResource(R.string.label_channel_password)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("field.channels.subscribe.password"),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = pushGoOutlinedTextFieldColors(),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        viewModel.clearChannelEntryError()
                        showChannelEntrySheet = false
                    }) {
                        Text(stringResource(R.string.label_cancel))
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                when (channelEntryMode) {
                                    ChannelEntryMode.Create -> {
                                        val success = viewModel.createChannel(
                                            context,
                                            createChannelName,
                                            createChannelPassword
                                        )
                                        if (success) {
                                            createChannelName = ""
                                            createChannelPassword = ""
                                            showChannelEntrySheet = false
                                        }
                                    }

                                    ChannelEntryMode.Subscribe -> {
                                        val success = viewModel.subscribeChannel(
                                            context,
                                            subscribeChannelId,
                                            subscribeChannelPassword
                                        )
                                        if (success) {
                                            subscribeChannelId = ""
                                            subscribeChannelPassword = ""
                                            showChannelEntrySheet = false
                                        }
                                    }
                                }
                            }
                        },
                        enabled = canSubmitChannelEntry,
                        modifier = Modifier.testTag("action.channels.entry.submit"),
                        colors = pushGoPrimaryButtonColors(),
                    ) {
                        Text(stringResource(channelEntryMode.labelRes))
                    }
                }
            }
        }
    }
}

private enum class ChannelEntryMode(val labelRes: Int, val testTag: String) {
    Create(R.string.label_create_channel, "mode.channels.entry.create"),
    Subscribe(R.string.label_subscribe_channel, "mode.channels.entry.subscribe"),
}

@Composable

internal fun ChannelRow(
    subscription: ChannelSubscription,
    activityText: String,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
) {
    val uiColors = PushGoThemeExtras.colors
    var menuExpanded by remember { mutableStateOf(false) }
    val rowSummary = joinAccessibilitySummary(
        subscription.displayName,
        subscription.channelId,
        activityText,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("channel.row.${subscription.channelId}")
            .heightIn(min = 64.dp)
            .background(PushGoThemeExtras.colors.fieldContainer)
            .clickable { onCopy() }
            .pushGoMergedActionSemantics(
                summary = rowSummary,
                onClickLabel = stringResource(R.string.a11y_action_copy_channel_id),
                onClickAction = onCopy,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subscription.displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = uiColors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subscription.channelId,
                style = MaterialTheme.typography.bodySmall,
                color = uiColors.textSecondary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = activityText,
                style = MaterialTheme.typography.bodySmall,
                color = uiColors.textSecondary,
                modifier = Modifier.testTag("channel.stats.${subscription.channelId}"),
                maxLines = 2,
            )
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.testTag("action.channel.${subscription.channelId}.menu"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.label_channel_actions),
                    tint = uiColors.textSecondary
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    modifier = Modifier.testTag("action.channel.${subscription.channelId}.rename"),
                    text = { Text(stringResource(R.string.label_rename_channel)) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null
                        )
                    }
                )
                DropdownMenuItem(
                    modifier = Modifier.testTag("action.channel.${subscription.channelId}.unsubscribe"),
                    text = { Text(stringResource(R.string.label_unsubscribe_channel)) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = null
                        )
                    }
                )
            }
        }
    }
}

private fun channelActivityText(
    context: android.content.Context,
    state: ChannelActivityState,
    identifier: String,
): String {
    return when (state) {
        ChannelActivityState.Loading -> context.getString(R.string.channel_stats_loading)
        ChannelActivityState.Failed -> context.getString(R.string.channel_stats_unavailable)
        is ChannelActivityState.Loaded -> {
            val stats = state.byIdentifier[identifier.trim()]
                ?: return context.getString(R.string.channel_stats_empty)
            val latest = stats.latestReceivedAt?.let { epochMillis ->
                val locale = context.resources.configuration.locales[0]
                val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMdjm")
                DateTimeFormatter.ofPattern(pattern, locale)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(epochMillis))
            } ?: context.getString(R.string.channel_stats_no_recent)
            context.getString(
                R.string.channel_stats_summary,
                stats.totalCount,
                stats.unreadCount,
                latest,
            )
        }
    }
}
