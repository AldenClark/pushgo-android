package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** An OS notification instance to retire after a committed local deletion, not a deletion job. */
@Entity(tableName = "notification_deletion_cleanup")
data class DeletionNotificationCleanupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tag: String?,
    @ColumnInfo(name = "notification_id") val notificationId: Int,
    @ColumnInfo(name = "post_time") val postTime: Long,
    val generation: String?,
)
