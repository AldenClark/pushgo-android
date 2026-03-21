package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessagePreviewExtractor
import java.time.Instant

@Entity(
    tableName = "entity_records",
    indices = [
        Index(value = ["entity_type", "event_time_epoch"]),
        Index(value = ["event_id", "event_time_epoch"]),
        Index(value = ["thing_id", "observed_time_epoch", "event_time_epoch"]),
        Index(value = ["delivery_id"]),
        Index(value = ["received_at"]),
    ]
)
data class EntityRecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
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
    val thingId: String?,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
    @ColumnInfo(name = "observed_time_epoch")
    val observedTimeEpoch: Long?,
) {
    fun asModel(): PushMessage {
        return PushMessage(
            id = id,
            messageId = deliveryId,
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
}
