package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingLocalDeletionDao {
    @Query("SELECT * FROM pending_local_deletions ORDER BY id ASC")
    fun observeFailures(): Flow<List<PendingLocalDeletionEntity>>

    @Query("SELECT * FROM pending_local_deletions ORDER BY id ASC")
    suspend fun loadFailures(): List<PendingLocalDeletionEntity>

    @Query("UPDATE pending_local_deletions SET state = 'PENDING', next_attempt_at_epoch_ms = :nowEpochMillis, updated_at_epoch_ms = :nowEpochMillis WHERE id = :id AND state = 'FAILED' AND confirmation_proven = 1")
    suspend fun retryFailed(id: Long, nowEpochMillis: Long): Int

    @Query("SELECT * FROM pending_local_deletions WHERE confirmation_proven = 0 ORDER BY id")
    suspend fun loadUnconfirmed(): List<PendingLocalDeletionEntity>

    @Query("""
        SELECT DISTINCT p.* FROM pending_local_deletions p JOIN channel_subscriptions c
          ON c.gateway_url = p.expected_gateway_url AND c.updated_at = p.expected_updated_at
        WHERE p.confirmation_proven = 0 AND p.operation_kind = 'CHANNEL'
          AND p.state IN ('PENDING','COMMITTING','FAILED') AND p.attempt_count >= 0
          AND c.is_deleted = 1 AND c.deleted_at IS NOT NULL AND p.id > :afterId
        ORDER BY p.id LIMIT :limit
    """)
    suspend fun loadCommittedChannelCleanup(afterId: Long, limit: Int): List<PendingLocalDeletionEntity>

    @Query("DELETE FROM pending_local_deletions WHERE id = :id AND confirmation_proven = 0 AND state = 'PENDING' AND attempt_count = 0")
    suspend fun cancelUnconfirmed(id: Long): Int

    @Query("UPDATE pending_local_deletions SET confirmation_proven = 1, next_attempt_at_epoch_ms = :now, updated_at_epoch_ms = :now WHERE id = :id AND confirmation_proven = 0 AND state = 'PENDING' AND attempt_count = 0")
    suspend fun confirmUnstartedChannel(id: Long, now: Long): Int

    @Insert
    suspend fun insert(entity: PendingLocalDeletionEntity): Long

    @Query(
        """
        SELECT * FROM pending_local_deletions
        WHERE confirmation_proven = 1 AND state IN ('PENDING', 'COMMITTING')
        ORDER BY undo_deadline_epoch_ms ASC, id ASC
        """
    )
    fun observeActive(): Flow<List<PendingLocalDeletionEntity>>

    @Query(
        """
        SELECT * FROM pending_local_deletions
        WHERE confirmation_proven = 1 AND state IN ('PENDING', 'COMMITTING')
        ORDER BY undo_deadline_epoch_ms ASC, id ASC
        """
    )
    suspend fun loadActive(): List<PendingLocalDeletionEntity>

    @Query("SELECT * FROM pending_local_deletions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): PendingLocalDeletionEntity?

    @Query(
        """
        UPDATE pending_local_deletions
        SET state = 'COMMITTING',
            attempt_count = attempt_count + 1,
            updated_at_epoch_ms = :nowEpochMillis,
            last_error = NULL
        WHERE id = :id
          AND state = 'PENDING'
          AND confirmation_proven = 1
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
        WHERE id = :id AND state = 'COMMITTING' AND confirmation_proven = 1
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
        WHERE id = :id AND state = 'COMMITTING' AND confirmation_proven = 1
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
        WHERE id = :id AND state = 'COMMITTING' AND confirmation_proven = 1
        """
    )
    suspend fun failClaimed(
        id: Long,
        nowEpochMillis: Long,
        lastError: String,
    ): Int

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
        WHERE state = 'COMMITTING' AND confirmation_proven = 1 AND id IN (:ids)
        """
    )
    suspend fun recoverInterruptedClaims(ids: List<Long>, nowEpochMillis: Long): Int
}
