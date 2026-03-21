package io.ethan.pushgo.data

import io.ethan.pushgo.data.db.AppSettingsDao
import io.ethan.pushgo.data.db.AppSettingsEntity
import io.ethan.pushgo.data.model.KeyEncoding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class SettingsRepository(
    private val appSettingsDao: AppSettingsDao,
    private val secretStore: SecureSecretStore,
) {
    private val settingsFlow = appSettingsDao.observe()

    val serverAddressFlow: Flow<String?> = settingsFlow.map { it?.serverAddress }
    val messagePageEnabledFlow: Flow<Boolean> =
        settingsFlow.map { it?.isMessagePageEnabled ?: true }
    val eventPageEnabledFlow: Flow<Boolean> =
        settingsFlow.map { it?.isEventPageEnabled ?: true }
    val thingPageEnabledFlow: Flow<Boolean> =
        settingsFlow.map { it?.isThingPageEnabled ?: true }
    val useFcmChannelFlow: Flow<Boolean> =
        settingsFlow.map { it?.useFcmChannel ?: true }

    private fun defaultSettings(): AppSettingsEntity {
        return AppSettingsEntity(
            id = 1,
            serverAddress = null,
            token = null,
            notificationKeyUpdatedAt = null,
            fcmToken = null,
            useFcmChannel = true,
            isMessagePageEnabled = true,
            isEventPageEnabled = true,
            isThingPageEnabled = true,
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

    suspend fun getGatewayToken(): String? {
        return secretStore.gatewayToken()
    }

    suspend fun setGatewayToken(token: String?) {
        val normalized = token?.trim()?.ifEmpty { null }
        secretStore.setGatewayToken(normalized)
        updateSettings { it.copy(token = null) }
    }

    suspend fun getNotificationKeyBytes(): ByteArray? =
        secretStore.notificationKeyBytes()

    suspend fun setNotificationKeyBytes(value: ByteArray?) {
        val trimmed = value?.takeIf { it.isNotEmpty() }
        secretStore.setNotificationKeyBytes(trimmed)
        updateSettings { current ->
            if (trimmed == null) {
                current.copy(notificationKeyUpdatedAt = null)
            } else {
                current.copy(
                    notificationKeyUpdatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    suspend fun getProviderDeviceKey(platform: String): String? {
        return secretStore.providerDeviceKey(platform)
    }

    suspend fun setProviderDeviceKey(platform: String, deviceKey: String?) {
        val normalized = deviceKey?.trim()?.ifEmpty { null }
        secretStore.setProviderDeviceKey(platform, normalized)
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

    suspend fun getFcmToken(): String? {
        return secretStore.fcmToken()
    }

    suspend fun setFcmToken(token: String?) {
        val normalized = token?.trim()?.ifEmpty { null }
        secretStore.setFcmToken(normalized)
        updateSettings { it.copy(fcmToken = null) }
    }

    suspend fun getUseFcmChannel(): Boolean = loadSettings().useFcmChannel

    suspend fun getMessagePageEnabled(): Boolean = loadSettings().isMessagePageEnabled

    suspend fun getEventPageEnabled(): Boolean = loadSettings().isEventPageEnabled

    suspend fun getThingPageEnabled(): Boolean = loadSettings().isThingPageEnabled

    suspend fun setUseFcmChannel(enabled: Boolean) {
        updateSettings { it.copy(useFcmChannel = enabled) }
    }

    suspend fun setMessagePageEnabled(enabled: Boolean) {
        updateSettings { it.copy(isMessagePageEnabled = enabled) }
    }

    suspend fun setEventPageEnabled(enabled: Boolean) {
        updateSettings { it.copy(isEventPageEnabled = enabled) }
    }

    suspend fun setThingPageEnabled(enabled: Boolean) {
        updateSettings { it.copy(isThingPageEnabled = enabled) }
    }

    suspend fun reenablePageForEntity(entityType: String) {
        when (entityType.trim().lowercase()) {
            "message" -> setMessagePageEnabled(true)
            "event" -> setEventPageEnabled(true)
            "thing" -> setThingPageEnabled(true)
        }
    }

    suspend fun resetForAutomation(defaultServerAddress: String?) {
        secretStore.clearAll()
        appSettingsDao.deleteAll()
        val normalizedAddress = defaultServerAddress?.trim()?.ifEmpty { null }
        appSettingsDao.upsert(
            defaultSettings().copy(
                serverAddress = normalizedAddress,
                useFcmChannel = false,
            )
        )
    }
}
