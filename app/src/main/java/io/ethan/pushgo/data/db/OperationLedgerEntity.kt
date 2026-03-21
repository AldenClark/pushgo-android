package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "operation_ledger",
    indices = [
        Index(value = ["op_id"]),
        Index(value = ["channel_id", "entity_type", "entity_id"]),
    ],
)
data class OperationLedgerEntity(
    @ColumnInfo(name = "scope_key")
    @PrimaryKey val scopeKey: String,
    @ColumnInfo(name = "op_id")
    val opId: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String?,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String?,
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String?,
    @ColumnInfo(name = "applied_at")
    val appliedAt: Long,
)
