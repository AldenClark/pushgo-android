package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Query
import io.ethan.pushgo.data.model.MessageChannelCount
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageChannelStatsDao {
    @Query(
        """
        SELECT channel, total_count, unread_count
        FROM message_channel_counts
        WHERE total_count > 0
        ORDER BY latest_received_at DESC, channel ASC
        """
    )
    fun observeChannelCounts(): Flow<List<MessageChannelCount>>

    @Query("SELECT COALESCE(SUM(unread_count), 0) FROM message_channel_counts")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(unread_count), 0) FROM message_channel_counts")
    suspend fun unreadCount(): Int

    @Query(
        """
        INSERT INTO message_channel_counts(channel, total_count, unread_count, latest_received_at)
        VALUES(:channel, :totalCount, :unreadCount, :latestReceivedAt)
        ON CONFLICT(channel) DO UPDATE SET
            total_count = message_channel_counts.total_count + excluded.total_count,
            unread_count = message_channel_counts.unread_count + excluded.unread_count,
            latest_received_at = MAX(message_channel_counts.latest_received_at, excluded.latest_received_at)
        """
    )
    suspend fun applyPositiveDelta(
        channel: String,
        totalCount: Int,
        unreadCount: Int,
        latestReceivedAt: Long,
    )

    @Query(
        """
        UPDATE message_channel_counts
        SET total_count = MAX(total_count - :totalCount, 0),
            unread_count = MAX(unread_count - :unreadCount, 0)
        WHERE channel = :channel
        """
    )
    suspend fun applyNegativeDelta(
        channel: String,
        totalCount: Int,
        unreadCount: Int,
    )

    @Query("UPDATE message_channel_counts SET latest_received_at = :latestReceivedAt WHERE channel = :channel")
    suspend fun setLatestReceivedAt(channel: String, latestReceivedAt: Long)

    @Query("DELETE FROM message_channel_counts WHERE total_count <= 0")
    suspend fun deleteEmptyRows()

    @Query("DELETE FROM message_channel_counts WHERE channel = :channel")
    suspend fun deleteChannel(channel: String)

    @Query("DELETE FROM message_channel_counts")
    suspend fun deleteAll()
}
