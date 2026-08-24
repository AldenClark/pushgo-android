package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessagePreviewExtractor
import io.ethan.pushgo.util.PayloadTimeNormalizer
import org.json.JSONArray
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
        Index(value = ["thing_id", "occurred_at_epoch", "event_time_epoch"]),
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
    @ColumnInfo(name = "occurred_at_epoch")
    val occurredAtEpoch: Long?,
    @ColumnInfo(name = "list_payload_json", defaultValue = "'{}'")
    val listPayloadJson: String = "{}",
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
            @ColumnInfo(name = "occurred_at_epoch")
            val occurredAtEpoch: Long?,
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
                occurredAtEpoch = projection.occurredAtEpoch,
                listPayloadJson = buildListPayloadJson(message.rawPayloadJson),
            )
        }

        fun buildListPayloadJson(rawPayloadJson: String): String {
            val source = runCatching { JSONObject(rawPayloadJson) }.getOrNull() ?: return "{}"
            val result = JSONObject()

            source.optString("severity", "")
                .trim()
                .take(MAX_LIST_SEVERITY_CHARS)
                .takeIf(String::isNotEmpty)
                ?.let { putIfWithinListBudget(result, "severity", it) }

            putBoundedStringArray(
                target = result,
                key = "tags",
                values = boundedStringValues(source.opt("tags"), MAX_LIST_TAGS, MAX_LIST_TAG_CHARS),
            )

            listOf(IMAGE_LOCAL_PATH_KEY, IMAGE_THUMBNAIL_LOCAL_PATH_KEY).forEach { key ->
                source.optString(key, "")
                    .trim()
                    .take(MAX_LIST_LOCAL_PATH_CHARS)
                    .takeIf(String::isNotEmpty)
                    ?.let { putIfWithinListBudget(result, key, it) }
            }

            putBoundedStringArray(
                target = result,
                key = "images",
                values = boundedStringValues(source.opt("images"), MAX_LIST_IMAGES, MAX_LIST_IMAGE_URL_CHARS),
            )
            return result.toString()
        }

        private fun boundedStringValues(raw: Any?, maxItems: Int, maxChars: Int): List<String> {
            val values = when (raw) {
                is JSONArray -> (0 until raw.length()).map { raw.opt(it) }
                is String -> runCatching { JSONArray(raw) }
                    .getOrNull()
                    ?.let { array -> (0 until array.length()).map { array.opt(it) } }
                    ?: listOf(raw)
                else -> emptyList()
            }
            return values.asSequence()
                .mapNotNull { value -> (value as? String)?.trim()?.take(maxChars)?.takeIf(String::isNotEmpty) }
                .distinct()
                .take(maxItems)
                .toList()
        }

        private fun putBoundedStringArray(target: JSONObject, key: String, values: List<String>) {
            if (values.isEmpty()) return
            val accepted = JSONArray()
            values.forEach { value ->
                val candidate = JSONArray(accepted.toString()).put(value).toString()
                if (putIfWithinListBudget(target, key, candidate)) {
                    accepted.put(value)
                }
            }
        }

        private fun putIfWithinListBudget(target: JSONObject, key: String, value: String): Boolean {
            val candidate = JSONObject(target.toString()).put(key, value).toString()
            if (candidate.toByteArray(Charsets.UTF_8).size > MAX_LIST_PAYLOAD_BYTES) return false
            target.put(key, value)
            return true
        }

        internal const val MAX_LIST_PAYLOAD_BYTES = 16 * 1024
        private const val MAX_LIST_SEVERITY_CHARS = 32
        private const val MAX_LIST_TAGS = 16
        private const val MAX_LIST_TAG_CHARS = 64
        private const val MAX_LIST_IMAGES = 4
        private const val MAX_LIST_IMAGE_URL_CHARS = 2_048
        private const val MAX_LIST_LOCAL_PATH_CHARS = 1_024
        private const val IMAGE_LOCAL_PATH_KEY = "image_local_path"
        private const val IMAGE_THUMBNAIL_LOCAL_PATH_KEY = "image_thumbnail_local_path"

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
            val eventTimeEpoch = PayloadTimeNormalizer.epochMillisFromJson(payload, "event_time")
            val occurredAtEpoch = PayloadTimeNormalizer.epochMillisFromJson(payload, "occurred_at")

            return EntityProjection(
                entityType = entityType,
                entityId = entityId,
                eventId = eventId,
                thingId = thingId,
                eventState = eventState,
                eventTimeEpoch = eventTimeEpoch,
                occurredAtEpoch = occurredAtEpoch,
            )
        }
    }
}
