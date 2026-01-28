package io.ethan.pushgo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.data.model.KeyLength

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val serverAddress: String?,
    val token: String?,
    val notificationKeyBase64: String?,
    val notificationKeyUpdatedAt: Long?,
    val keyEncoding: String = KeyEncoding.BASE64.name,
    val keyLength: String = KeyLength.BITS_256.name,
    val fcmToken: String?,
    val ringtoneId: String?,
    val themeMode: String?,
    val autoCleanupEnabled: Boolean = true,
)
