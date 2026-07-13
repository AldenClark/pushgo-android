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
        SELECT id, message_id, title, channel, url, is_read, received_at,
               CASE WHEN list_payload_json = '{}' THEN raw_payload_json ELSE list_payload_json END
                   AS list_payload_json,
               status, decryption_state, notification_id, server_id, body_preview
        FROM messages
        WHERE (:readState IS NULL OR is_read = :readState)
          AND (:excludedCount = 0 OR id NOT IN (:excludedIds))
          AND (:withUrl = 0 OR url IS NOT NULL)
          AND (
            :channelCount = 0
            OR COALESCE(NULLIF(TRIM(channel), ''), '') IN (:channels)
          )
          AND (
            :tagCount = 0
            OR EXISTS (
              SELECT 1
              FROM message_metadata_index mi
              WHERE mi.message_id = messages.id
                AND mi.key_name = 'tag'
                AND mi.value_norm IN (:tags)
            )
          )
          AND (:serverId IS NULL OR server_id = :serverId)
        ORDER BY
          CASE
            WHEN :prioritizeUnread = 1 AND is_read = 0 THEN 0
            WHEN :prioritizeUnread = 1 THEN 1
            ELSE 0
          END ASC,
          received_at DESC,
          id DESC
        """
    )
    fun observeMessages(
        readState: Boolean?,
        withUrl: Int,
        channels: List<String>,
        channelCount: Int,
        tags: List<String>,
        tagCount: Int,
        serverId: String?,
        prioritizeUnread: Int,
        excludedIds: List<String>,
        excludedCount: Int,
    ): PagingSource<Int, MessageListRow>

    @Query(
        """
        SELECT stats.channel AS value,
               stats.total_count - CASE WHEN :excludedCount = 0 THEN 0 ELSE (
                   SELECT COUNT(*) FROM messages m
                   WHERE m.id IN (:excludedIds)
                     AND COALESCE(NULLIF(TRIM(m.channel), ''), '') = stats.channel
               ) END AS count
        FROM message_channel_counts stats
        WHERE stats.total_count > 0
          AND stats.total_count - CASE WHEN :excludedCount = 0 THEN 0 ELSE (
              SELECT COUNT(*) FROM messages m
              WHERE m.id IN (:excludedIds)
                AND COALESCE(NULLIF(TRIM(m.channel), ''), '') = stats.channel
          ) END > 0
        ORDER BY count DESC, value ASC
        """
    )
    fun observeFacetChannelCounts(
        excludedIds: List<String>,
        excludedCount: Int,
    ): Flow<List<MessageFacetValueCount>>

    @Query(
        """
        SELECT mi.value_norm AS value, COUNT(DISTINCT m.id) AS count
        FROM messages m
        JOIN message_metadata_index mi
          ON mi.message_id = m.id
         AND mi.key_name = 'tag'
        WHERE mi.value_norm != ''
          AND (:excludedCount = 0 OR m.id NOT IN (:excludedIds))
        GROUP BY mi.value_norm
        ORDER BY count DESC, value ASC
        """
    )
    fun observeFacetTagCounts(
        excludedIds: List<String>,
        excludedCount: Int,
    ): Flow<List<MessageFacetValueCount>>

    @Query(
        """
        SELECT m.id, m.message_id, m.title, m.channel, m.url, m.is_read, m.received_at,
               CASE WHEN m.list_payload_json = '{}' THEN m.raw_payload_json ELSE m.list_payload_json END
                   AS list_payload_json,
               m.status, m.decryption_state, m.notification_id, m.server_id, m.body_preview
        FROM messages m
        JOIN message_fts f ON m.rowid = f.rowid
        WHERE message_fts MATCH :query
          AND (:readState IS NULL OR m.is_read = :readState)
          AND (:excludedCount = 0 OR m.id NOT IN (:excludedIds))
        ORDER BY
          m.received_at DESC,
          m.id DESC
        """
    )
    fun searchMessages(
        query: String,
        readState: Boolean?,
        excludedIds: List<String>,
        excludedCount: Int,
    ): PagingSource<Int, MessageListRow>

    @Query(
        """
        SELECT m.id, m.message_id, m.title, m.channel, m.url, m.is_read, m.received_at,
               CASE WHEN m.list_payload_json = '{}' THEN m.raw_payload_json ELSE m.list_payload_json END
                   AS list_payload_json,
               m.status, m.decryption_state, m.notification_id, m.server_id, m.body_preview
        FROM messages m
        WHERE m.id IN (
            SELECT mi.message_id
            FROM message_metadata_index mi
            WHERE mi.key_name = 'tag'
              AND mi.value_norm IN (:tags)
            GROUP BY mi.message_id
            HAVING COUNT(DISTINCT mi.value_norm) = :tagCount
        )
          AND (:readState IS NULL OR m.is_read = :readState)
          AND (:excludedCount = 0 OR m.id NOT IN (:excludedIds))
        ORDER BY
          m.received_at DESC,
          m.id DESC
        """
    )
    fun searchMessagesByTags(
        tags: List<String>,
        tagCount: Int,
        readState: Boolean?,
        excludedIds: List<String>,
        excludedCount: Int,
    ): PagingSource<Int, MessageListRow>

    @Query(
        """
        SELECT m.id, m.message_id, m.title, m.channel, m.url, m.is_read, m.received_at,
               CASE WHEN m.list_payload_json = '{}' THEN m.raw_payload_json ELSE m.list_payload_json END
                   AS list_payload_json,
               m.status, m.decryption_state, m.notification_id, m.server_id, m.body_preview
        FROM messages m
        JOIN message_fts f ON m.rowid = f.rowid
        WHERE message_fts MATCH :query
          AND (:readState IS NULL OR m.is_read = :readState)
          AND (:excludedCount = 0 OR m.id NOT IN (:excludedIds))
          AND m.id IN (
            SELECT mi.message_id
            FROM message_metadata_index mi
            WHERE mi.key_name = 'tag'
              AND mi.value_norm IN (:tags)
            GROUP BY mi.message_id
            HAVING COUNT(DISTINCT mi.value_norm) = :tagCount
          )
        ORDER BY
          m.received_at DESC,
          m.id DESC
        """
    )
    fun searchMessagesByTextAndTags(
        query: String,
        tags: List<String>,
        tagCount: Int,
        readState: Boolean?,
        excludedIds: List<String>,
        excludedCount: Int,
    ): PagingSource<Int, MessageListRow>

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
    suspend fun loadAllForExport(): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE received_at < :beforeReceivedAt
           OR (received_at = :beforeReceivedAt AND id < :beforeId)
        ORDER BY received_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getMetadataBackfillPage(
        beforeReceivedAt: Long,
        beforeId: String,
        limit: Int,
    ): List<MessageEntity>

    @Query("UPDATE messages SET list_payload_json = :listPayloadJson WHERE id = :id")
    suspend fun updateListPayload(id: String, listPayloadJson: String): Int

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
        WHERE is_read = 0
          AND (:excludedCount = 0 OR id NOT IN (:excludedIds))
          AND (:withUrl = 0 OR url IS NOT NULL)
          AND (
            :channelCount = 0
            OR COALESCE(NULLIF(TRIM(channel), ''), '') IN (:channels)
          )
          AND (
            :tagCount = 0
            OR EXISTS (
              SELECT 1
              FROM message_metadata_index mi
              WHERE mi.message_id = messages.id
                AND mi.key_name = 'tag'
                AND mi.value_norm IN (:tags)
            )
          )
          AND (:serverId IS NULL OR server_id = :serverId)
        """
    )
    fun observeUnreadCountForFilter(
        withUrl: Int,
        channels: List<String>,
        channelCount: Int,
        tags: List<String>,
        tagCount: Int,
        serverId: String?,
        excludedIds: List<String>,
        excludedCount: Int,
    ): Flow<Int>

    @Query(
        """
        SELECT id FROM messages
        WHERE is_read = 0
          AND (:excludedCount = 0 OR id NOT IN (:excludedIds))
          AND (:withUrl = 0 OR url IS NOT NULL)
          AND (
            :channelCount = 0
            OR COALESCE(NULLIF(TRIM(channel), ''), '') IN (:channels)
          )
          AND (
            :tagCount = 0
            OR EXISTS (
              SELECT 1
              FROM message_metadata_index mi
              WHERE mi.message_id = messages.id
                AND mi.key_name = 'tag'
                AND mi.value_norm IN (:tags)
            )
          )
          AND (:serverId IS NULL OR server_id = :serverId)
        """
    )
    suspend fun getUnreadIdsForFilter(
        withUrl: Int,
        channels: List<String>,
        channelCount: Int,
        tags: List<String>,
        tagCount: Int,
        serverId: String?,
        excludedIds: List<String>,
        excludedCount: Int,
    ): List<String>

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

    @Query("UPDATE messages SET is_read = 1 WHERE id IN (:ids) AND is_read = 0")
    suspend fun markReadByIds(ids: List<String>): Int

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
}
