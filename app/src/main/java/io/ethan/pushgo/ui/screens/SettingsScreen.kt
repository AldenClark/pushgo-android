package io.ethan.pushgo.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.rememberBottomGestureInset
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import io.ethan.pushgo.ui.theme.pushGoDangerButtonColors
import io.ethan.pushgo.ui.theme.pushGoOutlinedTextFieldColors
import io.ethan.pushgo.ui.theme.pushGoPrimaryButtonColors
import io.ethan.pushgo.ui.theme.pushGoPrimaryButtonElevation
import io.ethan.pushgo.ui.theme.pushGoSegmentedButtonColors
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel

import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.data.AppConstants
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.ethan.pushgo.update.UpdateCandidate

private val ScreenHorizontalPadding = 12.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenConnectionDiagnosis: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    val fcmSupported = remember(context) { isFcmSupported(context) }
    var notificationsEnabled by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val baseVersionName = remember {
        BuildConfig.VERSION_NAME.replace(Regex("(?i)\\s*build\\s*\\d+\\s*$"), "").trim()
    }
    val appVersionText = stringResource(
        R.string.label_app_version_format,
        baseVersionName,
    )
    var showDecryptionSheet by remember { mutableStateOf(false) }
    var showGatewaySheet by remember { mutableStateOf(false) }
    val bottomGestureInset = rememberBottomGestureInset()

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

    LaunchedEffect(fcmSupported, viewModel.isChannelModeLoaded) {
        if (viewModel.isChannelModeLoaded) {
            viewModel.ensurePrivateTransportWhenFcmUnsupported(context)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.refreshUpdateState(manual = false)
    }

    Scaffold(
        modifier = Modifier.testTag("screen.settings.content"),
        topBar = {
            Column(
                modifier = Modifier.background(uiColors.surfaceBase),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.label_back),
                                tint = uiColors.textPrimary,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.tab_settings),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        color = uiColors.textPrimary,
                    )
                }
                PushGoDividerSubtle()
            }
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(bottom = bottomGestureInset + 24.dp),
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
            if (viewModel.isChannelModeLoaded) {
                item {
                    TransportSelectorRow(
                        rowTestTag = "row.settings.notification_transport",
                        icon = Icons.Outlined.NotificationsActive,
                        title = stringResource(R.string.label_notification_transport),
                        subtitle = if (!fcmSupported && viewModel.gatewayPrivateChannelEnabled == false) {
                            stringResource(R.string.label_notification_transport_unavailable_hint)
                        } else if (fcmSupported && viewModel.gatewayPrivateChannelEnabled == false) {
                            stringResource(R.string.label_notification_transport_gateway_private_disabled_hint)
                        } else if (fcmSupported) {
                            stringResource(R.string.label_notification_transport_hint)
                        } else {
                            stringResource(R.string.label_notification_transport_private_only_hint)
                        },
                        selectedUseFcm = (viewModel.useFcmChannel && fcmSupported)
                            || viewModel.gatewayPrivateChannelEnabled == false,
                        isFcmSupported = fcmSupported,
                        isPrivateSupported = viewModel.gatewayPrivateChannelEnabled != false,
                        onSelectUseFcm = { useFcm -> viewModel.updateUseFcmChannel(context, useFcm) },
                    )
                }
            }
            if (
                viewModel.isChannelModeLoaded
                && viewModel.gatewayPrivateChannelEnabled != false
                && (!fcmSupported || !viewModel.useFcmChannel)
            ) {
                item {
                    SettingsRow(
                        testTag = "row.settings.private_transport",
                        icon = Icons.Outlined.NotificationsActive,
                        title = stringResource(R.string.label_private_transport_status),
                        subtitle = viewModel.privateTransportStatus,
                        onClick = null,
                    )
                }
            }
            item {
                SettingsRow(
                    testTag = "row.settings.connection_diagnosis",
                    icon = Icons.Outlined.Dns,
                    title = stringResource(R.string.label_connection_diagnosis),
                    subtitle = stringResource(R.string.label_connection_diagnosis_hint),
                    onClick = onOpenConnectionDiagnosis,
                )
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
                    uiColors.stateInfo.foreground
                } else {
                    uiColors.stateDanger.foreground
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
                SettingsSectionHeader(text = stringResource(R.string.section_updates))
            }
            item {
                SettingsToggleRow(
                    testTag = "switch.settings.update.auto_check",
                    icon = Icons.Outlined.NotificationsActive,
                    title = stringResource(R.string.label_update_auto_check),
                    subtitle = stringResource(R.string.label_update_auto_check_hint),
                    checked = viewModel.updateAutoCheckEnabled,
                    onCheckedChange = { viewModel.updateAutoCheckEnabled(context, it) },
                )
            }
            item {
                UpdateChannelSelectorRow(
                    rowTestTag = "row.settings.update.channel",
                    title = stringResource(R.string.label_update_channel),
                    subtitle = stringResource(R.string.label_update_channel_hint),
                    betaEnabled = viewModel.updateBetaChannelEnabled,
                    onToggleBeta = { viewModel.updateBetaChannelEnabled(context, it) },
                )
            }
            item {
                val subtitle = when {
                    viewModel.isCheckingUpdates -> stringResource(R.string.label_update_status_checking)
                    viewModel.availableUpdate != null -> stringResource(
                        R.string.label_update_status_available,
                        viewModel.availableUpdate?.versionName ?: "",
                    )
                    viewModel.updateSuppressedBySkip -> stringResource(R.string.label_update_status_skipped)
                    viewModel.updateSuppressedByCooldown -> stringResource(R.string.label_update_status_cooldown)
                    else -> stringResource(R.string.label_update_status_idle)
                }
                SettingsRow(
                    testTag = "row.settings.update.check_now",
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.label_update_check_now),
                    subtitle = subtitle,
                    onClick = { viewModel.refreshUpdateState(manual = true) },
                )
            }
            if (viewModel.availableUpdate != null) {
                item {
                    UpdateCandidateCard(
                        candidate = viewModel.availableUpdate!!,
                        installing = viewModel.isInstallingUpdate,
                        onInstall = { viewModel.installAvailableUpdate() },
                        onSkip = { viewModel.skipAvailableUpdate() },
                        onRemindLater = { viewModel.remindLaterForAvailableUpdate() },
                    )
                }
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
        PushGoModalBottomSheet(
            modifier = Modifier.testTag("sheet.settings.decryption"),
            onDismissRequest = { showDecryptionSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = bottomGestureInset + 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.section_decryption),
                    style = MaterialTheme.typography.titleLarge,
                )
                DecryptionKeyForm(
                    viewModel = viewModel,
                    onSave = {
                        viewModel.saveDecryptionConfig()
                        showDecryptionSheet = false
                    },
                    fillRemaining = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
    }

    if (showGatewaySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        PushGoModalBottomSheet(
            modifier = Modifier.testTag("sheet.settings.gateway"),
            onDismissRequest = { showGatewaySheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = bottomGestureInset + 16.dp),
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

    if (viewModel.shouldShowPrivateChannelWhitelistDialog) {
        PushGoAlertDialog(
            onDismissRequest = viewModel::consumePrivateChannelWhitelistDialog,
            title = { Text(text = stringResource(R.string.dialog_private_channel_whitelist_title)) },
            text = { Text(text = stringResource(R.string.dialog_private_channel_whitelist_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::consumePrivateChannelWhitelistDialog) {
                    Text(text = stringResource(R.string.label_got_it))
                }
            },
        )
    }

    if (viewModel.shouldShowInstallPermissionDialog) {
        PushGoAlertDialog(
            onDismissRequest = viewModel::consumeInstallPermissionDialog,
            title = { Text(text = stringResource(R.string.label_update_install_permission_title)) },
            text = { Text(text = stringResource(R.string.label_update_install_permission_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.consumeInstallPermissionDialog()
                        openUnknownAppSourcesSettings(context)
                    },
                ) {
                    Text(text = stringResource(R.string.label_turn_on))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::consumeInstallPermissionDialog) {
                    Text(text = stringResource(R.string.label_cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    val uiColors = PushGoThemeExtras.colors
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = uiColors.accentPrimary,
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
    val uiColors = PushGoThemeExtras.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding)
    ) {
        content()
        HorizontalDivider(
            color = uiColors.dividerStrong,
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
    val uiColors = PushGoThemeExtras.colors
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
                    tint = uiColors.textSecondary,
                )
            },
            trailingContent = {
                if (onClick != null) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = uiColors.textSecondary,
                    )
                }
            },
        )
    }
}

@Composable
private fun SettingsToggleRow(
    testTag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val uiColors = PushGoThemeExtras.colors
    SettingsItemContainer {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
                .clickable { onCheckedChange(!checked) },
            headlineContent = { Text(title) },
            supportingContent = { if (!subtitle.isNullOrBlank()) Text(subtitle) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = uiColors.textSecondary,
                )
            },
            trailingContent = {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            },
        )
    }
}

@Composable
private fun UpdateChannelSelectorRow(
    rowTestTag: String,
    title: String,
    subtitle: String,
    betaEnabled: Boolean,
    onToggleBeta: (Boolean) -> Unit,
) {
    val uiColors = PushGoThemeExtras.colors
    SettingsItemContainer {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(rowTestTag),
            headlineContent = { Text(title) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(subtitle)
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("segmented.settings.update.channel"),
                    ) {
                        SegmentedButton(
                            selected = !betaEnabled,
                            onClick = { if (betaEnabled) onToggleBeta(false) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier
                                .widthIn(min = 132.dp)
                                .testTag("option.settings.update.channel.stable"),
                            icon = {},
                            colors = pushGoSegmentedButtonColors(),
                        ) {
                            Text(text = stringResource(R.string.label_update_channel_stable))
                        }
                        SegmentedButton(
                            selected = betaEnabled,
                            onClick = { if (!betaEnabled) onToggleBeta(true) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier
                                .widthIn(min = 132.dp)
                                .testTag("option.settings.update.channel.beta"),
                            icon = {},
                            colors = pushGoSegmentedButtonColors(),
                        ) {
                            Text(text = stringResource(R.string.label_update_channel_beta_plus_stable))
                        }
                    }
                }
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = uiColors.textSecondary,
                )
            },
        )
    }
}

