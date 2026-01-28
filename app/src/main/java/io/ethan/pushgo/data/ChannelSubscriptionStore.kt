package io.ethan.pushgo.data

import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.data.db.ChannelSubscriptionDao
import io.ethan.pushgo.data.db.ChannelSubscriptionEntity

class ChannelSubscriptionStore(
    private val dao: ChannelSubscriptionDao,
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
        val existing = dao.getById(gatewayUrl, channelId)
        val resolvedAutoCleanupEnabled = existing?.autoCleanupEnabled ?: true
        val record = ChannelSubscriptionEntity(
            gatewayUrl = gatewayUrl,
            channelId = channelId,
            displayName = displayName,
            updatedAt = now,
            lastSyncedAt = lastSyncedAt,
            autoCleanupEnabled = resolvedAutoCleanupEnabled,
            password = password,
            isDeleted = false,
            deletedAt = null,
        )
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

    suspend fun updateAutoCleanupEnabled(gatewayUrl: String, channelId: String, enabled: Boolean) {
        dao.updateAutoCleanupEnabled(gatewayUrl, channelId, enabled, System.currentTimeMillis())
    }

    suspend fun softDeleteSubscription(gatewayUrl: String, channelId: String) {
        dao.softDelete(gatewayUrl, channelId, System.currentTimeMillis())
    }

    suspend fun passwordFor(gatewayUrl: String, channelId: String): String? {
        val entry = dao.getById(gatewayUrl, channelId) ?: return null
        if (entry.isDeleted) return null
        return entry.password
    }

    suspend fun loadActiveCredentials(gatewayUrl: String): List<Pair<String, String>> {
        return dao.getActive(gatewayUrl).mapNotNull { entry ->
            val password = entry.password?.trim().orEmpty()
            if (password.isEmpty()) {
                null
            } else {
                entry.channelId to password
            }
        }
    }

}
