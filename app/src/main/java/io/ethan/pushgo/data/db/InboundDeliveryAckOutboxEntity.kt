package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "inbound_delivery_ack_outbox",
    primaryKeys = ["gateway_url", "device_key", "delivery_id"],
)
data class InboundDeliveryAckOutboxEntity(
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String,
    @ColumnInfo(name = "gateway_url")
    val gatewayUrl: String,
    @ColumnInfo(name = "device_key")
    val deviceKey: String,
    @ColumnInfo(name = "ack_contract")
    val ackContract: String,
    val source: String,
    @ColumnInfo(name = "enqueued_at")
    val enqueuedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
)
