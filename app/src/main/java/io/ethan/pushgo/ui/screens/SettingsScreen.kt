package io.ethan.pushgo.ui.screens

import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel

import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.data.AppConstants
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

private val ScreenHorizontalPadding = 12.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val fcmSupported = remember(context) { isFcmSupported(context) }
    var notificationsEnabled by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val baseVersionName = remember {
        BuildConfig.VERSION_NAME.replace(Regex("(?i)\\s*build\\s*\\d+\\s*$"), "").trim()
    }
    val appVersionText = stringResource(
        R.string.label_app_version_format,
        baseVersionName,
        BuildConfig.VERSION_CODE,
    )
    var showDecryptionSheet by remember { mutableStateOf(false) }
    var showGatewaySheet by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel.errorMessage) {
        val message = viewModel.errorMessage
        if (message != null) {
            val text = message.resolve(context)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            announceForAccessibility(context, text)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(viewModel.successMessage) {
        val message = viewModel.successMessage
        if (message != null) {
            if (message is io.ethan.pushgo.ui.viewmodel.ResMessage && message.resId == R.string.message_gateway_saved) {
                showGatewaySheet = false
            }
            val text = message.resolve(context)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            announceForAccessibility(context, text)
            viewModel.consumeSuccess()
        }
    }

    Scaffold(
        modifier = Modifier.testTag("screen.settings.content"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.tab_settings),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                },
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.label_back),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PushGoSheetContainerColor(),
                ),
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {

            if (!notificationsEnabled) {
                item {
                    NotificationCard(
                        onClick = { openNotificationSettings(context) },
                    )
                }
            }
            item {
                SettingsSectionHeader(text = stringResource(R.string.section_connection_device))
            }
            item {
                val gatewaySubtitle = viewModel.gatewayAddress.ifBlank { AppConstants.defaultServerAddress }
                SettingsRow(
                    testTag = "row.settings.gateway",
                    icon = Icons.Outlined.Dns,
                    title = stringResource(R.string.label_gateway_settings),
                    subtitle = gatewaySubtitle,
                    onClick = { showGatewaySheet = true },
                )
            }
            if (fcmSupported && viewModel.isChannelModeLoaded) {
                item {
                    SettingsSwitchRow(
                        rowTestTag = "row.settings.use_fcm_channel",
                        switchTestTag = "switch.settings.use_fcm_channel",
                        icon = Icons.Outlined.NotificationsActive,
                        title = stringResource(R.string.label_use_fcm_channel),
                        subtitle = stringResource(R.string.label_use_fcm_channel_hint),
                        checked = viewModel.useFcmChannel,
                        onCheckedChange = { viewModel.updateUseFcmChannel(context, it) },
                    )
                }
            }
            if (viewModel.isChannelModeLoaded && !viewModel.useFcmChannel) {
                item {
                    SettingsRow(
                        testTag = "row.settings.private_transport",
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.label_private_transport_status),
                        subtitle = viewModel.privateTransportStatus,
                        onClick = null,
                    )
                }
            }

            item {
                SettingsSectionHeader(text = stringResource(R.string.section_security))
            }
            item {
                val statusText = stringResource(
                    if (viewModel.isDecryptionConfigured) {
                        R.string.label_decryption_configured
                    } else {
                        R.string.label_decryption_not_configured
                    }
                )
                val statusColor = if (viewModel.isDecryptionConfigured) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                }
                DecryptionSettingsRow(
                    testTag = "row.settings.decryption",
                    statusText = statusText,
                    statusColor = statusColor,
                    onAction = { showDecryptionSheet = true },
                )
            }

            item {
                SettingsSectionHeader(text = stringResource(R.string.section_data_pages))
            }
            item {
                DataPageChipGroupRow(
                    rowTestTag = "row.settings.page.visibility",
                    icon = Icons.Outlined.Memory,
                    title = stringResource(R.string.section_data_pages),
                    hint = stringResource(R.string.label_data_page_toggle_hint),
                    messageTitle = stringResource(R.string.tab_messages),
                    eventTitle = stringResource(R.string.label_send_type_event),
                    thingTitle = stringResource(R.string.label_send_type_thing),
                    messageEnabled = viewModel.isMessagePageEnabled,
                    eventEnabled = viewModel.isEventPageEnabled,
                    thingEnabled = viewModel.isThingPageEnabled,
                    onMessageToggle = { viewModel.updateMessagePageVisibility(it) },
                    onEventToggle = { viewModel.updateEventPageVisibility(it) },
                    onThingToggle = { viewModel.updateThingPageVisibility(it) },
                )
            }

            item {
                SettingsSectionHeader(text = stringResource(R.string.section_about))
            }
            item {
                SettingsRow(
                    testTag = "row.settings.app_version",
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.label_app_version),
                    subtitle = appVersionText,
                    onClick = null,
                )
            }
        }
    }

    if (showDecryptionSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            modifier = Modifier.testTag("sheet.settings.decryption"),
            onDismissRequest = { showDecryptionSheet = false },
            sheetState = sheetState,
            containerColor = PushGoSheetContainerColor(),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.section_decryption),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.label_decryption_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                DecryptionKeyForm(
                    viewModel = viewModel,
                    onSave = {
                        viewModel.saveDecryptionConfig()
                        showDecryptionSheet = false
                    },
                    fillRemaining = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showGatewaySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            modifier = Modifier.testTag("sheet.settings.gateway"),
            onDismissRequest = { showGatewaySheet = false },
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
                    text = stringResource(R.string.label_gateway_settings),
                    style = MaterialTheme.typography.titleLarge,
                )
                GatewaySection(viewModel = viewModel)
            }
        }
    }

}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(
                start = ScreenHorizontalPadding,
                end = ScreenHorizontalPadding,
                top = 16.dp,
                bottom = 4.dp
            )
            .semantics { heading() },
    )
}

