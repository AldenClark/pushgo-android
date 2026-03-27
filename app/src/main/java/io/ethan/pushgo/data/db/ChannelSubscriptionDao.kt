package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ChannelSubscriptionDao {
    @Query(
        "SELECT * FROM channel_subscriptions " +
            "WHERE is_deleted = 0 AND gateway_url = :gatewayUrl " +
            "ORDER BY updated_at DESC, channel_id ASC"
    )
    suspend fun getActive(gatewayUrl: String): List<ChannelSubscriptionEntity>

    @Query(
        "SELECT * FROM channel_subscriptions " +
            "WHERE gateway_url = :gatewayUrl " +
            "ORDER BY updated_at DESC, channel_id ASC"
    )
    suspend fun getAll(gatewayUrl: String): List<ChannelSubscriptionEntity>

    @Query("SELECT * FROM channel_subscriptions WHERE channel_id = :channelId AND gateway_url = :gatewayUrl LIMIT 1")
    suspend fun getById(gatewayUrl: String, channelId: String): ChannelSubscriptionEntity?

    @Insert
    suspend fun insert(entity: ChannelSubscriptionEntity)

    @Update
    suspend fun update(entity: ChannelSubscriptionEntity)

    @Query("UPDATE channel_subscriptions SET last_synced_at = :timestamp WHERE channel_id = :channelId AND gateway_url = :gatewayUrl")
    suspend fun updateLastSynced(gatewayUrl: String, channelId: String, timestamp: Long)

    @Query(
        """
        UPDATE channel_subscriptions
        SET display_name = :displayName, updated_at = :updatedAt
        WHERE channel_id = :channelId AND gateway_url = :gatewayUrl
        """
    )
    suspend fun updateDisplayName(gatewayUrl: String, channelId: String, displayName: String, updatedAt: Long)

    @Query(
        """
        UPDATE channel_subscriptions
        SET is_deleted = 1, deleted_at = :deletedAt
        WHERE channel_id = :channelId AND gateway_url = :gatewayUrl
        """
    )
    suspend fun softDelete(gatewayUrl: String, channelId: String, deletedAt: Long)

    @Query("SELECT COUNT(*) FROM channel_subscriptions")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM channel_subscriptions WHERE is_deleted = 0 AND gateway_url = :gatewayUrl")
    suspend fun countActive(gatewayUrl: String): Int

    @Query("DELETE FROM channel_subscriptions")
    suspend fun deleteAll()
}
