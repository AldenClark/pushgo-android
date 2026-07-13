package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "message_global_stats")
data class MessageGlobalStatsEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "message_store_revision")
data class MessageStoreRevisionEntity(
    @PrimaryKey val id: Int = 1,
    val revision: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "message_derived_state")
data class MessageDerivedStateEntity(
    @PrimaryKey val component: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    val status: String,
    @ColumnInfo(name = "source_revision") val sourceRevision: Long,
    @ColumnInfo(name = "cursor_local_message_id") val cursorLocalMessageId: String?,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
    @ColumnInfo(name = "last_error") val lastError: String?,
)
