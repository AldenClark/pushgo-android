package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessagePreviewExtractor
import org.json.JSONObject
import java.time.Instant

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["message_id"], unique = true, name = "index_messages_message_id_unique"),
        Index(value = ["channel", "received_at"]),
        Index(value = ["is_read", "received_at"]),
        Index(value = ["received_at"]),
        Index(value = ["entity_type", "event_time_epoch"]),
        Index(value = ["event_id", "event_time_epoch"]),
        Index(value = ["thing_id", "event_time_epoch"]),
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id")
    val messageId: String?,
    val title: String,
    val body: String,
    val channel: String?,
    val url: String?,
    @ColumnInfo(name = "is_read")
    val isRead: Boolean,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    val status: String,
    @ColumnInfo(name = "decryption_state")
    val decryptionState: String?,
    @ColumnInfo(name = "notification_id")
    val notificationId: String?,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "body_preview")
    val bodyPreview: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String?,
    @ColumnInfo(name = "thing_id")
    val thingId: String?,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
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
            bodyPreview = bodyPreview,
        )
    }

    companion object {
        private data class EntityProjection(
            @ColumnInfo(name = "entity_type")
            val entityType: String,
            @ColumnInfo(name = "entity_id")
            val entityId: String?,
            @ColumnInfo(name = "event_id")
            val eventId: String?,
            @ColumnInfo(name = "thing_id")
            val thingId: String?,
            @ColumnInfo(name = "event_state")
            val eventState: String?,
            @ColumnInfo(name = "event_time_epoch")
            val eventTimeEpoch: Long?,
        )

        fun fromModel(message: PushMessage): MessageEntity {
            val projection = deriveEntityProjection(
                rawPayloadJson = message.rawPayloadJson,
                _fallbackMessageId = message.messageId,
            )
            val stableMessageId = message.messageId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: message.deliveryId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: message.id
            return MessageEntity(
                id = message.id,
                messageId = stableMessageId,
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
                bodyPreview = message.bodyPreview?.takeIf { it.isNotBlank() }
                    ?: MessagePreviewExtractor.listPreview(message.body),
                entityType = projection.entityType,
                entityId = projection.entityId,
                eventId = projection.eventId,
                thingId = projection.thingId,
                eventState = projection.eventState,
                eventTimeEpoch = projection.eventTimeEpoch,
            )
        }

        private fun deriveEntityProjection(
            rawPayloadJson: String,
            _fallbackMessageId: String?,
        ): EntityProjection {
            val payload = runCatching { JSONObject(rawPayloadJson) }.getOrNull()

            fun text(key: String): String? {
                val value = payload?.optString(key, "")?.trim().orEmpty()
                return value.takeIf { it.isNotEmpty() }
            }

            val entityType = when (text("entity_type")?.lowercase()) {
                "message" -> "message"
                "event" -> "event"
                "thing" -> "thing"
                else -> ""
            }
            val eventId = text("event_id")
            val thingId = text("thing_id")
            val entityId = text("entity_id")
            val eventState = text("event_state")
            val eventTimeEpoch = payload?.optLong("event_time")
                ?.takeIf { it > 0L }
                ?.times(1000)

            return EntityProjection(
                entityType = entityType,
                entityId = entityId,
                eventId = eventId,
                thingId = thingId,
                eventState = eventState,
                eventTimeEpoch = eventTimeEpoch,
            )
        }
    }
}
