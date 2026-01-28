package io.ethan.pushgo.data.db

import androidx.room.Entity
import io.ethan.pushgo.data.model.ChannelSubscription

@Entity(
    tableName = "channel_subscriptions",
    primaryKeys = ["gatewayUrl", "channelId"],
)
data class ChannelSubscriptionEntity(
    val gatewayUrl: String,
    val channelId: String,
    val displayName: String,
    val updatedAt: Long,
    val lastSyncedAt: Long?,
    val autoCleanupEnabled: Boolean = true,
    val password: String?,
    val isDeleted: Boolean = false,
    val deletedAt: Long?,
) {
    fun asModel(): ChannelSubscription {
        return ChannelSubscription(
            channelId = channelId,
            displayName = displayName,
            updatedAt = updatedAt,
            lastSyncedAt = lastSyncedAt,
            autoCleanupEnabled = autoCleanupEnabled,
        )
    }
}
