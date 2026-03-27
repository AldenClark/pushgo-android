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

    @Query("SELECT * FROM event_change_logs WHERE delivery_id = :deliveryId LIMIT 1")
    suspend fun getByDeliveryId(deliveryId: String): EventChangeLogEntity?

    @Query(
        """
        SELECT * FROM event_change_logs
        ORDER BY COALESCE(event_time_epoch, received_at) DESC, received_at DESC
        """
    )
    suspend fun getAllProjection(): List<EventChangeLogEntity>

    @Query(
        """
        SELECT * FROM event_change_logs
        WHERE (
            :beforeReceivedAt IS NULL
            OR received_at < :beforeReceivedAt
            OR (received_at = :beforeReceivedAt AND id < :beforeId)
        )
        ORDER BY received_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getProjectionPage(
        beforeReceivedAt: Long?,
        beforeId: String?,
        limit: Int,
    ): List<EventChangeLogEntity>

    @Query("DELETE FROM event_change_logs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM event_change_logs")
    suspend fun countAll(): Int

    @Query("DELETE FROM event_change_logs WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: String): Int

    @Query("DELETE FROM event_change_logs WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int

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

    @Query("SELECT * FROM thing_change_logs WHERE delivery_id = :deliveryId LIMIT 1")
    suspend fun getByDeliveryId(deliveryId: String): ThingChangeLogEntity?

    @Query(
        """
        SELECT * FROM thing_change_logs
        ORDER BY COALESCE(observed_time_epoch, event_time_epoch, received_at) DESC, received_at DESC
        """
    )
    suspend fun getAllProjection(): List<ThingChangeLogEntity>

    @Query(
        """
        SELECT * FROM thing_change_logs
        WHERE (
            :beforeReceivedAt IS NULL
            OR received_at < :beforeReceivedAt
            OR (received_at = :beforeReceivedAt AND id < :beforeId)
        )
        ORDER BY received_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getProjectionPage(
        beforeReceivedAt: Long?,
        beforeId: String?,
        limit: Int,
    ): List<ThingChangeLogEntity>

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

    @Query("DELETE FROM thing_change_logs WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int
}

@Dao
interface ThingSubEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ThingSubEventEntity)

    @Query("SELECT * FROM thing_sub_events WHERE delivery_id = :deliveryId LIMIT 1")
    suspend fun getByDeliveryId(deliveryId: String): ThingSubEventEntity?

    @Query(
        """
        SELECT * FROM thing_sub_events
        ORDER BY COALESCE(event_time_epoch, received_at) DESC, received_at DESC
        """
    )
    suspend fun getAllProjection(): List<ThingSubEventEntity>

    @Query(
        """
        SELECT * FROM thing_sub_events
        WHERE (
            :beforeReceivedAt IS NULL
            OR received_at < :beforeReceivedAt
            OR (received_at = :beforeReceivedAt AND id < :beforeId)
        )
        ORDER BY received_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getProjectionPage(
        beforeReceivedAt: Long?,
        beforeId: String?,
        limit: Int,
    ): List<ThingSubEventEntity>

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

    @Query("DELETE FROM thing_sub_events WHERE thing_id = :thingId")
    suspend fun deleteByThingId(thingId: String): Int

    @Query("DELETE FROM thing_sub_events WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int

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
        WHERE NOT EXISTS (
            SELECT 1
            FROM event_change_logs e
            WHERE e.event_id = h.event_id
        )
          AND (
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

    @Query("DELETE FROM top_level_event_heads WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int

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
        WHERE NOT EXISTS (
            SELECT 1
            FROM thing_change_logs t
            WHERE t.thing_id = h.thing_id
        )
          AND (
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

    @Query("DELETE FROM thing_heads WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int
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
        ORDER BY COALESCE(occurred_at_epoch, event_time_epoch, received_at) DESC, received_at DESC
        """
    )
    suspend fun getAllProjection(): List<ThingSubMessageEntity>

    @Query(
        """
        SELECT * FROM thing_sub_messages
        WHERE (
            :beforeReceivedAt IS NULL
            OR received_at < :beforeReceivedAt
            OR (received_at = :beforeReceivedAt AND id < :beforeId)
        )
        ORDER BY received_at DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun getProjectionPage(
        beforeReceivedAt: Long?,
        beforeId: String?,
        limit: Int,
    ): List<ThingSubMessageEntity>

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

    @Query("DELETE FROM thing_sub_messages WHERE channel = :channelId")
    suspend fun deleteByChannel(channelId: String): Int
}
