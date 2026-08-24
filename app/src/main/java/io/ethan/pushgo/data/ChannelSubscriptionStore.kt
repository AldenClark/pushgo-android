package io.ethan.pushgo.data

import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.data.db.ChannelSubscriptionDao
import io.ethan.pushgo.data.db.ChannelSubscriptionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChannelSubscriptionStore(
    private val dao: ChannelSubscriptionDao,
    private val secretStore: SecureSecretStore,
) {
    private val mutationBarrier = ChannelMutationBarrier()

    suspend fun <T> withChannelMutation(
        gatewayUrl: String,
        channelId: String,
        block: suspend () -> T,
    ): T = mutationBarrier.withLock(gatewayUrl, channelId, block)

    suspend fun pendingDeletionTargetState(
        gatewayUrl: String,
        channelId: String,
        expectedUpdatedAt: Long,
    ): PendingChannelDeletionTargetState {
        val entity = dao.getById(gatewayUrl, channelId)
            ?: return PendingChannelDeletionTargetState.ALREADY_DELETED
        if (entity.isDeleted) return PendingChannelDeletionTargetState.ALREADY_DELETED
        return if (entity.updatedAt == expectedUpdatedAt) {
            PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION
        } else {
            PendingChannelDeletionTargetState.CONFLICTING_VERSION
        }
    }

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
        val existing = dao.getById(gatewayUrl, channelId)
        val now = nextChannelSubscriptionVersion(existing?.updatedAt, System.currentTimeMillis())
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
        if (existing == null) {
            dao.insert(record)
        } else {
            dao.update(record.copy(updatedAt = now))
        }
        secretStore.setChannelPassword(gatewayUrl, channelId, normalizedPassword)
        return record.asModel()
    }

    suspend fun updateLastSynced(gatewayUrl: String, channelId: String, timestamp: Long) {
        dao.updateLastSynced(gatewayUrl, channelId, timestamp)
    }

    suspend fun updateDisplayName(gatewayUrl: String, channelId: String, displayName: String) {
        val previousVersion = dao.getById(gatewayUrl, channelId)?.updatedAt
        dao.updateDisplayName(
            gatewayUrl,
            channelId,
            displayName,
            nextChannelSubscriptionVersion(previousVersion, System.currentTimeMillis()),
        )
    }

    suspend fun softDeleteSubscription(gatewayUrl: String, channelId: String) {
        markDeletedInDatabase(gatewayUrl, channelId, System.currentTimeMillis())
        removePassword(gatewayUrl, channelId)
    }

    suspend fun markDeletedInDatabase(gatewayUrl: String, channelId: String, deletedAt: Long): Int {
        return dao.softDelete(gatewayUrl, channelId, deletedAt)
    }

    suspend fun markDeletedInDatabaseIfUnchanged(
        gatewayUrl: String,
        channelId: String,
        expectedUpdatedAt: Long,
        deletedAt: Long,
    ): Int {
        return dao.softDeleteIfUnchanged(
            gatewayUrl = gatewayUrl,
            channelId = channelId,
            expectedUpdatedAt = expectedUpdatedAt,
            deletedAt = deletedAt,
        )
    }

    fun removePassword(gatewayUrl: String, channelId: String) {
        secretStore.removeChannelPassword(gatewayUrl, channelId)
    }

    /**
     * A replay may run after the same channel has been subscribed again. The Room version is the
     * durable ownership token for the secret, so an old deletion never clears a newer credential.
     */
    suspend fun removePasswordIfDeletedVersionMatches(
        gatewayUrl: String,
        channelId: String,
        expectedUpdatedAt: Long,
    ): Boolean {
        val current = dao.getById(gatewayUrl, channelId)
        val ownsCredential = current.credentialBelongsToDeletedVersion(expectedUpdatedAt)
        if (ownsCredential) {
            secretStore.removeChannelPassword(gatewayUrl, channelId)
        }
        return ownsCredential
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

internal fun ChannelSubscriptionEntity?.credentialBelongsToDeletedVersion(expectedUpdatedAt: Long): Boolean =
    this == null || (isDeleted && updatedAt == expectedUpdatedAt)

internal fun nextChannelSubscriptionVersion(previous: Long?, wallClockEpochMillis: Long): Long {
    val afterPrevious = previous?.let { value ->
        if (value == Long.MAX_VALUE) value else value + 1L
    } ?: Long.MIN_VALUE
    return maxOf(wallClockEpochMillis, afterPrevious)
}

/** Per-(gateway, channel) barrier shared by remote mutation and its local credential transition. */
internal class ChannelMutationBarrier {
    private data class Key(val gatewayUrl: String, val channelId: String)
    private data class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val registryMutex = Mutex()
    private val entries = mutableMapOf<Key, Entry>()

    suspend fun <T> withLock(
        gatewayUrl: String,
        channelId: String,
        block: suspend () -> T,
    ): T {
        val key = Key(
            gatewayUrl = gatewayUrl.trim().removeSuffix("/"),
            channelId = channelId.trim(),
        )
        require(key.gatewayUrl.isNotEmpty()) { "Gateway must not be blank" }
        require(key.channelId.isNotEmpty()) { "Channel must not be blank" }
        val entry = registryMutex.withLock {
            entries.getOrPut(key, ::Entry).also { it.users += 1 }
        }
        return try {
            entry.mutex.withLock { block() }
        } finally {
            registryMutex.withLock {
                entry.users -= 1
                if (entry.users == 0 && entries[key] === entry) {
                    entries.remove(key)
                }
            }
        }
    }
}
