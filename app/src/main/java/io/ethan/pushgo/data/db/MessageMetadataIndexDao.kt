package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageMetadataIndexDao {
    @Query(
        """
        SELECT COUNT(*)
        FROM messages m
        WHERE NOT EXISTS (
            SELECT 1
            FROM message_metadata_index mi
            WHERE mi.message_id = m.id
              AND mi.key_name = 'search_text'
              AND mi.value_norm = :version
              AND mi.label IS NOT NULL
        )
        """
    )
    suspend fun countMessagesMissingSearchText(version: String): Int

    @Query(
        """
        DELETE FROM message_metadata_index
        WHERE message_id = :messageId
          AND key_name != 'summary_projection'
        """
    )
    suspend fun deleteByMessageId(messageId: String)

    @Query(
        """
        DELETE FROM message_metadata_index
        WHERE message_id = :messageId
          AND key_name = 'summary_projection'
        """
    )
    suspend fun deleteSummaryProjectionMarker(messageId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MessageMetadataIndexEntity>)
}
