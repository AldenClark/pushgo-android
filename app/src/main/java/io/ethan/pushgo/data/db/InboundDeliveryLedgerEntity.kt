package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "inbound_delivery_ledger",
    primaryKeys = ["gateway_url", "device_key", "delivery_id"],
    indices = [
        Index(
            value = ["ack_state", "acked_at"],
            name = "index_inbound_delivery_ledger_ack_state_acked_at",
        ),
    ],
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
