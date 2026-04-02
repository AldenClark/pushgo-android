package io.ethan.pushgo.data

import android.util.Log
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.util.UrlValidators
import org.json.JSONObject
import java.net.URLEncoder

class ChannelSubscriptionRepository(
    private val store: ChannelSubscriptionStore,
    private val settingsRepository: SettingsRepository,
    private val messageStateCoordinator: MessageStateCoordinator,
) {
    companion object {
        private const val TAG = "ChannelSubscriptionRepo"
        private const val FCM_CHANNEL_TYPE = "fcm"
    }

    private val service = ChannelSubscriptionService()

    suspend fun loadSubscriptions(): List<ChannelSubscription> {
        val config = resolveServerConfig()
        return store.loadSubscriptions(config.address)
    }

    suspend fun loadActiveCredentials(): List<Pair<String, String>> {
        val config = resolveServerConfig()
        return store.loadActiveCredentials(config.address)
    }

    suspend fun loadGatewayConfig(): Pair<String, String?> {
        val config = resolveServerConfig()
        return config.address to config.token
    }

    suspend fun pullMessage(deliveryId: String): PullItem? {
        val normalizedDeliveryId = deliveryId.trim()
        if (normalizedDeliveryId.isEmpty()) {
            throw ChannelSubscriptionException("Missing delivery_id")
        }
        val config = resolveServerConfig()
        return service.pullMessage(
            baseUrl = config.address,
            token = config.token,
            deliveryId = normalizedDeliveryId,
        )
    }

    suspend fun loadSubscriptionLookup(includeDeleted: Boolean = true): Map<String, String> {
        val config = resolveServerConfig()
        val items = store.loadSubscriptions(gatewayUrl = config.address, includeDeleted = includeDeleted)
        return items.associate { it.channelId.trim() to it.displayName }
    }

    suspend fun channelPassword(channelId: String): String? {
        val trimmed = channelId.trim()
        if (trimmed.isEmpty()) return null
        val config = resolveServerConfig()
        return store.passwordFor(config.address, trimmed)?.trim()?.ifEmpty { null }
    }

    suspend fun channelExists(rawChannelId: String): ChannelExistsResult {
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val config = resolveServerConfig()
        return service.channelExists(
            baseUrl = config.address,
            token = config.token,
            channelId = channelId,
        )
    }

    suspend fun createChannel(
        rawAlias: String,
        password: String,
        deviceToken: String?,
    ): ChannelSubscribeResult {
        val alias = ChannelNameValidator.normalize(rawAlias)
        val normalizedPassword = ChannelPasswordValidator.normalize(password)
        return subscribeInternal(
            channelId = null,
            channelName = alias,
            password = normalizedPassword,
            deviceToken = deviceToken,
        )
    }

    suspend fun subscribeChannel(
        rawChannelId: String,
        password: String,
        deviceToken: String?,
    ): ChannelSubscribeResult {
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val normalizedPassword = ChannelPasswordValidator.normalize(password)
        return subscribeInternal(
            channelId = channelId,
            channelName = null,
            password = normalizedPassword,
            deviceToken = deviceToken,
        )
    }

    suspend fun renameChannel(
        rawChannelId: String,
        rawAlias: String,
    ): ChannelRenameResult {
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val alias = ChannelNameValidator.normalize(rawAlias)
        val config = resolveServerConfig()
        val password = store.passwordFor(config.address, channelId)
            ?: throw ChannelSubscriptionException("Missing channel password")
        
        val result = service.renameChannel(
            baseUrl = config.address,
            token = config.token,
            channelId = channelId,
            channelName = alias,
            password = password,
        )
        store.updateDisplayName(config.address, result.channelId, result.channelName)
        return result
    }

    suspend fun unsubscribeChannel(
        rawChannelId: String,
        deviceToken: String?,
        deleteLocalMessages: Boolean,
    ): Int {
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val config = resolveServerConfig()
        val token = deviceToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ChannelSubscriptionException("Missing system token for unsubscribe")
        var deviceKey = ensureProviderRoute(token, config)
        try {
            service.unsubscribe(
                baseUrl = config.address,
                token = config.token,
                deviceKey = deviceKey,
                channelId = channelId,
            )
        } catch (error: ChannelSubscriptionException) {
            if (!isDeviceKeyMissingError(error.message)) {
                throw error
            }
            deviceKey = ensureProviderRoute(token, config)
            service.unsubscribe(
                baseUrl = config.address,
                token = config.token,
                deviceKey = deviceKey,
                channelId = channelId,
            )
        }
        store.softDeleteSubscription(config.address, channelId)
        return if (deleteLocalMessages) {
            messageStateCoordinator.deleteMessagesByChannel(channelId)
        } else {
            0
        }
    }

    suspend fun handleTokenUpdate(deviceToken: String) {
        settingsRepository.setFcmToken(deviceToken.trim().ifEmpty { null })
    }

    suspend fun syncProviderDeviceToken(deviceToken: String): String {
        val normalized = deviceToken.trim()
        if (normalized.isEmpty()) {
            throw ChannelSubscriptionException("Missing system token for provider route")
        }
        val config = resolveServerConfig()
        return ensureProviderRoute(normalized, config)
    }

    suspend fun cleanupLegacyGatewayDeviceRoute(
        legacyBaseUrl: String,
        legacyToken: String?,
        legacyDeviceKey: String,
    ) {
        val deviceKey = legacyDeviceKey.trim()
        if (deviceKey.isEmpty()) return
        runCatching {
            service.deleteDeviceChannel(
                baseUrl = legacyBaseUrl,
                token = legacyToken,
                deviceKey = deviceKey,
                channelType = FCM_CHANNEL_TYPE,
            )
        }
        runCatching {
            service.deleteDeviceChannel(
                baseUrl = legacyBaseUrl,
                token = legacyToken,
                deviceKey = deviceKey,
                channelType = "private",
            )
        }
    }

    suspend fun syncSubscriptionsIfNeeded(deviceToken: String): SyncOutcome {
        val normalizedToken = deviceToken.trim()
        if (normalizedToken.isEmpty()) return SyncOutcome()
        val config = resolveServerConfig()
        val credentials = store.loadActiveCredentials(config.address)
        if (credentials.isEmpty()) return SyncOutcome()
        var deviceKey = ensureProviderRoute(normalizedToken, config)
        val channels = credentials.map { (channelId, password) ->
            ChannelSyncItem(channelId = channelId, password = password)
        }
        val payload = try {
            service.sync(
                baseUrl = config.address,
                token = config.token,
                deviceKey = deviceKey,
                channels = channels
            )
        } catch (error: ChannelSubscriptionException) {
            if (!isDeviceKeyMissingError(error.message)) {
                throw error
            }
            deviceKey = ensureProviderRoute(normalizedToken, config)
            service.sync(
                baseUrl = config.address,
                token = config.token,
                deviceKey = deviceKey,
                channels = channels
            )
        }
        val now = System.currentTimeMillis()
        val staleChannels = mutableListOf<String>()
        val passwordMismatchChannels = mutableListOf<String>()
        payload.channels.forEach { result ->
            if (result.subscribed) {
                val displayName = result.channelName?.ifEmpty { null } ?: result.channelId
                store.updateDisplayName(config.address, result.channelId, displayName)
                store.updateLastSynced(config.address, result.channelId, now)
                return@forEach
            }
            when (result.errorCode?.trim()?.lowercase()) {
                "channel_not_found" -> staleChannels += result.channelId
                "password_mismatch" -> passwordMismatchChannels += result.channelId
            }
        }
        val invalidChannels = (staleChannels + passwordMismatchChannels).distinct()
        invalidChannels.forEach { channelId ->
            store.softDeleteSubscription(config.address, channelId)
        }
        return SyncOutcome(
            staleChannels = staleChannels,
            passwordMismatchChannels = passwordMismatchChannels,
        )
    }

    private suspend fun ensureProviderRoute(deviceToken: String, config: ServerConfig): String {
        val normalizedToken = deviceToken.trim()
        if (normalizedToken.isEmpty()) {
            throw ChannelSubscriptionException("Missing system token for provider route")
        }
        val previousToken = settingsRepository.getFcmToken()?.trim()?.ifEmpty { null }
        if (previousToken != normalizedToken) {
            settingsRepository.setFcmToken(normalizedToken)
        }
        val existingDeviceKey = settingsRepository.getProviderDeviceKey(platform = "android")
        if (!previousToken.isNullOrBlank() && previousToken != normalizedToken) {
            runCatching {
                retireOldProviderToken(
                    config = config,
                    deviceKey = existingDeviceKey?.trim().orEmpty(),
                    oldProviderToken = previousToken,
                )
            }.onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(TAG, "retire old FCM token failed: ${error.message}", error)
            }
        }
        val upserted = service.upsertDeviceChannel(
            baseUrl = config.address,
            token = config.token,
            deviceKey = existingDeviceKey,
            platform = "android",
            channelType = FCM_CHANNEL_TYPE,
            providerToken = normalizedToken,
        )
        val resolvedDeviceKey = upserted.deviceKey.trim()
        settingsRepository.setProviderDeviceKey(platform = "android", deviceKey = resolvedDeviceKey)
        return resolvedDeviceKey
    }

    private suspend fun retireOldProviderToken(
        config: ServerConfig,
        deviceKey: String,
        oldProviderToken: String,
    ) {
        val normalizedOldToken = oldProviderToken.trim()
        if (normalizedOldToken.isEmpty()) return
        val requestedDeviceKey = deviceKey.trim().ifEmpty { null }
        val upserted = service.upsertDeviceChannel(
            baseUrl = config.address,
            token = config.token,
            deviceKey = requestedDeviceKey,
            platform = "android",
            channelType = FCM_CHANNEL_TYPE,
            providerToken = normalizedOldToken,
        )
        val resolvedDeviceKey = upserted.deviceKey.trim()
        settingsRepository.setProviderDeviceKey(platform = "android", deviceKey = resolvedDeviceKey)
        service.deleteDeviceChannel(
            baseUrl = config.address,
            token = config.token,
            deviceKey = resolvedDeviceKey,
            channelType = FCM_CHANNEL_TYPE,
        )
    }

    private suspend fun subscribeInternal(
        channelId: String?,
        channelName: String?,
        password: String,
        deviceToken: String?,
    ): ChannelSubscribeResult {
        val token = deviceToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ChannelSubscriptionException("Missing system token for channel subscribe")
        val config = resolveServerConfig()
        suspend fun doSubscribe(activeDeviceKey: String): ChannelSubscribeResult {
            return service.subscribe(
                baseUrl = config.address,
                token = config.token,
                deviceKey = activeDeviceKey,
                channelId = channelId,
                channelName = channelName,
                password = password,
            )
        }

        var deviceKey = ensureProviderRoute(token, config)
        val result = try {
            doSubscribe(deviceKey)
        } catch (error: ChannelSubscriptionException) {
            if (isDeviceKeyMissingError(error.message)) {
                deviceKey = ensureProviderRoute(token, config)
                doSubscribe(deviceKey)
            } else {
                if (!channelId.isNullOrBlank() && shouldSoftDeleteForServerError(error.message)) {
                    store.softDeleteSubscription(config.address, channelId)
                }
                throw error
            }
        }
        if (!result.subscribed) {
            throw ChannelSubscriptionException("Request failed")
        }
        val now = System.currentTimeMillis()
        store.upsertSubscription(
            gatewayUrl = config.address,
            channelId = result.channelId,
            displayName = result.channelName,
            password = password,
            lastSyncedAt = now,
        )
        return result
    }

    private fun isDeviceKeyMissingError(rawMessage: String?): Boolean {
        val message = rawMessage?.trim()?.lowercase().orEmpty()
        if (message.isEmpty()) return false
        return message.contains("device_key_not_found")
            || message.contains("device_key not found")
            || message.contains("device key not found")
    }

    suspend fun upsertLocalPrivateCredential(
        rawChannelId: String,
        password: String,
        displayName: String? = null,
    ): ChannelSubscription {
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val normalizedPassword = ChannelPasswordValidator.normalize(password)
        val config = resolveServerConfig()
        val name = displayName?.trim()?.ifEmpty { null } ?: channelId
        return store.upsertSubscription(
            gatewayUrl = config.address,
            channelId = channelId,
            displayName = name,
            password = normalizedPassword,
            lastSyncedAt = System.currentTimeMillis(),
        )
    }

    suspend fun softDeleteLocalSubscription(rawChannelId: String, deleteLocalMessages: Boolean): Int {
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val config = resolveServerConfig()
        store.softDeleteSubscription(config.address, channelId)
        return if (deleteLocalMessages) {
            messageStateCoordinator.deleteMessagesByChannel(channelId)
        } else {
            0
        }
    }

    suspend fun closeEvent(
        rawEventId: String,
        rawThingId: String?,
        rawChannelId: String,
        rawStatus: String?,
        rawMessage: String?,
        rawSeverity: String?,
    ) {
        val eventId = rawEventId.trim().ifEmpty {
            throw ChannelSubscriptionException("Missing event id")
        }
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val config = resolveServerConfig()
        val password = store.passwordFor(config.address, channelId)
            ?.trim()
            ?.ifEmpty { null }
            ?: throw ChannelSubscriptionException("Missing channel password")
        val normalizedThingId = rawThingId?.trim()?.ifEmpty { null }
        val normalizedStatus = rawStatus
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 24 }
            ?: "closed"
        val normalizedMessage = rawMessage?.trim()?.ifEmpty { null } ?: "closed"
        val normalizedSeverity = rawSeverity
            ?.trim()
            ?.lowercase()
            ?.takeIf { it in setOf("critical", "high", "normal", "low") }
            ?: "normal"

        val payload = JSONObject().apply {
            put("channel_id", channelId)
            put("password", password)
            put("op_id", OpaqueId.generateHex128())
            put("event_id", eventId)
            put("status", normalizedStatus)
            put("message", normalizedMessage)
            put("severity", normalizedSeverity)
            if (!normalizedThingId.isNullOrBlank()) {
                put("thing_id", normalizedThingId)
            }
        }
        val endpointPath = if (normalizedThingId != null) {
            val escapedThingId = URLEncoder.encode(normalizedThingId, "UTF-8")
            "/thing/$escapedThingId/event/close"
        } else {
            "/event/close"
        }
        service.eventToChannel(
            baseUrl = config.address,
            token = config.token,
            payload = payload,
            endpointPath = endpointPath,
        )
    }

    private suspend fun resolveServerConfig(): ServerConfig {
        val rawAddress = settingsRepository.getServerAddress()
            ?.trim()
            ?.ifEmpty { null }
            ?: AppConstants.defaultServerAddress
        val address = UrlValidators.normalizeGatewayBaseUrl(rawAddress) ?: AppConstants.defaultServerAddress
        val token = settingsRepository.getGatewayToken()?.trim()?.ifEmpty { null }
            ?: AppConstants.defaultGatewayToken?.trim()?.ifEmpty { null }
        return ServerConfig(address = address, token = token)
    }

    private data class ServerConfig(
        val address: String,
        val token: String?,
    )

    data class SyncOutcome(
        val staleChannels: List<String> = emptyList(),
        val passwordMismatchChannels: List<String> = emptyList(),
    ) {
        val invalidChannels: List<String>
            get() = (staleChannels + passwordMismatchChannels).distinct()
    }

    private fun shouldSoftDeleteForServerError(rawMessage: String?): Boolean {
        val message = rawMessage?.trim()?.lowercase().orEmpty()
        if (message.isEmpty()) return false
        return message.contains("channel_not_found") || message.contains("password_mismatch")
    }
}
