package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TransportTransitionDao {
    @Query("SELECT * FROM transport_transitions ORDER BY created_at ASC LIMIT 1")
    suspend fun getPending(): TransportTransitionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TransportTransitionEntity)

    @Update
    suspend fun update(entity: TransportTransitionEntity)

    @Query("DELETE FROM transport_transitions WHERE operation_id = :operationId")
    suspend fun delete(operationId: String)
}
