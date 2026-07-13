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

    @Query("SELECT unread_count FROM message_global_stats WHERE id = 1")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT unread_count FROM message_global_stats WHERE id = 1")
    suspend fun unreadCount(): Int

    @Query("SELECT total_count FROM message_global_stats WHERE id = 1")
    suspend fun totalCount(): Int

    @Query("SELECT revision FROM message_store_revision WHERE id = 1")
    fun observeStoreRevision(): Flow<Long>
}
