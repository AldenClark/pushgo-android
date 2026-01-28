package io.ethan.pushgo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import java.time.Instant

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["channel", "receivedAt"]),
        Index(value = ["isRead", "receivedAt"]),
        Index(value = ["receivedAt"]),
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val messageId: String?,
    val title: String,
    val body: String,
    val channel: String?,
    val url: String?,
    val isRead: Boolean,
    val receivedAt: Long,
    val rawPayloadJson: String,
    val status: String,
    val decryptionState: String?,
    val notificationId: String?,
    val serverId: String?,
) {
    fun asModel(): PushMessage {
        val state = decryptionState?.let { runCatching { DecryptionState.valueOf(it) }.getOrNull() }
        val statusValue = runCatching { MessageStatus.valueOf(status) }.getOrNull() ?: MessageStatus.NORMAL
        return PushMessage(
            id = id,
            messageId = messageId,
            title = title,
            body = body,
            channel = channel,
            url = url,
            isRead = isRead,
            receivedAt = Instant.ofEpochMilli(receivedAt),
            rawPayloadJson = rawPayloadJson,
            status = statusValue,
            decryptionState = state,
            notificationId = notificationId,
            serverId = serverId,
        )
    }

    companion object {
        fun fromModel(message: PushMessage): MessageEntity {
            return MessageEntity(
                id = message.id,
                messageId = message.messageId,
                title = message.title,
                body = message.body,
                channel = message.channel,
                url = message.url,
                isRead = message.isRead,
                receivedAt = message.receivedAt.toEpochMilli(),
                rawPayloadJson = message.rawPayloadJson,
                status = message.status.name,
                decryptionState = message.decryptionState?.name,
                notificationId = message.notificationId,
                serverId = message.serverId,
            )
        }
    }
}