@Composable
private fun UpdateCandidateCard(
    candidate: UpdateCandidate,
    installing: Boolean,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onRemindLater: () -> Unit,
) {
    val uiColors = PushGoThemeExtras.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp)
            .testTag("card.settings.update.available"),
        colors = CardDefaults.cardColors(
            containerColor = PushGoThemeExtras.colors.stateInfo.background,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.label_update_available_title, candidate.versionName),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = candidate.notes?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.label_update_available_body),
                style = MaterialTheme.typography.bodyMedium,
                color = uiColors.textSecondary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !installing,
                    onClick = onInstall,
                    modifier = Modifier.testTag("action.settings.update.install"),
                    colors = pushGoPrimaryButtonColors(),
                ) {
                    Text(text = stringResource(R.string.label_update_install_now))
                }
                TextButton(
                    enabled = !installing,
                    onClick = onRemindLater,
                    modifier = Modifier.testTag("action.settings.update.remind_later"),
                ) {
                    Text(text = stringResource(R.string.label_update_remind_later))
                }
                TextButton(
                    enabled = !installing,
                    onClick = onSkip,
                    modifier = Modifier.testTag("action.settings.update.skip"),
                ) {
                    Text(text = stringResource(R.string.label_update_skip_version))
                }
            }
        }
    }
}

