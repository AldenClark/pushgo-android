package io.ethan.pushgo.ui.screens

import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.ethan.pushgo.R
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.data.model.KeyLength
import io.ethan.pushgo.data.model.ThemeMode
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.notifications.RingtoneCatalog
import io.ethan.pushgo.ui.PushGoViewModelFactory
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.ui.export.ExportUtils
import io.ethan.pushgo.ui.ringtone.RingtonePreviewPlayer
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import io.ethan.pushgo.BuildConfig

@Composable
fun SettingsScreen(
    navController: NavController,
    factory: PushGoViewModelFactory
) {
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val noMessagesExportText = stringResource(R.string.no_messages_available_to_export)
    val exportSuccessText = stringResource(R.string.message_json_exported_successfully)
    val channelIdCopiedText = stringResource(R.string.label_channel_id_copied)
    var expandGateway by remember { mutableStateOf(false) }
    var expandAppearance by remember { mutableStateOf(false) }
    var expandLanguage by remember { mutableStateOf(false) }
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

    val exportLauncher = rememberLauncherForActivityResult(
        contract = object : ActivityResultContracts.CreateDocument("application/json") {
            override fun createIntent(context: Context, input: String): Intent {
                val intent = super.createIntent(context, input)
                return Intent.createChooser(intent, context.getString(R.string.export_all_messages_json))
            }
        },
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val messages = viewModel.loadAllMessages()
            if (messages.isEmpty()) {
                Toast.makeText(context, noMessagesExportText, Toast.LENGTH_SHORT).show()
                announceForAccessibility(context, noMessagesExportText)
                return@launch
            }
            ExportUtils.writeMessagesJson(context, uri, messages)
            Toast.makeText(context, exportSuccessText, Toast.LENGTH_SHORT).show()
            announceForAccessibility(context, exportSuccessText)
        }
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.tab_settings),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
            )
        }

        if (!notificationsEnabled) {
            item {
                NotificationCard(
                    onClick = { openNotificationSettings(context) },
                )
            }
        }
        item {
            SettingsSectionHeader(text = stringResource(R.string.section_channels))
        }
        item {
            SettingsRow(
                icon = Icons.Outlined.Group,
                title = stringResource(R.string.label_channel_manage),
                subtitle = null,
                expanded = false,
                onClick = { navController.navigate("settings/channels") },
            )
        }
        item {
            SettingsSectionHeader(text = stringResource(R.string.section_connection_device))
        }
        item {
            SettingsRow(
                icon = Icons.Outlined.Dns,
                title = stringResource(R.string.label_gateway_settings),
                subtitle = stringResource(R.string.label_gateway_settings_hint),
                expanded = expandGateway,
                onClick = { expandGateway = !expandGateway },
            )
        }
        if (expandGateway) {
            item {
                GatewaySection(viewModel = viewModel)
            }
        }

        item {
            SettingsSectionHeader(text = stringResource(R.string.section_security))
        }
        item {
            SettingsRow(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.section_decryption),
                subtitle = stringResource(R.string.label_decryption_subtitle),
                expanded = false,
                onClick = { navController.navigate("settings/decryption") },
            )
        }

        item {
            SettingsSectionHeader(text = stringResource(R.string.section_notifications_sounds))
        }
        val selectedRingtone = RingtoneCatalog.findById(viewModel.ringtoneId)
        item {
            SettingsRow(
                icon = Icons.Outlined.LibraryMusic,
                title = stringResource(R.string.section_ringtone),
                subtitle = stringResource(selectedRingtone.displayNameRes),
                expanded = false,
                onClick = { navController.navigate("settings/ringtone") },
            )
        }

        item {
            SettingsSectionHeader(text = stringResource(R.string.section_storage))
        }
        item {
            SettingsRow(
                icon = Icons.Outlined.FileUpload,
                title = stringResource(R.string.export_all_messages_json),
                subtitle = stringResource(R.string.label_export_messages_hint),
                expanded = false,
                onClick = { exportLauncher.launch("pushgo-messages-all.json") },
            )
        }
        item {
            SettingsSwitchRow(
                icon = Icons.Outlined.DeleteSweep,
                title = stringResource(R.string.auto_cleanup_messages),
                subtitle = stringResource(R.string.auto_cleanup_messages_hint),
                checked = viewModel.autoCleanupEnabled,
                onCheckedChange = viewModel::updateAutoCleanupEnabled,
            )
        }

        item {
            SettingsSectionHeader(text = stringResource(R.string.section_personalization))
        }
        item {
            SettingsRow(
                icon = Icons.Outlined.Settings,
                title = stringResource(R.string.section_appearance),
                subtitle = stringResource(R.string.label_theme),
                expanded = expandAppearance,
                onClick = { expandAppearance = !expandAppearance },
            )
        }
        if (expandAppearance) {
            item {
                AppearanceSection(
                    current = viewModel.themeMode,
                    onChange = viewModel::updateThemeMode,
                )
            }
        }
        item {
            SettingsRow(
                icon = Icons.Outlined.Settings,
                title = stringResource(R.string.section_language),
                subtitle = stringResource(R.string.label_language),
                expanded = expandLanguage,
                onClick = { expandLanguage = !expandLanguage },
            )
        }
        if (expandLanguage) {
            item { LanguageSection() }
        }

        item {
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.label_app_version),
                subtitle = appVersionText,
                expanded = false,
                onClick = null,
            )
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
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onClick: (() -> Unit)?,
) {
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    } else {
        Modifier.fillMaxWidth()
    }
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
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        thickness = 1.dp,
        modifier = Modifier.padding(start = 72.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
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
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        thickness = 1.dp,
        modifier = Modifier.padding(start = 72.dp),
    )
}

