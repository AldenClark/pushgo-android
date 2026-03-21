package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inbound_delivery_ack_outbox")
data class InboundDeliveryAckOutboxEntity(
    @ColumnInfo(name = "delivery_id")
    @PrimaryKey val deliveryId: String,
    val source: String,
    @ColumnInfo(name = "enqueued_at")
    val enqueuedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
