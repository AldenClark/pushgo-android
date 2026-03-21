package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EntityRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entityRecord: EntityRecordEntity)

    @Query("SELECT * FROM entity_records WHERE delivery_id = :deliveryId LIMIT 1")
    suspend fun getByDeliveryId(deliveryId: String): EntityRecordEntity?

    @Query(
        """
        SELECT COUNT(*) FROM (
          SELECT DISTINCT event_id
          FROM entity_records
          WHERE event_id IS NOT NULL AND TRIM(event_id) <> ''
        )
        """
    )
    fun observeEventCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM (
          SELECT DISTINCT thing_id
          FROM entity_records
          WHERE thing_id IS NOT NULL AND TRIM(thing_id) <> ''
        )
        """
    )
    fun observeThingCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM entity_records
        WHERE event_id IS NOT NULL AND TRIM(event_id) <> ''
        ORDER BY COALESCE(event_time_epoch, received_at) DESC, received_at DESC
        """
    )
    suspend fun getEventProjectionRecords(): List<EntityRecordEntity>

    @Query(
        """
        SELECT * FROM entity_records
        WHERE thing_id IS NOT NULL AND TRIM(thing_id) <> ''
        ORDER BY COALESCE(observed_time_epoch, event_time_epoch, received_at) DESC, received_at DESC
        """
    )
    suspend fun getThingProjectionRecords(): List<EntityRecordEntity>

    @Query("DELETE FROM entity_records")
    suspend fun deleteAll()

    @Query("DELETE FROM entity_records WHERE event_id = :eventId")
    suspend fun deleteByEventId(eventId: String): Int

    @Query("DELETE FROM entity_records WHERE thing_id = :thingId")
    suspend fun deleteByThingId(thingId: String): Int
}
