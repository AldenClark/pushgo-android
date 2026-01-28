package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.widget.Toast
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppConstants
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.ChannelPasswordException
import io.ethan.pushgo.data.ChannelPasswordValidator
import io.ethan.pushgo.data.ChannelSubscriptionService
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.MessagePostProcessWorker
import io.ethan.pushgo.notifications.NotificationDecryptor
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.notifications.RingtoneCatalog
import io.ethan.pushgo.ui.announceForAccessibility
import io.ethan.pushgo.util.UrlValidators
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.navigation.NavController
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Instant
import java.util.UUID

private data class TestPushForm(
    val title: String,
    val body: String,
    val url: String,
    val channelId: String,
    val icon: String,
    val image: String,
    val ciphertext: String,
    val soundId: String?,
)

@Composable
fun PushScreen(
    container: AppContainer,
    navController: NavController,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val serverAddress by container.settingsRepository.serverAddressFlow.collectAsState(initial = null)
    val displayServerAddress = serverAddress ?: AppConstants.defaultServerAddress
    val defaultTitle = stringResource(R.string.push_default_title)
    val defaultBody = stringResource(R.string.push_default_body)
    val sendTestNotificationText = stringResource(R.string.label_send_test_notification)
    val sendLocalNotificationText = stringResource(R.string.label_send_local_notification)
    val pushService = remember { ChannelSubscriptionService() }
    var subscriptions by remember { mutableStateOf(emptyList<ChannelSubscription>()) }

    var form by remember(defaultTitle, defaultBody) {
        mutableStateOf(
            TestPushForm(
                title = defaultTitle,
                body = defaultBody,
                url = "",
                channelId = "",
                icon = "",
                image = "",
                ciphertext = "",
                soundId = null,
            )
        )
    }

    LaunchedEffect(Unit) {
        subscriptions = container.channelRepository.loadSubscriptions()
    }

    LaunchedEffect(subscriptions) {
        if (subscriptions.isEmpty()) {
            if (form.channelId.isNotBlank()) {
                form = form.copy(channelId = "")
            }
        } else if (form.channelId.isBlank() || subscriptions.none { it.channelId == form.channelId }) {
            form = form.copy(channelId = subscriptions.first().channelId)
        }
    }

    val gatewayJson = remember(form) { buildGatewayPayload(form) }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.tab_push),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.label_server_address),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = displayServerAddress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(R.string.section_push_form), style = MaterialTheme.typography.titleMedium)
                ChannelSelector(
                    selectedId = form.channelId,
                    subscriptions = subscriptions,
                    onSelect = { form = form.copy(channelId = it.channelId) },
                )
                if (subscriptions.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.label_channel_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.label_channel_empty_action),
                            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                navController.navigate("settings/channels")
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = form.title,
                    onValueChange = { form = form.copy(title = it) },
                    label = { Text(stringResource(R.string.label_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.body,
                    onValueChange = { form = form.copy(body = it) },
                    label = { Text(stringResource(R.string.label_body)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = form.url,
                    onValueChange = { form = form.copy(url = it) },
                    label = { Text(stringResource(R.string.label_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.icon,
                    onValueChange = { form = form.copy(icon = it) },
                    label = { Text(stringResource(R.string.label_icon_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.image,
                    onValueChange = { form = form.copy(image = it) },
                    label = { Text(stringResource(R.string.label_image_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.ciphertext,
                    onValueChange = { form = form.copy(ciphertext = it) },
                    label = { Text(stringResource(R.string.label_ciphertext)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                SoundSelector(
                    selectedId = form.soundId,
                    onSelect = { form = form.copy(soundId = it) },
                )
                val canSend = form.channelId.trim().isNotEmpty()
                Button(
                    onClick = {
                        scope.launch {
                            val channelId = form.channelId.trim()
                            if (channelId.isEmpty()) return@launch

                            val baseUrl = serverAddress?.trim()?.ifEmpty { null }
                                ?: AppConstants.defaultServerAddress
                            val token = container.settingsRepository.getGatewayToken()
                                ?.trim()
                                ?.ifEmpty { null }
                            val normalizedPassword = normalizeChannelPassword(
                                channelRepository = container.channelRepository,
                                channelId = channelId,
                                resources = resources,
                                context = context,
                            ) ?: return@launch

                            val payload = buildGatewayRequestPayload(form, normalizedPassword)
                            runCatching {
                                pushService.pushToChannel(
                                    baseUrl = baseUrl,
                                    token = token,
                                    payload = payload,
                                )
                            }.onSuccess {
                                Toast.makeText(context, sendTestNotificationText, Toast.LENGTH_SHORT).show()
                                announceForAccessibility(context, sendTestNotificationText)
                            }.onFailure { error ->
                                val message = error.message?.trim().orEmpty().ifEmpty { "Request failed" }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                announceForAccessibility(context, message)
                            }
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(sendTestNotificationText)
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val channelId = form.channelId.trim()
                            if (channelId.isEmpty()) return@launch
                            normalizeChannelPassword(
                                channelRepository = container.channelRepository,
                                channelId = channelId,
                                resources = resources,
                                context = context,
                            ) ?: return@launch
                            sendLocalNotification(
                                container = container,
                                context = context,
                                form = form,
                                successMessage = sendLocalNotificationText,
                            )
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(sendLocalNotificationText)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.label_gateway_payload), style = MaterialTheme.typography.titleMedium)
                
                var showMenu by remember { mutableStateOf(false) }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { showMenu = true }
                            )
                        }
                ) {
                    SelectionContainer {
                        Text(
                            text = gatewayJson.toString(2),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.label_copy_json)) },
                            onClick = {
                                scope.launch { clipboard.setText(AnnotatedString(gatewayJson.toString(2))) }
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundSelector(selectedId: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = selectedId?.let { RingtoneCatalog.findById(it) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { }) {
        OutlinedTextField(
            value = current?.let { stringResource(it.displayNameRes) } ?: "",
            onValueChange = {},
            label = { Text(stringResource(R.string.label_sound)) },
            placeholder = { Text(stringResource(R.string.label_sound_none)) },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.label_sound_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            RingtoneCatalog.catalog.forEach { ringtone ->
                DropdownMenuItem(
                    text = { Text(stringResource(ringtone.displayNameRes)) },
                    onClick = {
                        onSelect(ringtone.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelSelector(
    selectedId: String,
    subscriptions: List<ChannelSubscription>,
    onSelect: (ChannelSubscription) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val hasSubscriptions = subscriptions.isNotEmpty()
    val selected = subscriptions.firstOrNull { it.channelId == selectedId } ?: subscriptions.firstOrNull()
    val selectedLabel = if (selected != null) {
        stringResource(
            R.string.label_channel_option,
            selected.displayName,
            selected.channelId,
        )
    } else {
        ""
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (hasSubscriptions) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            label = { Text(stringResource(R.string.label_channel_id)) },
            placeholder = {
                if (!hasSubscriptions) {
                    Text(stringResource(R.string.label_channel_select_hint))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            singleLine = true,
            readOnly = true,
            enabled = hasSubscriptions,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded && hasSubscriptions,
            onDismissRequest = { expanded = false },
        ) {
            subscriptions.forEach { subscription ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.label_channel_option,
                                subscription.displayName,
                                subscription.channelId
                            )
                        )
                    },
                    onClick = {
                        onSelect(subscription)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun resolveSoundName(form: TestPushForm): String {
    val selectedId = form.soundId ?: return ""
    val ringtone = RingtoneCatalog.findById(selectedId)
    return ringtone.filename
}

private suspend fun normalizeChannelPassword(
    channelRepository: ChannelSubscriptionRepository,
    channelId: String,
    resources: android.content.res.Resources,
    context: android.content.Context,
): String? {
    val storedPassword = channelRepository.channelPassword(channelId) ?: ""
    return try {
        ChannelPasswordValidator.normalize(storedPassword)
    } catch (ex: ChannelPasswordException) {
        val text = resources.getString(ex.resId)
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        announceForAccessibility(context, text)
        null
    }
}

private fun buildLocalPayload(form: TestPushForm): Map<String, String> {
    val payload = linkedMapOf<String, String>()
    form.title.trim().takeIf { it.isNotEmpty() }?.let { payload["title"] = it }
    form.body.trim().takeIf { it.isNotEmpty() }?.let { payload["body"] = it }
    val channelId = form.channelId.trim()
    if (channelId.isNotEmpty()) {
        payload["channel_id"] = channelId
    }
    UrlValidators.normalizeHttpsUrl(form.url)?.let { payload["url"] = it }
    form.icon.trim().takeIf { it.isNotEmpty() }?.let { payload["icon"] = it }
    form.image.trim().takeIf { it.isNotEmpty() }?.let { payload["image"] = it }
    form.ciphertext.trim().takeIf { it.isNotEmpty() }?.let { payload["ciphertext"] = it }
    val soundName = resolveSoundName(form)
    if (soundName.isNotEmpty()) payload["sound"] = soundName
    return payload
}

private fun buildLocalRawPayload(
    form: TestPushForm,
    payload: Map<String, String>,
    decryptResult: NotificationDecryptor.Result,
): String {
    val json = JSONObject()
    payload.forEach { (key, value) -> json.put(key, value) }
    if (!payload.containsKey("title")) json.put("title", form.title)
    if (!payload.containsKey("body")) json.put("body", form.body)
    decryptResult.decryptedBody?.let { json.put("ciphertext_body", it) }
    decryptResult.image?.let { json.put("image", it) }
    decryptResult.icon?.let { json.put("icon", it) }
    decryptResult.decryptionState?.let { json.put("decryptionState", it.name) }
    return json.toString()
}

private suspend fun sendLocalNotification(
    container: AppContainer,
    context: android.content.Context,
    form: TestPushForm,
    successMessage: String,
) {
    val payload = buildLocalPayload(form)
    val keyBase64 = container.settingsRepository.getNotificationKeyBase64()
    val decryptResult = NotificationDecryptor.decryptIfNeeded(
        data = payload,
        title = form.title,
        body = form.body,
        keyBase64 = keyBase64,
    )
    val rawPayload = buildLocalRawPayload(form, payload, decryptResult)
    val message = PushMessage(
        id = UUID.randomUUID().toString(),
        messageId = null,
        title = decryptResult.title,
        body = decryptResult.body,
        channel = form.channelId.trim().ifEmpty { null },
        url = UrlValidators.normalizeHttpsUrl(form.url),
        isRead = false,
        receivedAt = Instant.now(),
        rawPayloadJson = rawPayload,
        status = MessageStatus.NORMAL,
        decryptionState = decryptResult.decryptionState,
        notificationId = null,
        serverId = null,
    )

    val repository = container.messageRepository
    repository.insert(message)

    val soundName = payload["sound"]
    val ringtoneOverride = soundName?.let { RingtoneCatalog.resolveBySoundValue(it)?.id }
    val preferredRingtone = container.settingsRepository.getRingtoneId()
    val ringtoneId = ringtoneOverride ?: preferredRingtone
    val imageUrl = UrlValidators.normalizeHttpsUrl(decryptResult.image)
        ?: UrlValidators.normalizeHttpsUrl(payload["image"])
    val iconUrl = UrlValidators.normalizeHttpsUrl(decryptResult.icon)
        ?: UrlValidators.normalizeHttpsUrl(payload["icon"])

    enqueuePostProcess(
        context = context,
        messageId = message.id,
        ringMode = null,
        ringtoneId = ringtoneId,
        imageUrl = imageUrl,
        iconUrl = iconUrl,
    )

    val unreadCount = repository.unreadCount()
    NotificationHelper.showMessageNotification(
        context = context,
        message = message,
        ringtoneId = ringtoneId,
        ringMode = null,
        level = null,
        unreadCount = unreadCount,
    )

    Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
    announceForAccessibility(context, successMessage)
}

private fun enqueuePostProcess(
    context: android.content.Context,
    messageId: String,
    ringMode: String?,
    ringtoneId: String?,
    imageUrl: String?,
    iconUrl: String?,
) {
    val input = workDataOf(
        MessagePostProcessWorker.KEY_MESSAGE_ID to messageId,
        MessagePostProcessWorker.KEY_RING_MODE to ringMode,
        MessagePostProcessWorker.KEY_RINGTONE_ID to ringtoneId,
        MessagePostProcessWorker.KEY_IMAGE_URL to imageUrl,
        MessagePostProcessWorker.KEY_ICON_URL to iconUrl,
    )
    val request = OneTimeWorkRequestBuilder<MessagePostProcessWorker>()
        .setInputData(input)
        .build()
    WorkManager.getInstance(context.applicationContext).enqueue(request)
}

private fun buildGatewayPayload(form: TestPushForm): JSONObject {
    return buildGatewayPayload(form, null)
}

private fun buildGatewayRequestPayload(
    form: TestPushForm,
    password: String,
): JSONObject {
    return buildGatewayPayload(form, password)
}

private fun buildGatewayPayload(form: TestPushForm, password: String?): JSONObject {
    val payload = JSONObject()
    payload.putTrimmedIfNotEmpty("title", form.title)
    payload.putTrimmedIfNotEmpty("body", form.body)
    val channelId = form.channelId.trim()
    if (channelId.isNotEmpty()) {
        payload.put("channel_id", channelId)
        payload.put("password", password ?: "********")
    }
    UrlValidators.normalizeHttpsUrl(form.url)?.let { payload.put("url", it) }
    payload.putTrimmedIfNotEmpty("icon", form.icon)
    payload.putTrimmedIfNotEmpty("image", form.image)
    payload.putTrimmedIfNotEmpty("ciphertext", form.ciphertext)
    val soundName = resolveSoundName(form)
    if (soundName.isNotEmpty()) payload.put("sound", soundName)
    return payload
}

private fun JSONObject.putTrimmedIfNotEmpty(key: String, value: String) {
    val trimmed = value.trim()
    if (trimmed.isNotEmpty()) put(key, trimmed)
}
