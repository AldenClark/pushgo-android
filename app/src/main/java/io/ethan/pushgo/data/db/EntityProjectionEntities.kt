package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.ethan.pushgo.data.IncomingEntityRecord
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessagePreviewExtractor
import io.ethan.pushgo.util.PayloadTimeNormalizer
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
                id = entity.localDeliveryKey
                    ?: entity.deliveryId
                    ?: "${eventId}:${entity.receivedAt.toEpochMilli()}",
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
                id = entity.localDeliveryKey
                    ?: entity.deliveryId
                    ?: "${thingId}:${entity.receivedAt.toEpochMilli()}",
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
                id = entity.localDeliveryKey
                    ?: entity.deliveryId
                    ?: "${thingId}:${eventId}:${entity.receivedAt.toEpochMilli()}",
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
            val sourceId = entity.localDeliveryKey ?: entity.deliveryId ?: "event-head:$eventId"
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

        fun fromMerged(
            existing: TopLevelEventHeadEntity?,
            entity: IncomingEntityRecord,
        ): TopLevelEventHeadEntity {
            val incoming = fromIncoming(entity)
            val incomingPayload = parsePayloadObject(entity.rawPayloadJson)
            val mergedPayloadJson = mergeEntityPayloadJson(existing?.rawPayloadJson, entity.rawPayloadJson)
            return incoming.copy(
                sourceId = incoming.sourceId,
                messageId = incoming.messageId ?: existing?.messageId,
                title = textStringFromPatch(
                    incomingPayload = incomingPayload,
                    keys = listOf("title"),
                    incoming = incoming.title,
                    existing = existing?.title,
                ),
                body = textStringFromPatch(
                    incomingPayload = incomingPayload,
                    keys = listOf("body", "description", "message"),
                    incoming = incoming.body,
                    existing = existing?.body,
                ),
                rawPayloadJson = mergedPayloadJson,
                serverId = incoming.serverId ?: existing?.serverId,
                thingId = incoming.thingId ?: existing?.thingId,
                eventState = textNullableFromPatch(
                    incomingPayload = incomingPayload,
                    keys = listOf("event_state"),
                    incoming = incoming.eventState,
                    existing = existing?.eventState,
                ),
                eventTimeEpoch = incoming.eventTimeEpoch ?: existing?.eventTimeEpoch,
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
            val sourceId = entity.localDeliveryKey ?: entity.deliveryId ?: "thing-head:$thingId"
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

        fun fromMerged(
            existing: ThingHeadEntity?,
            entity: IncomingEntityRecord,
        ): ThingHeadEntity {
            val incoming = fromIncoming(entity)
            val incomingPayload = parsePayloadObject(entity.rawPayloadJson)
            val mergedPayloadJson = mergeEntityPayloadJson(existing?.rawPayloadJson, entity.rawPayloadJson)
            return incoming.copy(
                sourceId = incoming.sourceId,
                messageId = incoming.messageId ?: existing?.messageId,
                title = textStringFromPatch(
                    incomingPayload = incomingPayload,
                    keys = listOf("title"),
                    incoming = incoming.title,
                    existing = existing?.title,
                ),
                body = textStringFromPatch(
                    incomingPayload = incomingPayload,
                    keys = listOf("body", "description", "message"),
                    incoming = incoming.body,
                    existing = existing?.body,
                ),
                rawPayloadJson = mergedPayloadJson,
                serverId = incoming.serverId ?: existing?.serverId,
                eventId = incoming.eventId ?: existing?.eventId,
                eventState = textNullableFromPatch(
                    incomingPayload = incomingPayload,
                    keys = listOf("event_state", "state"),
                    incoming = incoming.eventState,
                    existing = existing?.eventState,
                ),
                eventTimeEpoch = incoming.eventTimeEpoch ?: existing?.eventTimeEpoch,
                observedTimeEpoch = incoming.observedTimeEpoch ?: existing?.observedTimeEpoch,
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
            val eventTimeEpoch = PayloadTimeNormalizer.epochMillisFromJson(payload, "event_time")
            val occurredAtEpoch = PayloadTimeNormalizer.epochMillisFromJson(payload, "occurred_at")
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

private val objectPatchPayloadKeys = setOf("attrs", "metadata", "external_ids")
private val blankTextPatchPayloadKeys = setOf("title", "body", "description", "message")

private fun parsePayloadObject(raw: String?): JSONObject? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { JSONObject(text) }.getOrNull()
}

private fun mergeEntityPayloadJson(existingRaw: String?, incomingRaw: String): String {
    val incoming = parsePayloadObject(incomingRaw) ?: return incomingRaw
    val merged = parsePayloadObject(existingRaw)?.let(::copyJsonObject) ?: JSONObject()
    val keys = incoming.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = incoming.opt(key)
        if (key in blankTextPatchPayloadKeys && value is String && value.trim().isEmpty()) {
            continue
        }
        if (key in objectPatchPayloadKeys) {
            if (value == null || value == JSONObject.NULL) {
                merged.remove(key)
                continue
            }
                val patch = value.toPatchObjectOrNull()
                if (patch != null) {
                    val base = merged.opt(key).toPatchObjectOrNull() ?: JSONObject()
                    applyObjectPatch(base, patch)
                    merged.put(key, base)
                } else {
                    merged.put(key, value)
                }
        } else {
            merged.put(key, value)
        }
    }
    return merged.toString()
}

private fun copyJsonObject(source: JSONObject): JSONObject {
    val copy = JSONObject()
    val keys = source.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        copy.put(key, source.opt(key))
    }
    return copy
}

private fun applyObjectPatch(base: JSONObject, patch: JSONObject) {
    val keys = patch.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = patch.opt(key)
        if (value == null || value == JSONObject.NULL) {
            base.remove(key)
        } else {
            base.put(key, value)
        }
    }
}

private fun Any?.toPatchObjectOrNull(): JSONObject? {
    return when (this) {
        null, JSONObject.NULL -> null
        is JSONObject -> this
        is String -> parsePayloadObject(this)
        else -> null
    }
}

private fun textStringFromPatch(
    incomingPayload: JSONObject?,
    keys: List<String>,
    incoming: String,
    existing: String?,
): String {
    val incomingText = incoming.trim().takeIf { it.isNotEmpty() }
    val existingText = existing?.trim()?.takeIf { it.isNotEmpty() }
    return patchTextFromPayload(incomingPayload, keys) ?: existingText ?: incomingText.orEmpty()
}

private fun textNullableFromPatch(
    incomingPayload: JSONObject?,
    keys: List<String>,
    incoming: String?,
    existing: String?,
): String? {
    val incomingText = incoming?.trim()?.takeIf { it.isNotEmpty() }
    val existingText = existing?.trim()?.takeIf { it.isNotEmpty() }
    return patchTextFromPayload(incomingPayload, keys) ?: existingText ?: incomingText
}

private fun patchTextFromPayload(incomingPayload: JSONObject?, keys: List<String>): String? {
    if (incomingPayload == null) return null
    for (key in keys) {
        if (!incomingPayload.has(key)) continue
        val value = incomingPayload.opt(key)
        val rawText: String? = if (value == null || value == JSONObject.NULL) {
            null
        } else if (value is String) {
            value
        } else {
            value.toString()
        }
        val text = rawText
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (text != null) return text
    }
    return null
}
