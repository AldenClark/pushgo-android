package io.ethan.pushgo.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query(
        """
        SELECT * FROM messages
        WHERE (:readState IS NULL OR isRead = :readState)
          AND (:withUrl = 0 OR url IS NOT NULL)
          AND (
            :channel IS NULL
            OR (:channel = '' AND (channel IS NULL OR channel = ''))
            OR (:channel != '' AND channel = :channel)
          )
          AND (:serverId IS NULL OR serverId = :serverId)
        ORDER BY receivedAt DESC
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
        ORDER BY m.receivedAt DESC
        LIMIT :limit
        """
    )
    fun searchMessages(query: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY receivedAt DESC")
    suspend fun getAll(): List<MessageEntity>

    @Query(
        """
        SELECT id FROM messages
        WHERE (:readState IS NULL OR isRead = :readState)
          AND receivedAt < :cutoff
        """
    )
    suspend fun getIdsBefore(readState: Boolean?, cutoff: Long): List<String>

    @Query(
        """
        SELECT id FROM messages
        WHERE (:readState IS NULL OR isRead = :readState)
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

    @Query("SELECT COUNT(*) FROM messages WHERE isRead = 0")
    suspend fun unreadCount(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE (:readState IS NULL OR isRead = :readState)
          AND (:cutoff IS NULL OR receivedAt < :cutoff)
        """
    )
    suspend fun countMessages(readState: Boolean?, cutoff: Long?): Int

    @Query("UPDATE messages SET isRead = 1 WHERE id = :id AND isRead = 0")
    suspend fun markRead(id: String)

    @Query("UPDATE messages SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllRead()

    @Query("UPDATE messages SET rawPayloadJson = :rawPayloadJson WHERE id = :id")
    suspend fun updateRawPayload(id: String, rawPayloadJson: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM messages WHERE channel = :channel")
    suspend fun deleteByChannel(channel: String): Int

    @Query(
        """
        DELETE FROM messages
        WHERE (:readState IS NULL OR isRead = :readState)
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
          WHERE isRead = 1
            AND (
              :excludedSize = 0
              OR channel IS NULL
              OR channel NOT IN (:excludedChannels)
            )
          ORDER BY receivedAt ASC
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
        WHERE isRead = 1
          AND (
            :excludedSize = 0
            OR channel IS NULL
            OR channel NOT IN (:excludedChannels)
          )
        ORDER BY receivedAt ASC
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
          WHERE isRead = 1
          ORDER BY receivedAt ASC
          LIMIT :limit
        )
        """
    )
    suspend fun deleteOldestRead(limit: Int): Int

    @Query(
        """
        SELECT id FROM messages
        WHERE isRead = 1
        ORDER BY receivedAt ASC
        LIMIT :limit
        """
    )
    suspend fun getOldestReadIds(limit: Int): List<String>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Query("DELETE FROM messages WHERE isRead = 1")
    suspend fun deleteAllRead()

    @Query(
        """
        DELETE FROM messages
        WHERE (:readState IS NULL OR isRead = :readState)
          AND receivedAt < :cutoff
        """
    )
    suspend fun deleteBefore(readState: Boolean?, cutoff: Long)

    @Query(
        """
        SELECT
          COALESCE(NULLIF(TRIM(channel), ''), '') AS channel,
          COUNT(*) AS totalCount,
          COALESCE(SUM(CASE WHEN isRead = 0 THEN 1 ELSE 0 END), 0) AS unreadCount
        FROM messages
        GROUP BY COALESCE(NULLIF(TRIM(channel), ''), '')
        ORDER BY MAX(receivedAt) DESC
        """
    )
    fun observeChannelCounts(): Flow<List<io.ethan.pushgo.data.model.MessageChannelCount>>
}
