package io.ethan.pushgo.data.model

enum class MessageListSortMode(val persistedValue: String) {
    TIME_DESC("time_desc"),
    UNREAD_FIRST("unread_first");

    companion object {
        fun fromPersistedValue(rawValue: String?): MessageListSortMode {
            return entries.firstOrNull { it.persistedValue == rawValue } ?: TIME_DESC
        }
    }
}

data class MessageFilter(
    val withUrlOnly: Boolean = false,
    val channel: String? = null,
    val serverId: String? = null,
    val sortMode: MessageListSortMode = MessageListSortMode.TIME_DESC,
)
