package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.ethan.pushgo.data.IncomingEntityRecord
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessagePreviewExtractor
import java.time.Instant
import org.json.JSONObject

@Entity(
    tableName = "event_change_logs",
    indices = [
        Index(value = ["delivery_id"]),
        Index(value = ["event_id", "event_time_epoch"]),
        Index(value = ["channel"]),
        Index(value = ["received_at"]),
    ],
)
data class EventChangeLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "op_id")
    val opId: String?,
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String?,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "thing_id")
    val thingId: String?,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
) {
    fun asModel(): PushMessage = asModelInternal(
        id = id,
        messageId = deliveryId,
        title = title,
        body = body,
        channel = channel,
        rawPayloadJson = rawPayloadJson,
        receivedAt = receivedAt,
        serverId = serverId,
    )

    companion object {
        fun fromIncoming(entity: IncomingEntityRecord): EventChangeLogEntity {
            val eventId = entity.eventId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
            return EventChangeLogEntity(
                id = entity.deliveryId ?: "${eventId}:${entity.receivedAt.toEpochMilli()}",
                entityId = entity.entityId,
                channel = entity.channel,
                title = entity.title,
                body = entity.body,
                rawPayloadJson = entity.rawPayloadJson,
                receivedAt = entity.receivedAt.toEpochMilli(),
                opId = entity.opId?.trim()?.takeIf { it.isNotEmpty() },
                deliveryId = entity.deliveryId?.trim()?.takeIf { it.isNotEmpty() },
                serverId = entity.serverId,
                eventId = eventId,
                thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() },
                eventState = entity.eventState?.trim()?.takeIf { it.isNotEmpty() },
                eventTimeEpoch = entity.eventTimeEpoch,
            )
        }
    }
}

@Entity(
    tableName = "thing_change_logs",
    indices = [
        Index(value = ["delivery_id"]),
        Index(value = ["thing_id", "observed_time_epoch", "event_time_epoch"]),
        Index(value = ["channel"]),
        Index(value = ["received_at"]),
    ],
)
data class ThingChangeLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "op_id")
    val opId: String?,
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String?,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String?,
    @ColumnInfo(name = "thing_id")
    val thingId: String,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
    @ColumnInfo(name = "observed_time_epoch")
    val observedTimeEpoch: Long?,
) {
    fun asModel(): PushMessage = asModelInternal(
        id = id,
        messageId = deliveryId,
        title = title,
        body = body,
        channel = channel,
        rawPayloadJson = rawPayloadJson,
        receivedAt = receivedAt,
        serverId = serverId,
    )

    companion object {
        fun fromIncoming(entity: IncomingEntityRecord): ThingChangeLogEntity {
            val thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
            return ThingChangeLogEntity(
                id = entity.deliveryId ?: "${thingId}:${entity.receivedAt.toEpochMilli()}",
                entityId = entity.entityId,
                channel = entity.channel,
                title = entity.title,
                body = entity.body,
                rawPayloadJson = entity.rawPayloadJson,
                receivedAt = entity.receivedAt.toEpochMilli(),
                opId = entity.opId?.trim()?.takeIf { it.isNotEmpty() },
                deliveryId = entity.deliveryId?.trim()?.takeIf { it.isNotEmpty() },
                serverId = entity.serverId,
                eventId = entity.eventId?.trim()?.takeIf { it.isNotEmpty() },
                thingId = thingId,
                eventState = entity.eventState?.trim()?.takeIf { it.isNotEmpty() },
                eventTimeEpoch = entity.eventTimeEpoch,
                observedTimeEpoch = entity.observedTimeEpoch,
            )
        }
    }
}

