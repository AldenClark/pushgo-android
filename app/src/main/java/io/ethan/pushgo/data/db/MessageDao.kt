package io.ethan.pushgo.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Update
    suspend fun update(message: MessageEntity): Int

    @Query(
        """
        SELECT * FROM messages
        WHERE (:readState IS NULL OR is_read = :readState)
          AND (:withUrl = 0 OR url IS NOT NULL)
          AND (
            :channel IS NULL
            OR (:channel = '' AND (channel IS NULL OR channel = ''))
            OR (:channel != '' AND channel = :channel)
          )
          AND (:serverId IS NULL OR server_id = :serverId)
        ORDER BY received_at DESC
        """
    )
    fun observeMessages(
        readState: Boolean?,
        withUrl: Int,
        channel: String?,
        serverId: String?,
    ): PagingSource<Int, MessageEntity>

    @Query(
        """
        SELECT m.* FROM messages m
        JOIN message_fts f ON m.rowid = f.rowid
        WHERE message_fts MATCH :query
        ORDER BY m.received_at DESC
        LIMIT :limit
        """
    )
    fun searchMessages(query: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE message_id = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE notification_id = :notificationId LIMIT 1")
    suspend fun getByNotificationId(notificationId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE message_id IN (:messageIds)")
    suspend fun getByMessageIds(messageIds: List<String>): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY received_at DESC")
    suspend fun getAll(): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE event_id IS NOT NULL
        ORDER BY COALESCE(event_time_epoch, occurred_at_epoch, received_at) DESC, received_at DESC
        """
    )
    suspend fun getEventProjectionMessages(): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE thing_id IS NOT NULL
        ORDER BY COALESCE(occurred_at_epoch, event_time_epoch, received_at) DESC, received_at DESC
        """
    )
    suspend fun getThingProjectionMessages(): List<MessageEntity>

    @Query(
        """
        SELECT id FROM messages
        WHERE (:readState IS NULL OR is_read = :readState)
          AND received_at < :cutoff
        """
    )
    suspend fun getIdsBefore(readState: Boolean?, cutoff: Long): List<String>

    @Query(
        """
        SELECT id FROM messages
        WHERE (:readState IS NULL OR is_read = :readState)
          AND (
            :channel IS NULL
            OR (:channel = '' AND (channel IS NULL OR channel = ''))
            OR (:channel != '' AND channel = :channel)
          )
        """
    )
    suspend fun getIdsByChannelRead(channel: String?, readState: Boolean?): List<String>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE is_read = 0")
    suspend fun unreadCount(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE is_read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE entity_type = 'event' OR event_id IS NOT NULL
        """
    )
    fun observeEventCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE entity_type = 'thing' OR thing_id IS NOT NULL
        """
    )
    fun observeThingCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE (:readState IS NULL OR is_read = :readState)
          AND (:cutoff IS NULL OR received_at < :cutoff)
        """
    )
    suspend fun countMessages(readState: Boolean?, cutoff: Long?): Int

    @Query("UPDATE messages SET is_read = 1 WHERE id = :id AND is_read = 0")
    suspend fun markRead(id: String)

    @Query("UPDATE messages SET is_read = 1 WHERE is_read = 0")
    suspend fun markAllRead()

    @Query("UPDATE messages SET raw_payload_json = :rawPayloadJson WHERE id = :id")
    suspend fun updateRawPayload(id: String, rawPayloadJson: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM messages WHERE channel = :channel")
    suspend fun deleteByChannel(channel: String): Int

    @Query(
        """
        DELETE FROM messages
        WHERE (:readState IS NULL OR is_read = :readState)
          AND (
            :channel IS NULL
            OR (:channel = '' AND (channel IS NULL OR channel = ''))
            OR (:channel != '' AND channel = :channel)
          )
        """
    )
    suspend fun deleteByChannelRead(channel: String?, readState: Boolean?): Int

    @Query(
        """
        DELETE FROM messages
        WHERE id IN (
          SELECT id FROM messages
          WHERE is_read = 1
            AND (
              :excludedSize = 0
              OR channel IS NULL
              OR channel NOT IN (:excludedChannels)
            )
          ORDER BY received_at ASC
          LIMIT :limit
        )
        """
    )
    suspend fun deleteOldestReadExcludingChannels(
        limit: Int,
        excludedChannels: List<String>,
        excludedSize: Int,
    ): Int

    @Query(
        """
        SELECT id FROM messages
        WHERE is_read = 1
          AND (
            :excludedSize = 0
            OR channel IS NULL
            OR channel NOT IN (:excludedChannels)
          )
        ORDER BY received_at ASC
        LIMIT :limit
        """
    )
    suspend fun getOldestReadIdsExcludingChannels(
        limit: Int,
        excludedChannels: List<String>,
        excludedSize: Int,
    ): List<String>

    @Query(
        """
        DELETE FROM messages
        WHERE id IN (
          SELECT id FROM messages
          WHERE is_read = 1
          ORDER BY received_at ASC
          LIMIT :limit
        )
        """
    )
    suspend fun deleteOldestRead(limit: Int): Int

    @Query(
        """
        SELECT id FROM messages
        WHERE is_read = 1
        ORDER BY received_at ASC
        LIMIT :limit
        """
    )
    suspend fun getOldestReadIds(limit: Int): List<String>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM messages WHERE is_read = 1")
    suspend fun deleteAllRead()

    @Query(
        """
        DELETE FROM messages
        WHERE (:readState IS NULL OR is_read = :readState)
          AND received_at < :cutoff
        """
    )
    suspend fun deleteBefore(readState: Boolean?, cutoff: Long)

    @Query(
        """
        SELECT
          COALESCE(NULLIF(TRIM(channel), ''), '') AS channel,
          COUNT(*) AS total_count,
          COALESCE(SUM(CASE WHEN is_read = 0 THEN 1 ELSE 0 END), 0) AS unread_count,
          COALESCE(MAX(received_at), 0) AS latest_received_at
        FROM messages
        WHERE (
            :channel IS NULL
            OR (:channel = '' AND (channel IS NULL OR channel = ''))
            OR (:channel != '' AND channel = :channel)
        )
          AND (:readState IS NULL OR is_read = :readState)
        GROUP BY COALESCE(NULLIF(TRIM(channel), ''), '')
        """
    )
    suspend fun getChannelAggregates(
        channel: String?,
        readState: Boolean?,
    ): List<MessageChannelStatsAggregate>

    @Query(
        """
        SELECT
          COALESCE(NULLIF(TRIM(channel), ''), '') AS channel,
          COUNT(*) AS total_count,
          COALESCE(SUM(CASE WHEN is_read = 0 THEN 1 ELSE 0 END), 0) AS unread_count,
          COALESCE(MAX(received_at), 0) AS latest_received_at
        FROM messages
        WHERE (:readState IS NULL OR is_read = :readState)
          AND received_at < :cutoff
        GROUP BY COALESCE(NULLIF(TRIM(channel), ''), '')
        """
    )
    suspend fun getChannelAggregatesBefore(
        readState: Boolean?,
        cutoff: Long,
    ): List<MessageChannelStatsAggregate>

    @Query(
        """
        SELECT
          COALESCE(NULLIF(TRIM(channel), ''), '') AS channel,
          COUNT(*) AS total_count,
          COUNT(*) AS unread_count,
          COALESCE(MAX(received_at), 0) AS latest_received_at
        FROM messages
        WHERE is_read = 0
        GROUP BY COALESCE(NULLIF(TRIM(channel), ''), '')
        """
    )
    suspend fun getUnreadAggregates(): List<MessageChannelStatsAggregate>

    @Query(
        """
        SELECT
          COALESCE(NULLIF(TRIM(channel), ''), '') AS channel,
          COUNT(*) AS total_count,
          COALESCE(SUM(CASE WHEN is_read = 0 THEN 1 ELSE 0 END), 0) AS unread_count,
          COALESCE(MAX(received_at), 0) AS latest_received_at
        FROM messages
        WHERE id IN (
          SELECT id FROM messages
          WHERE is_read = 1
            AND (
              :excludedSize = 0
              OR channel IS NULL
              OR channel NOT IN (:excludedChannels)
            )
          ORDER BY received_at ASC
          LIMIT :limit
        )
        GROUP BY COALESCE(NULLIF(TRIM(channel), ''), '')
        """
    )
    suspend fun getOldestReadAggregatesExcludingChannels(
        limit: Int,
        excludedChannels: List<String>,
        excludedSize: Int,
    ): List<MessageChannelStatsAggregate>

    @Query(
        """
        SELECT
          COALESCE(NULLIF(TRIM(channel), ''), '') AS channel,
          COUNT(*) AS total_count,
          COALESCE(SUM(CASE WHEN is_read = 0 THEN 1 ELSE 0 END), 0) AS unread_count,
          COALESCE(MAX(received_at), 0) AS latest_received_at
        FROM messages
        WHERE id IN (
          SELECT id FROM messages
          WHERE is_read = 1
          ORDER BY received_at ASC
          LIMIT :limit
        )
        GROUP BY COALESCE(NULLIF(TRIM(channel), ''), '')
        """
    )
    suspend fun getOldestReadAggregates(limit: Int): List<MessageChannelStatsAggregate>

    @Query(
        """
        SELECT MAX(received_at) FROM messages
        WHERE (
            (:channel = '' AND (channel IS NULL OR TRIM(channel) = ''))
            OR (:channel != '' AND TRIM(channel) = :channel)
        )
        """
    )
    suspend fun latestReceivedAtByNormalizedChannel(channel: String): Long?
}
