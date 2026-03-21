package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageMetadataIndexDao {
    @Query("DELETE FROM message_metadata_index WHERE message_id = :messageId")
    suspend fun deleteByMessageId(messageId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MessageMetadataIndexEntity>)
}
