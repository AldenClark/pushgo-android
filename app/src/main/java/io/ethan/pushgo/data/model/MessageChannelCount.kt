package io.ethan.pushgo.data.model

data class MessageChannelCount(
    val channel: String,
    val totalCount: Int,
    val unreadCount: Int,
)
