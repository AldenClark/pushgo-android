package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "legacy_provider_ingress",
    primaryKeys = ["gateway_url", "device_key", "delivery_id"],
)
data class LegacyProviderIngressEntity(
    @ColumnInfo(name = "gateway_url") val gatewayUrl: String,
    @ColumnInfo(name = "device_key") val deviceKey: String,
    @ColumnInfo(name = "delivery_id") val deliveryId: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
)
