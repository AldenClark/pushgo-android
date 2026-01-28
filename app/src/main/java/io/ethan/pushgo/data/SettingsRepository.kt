package io.ethan.pushgo.data

import io.ethan.pushgo.data.db.AppSettingsDao
import io.ethan.pushgo.data.db.AppSettingsEntity
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.data.model.KeyLength
import io.ethan.pushgo.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class SettingsRepository(
    private val appSettingsDao: AppSettingsDao,
) {
    private val settingsFlow = appSettingsDao.observe()

    val serverAddressFlow: Flow<String?> = settingsFlow.map { it?.serverAddress }
    val ringtoneIdFlow: Flow<String?> = settingsFlow.map { it?.ringtoneId }
    val themeModeFlow: Flow<ThemeMode> = settingsFlow.map { settings ->
        val raw = settings?.themeMode
        if (raw != null) {
            runCatching { ThemeMode.valueOf(raw) }.getOrNull() ?: ThemeMode.SYSTEM
        } else {
            ThemeMode.SYSTEM
        }
    }
    val autoCleanupEnabledFlow: Flow<Boolean> = settingsFlow.map { it?.autoCleanupEnabled ?: true }

    private fun defaultSettings(): AppSettingsEntity {
        return AppSettingsEntity(
            id = 1,
            serverAddress = null,
            token = null,
            notificationKeyBase64 = null,
            notificationKeyUpdatedAt = null,
            fcmToken = null,
            ringtoneId = null,
            themeMode = null,
            autoCleanupEnabled = true,
        )
    }

    private suspend fun loadSettings(): AppSettingsEntity {
        return appSettingsDao.get() ?: defaultSettings()
    }

    private suspend fun updateSettings(update: (AppSettingsEntity) -> AppSettingsEntity) {
        appSettingsDao.upsert(update(loadSettings()))
    }

    suspend fun setServerAddress(address: String?) {
        updateSettings { it.copy(serverAddress = address) }
    }

    suspend fun getServerAddress(): String? = loadSettings().serverAddress

    suspend fun getGatewayToken(): String? = loadSettings().token

    suspend fun setGatewayToken(token: String?) {
        updateSettings { it.copy(token = token) }
    }

    suspend fun getNotificationKeyBase64(): String? = loadSettings().notificationKeyBase64

    suspend fun setNotificationKeyBase64(value: String?) {
        val trimmed = value?.trim().orEmpty()
        updateSettings { current ->
            if (trimmed.isEmpty()) {
                current.copy(notificationKeyBase64 = null, notificationKeyUpdatedAt = null)
            } else {
                current.copy(
                    notificationKeyBase64 = trimmed,
                    notificationKeyUpdatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    suspend fun getNotificationKeyUpdatedAt(): Instant? {
        val millis = loadSettings().notificationKeyUpdatedAt ?: return null
        return Instant.ofEpochMilli(millis)
    }

    suspend fun getKeyEncoding(): KeyEncoding {
        val raw = loadSettings().keyEncoding
        return runCatching { KeyEncoding.valueOf(raw) }.getOrNull() ?: KeyEncoding.BASE64
    }

    suspend fun setKeyEncoding(encoding: KeyEncoding) {
        updateSettings { it.copy(keyEncoding = encoding.name) }
    }

    suspend fun getKeyLength(): KeyLength {
        val raw = loadSettings().keyLength
        return runCatching { KeyLength.valueOf(raw) }.getOrNull() ?: KeyLength.BITS_256
    }

    suspend fun setKeyLength(length: KeyLength) {
        updateSettings { it.copy(keyLength = length.name) }
    }

    suspend fun setRingtoneId(id: String?) {
        updateSettings { it.copy(ringtoneId = id) }
    }

    suspend fun getRingtoneId(): String? = loadSettings().ringtoneId

    suspend fun setThemeMode(mode: ThemeMode) {
        updateSettings { it.copy(themeMode = mode.name) }
    }

    suspend fun getFcmToken(): String? = loadSettings().fcmToken

    suspend fun setFcmToken(token: String?) {
        updateSettings { it.copy(fcmToken = token) }
    }

    suspend fun getAutoCleanupEnabled(): Boolean = loadSettings().autoCleanupEnabled

    suspend fun setAutoCleanupEnabled(enabled: Boolean) {
        updateSettings { it.copy(autoCleanupEnabled = enabled) }
    }
}
