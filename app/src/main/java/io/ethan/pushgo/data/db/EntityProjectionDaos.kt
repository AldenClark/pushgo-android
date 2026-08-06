package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventChangeLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: EventChangeLogEntity)

    @Query("SELECT * FROM event_change_logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EventChangeLogEntity?

    @Query(
        """
        SELECT * FROM event_change_logs
        WHERE event_id = :eventId
        ORDER BY COALESCE(event_time_epoch, received_at) DESC, received_at DESC, id DESC
        """
    )
    suspend fun getByEventId(eventId: String): List<EventChangeLogEntity>

    @Query("DELETE FROM event_change_logs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM event_change_logs")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM event_change_logs")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(received_at), 0) FROM event_change_logs")
    fun observeLatestReceivedAt(): Flow<Long>

    @Query("DELETE FROM event_change_logs WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: String): Int

    @Query("DELETE FROM event_change_logs WHERE event_id IN (:eventIds)")
    suspend fun deleteByEventIds(eventIds: List<String>): Int

    @Query("DELETE FROM event_change_logs WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int

    @Query("SELECT entity_id FROM event_change_logs WHERE channel = :channelId")
    suspend fun getEntityIdsByChannel(channelId: String): List<String>

    @Query(
        """
        SELECT title FROM event_change_logs
        WHERE event_id = :eventId
          AND TRIM(title) <> ''
        ORDER BY COALESCE(event_time_epoch, received_at) DESC, received_at DESC
        LIMIT 1
        """
    )
    suspend fun findLatestTitleByEventId(eventId: String): String?
}

@Dao
interface ThingChangeLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ThingChangeLogEntity)

    @Query("SELECT * FROM thing_change_logs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ThingChangeLogEntity?

    @Query(
        """
        SELECT * FROM thing_change_logs
        WHERE thing_id = :thingId
        ORDER BY COALESCE(observed_time_epoch, event_time_epoch, received_at) DESC, received_at DESC, id DESC
        """
    )
    suspend fun getByThingId(thingId: String): List<ThingChangeLogEntity>

    @Query("DELETE FROM thing_change_logs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM thing_change_logs")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM thing_change_logs")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(received_at), 0) FROM thing_change_logs")
    fun observeLatestReceivedAt(): Flow<Long>

    @Query("DELETE FROM thing_change_logs WHERE thing_id = :thingId")
    suspend fun deleteByThingId(thingId: String): Int

    @Query("DELETE FROM thing_change_logs WHERE thing_id IN (:thingIds)")
    suspend fun deleteByThingIds(thingIds: List<String>): Int

    @Query("DELETE FROM thing_change_logs WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int

    @Query("SELECT entity_id FROM thing_change_logs WHERE channel = :channelId")
    suspend fun getEntityIdsByChannel(channelId: String): List<String>

    @Query(
        """
        SELECT title FROM thing_change_logs
        WHERE thing_id = :thingId
          AND TRIM(title) <> ''
        ORDER BY COALESCE(observed_time_epoch, event_time_epoch, received_at) DESC, received_at DESC
        LIMIT 1
        """
    )
    suspend fun findLatestTitleByThingId(thingId: String): String?
}

