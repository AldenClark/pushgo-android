package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.ethan.pushgo.data.IncomingEntityRecord
import java.time.Instant

@Entity(
    tableName = "pending_thing_messages",
    indices = [
        Index(value = ["thing_id", "occurred_at_epoch", "event_time_epoch", "received_at"]),
        Index(value = ["message_id"], unique = true),
    ],
)
data class PendingThingMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "message_id")
    val messageId: String,
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
    val thingId: String,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
    @ColumnInfo(name = "occurred_at_epoch")
    val occurredAtEpoch: Long?,
) {
    companion object {
        fun fromThingScopedMessage(message: ThingSubMessageEntity): PendingThingMessageEntity {
            val thingId = message.thingId?.trim().orEmpty()
            return PendingThingMessageEntity(
                id = message.id,
                messageId = message.messageId?.trim().orEmpty(),
                title = message.title,
                body = message.body,
                channel = message.channel,
                url = message.url,
                receivedAt = message.receivedAt,
                rawPayloadJson = message.rawPayloadJson,
                status = message.status,
                decryptionState = message.decryptionState,
                notificationId = message.notificationId,
                serverId = message.serverId,
                bodyPreview = message.bodyPreview,
                entityType = message.entityType,
                entityId = message.entityId,
                eventId = message.eventId,
                thingId = thingId,
                eventState = message.eventState,
                eventTimeEpoch = message.eventTimeEpoch,
                occurredAtEpoch = message.occurredAtEpoch,
            )
        }
    }
}

@Entity(
    tableName = "pending_thing_events",
    indices = [
        Index(value = ["thing_id", "event_time_epoch", "received_at"]),
        Index(value = ["delivery_id"], unique = true),
    ],
)
data class PendingThingEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
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
    fun toIncomingEntityRecord(): IncomingEntityRecord {
        return IncomingEntityRecord(
            entityType = "event",
            entityId = entityId,
            channel = channel,
            title = title,
            body = body,
            rawPayloadJson = rawPayloadJson,
            receivedAt = Instant.ofEpochMilli(receivedAt),
            opId = opId,
            deliveryId = deliveryId,
            serverId = serverId,
            eventId = eventId,
            thingId = thingId,
            eventState = eventState,
            eventTimeEpoch = eventTimeEpoch,
            observedTimeEpoch = null,
        )
    }

    companion object {
        fun fromIncoming(entity: IncomingEntityRecord): PendingThingEventEntity {
            val thingId = entity.thingId?.trim().orEmpty()
            val eventId = entity.eventId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
            return PendingThingEventEntity(
                id = entity.deliveryId ?: "${thingId}:${eventId}:${entity.receivedAt.toEpochMilli()}",
                entityId = entity.entityId,
                channel = entity.channel,
                title = entity.title,
                body = entity.body,
                rawPayloadJson = entity.rawPayloadJson,
                receivedAt = entity.receivedAt.toEpochMilli(),
                opId = entity.opId,
                deliveryId = entity.deliveryId,
                serverId = entity.serverId,
                eventId = eventId,
                thingId = thingId,
                eventState = entity.eventState,
                eventTimeEpoch = entity.eventTimeEpoch,
            )
        }
    }
}

@Dao
interface PendingThingMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: PendingThingMessageEntity): Long

    @Query(
        """
        SELECT * FROM pending_thing_messages
        WHERE thing_id = :thingId
        ORDER BY COALESCE(occurred_at_epoch, event_time_epoch, received_at) ASC, received_at ASC, id ASC
        """
    )
    suspend fun loadByThingId(thingId: String): List<PendingThingMessageEntity>

    @Query("DELETE FROM pending_thing_messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int
}

@Dao
interface PendingThingEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: PendingThingEventEntity): Long

    @Query(
        """
        SELECT * FROM pending_thing_events
        WHERE thing_id = :thingId
        ORDER BY COALESCE(event_time_epoch, received_at) ASC, received_at ASC, id ASC
        """
    )
    suspend fun loadByThingId(thingId: String): List<PendingThingEventEntity>

    @Query("DELETE FROM pending_thing_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int
}
