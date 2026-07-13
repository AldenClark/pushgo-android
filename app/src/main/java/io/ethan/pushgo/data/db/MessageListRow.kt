package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.data.model.MessageListItem
import io.ethan.pushgo.data.model.MessageStatus
import java.time.Instant

data class MessageListRow(
    val id: String,
    @ColumnInfo(name = "message_id") val messageId: String?,
    val title: String,
    val channel: String?,
    val url: String?,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "list_payload_json") val listPayloadJson: String,
    val status: String,
    @ColumnInfo(name = "decryption_state") val decryptionState: String?,
    @ColumnInfo(name = "notification_id") val notificationId: String?,
    @ColumnInfo(name = "server_id") val serverId: String?,
    @ColumnInfo(name = "body_preview") val bodyPreview: String,
) {
    fun asListItem(): MessageListItem = MessageListItem(
        id = id,
        messageId = messageId,
        title = title,
        channel = channel,
        url = url,
        isRead = isRead,
        receivedAt = Instant.ofEpochMilli(receivedAt),
        listPayloadJson = listPayloadJson,
        status = runCatching { MessageStatus.valueOf(status) }.getOrNull() ?: MessageStatus.NORMAL,
        decryptionState = decryptionState?.let {
            runCatching { DecryptionState.valueOf(it) }.getOrNull()
        },
        notificationId = notificationId,
        serverId = serverId,
        bodyPreview = bodyPreview,
    )
}
