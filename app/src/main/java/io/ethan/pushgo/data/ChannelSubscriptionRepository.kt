package io.ethan.pushgo.data

import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.notifications.MessageStateCoordinator

class ChannelSubscriptionRepository(
    private val store: ChannelSubscriptionStore,
    private val settingsRepository: SettingsRepository,
    private val messageStateCoordinator: MessageStateCoordinator,
) {
    private val service = ChannelSubscriptionService()

    suspend fun loadSubscriptions(): List<ChannelSubscription> {
        val config = resolveServerConfig()
        return store.loadSubscriptions(config.address)
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
        deviceToken: String,
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
        deviceToken: String,
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

    suspend fun setChannelAutoCleanupEnabled(rawChannelId: String, enabled: Boolean) {
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val config = resolveServerConfig()
        store.updateAutoCleanupEnabled(config.address, channelId, enabled)
    }

    suspend fun unsubscribeChannel(
        rawChannelId: String,
        deviceToken: String,
        deleteLocalMessages: Boolean,
    ): Int {
        val channelId = ChannelIdValidator.normalize(rawChannelId)
        val config = resolveServerConfig()
        ensureTokenUpToDate(deviceToken, config)
        service.unsubscribe(
            baseUrl = config.address,
            token = config.token,
            channelId = channelId,
            deviceToken = deviceToken,
            platform = "android",
        )
        store.softDeleteSubscription(config.address, channelId)
        return if (deleteLocalMessages) {
            messageStateCoordinator.deleteMessagesByChannel(channelId)
        } else {
            0
        }
    }

    suspend fun handleTokenUpdate(deviceToken: String) {
        if (deviceToken.isBlank()) return
        val config = resolveServerConfig()
        ensureTokenUpToDate(deviceToken, config)
        syncSubscriptions(deviceToken, config)
    }

    private suspend fun syncSubscriptions(deviceToken: String, config: ServerConfig) {
        if (deviceToken.isBlank()) return
        val credentials = store.loadActiveCredentials(config.address)
        val now = System.currentTimeMillis()
        credentials.forEach { (channelId, password) ->
            val result = service.subscribe(
                baseUrl = config.address,
                token = config.token,
                channelId = channelId,
                channelName = null,
                password = password,
                deviceToken = deviceToken,
                platform = "android",
            )
            store.updateDisplayName(config.address, channelId, result.channelName)
            store.updateLastSynced(config.address, channelId, now)
        }
    }

    private suspend fun ensureTokenUpToDate(deviceToken: String, config: ServerConfig) {
        val cached = settingsRepository.getFcmToken()
        if (!cached.isNullOrBlank() && cached != deviceToken) {
            runCatching {
                service.retireDevice(
                    baseUrl = config.address,
                    token = config.token,
                    deviceToken = cached,
                    platform = "android",
                )
            }
        }
        if (cached != deviceToken) {
            settingsRepository.setFcmToken(deviceToken)
        }
    }

    private suspend fun subscribeInternal(
        channelId: String?,
        channelName: String?,
        password: String,
        deviceToken: String,
    ): ChannelSubscribeResult {
        val config = resolveServerConfig()
        ensureTokenUpToDate(deviceToken, config)
        val result = service.subscribe(
            baseUrl = config.address,
            token = config.token,
            channelId = channelId,
            channelName = channelName,
            password = password,
            deviceToken = deviceToken,
            platform = "android",
        )
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

    private suspend fun resolveServerConfig(): ServerConfig {
        val address = settingsRepository.getServerAddress()
            ?.trim()
            ?.ifEmpty { null }
            ?: AppConstants.defaultServerAddress
        val token = settingsRepository.getGatewayToken()?.trim()?.ifEmpty { null }
        return ServerConfig(address = address, token = token)
    }

    private data class ServerConfig(
        val address: String,
        val token: String?,
    )
}
