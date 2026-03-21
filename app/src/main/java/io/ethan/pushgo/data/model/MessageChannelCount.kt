package io.ethan.pushgo.data.model

import androidx.room.ColumnInfo

data class MessageChannelCount(
    val channel: String,
    @ColumnInfo(name = "total_count")
    val totalCount: Int,
    @ColumnInfo(name = "unread_count")
    val unreadCount: Int,
)