@Composable
private fun DecryptionSettingsRow(
    testTag: String? = null,
    statusText: String,
    statusColor: androidx.compose.ui.graphics.Color,
    onAction: () -> Unit,
) {
    val uiColors = PushGoThemeExtras.colors
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
                    tint = uiColors.textSecondary,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = uiColors.textSecondary,
                )
            },
        )
    }
}

@Composable
private fun TransportSelectorRow(
    rowTestTag: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    selectedUseFcm: Boolean,
    isFcmSupported: Boolean,
    isPrivateSupported: Boolean,
    onSelectUseFcm: (Boolean) -> Unit,
) {
    val uiColors = PushGoThemeExtras.colors
    SettingsItemContainer {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (rowTestTag != null) Modifier.testTag(rowTestTag) else Modifier),
            headlineContent = { Text(title) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!subtitle.isNullOrBlank()) {
                        Text(subtitle)
                    }
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.testTag("segmented.settings.notification_transport"),
                    ) {
                        SegmentedButton(
                            selected = selectedUseFcm,
                            onClick = {
                                if (!selectedUseFcm) {
                                    onSelectUseFcm(true)
                                }
                            },
                            enabled = isFcmSupported,
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier.testTag("option.settings.notification_transport.fcm"),
                            icon = {},
                            colors = pushGoSegmentedButtonColors(),
                        ) {
                            Text(
                                text = stringResource(R.string.label_transport_fcm),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                        SegmentedButton(
                            selected = !selectedUseFcm,
                            onClick = {
                                if (selectedUseFcm) {
                                    onSelectUseFcm(false)
                                }
                            },
                            enabled = isPrivateSupported,
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier.testTag("option.settings.notification_transport.private"),
                            icon = {},
                            colors = pushGoSegmentedButtonColors(),
                        ) {
                            Text(
                                text = stringResource(R.string.label_transport_private),
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = uiColors.textSecondary,
                )
            },
        )
    }
}

