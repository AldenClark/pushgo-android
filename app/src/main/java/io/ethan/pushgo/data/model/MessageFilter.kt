package io.ethan.pushgo.data.model

enum class ReadFilter {
    ALL,
    UNREAD,
    READ,
}

data class MessageFilter(
    val readFilter: ReadFilter = ReadFilter.ALL,
    val withUrlOnly: Boolean = false,
    val channel: String? = null,
    val serverId: String? = null,
)
