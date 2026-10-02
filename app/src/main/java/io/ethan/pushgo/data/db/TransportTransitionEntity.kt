package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transport_transitions")
data class TransportTransitionEntity(
    @PrimaryKey
    @ColumnInfo(name = "operation_id")
    val operationId: String,
    @ColumnInfo(name = "gateway_url")
    val gatewayUrl: String,
    @ColumnInfo(name = "device_key")
    val deviceKey: String,
    @ColumnInfo(name = "target_channel_type")
    val targetChannelType: String,
    @ColumnInfo(name = "transition_id")
    val transitionId: String?,
    @ColumnInfo(name = "base_revision")
    val baseRevision: Long,
    @ColumnInfo(name = "committed_revision")
    val committedRevision: Long?,
    val phase: String,
    @ColumnInfo(name = "candidate_token_fingerprint")
    val candidateTokenFingerprint: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_error")
    val lastError: String?,
)
