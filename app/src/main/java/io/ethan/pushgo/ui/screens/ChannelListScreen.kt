package io.ethan.pushgo.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.ethan.pushgo.R
import io.ethan.pushgo.data.ChannelPasswordValidator
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor
import kotlinx.coroutines.launch

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ChannelListScreen(
    navController: NavController,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel.errorMessage) {
        val message = viewModel.errorMessage
        if (message != null) {
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
    var pendingChannelRemoval by remember { mutableStateOf<ChannelSubscription?>(null) }
    var pendingChannelRename by remember { mutableStateOf<ChannelSubscription?>(null) }
    var showChannelEntrySheet by remember { mutableStateOf(false) }
    var channelEntryMode by remember { mutableStateOf(ChannelEntryMode.Create) }
    var createChannelName by remember { mutableStateOf("") }
    var createChannelPassword by remember { mutableStateOf("") }
    var subscribeChannelId by remember { mutableStateOf("") }
    var subscribeChannelPassword by remember { mutableStateOf("") }
    var renameAlias by remember { mutableStateOf("") }

    val isCreatePasswordInvalid = createChannelPassword.trim().isNotEmpty() &&
        runCatching { ChannelPasswordValidator.normalize(createChannelPassword) }.isFailure
    val isSubscribePasswordInvalid = subscribeChannelPassword.trim().isNotEmpty() &&
        runCatching { ChannelPasswordValidator.normalize(subscribeChannelPassword) }.isFailure
    val canSubmitCreate = !viewModel.isSavingChannel &&
        createChannelName.trim().isNotEmpty() &&
        createChannelPassword.trim().isNotEmpty() &&
        !isCreatePasswordInvalid
    val canSubmitSubscribe = !viewModel.isSavingChannel &&
        subscribeChannelId.trim().isNotEmpty() &&
        subscribeChannelPassword.trim().isNotEmpty() &&
        !isSubscribePasswordInvalid
    val canSubmitChannelEntry = when (channelEntryMode) {
        ChannelEntryMode.Create -> canSubmitCreate
        ChannelEntryMode.Subscribe -> canSubmitSubscribe
    }

    val channelIdCopiedText = stringResource(R.string.label_channel_id_copied)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                color = MaterialTheme.colorScheme.onSurface
            )

            IconButton(onClick = {
                channelEntryMode = ChannelEntryMode.Create
                createChannelName = ""
                createChannelPassword = ""
                subscribeChannelId = ""
                subscribeChannelPassword = ""
                showChannelEntrySheet = true
            }) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.label_add_channel),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = {
                navController.navigate(io.ethan.pushgo.ui.SettingsRoute) {
                    launchSingleTop = true
                }
            }) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.tab_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            if (viewModel.channelSubscriptions.isEmpty()) {
                item {
                    AppEmptyState(
                        icon = Icons.Outlined.Group,
                        title = stringResource(R.string.channel_list_empty_title),
                        description = stringResource(R.string.channel_list_empty_hint),
                    )
                }
            } else {
                items(viewModel.channelSubscriptions, key = { it.channelId }) { subscription ->
                    ChannelRow(
                        subscription = subscription,
                        onRename = {
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
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
    if (pendingChannelRemoval != null) {
        val target = pendingChannelRemoval
        AlertDialog(
            onDismissRequest = { pendingChannelRemoval = null },
            title = {
                Text(
                    text = stringResource(
                        R.string.label_unsubscribe_channel_title,
                        target?.displayName ?: ""
                    )
                )
            },
            text = {
                Text(stringResource(R.string.label_unsubscribe_channel_hint))
            },
            confirmButton = {
                Column {
                    TextButton(
                        onClick = {
                            val channelId = target?.channelId ?: return@TextButton
                            scope.launch {
                                viewModel.unsubscribeChannel(context, channelId, deleteLocalMessages = true)
                                pendingChannelRemoval = null
                            }
                        },
                        enabled = !viewModel.isRemovingChannel,
                    ) {
                        Text(stringResource(R.string.label_unsubscribe_delete_history))
                    }
                    TextButton(
                        onClick = {
                            val channelId = target?.channelId ?: return@TextButton
                            scope.launch {
                                viewModel.unsubscribeChannel(context, channelId, deleteLocalMessages = false)
                                pendingChannelRemoval = null
                            }
                        },
                        enabled = !viewModel.isRemovingChannel,
                    ) {
                        Text(stringResource(R.string.label_unsubscribe_keep_history))
                    }
                }
            }
        )
    }

    if (pendingChannelRename != null) {
        val target = pendingChannelRename
        AlertDialog(
            onDismissRequest = { pendingChannelRename = null },
            title = {
                Text(
                    text = stringResource(
                        R.string.label_rename_channel_title,
                        target?.displayName ?: ""
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameAlias,
                        onValueChange = { renameAlias = it },
                        label = { Text(stringResource(R.string.label_channel_alias)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        text = stringResource(R.string.label_rename_channel_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val channelId = target?.channelId ?: return@TextButton
                        val alias = renameAlias
                        scope.launch {
                            viewModel.renameChannel(channelId, alias)
                            pendingChannelRename = null
                        }
                    },
                    enabled = !viewModel.isRenamingChannel && renameAlias.trim().isNotEmpty(),
                ) {
                    Text(stringResource(R.string.label_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingChannelRename = null }) {
                    Text(stringResource(R.string.label_cancel))
                }
            }
        )
    }

    if (showChannelEntrySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            modifier = Modifier.testTag("sheet.channels.entry"),
            onDismissRequest = { showChannelEntrySheet = false },
            sheetState = sheetState,
            containerColor = PushGoSheetContainerColor(),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
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
                            onClick = { channelEntryMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ChannelEntryMode.entries.size
                            ),
                        ) {
                            Text(stringResource(mode.labelRes))
                        }
                    }
                }

                when (channelEntryMode) {
                    ChannelEntryMode.Create -> {
                        OutlinedTextField(
                            value = createChannelName,
                            onValueChange = { createChannelName = it },
                            label = { Text(stringResource(R.string.label_channel_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = createChannelPassword,
                            onValueChange = { createChannelPassword = it },
                            label = { Text(stringResource(R.string.label_channel_password)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = isCreatePasswordInvalid,
                            supportingText = {
                                if (isCreatePasswordInvalid) {
                                    Text(stringResource(R.string.error_channel_password_length))
                                }
                            },
                        )
                    }

                    ChannelEntryMode.Subscribe -> {
                        OutlinedTextField(
                            value = subscribeChannelId,
                            onValueChange = { subscribeChannelId = it },
                            label = { Text(stringResource(R.string.label_channel_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = subscribeChannelPassword,
                            onValueChange = { subscribeChannelPassword = it },
                            label = { Text(stringResource(R.string.label_channel_password)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = isSubscribePasswordInvalid,
                            supportingText = {
                                if (isSubscribePasswordInvalid) {
                                    Text(stringResource(R.string.error_channel_password_length))
                                }
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showChannelEntrySheet = false }) {
                        Text(stringResource(R.string.label_cancel))
                    }
                    TextButton(
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
                    ) {
                        Text(stringResource(channelEntryMode.labelRes))
                    }
                }
            }
        }
    }
}

private enum class ChannelEntryMode(val labelRes: Int) {
    Create(R.string.label_create_channel),
    Subscribe(R.string.label_subscribe_channel),
}

@Composable

private fun ChannelRow(
    subscription: ChannelSubscription,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .clickable { onCopy() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subscription.displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subscription.channelId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.label_channel_actions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
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