@Composable
private fun SettingsItemContainer(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding)
    ) {
        content()
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
            modifier = Modifier.padding(start = 72.dp),
        )
    }
}

@Composable
private fun SettingsRow(
    testTag: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable { onClick() }
    } else {
        Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    }
    SettingsItemContainer {
        ListItem(
            modifier = modifier,
            headlineContent = { Text(title) },
            supportingContent = { if (!subtitle.isNullOrBlank()) Text(subtitle) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

@Composable
private fun DecryptionSettingsRow(
    testTag: String? = null,
    statusText: String,
    statusColor: androidx.compose.ui.graphics.Color,
    onAction: () -> Unit,
) {
    SettingsItemContainer {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .clickable { onAction() },
            headlineContent = { Text(stringResource(R.string.section_decryption)) },
            supportingContent = { Text(text = statusText, color = statusColor) },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    rowTestTag: String? = null,
    switchTestTag: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsItemContainer {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (rowTestTag != null) Modifier.testTag(rowTestTag) else Modifier),
            headlineContent = { Text(title) },
            supportingContent = { if (!subtitle.isNullOrBlank()) Text(subtitle) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Switch(
                    modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier,
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DataPageChipGroupRow(
    rowTestTag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String,
    messageTitle: String,
    eventTitle: String,
    thingTitle: String,
    messageEnabled: Boolean,
    eventEnabled: Boolean,
    thingEnabled: Boolean,
    onMessageToggle: (Boolean) -> Unit,
    onEventToggle: (Boolean) -> Unit,
    onThingToggle: (Boolean) -> Unit,
) {
    SettingsItemContainer {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(rowTestTag),
            headlineContent = { Text(text = title) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 3,
                        maxLines = 2,
                    ) {
                        DataPageFilterChip(
                            title = messageTitle,
                            selected = messageEnabled,
                            testTag = "switch.settings.page.messages",
                            onToggle = onMessageToggle,
                        )
                        DataPageFilterChip(
                            title = eventTitle,
                            selected = eventEnabled,
                            testTag = "switch.settings.page.events",
                            onToggle = onEventToggle,
                        )
                        DataPageFilterChip(
                            title = thingTitle,
                            selected = thingEnabled,
                            testTag = "switch.settings.page.things",
                            onToggle = onThingToggle,
                        )
                    }
                }
            },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

@Composable
private fun DataPageFilterChip(
    title: String,
    selected: Boolean,
    testTag: String,
    onToggle: (Boolean) -> Unit,
) {
    FilterChip(
        modifier = Modifier.testTag(testTag),
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(title) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun NotificationCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("banner.settings.notifications_disabled")
            .padding(horizontal = 24.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.label_enable_notifications),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = stringResource(R.string.label_enable_notifications_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
            Button(
                modifier = Modifier.testTag("action.settings.open_notification_settings"),
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.label_turn_on))
            }
        }
    }
}


private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    context.startActivity(intent)
}

private fun isFcmSupported(context: Context): Boolean {
    val availability = GoogleApiAvailability.getInstance()
    return availability.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
}

@Composable
private fun GatewaySection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = viewModel.gatewayAddress,
            onValueChange = viewModel::updateGatewayAddress,
            label = { Text(stringResource(R.string.label_server_address)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("field.settings.gateway.address"),
            singleLine = true,
        )
        OutlinedTextField(
            value = viewModel.gatewayToken,
            onValueChange = viewModel::updateGatewayToken,
            label = { Text(stringResource(R.string.label_server_token)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("field.settings.gateway.token"),
            singleLine = true,
        )
        Button(
            onClick = { viewModel.saveGatewayConfig(context) },
            enabled = !viewModel.isSavingGateway,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("action.settings.gateway.save"),
        ) {
            Text(stringResource(R.string.label_save_gateway))
        }
    }
}
