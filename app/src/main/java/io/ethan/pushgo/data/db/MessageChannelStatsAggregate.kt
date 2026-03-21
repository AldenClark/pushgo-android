package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo

data class MessageChannelStatsAggregate(
    val channel: String,
    @ColumnInfo(name = "total_count")
    val totalCount: Int,
    @ColumnInfo(name = "unread_count")
    val unreadCount: Int,
    @ColumnInfo(name = "latest_received_at")
    val latestReceivedAt: Long,
)