@Composable
private fun NotificationCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = stringResource(themeModeLabel(current)),
                onValueChange = {},
                label = { Text(stringResource(R.string.label_theme)) },
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ThemeMode.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(themeModeLabel(option))) },
                        onClick = {
                            onChange(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
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
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = viewModel.gatewayToken,
            onValueChange = viewModel::updateGatewayToken,
            label = { Text(stringResource(R.string.label_server_token)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = { viewModel.saveGatewayConfig(context) },
            enabled = !viewModel.isSavingGateway,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.label_save_gateway))
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DecryptionSection(viewModel: SettingsViewModel) {
    var showKey by remember { mutableStateOf(false) }
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    val lastUpdated = viewModel.decryptionUpdatedAt?.atZone(ZoneId.systemDefault())?.let { formatter.format(it) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (viewModel.isDecryptionConfigured) {
            Text(
                text = if (lastUpdated.isNullOrBlank()) {
                    stringResource(R.string.label_decryption_configured)
                } else {
                    stringResource(R.string.label_decryption_configured_updated, lastUpdated)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                text = stringResource(R.string.label_decryption_not_configured),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            text = stringResource(R.string.label_key_strength),
            style = MaterialTheme.typography.titleMedium,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            KeyLength.entries.forEach { option ->
                SegmentedButton(
                    selected = option == viewModel.keyLength,
                    onClick = { viewModel.updateKeyLength(option) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = KeyLength.entries.indexOf(option),
                        count = KeyLength.entries.size,
                    ),
                ) {
                    Text("${option.bytes * 8}-bit")
                }
            }
        }
        Text(
            text = stringResource(R.string.label_key_strength_hint),
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            text = stringResource(R.string.label_configuration),
            style = MaterialTheme.typography.titleMedium,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            KeyEncoding.entries.forEach { option ->
                SegmentedButton(
                    selected = option == viewModel.keyEncoding,
                    onClick = { viewModel.updateKeyEncoding(option) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = KeyEncoding.entries.indexOf(option),
                        count = KeyEncoding.entries.size,
                    ),
                ) {
                    Text(stringResource(keyEncodingLabel(option)))
                }
            }
        }

        OutlinedTextField(
            value = viewModel.decryptionKeyInput,
            onValueChange = viewModel::updateDecryptionKeyInput,
            label = { Text(stringResource(R.string.label_notification_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showKey) {
                androidx.compose.ui.text.input.VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = if (showKey) {
                            stringResource(R.string.label_hide_key)
                        } else {
                            stringResource(R.string.label_show_key)
                        },
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(),
        )

        Text(
            text = stringResource(R.string.label_decryption_hint),
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = viewModel::saveDecryptionConfig,
            enabled = !viewModel.isSavingDecryption,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.label_save_decryption))
        }
    }
}

@Composable
private fun RingtoneLibraryDialog(
    onDismiss: () -> Unit,
    player: RingtonePreviewPlayer,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.section_ringtone)) },
        text = {
            LazyColumn(
                modifier = Modifier.height(300.dp)
            ) {
                items(RingtoneCatalog.catalog) { ringtone ->
                    val isSelected = ringtone.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(ringtone.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(ringtone.displayNameRes),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        IconButton(onClick = { player.play(ringtone) }) {
                            Icon(
                                Icons.Outlined.PlayCircle,
                                contentDescription = stringResource(R.string.label_preview_ringtone),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { onSelect(ringtone.id) },
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.label_close))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSection() {
    val options = remember {
        listOf(
            LanguageOption(R.string.label_language_system, null),
            LanguageOption(R.string.label_language_english, "en"),
            LanguageOption(R.string.label_language_simplified_chinese, "zh-CN"),
            LanguageOption(R.string.label_language_traditional_chinese, "zh-TW"),
            LanguageOption(R.string.label_language_japanese, "ja"),
            LanguageOption(R.string.label_language_korean, "ko"),
            LanguageOption(R.string.label_language_german, "de"),
            LanguageOption(R.string.label_language_french, "fr"),
            LanguageOption(R.string.label_language_spanish, "es"),
        )
    }
    val currentTag = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
    var expanded by remember { mutableStateOf(false) }
    var selectedTag by remember(currentTag) { mutableStateOf(currentTag) }
    val selected = options.firstOrNull { it.localeTag == selectedTag } ?: options.first()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = stringResource(selected.labelRes),
                onValueChange = {},
                label = { Text(stringResource(R.string.label_language)) },
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes)) },
                        onClick = {
                            selectedTag = option.localeTag
                            expanded = false
                            val locales = option.localeTag?.let {
                                LocaleListCompat.forLanguageTags(it)
                            } ?: LocaleListCompat.getEmptyLocaleList()
                            AppCompatDelegate.setApplicationLocales(locales)
                        },
                    )
                }
            }
        }
    }
}

private fun keyEncodingLabel(encoding: KeyEncoding): Int {
    return when (encoding) {
        KeyEncoding.BASE64 -> R.string.label_key_encoding_base64
        KeyEncoding.HEX -> R.string.label_key_encoding_hex
        KeyEncoding.PLAINTEXT -> R.string.label_key_encoding_plaintext
    }
}

private fun themeModeLabel(mode: ThemeMode): Int {
    return when (mode) {
        ThemeMode.SYSTEM -> R.string.label_theme_system
        ThemeMode.LIGHT -> R.string.label_theme_light
        ThemeMode.DARK -> R.string.label_theme_dark
    }
}

private data class LanguageOption(
    @param:StringRes val labelRes: Int,
    val localeTag: String?,
)