@Entity(
    tableName = "thing_sub_events",
    indices = [
        Index(value = ["delivery_id"]),
        Index(value = ["thing_id", "event_time_epoch"]),
        Index(value = ["event_id", "event_time_epoch"]),
        Index(value = ["channel"]),
        Index(value = ["received_at"]),
    ],
)
data class ThingSubEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "op_id")
    val opId: String?,
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String?,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "thing_id")
    val thingId: String,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
) {
    fun asModel(): PushMessage = asModelInternal(
        id = id,
        messageId = deliveryId,
        title = title,
        body = body,
        channel = channel,
        rawPayloadJson = rawPayloadJson,
        receivedAt = receivedAt,
        serverId = serverId,
    )

    companion object {
        fun fromIncoming(entity: IncomingEntityRecord): ThingSubEventEntity {
            val thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
            val eventId = entity.eventId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
            return ThingSubEventEntity(
                id = entity.deliveryId ?: "${thingId}:${eventId}:${entity.receivedAt.toEpochMilli()}",
                entityId = entity.entityId,
                channel = entity.channel,
                title = entity.title,
                body = entity.body,
                rawPayloadJson = entity.rawPayloadJson,
                receivedAt = entity.receivedAt.toEpochMilli(),
                opId = entity.opId?.trim()?.takeIf { it.isNotEmpty() },
                deliveryId = entity.deliveryId?.trim()?.takeIf { it.isNotEmpty() },
                serverId = entity.serverId,
                eventId = eventId,
                thingId = thingId,
                eventState = entity.eventState?.trim()?.takeIf { it.isNotEmpty() },
                eventTimeEpoch = entity.eventTimeEpoch,
            )
        }
    }
}

@Entity(
    tableName = "top_level_event_heads",
    indices = [
        Index(value = ["received_at", "source_id"]),
        Index(value = ["channel"]),
        Index(value = ["updated_at"]),
    ],
)
data class TopLevelEventHeadEntity(
    @ColumnInfo(name = "event_id")
    @PrimaryKey val eventId: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "message_id")
    val messageId: String?,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "thing_id")
    val thingId: String?,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
) {
    fun asModel(): PushMessage = asModelInternal(
        id = sourceId,
        messageId = messageId,
        title = title,
        body = body,
        channel = channel,
        rawPayloadJson = rawPayloadJson,
        receivedAt = receivedAt,
        serverId = serverId,
    )

    companion object {
        fun fromIncoming(entity: IncomingEntityRecord): TopLevelEventHeadEntity {
            val eventId = entity.eventId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
            val sourceId = entity.deliveryId ?: "event-head:$eventId"
            val now = entity.receivedAt.toEpochMilli()
            return TopLevelEventHeadEntity(
                eventId = eventId,
                sourceId = sourceId,
                messageId = entity.deliveryId?.trim()?.takeIf { it.isNotEmpty() },
                entityId = entity.entityId,
                channel = entity.channel,
                title = entity.title,
                body = entity.body,
                rawPayloadJson = entity.rawPayloadJson,
                receivedAt = now,
                updatedAt = now,
                serverId = entity.serverId,
                thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() },
                eventState = entity.eventState?.trim()?.takeIf { it.isNotEmpty() },
                eventTimeEpoch = entity.eventTimeEpoch,
            )
        }
    }
}

@Entity(
    tableName = "thing_heads",
    indices = [
        Index(value = ["received_at", "source_id"]),
        Index(value = ["channel"]),
        Index(value = ["updated_at"]),
    ],
)
data class ThingHeadEntity(
    @ColumnInfo(name = "thing_id")
    @PrimaryKey val thingId: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "message_id")
    val messageId: String?,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String?,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
    @ColumnInfo(name = "observed_time_epoch")
    val observedTimeEpoch: Long?,
) {
    fun asModel(): PushMessage = asModelInternal(
        id = sourceId,
        messageId = messageId,
        title = title,
        body = body,
        channel = channel,
        rawPayloadJson = rawPayloadJson,
        receivedAt = receivedAt,
        serverId = serverId,
    )

    companion object {
        fun fromIncoming(entity: IncomingEntityRecord): ThingHeadEntity {
            val thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
            val sourceId = entity.deliveryId ?: "thing-head:$thingId"
            val now = entity.receivedAt.toEpochMilli()
            return ThingHeadEntity(
                thingId = thingId,
                sourceId = sourceId,
                messageId = entity.deliveryId?.trim()?.takeIf { it.isNotEmpty() },
                entityId = entity.entityId,
                channel = entity.channel,
                title = entity.title,
                body = entity.body,
                rawPayloadJson = entity.rawPayloadJson,
                receivedAt = now,
                updatedAt = now,
                serverId = entity.serverId,
                eventId = entity.eventId?.trim()?.takeIf { it.isNotEmpty() },
                eventState = entity.eventState?.trim()?.takeIf { it.isNotEmpty() },
                eventTimeEpoch = entity.eventTimeEpoch,
                observedTimeEpoch = entity.observedTimeEpoch,
            )
        }
    }
}

