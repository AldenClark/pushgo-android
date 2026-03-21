package io.ethan.pushgo.data

import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.data.db.ChannelSubscriptionDao
import io.ethan.pushgo.data.db.ChannelSubscriptionEntity

class ChannelSubscriptionStore(
    private val dao: ChannelSubscriptionDao,
    private val secretStore: SecureSecretStore,
) {
    suspend fun loadSubscriptions(gatewayUrl: String, includeDeleted: Boolean = false): List<ChannelSubscription> {
        val entities = if (includeDeleted) {
            dao.getAll(gatewayUrl)
        } else {
            dao.getActive(gatewayUrl)
        }
        return entities.map { it.asModel() }
    }

    suspend fun upsertSubscription(
        gatewayUrl: String,
        channelId: String,
        displayName: String,
        password: String,
        lastSyncedAt: Long? = null,
    ): ChannelSubscription {
        val now = System.currentTimeMillis()
        val record = ChannelSubscriptionEntity(
            gatewayUrl = gatewayUrl,
            channelId = channelId,
            displayName = displayName,
            updatedAt = now,
            lastSyncedAt = lastSyncedAt,
            isDeleted = false,
            deletedAt = null,
        )
        val normalizedPassword = password.trim().ifEmpty { null }
        secretStore.setChannelPassword(gatewayUrl, channelId, normalizedPassword)
        val existing = dao.getById(gatewayUrl, channelId)
        if (existing == null) {
            dao.insert(record)
        } else {
            dao.update(record.copy(updatedAt = now))
        }
        return record.asModel()
    }

    suspend fun updateLastSynced(gatewayUrl: String, channelId: String, timestamp: Long) {
        dao.updateLastSynced(gatewayUrl, channelId, timestamp)
    }

    suspend fun updateDisplayName(gatewayUrl: String, channelId: String, displayName: String) {
        dao.updateDisplayName(gatewayUrl, channelId, displayName, System.currentTimeMillis())
    }

    suspend fun softDeleteSubscription(gatewayUrl: String, channelId: String) {
        dao.softDelete(gatewayUrl, channelId, System.currentTimeMillis())
        secretStore.removeChannelPassword(gatewayUrl, channelId)
    }

    suspend fun passwordFor(gatewayUrl: String, channelId: String): String? {
        val entry = dao.getById(gatewayUrl, channelId) ?: return null
        if (entry.isDeleted) return null
        return secretStore.channelPassword(gatewayUrl, channelId)
            ?.trim()
            ?.ifEmpty { null }
    }

    suspend fun loadActiveCredentials(gatewayUrl: String): List<Pair<String, String>> {
        val entries = dao.getActive(gatewayUrl)
        val credentials = mutableListOf<Pair<String, String>>()
        for (entry in entries) {
            val secret = secretStore.channelPassword(gatewayUrl, entry.channelId)
                ?.trim()
                ?.ifEmpty { null }
            if (secret != null) {
                credentials += entry.channelId to secret
            }
        }
        return credentials
    }

    suspend fun countActive(gatewayUrl: String): Int {
        return dao.countActive(gatewayUrl)
    }

    suspend fun clearAll() {
        dao.deleteAll()
    }
}
