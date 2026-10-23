package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DeletionNotificationCleanupDao {
    @Insert
    suspend fun insertAll(receipts: List<DeletionNotificationCleanupEntity>)

    @Query("SELECT * FROM notification_deletion_cleanup ORDER BY id")
    suspend fun loadAll(): List<DeletionNotificationCleanupEntity>

    @Query("DELETE FROM notification_deletion_cleanup WHERE id = :id")
    suspend fun deleteById(id: Long)
}
