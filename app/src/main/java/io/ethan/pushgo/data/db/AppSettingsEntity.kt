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
    @ColumnInfo(name = "update_auto_check_enabled")
    val updateAutoCheckEnabled: Boolean = true,
    @ColumnInfo(name = "update_beta_channel_enabled")
    val updateBetaChannelEnabled: Boolean = false,
    @ColumnInfo(name = "update_skipped_version_code")
    val updateSkippedVersionCode: Int? = null,
    @ColumnInfo(name = "update_last_prompted_version_code")
    val updateLastPromptedVersionCode: Int? = null,
    @ColumnInfo(name = "update_prompt_cooldown_until")
    val updatePromptCooldownUntil: Long? = null,
    @ColumnInfo(name = "update_prompt_dismiss_count")
    val updatePromptDismissCount: Int = 0,
    @ColumnInfo(name = "update_last_check_at")
    val updateLastCheckAt: Long? = null,
)
