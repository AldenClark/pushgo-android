package io.ethan.pushgo.data.model

import java.time.Instant

data class ChannelSubscription(
    val channelId: String,
    val displayName: String,
    val updatedAt: Long,
    val lastSyncedAt: Long?,
    val autoCleanupEnabled: Boolean = true,
) {
    fun lastSyncedInstant(): Instant? = lastSyncedAt?.let { Instant.ofEpochMilli(it) }
}
