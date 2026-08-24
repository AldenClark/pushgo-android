package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_local_deletions",
    indices = [
        Index(value = ["state", "next_attempt_at_epoch_ms", "undo_deadline_epoch_ms"]),
    ],
)
data class PendingLocalDeletionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val summary: String,
    @ColumnInfo(name = "operation_kind")
    val operationKind: String,
    @ColumnInfo(name = "target_ids_json")
    val targetIdsJson: String,
    @ColumnInfo(name = "expected_gateway_url")
    val expectedGatewayUrl: String?,
    @ColumnInfo(name = "expected_updated_at")
    val expectedUpdatedAt: Long?,
    @ColumnInfo(name = "expected_use_provider")
    val expectedUseProvider: Boolean?,
    @ColumnInfo(name = "requested_at_epoch_ms")
    val requestedAtEpochMillis: Long,
    @ColumnInfo(name = "undo_deadline_epoch_ms")
    val undoDeadlineEpochMillis: Long,
    val state: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "next_attempt_at_epoch_ms")
    val nextAttemptAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "last_error")
    val lastError: String?,
)
