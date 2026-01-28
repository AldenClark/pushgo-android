package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ChannelSubscriptionDao {
    @Query("SELECT * FROM channel_subscriptions WHERE isDeleted = 0 AND gatewayUrl = :gatewayUrl ORDER BY updatedAt DESC")
    suspend fun getActive(gatewayUrl: String): List<ChannelSubscriptionEntity>

    @Query("SELECT * FROM channel_subscriptions WHERE gatewayUrl = :gatewayUrl ORDER BY updatedAt DESC")
    suspend fun getAll(gatewayUrl: String): List<ChannelSubscriptionEntity>

    @Query("SELECT * FROM channel_subscriptions WHERE channelId = :channelId AND gatewayUrl = :gatewayUrl LIMIT 1")
    suspend fun getById(gatewayUrl: String, channelId: String): ChannelSubscriptionEntity?

    @Insert
    suspend fun insert(entity: ChannelSubscriptionEntity)

    @Update
    suspend fun update(entity: ChannelSubscriptionEntity)

    @Query("UPDATE channel_subscriptions SET lastSyncedAt = :timestamp WHERE channelId = :channelId AND gatewayUrl = :gatewayUrl")
    suspend fun updateLastSynced(gatewayUrl: String, channelId: String, timestamp: Long)

    @Query(
        """
        UPDATE channel_subscriptions
        SET displayName = :displayName, updatedAt = :updatedAt
        WHERE channelId = :channelId AND gatewayUrl = :gatewayUrl
        """
    )
    suspend fun updateDisplayName(gatewayUrl: String, channelId: String, displayName: String, updatedAt: Long)

    @Query("UPDATE channel_subscriptions SET password = :password WHERE channelId = :channelId AND gatewayUrl = :gatewayUrl")
    suspend fun updatePassword(gatewayUrl: String, channelId: String, password: String?)

    @Query(
        """
        UPDATE channel_subscriptions
        SET autoCleanupEnabled = :enabled, updatedAt = :updatedAt
        WHERE channelId = :channelId AND gatewayUrl = :gatewayUrl
        """
    )
    suspend fun updateAutoCleanupEnabled(
        gatewayUrl: String,
        channelId: String,
        enabled: Boolean,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE channel_subscriptions
        SET isDeleted = 1, deletedAt = :deletedAt, password = NULL
        WHERE channelId = :channelId AND gatewayUrl = :gatewayUrl
        """
    )
    suspend fun softDelete(gatewayUrl: String, channelId: String, deletedAt: Long)

    @Query("SELECT COUNT(*) FROM channel_subscriptions")
    suspend fun count(): Int
}
