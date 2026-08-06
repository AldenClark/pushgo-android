package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "inbound_delivery_ledger",
    primaryKeys = ["gateway_url", "device_key", "delivery_id"],
)
data class InboundDeliveryLedgerEntity(
    @ColumnInfo(name = "gateway_url")
    val gatewayUrl: String,
    @ColumnInfo(name = "device_key")
    val deviceKey: String,
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String?,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String?,
    @ColumnInfo(name = "op_id")
    val opId: String?,
    @ColumnInfo(name = "applied_at")
    val appliedAt: Long,
    @ColumnInfo(name = "ack_state")
    val ackState: String,
    @ColumnInfo(name = "acked_at")
    val ackedAt: Long?,
)
