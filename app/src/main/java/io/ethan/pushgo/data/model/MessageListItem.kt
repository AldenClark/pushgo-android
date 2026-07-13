package io.ethan.pushgo.data.model

import io.ethan.pushgo.util.JsonCompat
import java.time.Instant

/** Immutable, payload-bounded model used by message list and search UI only. */
data class MessageListItem(
    val id: String,
    val messageId: String?,
    val title: String,
    val channel: String?,
    val url: String?,
    val isRead: Boolean,
    val receivedAt: Instant,
    val listPayloadJson: String,
    val status: MessageStatus,
    val decryptionState: DecryptionState?,
    val notificationId: String?,
    val serverId: String?,
    val bodyPreview: String,
) {
    private val listPayload: Map<String, Any?>? by lazy(LazyThreadSafetyMode.NONE) {
        JsonCompat.parseObject(listPayloadJson)
    }

    val severity: MessageSeverity?
        get() = MessageSeverity.fromRaw(payloadString("severity"))

    val tags: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        val encoded = (listPayload?.get("tags") as? String)?.trim().orEmpty()
        val values = JsonCompat.parseArray(encoded) ?: return@lazy emptyList()
        values.asSequence()
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .toList()
    }

    private fun payloadString(key: String): String? =
        (listPayload?.get(key) as? String)?.trim()?.takeIf(String::isNotEmpty)
}

/** Detail-only compatibility adapter. List/search database paths construct this model directly. */
fun PushMessage.asListItem(): MessageListItem = MessageListItem(
    id = id,
    messageId = messageId,
    title = title,
    channel = channel,
    url = url,
    isRead = isRead,
    receivedAt = receivedAt,
    listPayloadJson = rawPayloadJson,
    status = status,
    decryptionState = decryptionState,
    notificationId = notificationId,
    serverId = serverId,
    bodyPreview = bodyPreview.orEmpty(),
)