@Dao
interface ThingSubEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ThingSubEventEntity)

    @Query("SELECT * FROM thing_sub_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ThingSubEventEntity?

    @Query(
        """
        SELECT * FROM thing_sub_events
        WHERE event_id = :eventId
        ORDER BY COALESCE(event_time_epoch, received_at) DESC, received_at DESC, id DESC
        """
    )
    suspend fun getByEventId(eventId: String): List<ThingSubEventEntity>

    @Query(
        """
        SELECT * FROM thing_sub_events
        WHERE thing_id = :thingId
        ORDER BY COALESCE(event_time_epoch, received_at) DESC, received_at DESC, id DESC
        """
    )
    suspend fun getByThingId(thingId: String): List<ThingSubEventEntity>

    @Query("DELETE FROM thing_sub_events")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM thing_sub_events")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM thing_sub_events")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(received_at), 0) FROM thing_sub_events")
    fun observeLatestReceivedAt(): Flow<Long>

    @Query("DELETE FROM thing_sub_events WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: String): Int

    @Query("DELETE FROM thing_sub_events WHERE event_id IN (:eventIds)")
    suspend fun deleteByEventIds(eventIds: List<String>): Int

    @Query("DELETE FROM thing_sub_events WHERE thing_id = :thingId")
    suspend fun deleteByThingId(thingId: String): Int

    @Query("DELETE FROM thing_sub_events WHERE thing_id IN (:thingIds)")
    suspend fun deleteByThingIds(thingIds: List<String>): Int

    @Query("DELETE FROM thing_sub_events WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int

    @Query("SELECT entity_id FROM thing_sub_events WHERE channel = :channelId")
    suspend fun getEntityIdsByChannel(channelId: String): List<String>

    @Query(
        """
        SELECT title FROM thing_sub_events
        WHERE event_id = :eventId
          AND TRIM(title) <> ''
        ORDER BY COALESCE(event_time_epoch, received_at) DESC, received_at DESC
        LIMIT 1
        """
    )
    suspend fun findLatestTitleByEventId(eventId: String): String?
}

@Dao
interface TopLevelEventHeadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(head: TopLevelEventHeadEntity)

    @Query("SELECT * FROM top_level_event_heads WHERE event_id = :eventId LIMIT 1")
    suspend fun getByEventId(eventId: String): TopLevelEventHeadEntity?

    @Query(
        """
        SELECT * FROM top_level_event_heads
        ORDER BY COALESCE(event_time_epoch, received_at) DESC, received_at DESC
        """
    )
    suspend fun getAllProjection(): List<TopLevelEventHeadEntity>

    @Query(
        """
        SELECT * FROM top_level_event_heads h
        WHERE (
            :beforeReceivedAt IS NULL
            OR h.received_at < :beforeReceivedAt
            OR (h.received_at = :beforeReceivedAt AND h.source_id < :beforeId)
        )
        ORDER BY h.received_at DESC, h.source_id DESC
        LIMIT :limit
        """
    )
    suspend fun getProjectionPage(
        beforeReceivedAt: Long?,
        beforeId: String?,
        limit: Int,
    ): List<TopLevelEventHeadEntity>

    @Query("SELECT COUNT(*) FROM top_level_event_heads")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(received_at), 0) FROM top_level_event_heads")
    fun observeLatestReceivedAt(): Flow<Long>

    @Query("SELECT COUNT(*) FROM top_level_event_heads")
    suspend fun countAll(): Int

    @Query("DELETE FROM top_level_event_heads")
    suspend fun deleteAll()

    @Query("DELETE FROM top_level_event_heads WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: String): Int

    @Query("DELETE FROM top_level_event_heads WHERE event_id IN (:eventIds)")
    suspend fun deleteByEventIds(eventIds: List<String>): Int

    @Query("DELETE FROM top_level_event_heads WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int

    @Query("SELECT entity_id FROM top_level_event_heads WHERE channel = :channelId")
    suspend fun getEntityIdsByChannel(channelId: String): List<String>

    @Query(
        """
        SELECT title FROM top_level_event_heads
        WHERE event_id = :eventId
          AND TRIM(title) <> ''
        LIMIT 1
        """
    )
    suspend fun findTitleByEventId(eventId: String): String?
}

@Dao
interface ThingHeadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(head: ThingHeadEntity)

    @Query("SELECT * FROM thing_heads WHERE thing_id = :thingId LIMIT 1")
    suspend fun getByThingId(thingId: String): ThingHeadEntity?

    @Query(
        """
        SELECT * FROM thing_heads
        ORDER BY COALESCE(observed_time_epoch, event_time_epoch, received_at) DESC, received_at DESC
        """
    )
    suspend fun getAllProjection(): List<ThingHeadEntity>

    @Query(
        """
        SELECT * FROM thing_heads h
        WHERE (
            :beforeReceivedAt IS NULL
            OR h.received_at < :beforeReceivedAt
            OR (h.received_at = :beforeReceivedAt AND h.source_id < :beforeId)
        )
        ORDER BY h.received_at DESC, h.source_id DESC
        LIMIT :limit
        """
    )
    suspend fun getProjectionPage(
        beforeReceivedAt: Long?,
        beforeId: String?,
        limit: Int,
    ): List<ThingHeadEntity>

    @Query("SELECT COUNT(*) FROM thing_heads")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(received_at), 0) FROM thing_heads")
    fun observeLatestReceivedAt(): Flow<Long>

    @Query("SELECT COUNT(*) FROM thing_heads")
    suspend fun countAll(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM thing_heads WHERE thing_id = :thingId)")
    suspend fun existsByThingId(thingId: String): Boolean

    @Query("DELETE FROM thing_heads")
    suspend fun deleteAll()

    @Query("DELETE FROM thing_heads WHERE thing_id = :thingId")
    suspend fun deleteByThingId(thingId: String): Int

    @Query("DELETE FROM thing_heads WHERE thing_id IN (:thingIds)")
    suspend fun deleteByThingIds(thingIds: List<String>): Int

    @Query("DELETE FROM thing_heads WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int

    @Query("SELECT entity_id FROM thing_heads WHERE channel = :channelId")
    suspend fun getEntityIdsByChannel(channelId: String): List<String>

    @Query(
        """
        SELECT title FROM thing_heads
        WHERE thing_id = :thingId
          AND TRIM(title) <> ''
        LIMIT 1
        """
    )
    suspend fun findTitleByThingId(thingId: String): String?
}

@Dao
interface ThingSubMessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: ThingSubMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(messages: List<ThingSubMessageEntity>): List<Long>

    @Update
    suspend fun update(message: ThingSubMessageEntity): Int

    @Query("SELECT * FROM thing_sub_messages WHERE message_id = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): ThingSubMessageEntity?

    @Query("SELECT * FROM thing_sub_messages WHERE message_id IN (:messageIds)")
    suspend fun getByMessageIds(messageIds: List<String>): List<ThingSubMessageEntity>

    @Query("SELECT * FROM thing_sub_messages WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ThingSubMessageEntity>

    @Query(
        """
        SELECT * FROM thing_sub_messages
        WHERE thing_id = :thingId
        ORDER BY COALESCE(occurred_at_epoch, event_time_epoch, received_at) DESC, received_at DESC, id DESC
        """
    )
    suspend fun getByThingId(thingId: String): List<ThingSubMessageEntity>

    @Query("DELETE FROM thing_sub_messages")
    suspend fun deleteAll()

    @Query("DELETE FROM thing_sub_messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM thing_sub_messages")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM thing_sub_messages")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(received_at), 0) FROM thing_sub_messages")
    fun observeLatestReceivedAt(): Flow<Long>

    @Query("DELETE FROM thing_sub_messages WHERE thing_id = :thingId")
    suspend fun deleteByThingId(thingId: String): Int

    @Query("DELETE FROM thing_sub_messages WHERE thing_id IN (:thingIds)")
    suspend fun deleteByThingIds(thingIds: List<String>): Int

    @Query("DELETE FROM thing_sub_messages WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int

    @Query("SELECT id FROM thing_sub_messages WHERE channel = :channelId")
    suspend fun getIdsByChannel(channelId: String): List<String>
}
