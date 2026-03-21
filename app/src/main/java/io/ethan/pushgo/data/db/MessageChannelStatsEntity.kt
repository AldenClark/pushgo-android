package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_channel_counts",
    indices = [Index(value = ["latest_received_at"])]
)
data class MessageChannelStatsEntity(
    @PrimaryKey val channel: String,
    @ColumnInfo(name = "total_count")
    val totalCount: Int,
    @ColumnInfo(name = "unread_count")
    val unreadCount: Int,
    @ColumnInfo(name = "latest_received_at")
    val latestReceivedAt: Long,
)