@Composable
private fun DataPageChipGroupRow(
    rowTestTag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
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
    val uiColors = PushGoThemeExtras.colors
    SettingsItemContainer {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(rowTestTag),
            headlineContent = { Text(text = title) },
            supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = uiColors.textSecondary,
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
            selectedContainerColor = PushGoThemeExtras.colors.selectionFill,
            selectedLabelColor = PushGoThemeExtras.colors.stateInfo.foreground,
        ),
    )
}

@Composable
private fun NotificationCard(onClick: () -> Unit) {
    val uiColors = PushGoThemeExtras.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("banner.settings.notifications_disabled")
            .padding(horizontal = 24.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = uiColors.stateDanger.background,
            contentColor = uiColors.stateDanger.foreground,
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
                    .background(uiColors.stateDanger.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = uiColors.stateDanger.foreground,
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
                    color = uiColors.stateDanger.foreground,
                )
            }
            Button(
                modifier = Modifier.testTag("action.settings.open_notification_settings"),
                onClick = onClick,
                colors = pushGoDangerButtonColors(),
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
    startActivityOrFallback(context, intent) {
        openAppDetailsSettings(context)
    }
}

private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    startActivityOrFallback(context, intent)
}

private fun openUnknownAppSourcesSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.fromParts("package", context.packageName, null),
    )
    startActivityOrFallback(context, intent) {
        openAppDetailsSettings(context)
    }
}

private fun startActivityOrFallback(
    context: Context,
    intent: Intent,
    fallback: (() -> Unit)? = null,
) {
    val launchIntent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(launchIntent) }
        .onFailure { fallback?.invoke() }
}

private fun isFcmSupported(context: Context): Boolean {
    val availability = GoogleApiAvailability.getInstance()
    return availability.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
}

@Composable
private fun GatewaySection(viewModel: SettingsViewModel) {
    val uiColors = PushGoThemeExtras.colors
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        GatewaySheetInputField(
            value = viewModel.gatewayAddress,
            onValueChange = viewModel::updateGatewayAddress,
            labelText = stringResource(R.string.label_server_address),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("field.settings.gateway.address"),
        )
        GatewaySheetInputField(
            value = viewModel.gatewayToken,
            onValueChange = viewModel::updateGatewayToken,
            labelText = stringResource(R.string.label_server_token),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("field.settings.gateway.token"),
        )
        Text(
            text = stringResource(R.string.label_gateway_change_channel_reset_hint),
            style = MaterialTheme.typography.bodySmall,
            color = uiColors.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.saveGatewayConfig(context) },
            enabled = !viewModel.isSavingGateway,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("action.settings.gateway.save")
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = pushGoPrimaryButtonColors(),
            elevation = pushGoPrimaryButtonElevation(),
        ) {
            Text(stringResource(R.string.label_save_gateway))
        }
    }
}

@Composable
private fun GatewaySheetInputField(
    value: String,
    onValueChange: (String) -> Unit,
    labelText: String,
    modifier: Modifier = Modifier,
) {
    val uiColors = PushGoThemeExtras.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(
                text = labelText.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = uiColors.stateInfo.foreground,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                ),
            )
        },
        placeholder = {
            Text(
                text = labelText,
                color = uiColors.placeholderText,
            )
        },
        modifier = modifier,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = pushGoOutlinedTextFieldColors(),
    )
}
