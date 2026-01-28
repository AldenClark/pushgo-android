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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.ethan.pushgo.R
import io.ethan.pushgo.data.ChannelPasswordValidator
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import io.ethan.pushgo.ui.announceForAccessibility
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ChannelListScreen(
    navController: NavController,
    factory: PushGoViewModelFactory,
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
        viewModel.notifyIfFcmUnsupported(context)
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
    var showCreateChannelDialog by remember { mutableStateOf(false) }
    var showSubscribeChannelDialog by remember { mutableStateOf(false) }
    var createChannelName by remember { mutableStateOf("") }
    var createChannelPassword by remember { mutableStateOf("") }
    var subscribeChannelId by remember { mutableStateOf("") }
    var subscribeChannelPassword by remember { mutableStateOf("") }
    var renameAlias by remember { mutableStateOf("") }

    val isCreatePasswordInvalid = createChannelPassword.trim().isNotEmpty() &&
        runCatching { ChannelPasswordValidator.normalize(createChannelPassword) }.isFailure
    val isSubscribePasswordInvalid = subscribeChannelPassword.trim().isNotEmpty() &&
        runCatching { ChannelPasswordValidator.normalize(subscribeChannelPassword) }.isFailure
    
    val channelIdCopiedText = stringResource(R.string.label_channel_id_copied)
    var menuExpanded by remember { mutableStateOf(false) }

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
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.label_back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.section_channels),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.label_add_channel),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.label_create_channel)) },
                        onClick = {
                            menuExpanded = false
                            showCreateChannelDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.label_subscribe_channel)) },
                        onClick = {
                            menuExpanded = false
                            showSubscribeChannelDialog = true
                        },
                    )
                }
            }
        }
        
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            if (viewModel.channelSubscriptions.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp, start = 32.dp, end = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Group,
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .padding(bottom = 16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = stringResource(R.string.channel_list_empty_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.channel_list_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                items(viewModel.channelSubscriptions, key = { it.channelId }) { subscription ->
                    ChannelRow(
                        subscription = subscription,
                        autoCleanupEnabled = viewModel.autoCleanupEnabled,
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
                        onToggleAutoCleanup = { enabled ->
                            scope.launch {
                                viewModel.updateChannelAutoCleanupEnabled(
                                    channelId = subscription.channelId,
                                    enabled = enabled,
                                )
                            }
                        }
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

    if (showCreateChannelDialog) {
        AlertDialog(
            onDismissRequest = { showCreateChannelDialog = false },
            title = { Text(stringResource(R.string.label_create_channel)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val alias = createChannelName
                        val password = createChannelPassword
                        scope.launch {
                            val success = viewModel.createChannel(context, alias, password)
                            if (success) {
                                createChannelName = ""
                                createChannelPassword = ""
                                showCreateChannelDialog = false
                            }
                        }
                    },
                    enabled = !viewModel.isSavingChannel
                        && createChannelName.trim().isNotEmpty()
                        && createChannelPassword.trim().isNotEmpty()
                        && !isCreatePasswordInvalid,
                ) {
                    Text(stringResource(R.string.label_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateChannelDialog = false }) {
                    Text(stringResource(R.string.label_cancel))
                }
            }
        )
    }

    if (showSubscribeChannelDialog) {
        AlertDialog(
            onDismissRequest = { showSubscribeChannelDialog = false },
            title = { Text(stringResource(R.string.label_subscribe_channel)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val channelId = subscribeChannelId
                        val password = subscribeChannelPassword
                        scope.launch {
                            val success = viewModel.subscribeChannel(context, channelId, password)
                            if (success) {
                                subscribeChannelId = ""
                                subscribeChannelPassword = ""
                                showSubscribeChannelDialog = false
                            }
                        }
                    },
                    enabled = !viewModel.isSavingChannel
                        && subscribeChannelId.trim().isNotEmpty()
                        && subscribeChannelPassword.trim().isNotEmpty()
                        && !isSubscribePasswordInvalid,
                ) {
                    Text(stringResource(R.string.label_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSubscribeChannelDialog = false }) {
                    Text(stringResource(R.string.label_cancel))
                }
            }
        )
    }
}

@Composable

private fun ChannelRow(
    subscription: ChannelSubscription,
    autoCleanupEnabled: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onToggleAutoCleanup: (Boolean) -> Unit,
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
                if (autoCleanupEnabled) {
                    val autoCleanupLabel = if (subscription.autoCleanupEnabled) {
                        R.string.label_disable_auto_cleanup
                    } else {
                        R.string.label_enable_auto_cleanup
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(autoCleanupLabel)) },
                        onClick = {
                            menuExpanded = false
                            onToggleAutoCleanup(!subscription.autoCleanupEnabled)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.AutoFixHigh,
                                contentDescription = null
                            )
                        }
                    )
                }
            }
        }
    }
}
