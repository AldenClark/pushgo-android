package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "message_metadata_index",
    primaryKeys = ["message_id", "key_name", "value_norm"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["message_id"]),
        Index(value = ["key_name", "value_norm", "received_at"]),
    ]
)
data class MessageMetadataIndexEntity(
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "key_name")
    val keyName: String,
    @ColumnInfo(name = "value_norm")
    val valueNorm: String,
    val label: String?,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
)
