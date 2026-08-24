package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingLocalDeletionDao {
    @Insert
    suspend fun insert(entity: PendingLocalDeletionEntity): Long

    @Query(
        """
        SELECT * FROM pending_local_deletions
        WHERE state IN ('PENDING', 'COMMITTING')
        ORDER BY undo_deadline_epoch_ms ASC, id ASC
        """
    )
    fun observeActive(): Flow<List<PendingLocalDeletionEntity>>

    @Query(
        """
        SELECT * FROM pending_local_deletions
        WHERE state IN ('PENDING', 'COMMITTING')
        ORDER BY undo_deadline_epoch_ms ASC, id ASC
        """
    )
    suspend fun loadActive(): List<PendingLocalDeletionEntity>

    @Query("SELECT * FROM pending_local_deletions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): PendingLocalDeletionEntity?

    @Query(
        """
        SELECT MAX(undo_deadline_epoch_ms) FROM pending_local_deletions
        WHERE state IN ('PENDING', 'COMMITTING')
        """
    )
    suspend fun latestUndoDeadline(): Long?

    @Query(
        """
        DELETE FROM pending_local_deletions
        WHERE id = :id
          AND state = 'PENDING'
          AND attempt_count = 0
        """
    )
    suspend fun cancelPending(id: Long): Int

    @Query(
        """
        UPDATE pending_local_deletions
        SET state = 'COMMITTING',
            attempt_count = attempt_count + 1,
            updated_at_epoch_ms = :nowEpochMillis,
            last_error = NULL
        WHERE id = :id
          AND state = 'PENDING'
          AND next_attempt_at_epoch_ms <= :nowEpochMillis
          AND (:force = 1 OR undo_deadline_epoch_ms <= :nowEpochMillis)
        """
    )
    suspend fun claim(
        id: Long,
        nowEpochMillis: Long,
        force: Boolean,
    ): Int

    @Query(
        """
        DELETE FROM pending_local_deletions
        WHERE id = :id AND state = 'COMMITTING'
        """
    )
    suspend fun completeClaimed(id: Long): Int

    @Query(
        """
        UPDATE pending_local_deletions
        SET state = 'PENDING',
            next_attempt_at_epoch_ms = :nextAttemptAtEpochMillis,
            updated_at_epoch_ms = :nowEpochMillis,
            last_error = :lastError
        WHERE id = :id AND state = 'COMMITTING'
        """
    )
    suspend fun retryClaimed(
        id: Long,
        nowEpochMillis: Long,
        nextAttemptAtEpochMillis: Long,
        lastError: String,
    ): Int

    @Query(
        """
        UPDATE pending_local_deletions
        SET state = 'FAILED',
            updated_at_epoch_ms = :nowEpochMillis,
            last_error = :lastError
        WHERE id = :id AND state = 'COMMITTING'
        """
    )
    suspend fun failClaimed(
        id: Long,
        nowEpochMillis: Long,
        lastError: String,
    ): Int

    @Query(
        """
        DELETE FROM pending_local_deletions
        WHERE state = 'FAILED'
          AND id NOT IN (
              SELECT id FROM pending_local_deletions
              WHERE state = 'FAILED'
              ORDER BY updated_at_epoch_ms DESC, id DESC
              LIMIT :keepCount
          )
        """
    )
    suspend fun pruneFailedHistory(keepCount: Int): Int

    @Query(
        """
        UPDATE pending_local_deletions
        SET state = 'PENDING',
            next_attempt_at_epoch_ms = :nowEpochMillis,
            updated_at_epoch_ms = :nowEpochMillis,
            last_error = CASE
                WHEN last_error IS NULL THEN 'Interrupted while committing; execution will be retried'
                ELSE last_error
            END
        WHERE state = 'COMMITTING'
        """
    )
    suspend fun recoverInterruptedClaims(nowEpochMillis: Long): Int
}
