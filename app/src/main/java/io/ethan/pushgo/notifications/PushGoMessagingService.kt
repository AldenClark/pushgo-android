package io.ethan.pushgo.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.util.UrlValidators
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class PushGoMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as PushGoApp
        val repository = app.container.messageRepository
        val badgeOnlyCount = extractBadgeOnlyCount(message.data)
        if (badgeOnlyCount != null) {
            NotificationHelper.updateActiveNotificationNumbers(applicationContext, badgeOnlyCount)
            return
        }
        serviceScope.launch {
            val parsed = parseMessage(message)
            if (parsed != null) {
                handleMessage(
                    repository,
                    parsed.message,
                    parsed.ringtoneIdOverride,
                    parsed.ringMode,
                    parsed.level,
                    parsed.imageUrl,
                    parsed.iconUrl,
                    parsed.unreadCountOverride,
                )
            }
        }
    }

    override fun onNewToken(token: String) {
        val app = application as PushGoApp
        app.handlePushTokenUpdate(token)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private suspend fun handleMessage(
        repository: MessageRepository,
        message: PushMessage,
        ringtoneIdOverride: String?,
        ringMode: String?,
        level: String?,
        imageUrl: String?,
        iconUrl: String?,
        unreadCountOverride: Int?,
    ) {
        val messageId = message.messageId
        if (messageId != null) {
            val existing = runCatching { repository.getByMessageId(messageId) }.getOrNull()
            if (existing != null) return
        }
        val inserted = runCatching {
            repository.insert(message)
            true
        }.getOrElse { false }
        if (!inserted) return
        val settingsRepository = (application as PushGoApp).container.settingsRepository
        val preferredRingtone = runCatching { settingsRepository.getRingtoneId() }.getOrNull()
        val ringtoneId = ringtoneIdOverride ?: preferredRingtone
        enqueuePostProcess(message.id, ringMode, ringtoneId, imageUrl, iconUrl)
        val unreadCount = unreadCountOverride ?: runCatching { repository.unreadCount() }.getOrNull()
        NotificationHelper.showMessageNotification(
            applicationContext,
            message,
            ringtoneId,
            ringMode,
            level,
            unreadCount,
        )
    }

    private suspend fun parseMessage(message: RemoteMessage): ParsedMessage? {
        val data = message.data
        val rawTitle = data["title"] ?: ""
        val rawBody = data["body"] ?: ""
        val channel = data["channel_id"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: data["channel"]?.trim()?.takeIf { it.isNotEmpty() }
        val url = UrlValidators.normalizeHttpsUrl(data["url"])
        val serverId = data["server_id"] ?: data["serverId"]
        val messageId = extractMessageId(data)
        val keyBase64 = (application as PushGoApp).container.settingsRepository.getNotificationKeyBase64()
        val decryptResult = NotificationDecryptor.decryptIfNeeded(data, rawTitle, rawBody, keyBase64)
        val payloadSound = data["sound"]
        val ringtoneOverride = RingtoneCatalog.resolveBySoundValue(payloadSound)?.id
        val ringMode = data["ring_mode"] ?: data["ringMode"]
        val level = data["level"]
        val notificationCount = data["notification_count"]
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
        if (!hasMessageContent(data, decryptResult)) {
            if (notificationCount != null) {
                NotificationHelper.updateActiveNotificationNumbers(applicationContext, notificationCount)
            }
            return null
        }
        val rawPayload = JSONObject().apply {
            data.forEach { (key, value) -> put(key, value) }
            if (!data.containsKey("title")) put("title", rawTitle)
            if (!data.containsKey("body")) put("body", rawBody)
            decryptResult.decryptedBody?.let { put("ciphertext_body", it) }
            decryptResult.image?.let { put("image", it) }
            decryptResult.icon?.let { put("icon", it) }
            decryptResult.decryptionState?.let { put("decryptionState", it.name) }
            message.messageId?.let { put("_fcm_message_id", it) }
        }.toString()

        val pushMessage = PushMessage(
            id = UUID.randomUUID().toString(),
            messageId = messageId,
            title = decryptResult.title,
            body = decryptResult.body,
            channel = channel,
            url = url,
            isRead = false,
            receivedAt = Instant.now(),
            rawPayloadJson = rawPayload,
            status = MessageStatus.NORMAL,
            decryptionState = decryptResult.decryptionState,
            notificationId = message.messageId,
            serverId = serverId,
        )
        return ParsedMessage(
            message = pushMessage,
            ringtoneIdOverride = ringtoneOverride,
            ringMode = ringMode,
            level = level,
            imageUrl = UrlValidators.normalizeHttpsUrl(decryptResult.image)
                ?: UrlValidators.normalizeHttpsUrl(data["image"]),
            iconUrl = UrlValidators.normalizeHttpsUrl(decryptResult.icon)
                ?: UrlValidators.normalizeHttpsUrl(data["icon"]),
            unreadCountOverride = notificationCount,
        )
    }

    private fun extractBadgeOnlyCount(data: Map<String, String>): Int? {
        val count = data["notification_count"]
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 0 } ?: return null
        val hasContent = listOf(
            "title",
            "body",
            "ciphertext",
            "url",
            "icon",
            "image",
            "sound",
            "body_render_payload",
            "body_render_is_markdown",
        ).any { key -> !data[key].isNullOrBlank() }
        return if (hasContent) null else count
    }

    private fun hasMessageContent(
        data: Map<String, String>,
        decryptResult: NotificationDecryptor.Result,
    ): Boolean {
        if (decryptResult.title.isNotBlank() || decryptResult.body.isNotBlank()) return true
        return listOf(
            data["ciphertext"],
            data["url"],
            data["icon"],
            data["image"],
            data["sound"],
            data["body_render_payload"],
        ).any { !it.isNullOrBlank() }
    }

    private fun extractMessageId(data: Map<String, String>): String? {
        val keys = listOf("messageId", "message_id", "id", "serverId", "server_id")
        keys.forEach { key ->
            val value = data[key]?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private data class ParsedMessage(
        val message: PushMessage,
        val ringtoneIdOverride: String?,
        val ringMode: String?,
        val level: String?,
        val imageUrl: String?,
        val iconUrl: String?,
        val unreadCountOverride: Int?,
    )

    private fun enqueuePostProcess(
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
        WorkManager.getInstance(applicationContext).enqueue(request)
    }
}
