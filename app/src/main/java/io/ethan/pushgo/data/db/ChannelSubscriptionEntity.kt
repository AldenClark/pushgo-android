package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import io.ethan.pushgo.data.model.ChannelSubscription

@Entity(
    tableName = "channel_subscriptions",
    primaryKeys = ["gateway_url", "channel_id"],
)
data class ChannelSubscriptionEntity(
    @ColumnInfo(name = "gateway_url")
    val gatewayUrl: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Long?,
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,
) {
    fun asModel(): ChannelSubscription {
        return ChannelSubscription(
            channelId = channelId,
            displayName = displayName,
            updatedAt = updatedAt,
            lastSyncedAt = lastSyncedAt,
        )
    }
}
