package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo

data class MessageFacetValueCount(
    @ColumnInfo(name = "value")
    val value: String,
    @ColumnInfo(name = "count")
    val count: Int,
)