@Entity(
    tableName = "thing_sub_messages",
    indices = [
        Index(value = ["message_id"], unique = true, name = "index_thing_sub_messages_message_id_unique"),
        Index(value = ["thing_id", "occurred_at_epoch", "event_time_epoch"]),
        Index(value = ["channel"]),
        Index(value = ["received_at"]),
    ],
)
data class ThingSubMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id")
    val messageId: String?,
    val title: String,
    val body: String,
    val channel: String?,
    val url: String?,
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
) {
    fun asModel(): PushMessage {
        val statusValue = runCatching { MessageStatus.valueOf(status) }.getOrNull() ?: MessageStatus.NORMAL
        return PushMessage(
            id = id,
            messageId = messageId,
            title = title,
            body = body,
            channel = channel,
            url = url,
            isRead = false,
            receivedAt = Instant.ofEpochMilli(receivedAt),
            rawPayloadJson = rawPayloadJson,
            status = statusValue,
            decryptionState = null,
            notificationId = notificationId,
            serverId = serverId,
            bodyPreview = bodyPreview,
        )
    }

    companion object {
        fun fromModel(message: PushMessage): ThingSubMessageEntity {
            val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
            val eventTimeEpoch = payload?.optLong("event_time")?.takeIf { it > 0L }?.times(1000)
            val occurredAtEpoch = payload?.optLong("occurred_at")?.takeIf { it > 0L }?.times(1000)
            val stableMessageId = message.messageId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: message.deliveryId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                ?: message.id
            return ThingSubMessageEntity(
                id = message.id,
                messageId = stableMessageId,
                title = message.title,
                body = message.body,
                channel = message.channel,
                url = message.url,
                receivedAt = message.receivedAt.toEpochMilli(),
                rawPayloadJson = message.rawPayloadJson,
                status = message.status.name,
                decryptionState = message.decryptionState?.name,
                notificationId = message.notificationId,
                serverId = message.serverId,
                bodyPreview = message.bodyPreview?.takeIf { it.isNotBlank() }
                    ?: MessagePreviewExtractor.listPreview(message.body),
                entityType = message.entityType,
                entityId = message.entityId,
                eventId = message.eventId,
                thingId = message.thingId,
                eventState = message.eventState,
                eventTimeEpoch = eventTimeEpoch,
                occurredAtEpoch = occurredAtEpoch,
            )
        }
    }
}

private fun asModelInternal(
    id: String,
    messageId: String?,
    title: String,
    body: String,
    channel: String?,
    rawPayloadJson: String,
    receivedAt: Long,
    serverId: String?,
): PushMessage {
    return PushMessage(
        id = id,
        messageId = messageId,
        title = title,
        body = body,
        channel = channel,
        url = null,
        isRead = false,
        receivedAt = Instant.ofEpochMilli(receivedAt),
        rawPayloadJson = rawPayloadJson,
        status = MessageStatus.NORMAL,
        decryptionState = null,
        notificationId = null,
        serverId = serverId,
        bodyPreview = MessagePreviewExtractor.listPreview(body),
    )
}
