package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.ethan.pushgo.data.model.KeyEncoding

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "server_address")
    val serverAddress: String?,
    val token: String?,
    @ColumnInfo(name = "notification_key_updated_at")
    val notificationKeyUpdatedAt: Long?,
    @ColumnInfo(name = "key_encoding")
    val keyEncoding: String = KeyEncoding.BASE64.name,
    @ColumnInfo(name = "fcm_token")
    val fcmToken: String?,
    @ColumnInfo(name = "use_fcm_channel")
    val useFcmChannel: Boolean = true,
    @ColumnInfo(name = "is_message_page_enabled")
    val isMessagePageEnabled: Boolean = true,
    @ColumnInfo(name = "is_event_page_enabled")
    val isEventPageEnabled: Boolean = true,
    @ColumnInfo(name = "is_thing_page_enabled")
    val isThingPageEnabled: Boolean = true,
)
