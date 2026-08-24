package io.ethan.pushgo.data

import androidx.room.withTransaction
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxDao
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxEntity
import io.ethan.pushgo.data.db.InboundDeliveryLedgerDao
import io.ethan.pushgo.data.db.InboundDeliveryLedgerEntity
import io.ethan.pushgo.data.db.LegacyProviderIngressDao
import io.ethan.pushgo.data.db.LegacyProviderIngressEntity
import io.ethan.pushgo.data.db.PushGoDatabase
import org.json.JSONObject

internal const val INBOUND_DELIVERY_ACK_STATE_PENDING = "pending"
internal const val INBOUND_DELIVERY_ACK_STATE_ACKED = "acked"
// Gateway may retain/replay a frozen delivery for as long as 35 days. Keep the
// client terminal decision one day longer so an ACKed delivery cannot be
// resurrected after a long offline interval or an ambiguous wire ACK.
internal const val INBOUND_DELIVERY_TOMBSTONE_RETENTION_DAYS = 36L
internal const val INBOUND_DELIVERY_PRUNE_BATCH_SIZE = 200

internal suspend fun claimInboundDelivery(
    inboundDeliveryLedgerDao: InboundDeliveryLedgerDao,
    channelId: String?,
    entityType: String?,
    entityId: String?,
    deliveryId: String?,
    opId: String?,
    appliedAt: Long,
    providerAckIdentity: ProviderAckIdentity? = null,
    deliveryScope: InboundDeliveryScope? = providerAckIdentity.inboundDeliveryScope(),
): Boolean {
    val normalizedDeliveryId = deliveryId?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    val normalizedType = canonicalEntityTypeOrEmpty(entityType)
    val normalizedEntityId = entityId?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedChannel = channelId?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedOpId = opId?.trim()?.takeIf { it.isNotEmpty() }
    val inserted = inboundDeliveryLedgerDao.insertOrIgnore(
        InboundDeliveryLedgerEntity(
            gatewayUrl = deliveryScope?.gatewayUrl.orEmpty(),
            deviceKey = deliveryScope?.deviceKey.orEmpty(),
            deliveryId = normalizedDeliveryId,
            channelId = normalizedChannel,
            entityType = normalizedType,
            entityId = normalizedEntityId,
            opId = normalizedOpId,
            appliedAt = appliedAt,
            ackState = INBOUND_DELIVERY_ACK_STATE_PENDING,
            ackedAt = null,
        )
    )
    return inserted != -1L
}

class InboundDeliveryLedgerRepository(
    private val database: PushGoDatabase,
    private val inboundDeliveryLedgerDao: InboundDeliveryLedgerDao,
    private val inboundDeliveryAckOutboxDao: InboundDeliveryAckOutboxDao,
    private val legacyProviderIngressDao: LegacyProviderIngressDao,
) {
    suspend fun stageLegacyPull(
        destination: ProviderAckDestination,
        items: Collection<PullItem>,
    ) {
        if (items.isEmpty()) return
        val identity = ProviderAckIdentity.create(
            destination = destination,
            contract = ProviderAckContract.LEGACY_SINGLE,
            source = "legacy_provider_staging",
        ) ?: error("legacy provider pull has invalid destination")
        val now = System.currentTimeMillis()
        database.withTransaction {
            legacyProviderIngressDao.insertAll(
                items.mapIndexed { index, item ->
                    LegacyProviderIngressEntity(
                        gatewayUrl = identity.gatewayUrl,
                        deviceKey = identity.deviceKey,
                        deliveryId = item.deliveryId,
                        payloadJson = JSONObject(item.authoritativePayloadForStorage()).toString(),
                        enqueuedAt = now + index,
                    )
                }
            )
        }
    }

    suspend fun loadPendingLegacyPull(limit: Int = 200): List<LegacyProviderIngressEntity> =
        legacyProviderIngressDao.loadPending(limit.coerceIn(1, 1_000))

    suspend fun deleteLegacyPull(record: LegacyProviderIngressEntity) {
        legacyProviderIngressDao.delete(record.gatewayUrl, record.deviceKey, record.deliveryId)
    }

    suspend fun completeLegacyPull(record: LegacyProviderIngressEntity) {
        database.withTransaction {
            inboundDeliveryLedgerDao.updateAckState(
                gatewayUrl = record.gatewayUrl,
                deviceKey = record.deviceKey,
                deliveryId = record.deliveryId,
                ackState = INBOUND_DELIVERY_ACK_STATE_ACKED,
                ackedAt = System.currentTimeMillis(),
            )
            legacyProviderIngressDao.delete(
                gatewayUrl = record.gatewayUrl,
                deviceKey = record.deviceKey,
                deliveryId = record.deliveryId,
            )
        }
    }

    suspend fun shouldAck(
        deliveryId: String?,
        identity: ProviderAckIdentity? = null,
    ): Boolean {
        return shouldAckDelivery(deliveryId, identity.inboundDeliveryScope())
    }

    suspend fun shouldAckDelivery(
        deliveryId: String?,
        scope: InboundDeliveryScope?,
    ): Boolean {
        return deliveryAckState(deliveryId, scope) ==
            INBOUND_DELIVERY_ACK_STATE_PENDING
    }

    suspend fun deliveryAckState(
        deliveryId: String?,
        scope: InboundDeliveryScope?,
    ): String? {
        val normalized = deliveryId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return inboundDeliveryLedgerDao.getAckState(
            gatewayUrl = scope?.gatewayUrl.orEmpty(),
            deviceKey = scope?.deviceKey.orEmpty(),
            deliveryId = normalized,
        )
    }

    suspend fun enqueueAcks(
        deliveryIds: Collection<String>,
        identity: ProviderAckIdentity,
    ): Boolean {
        val normalized = io.ethan.pushgo.notifications.normalizePendingAckDeliveryIds(deliveryIds)
        if (normalized.isEmpty()) return false
        val now = System.currentTimeMillis()
        return database.withTransaction {
            // The empty -> nonempty edge is the durable drain signal. Producers that
            // arrive while a worker is active do not replace/cancel it; a producer that
            // races after the worker's final empty read observes empty and kicks a successor.
            val shouldKickDrain = !inboundDeliveryAckOutboxDao.hasPending()
            normalized.forEach { deliveryId ->
                val record = InboundDeliveryAckOutboxEntity(
                    gatewayUrl = identity.gatewayUrl,
                    deviceKey = identity.deviceKey,
                    deliveryId = deliveryId,
                    ackContract = identity.contract.persistedValue,
                    source = identity.source,
                    enqueuedAt = now,
                    updatedAt = now,
                )
                if (inboundDeliveryAckOutboxDao.insertOrIgnore(record) == -1L) {
                    check(
                        inboundDeliveryAckOutboxDao.refreshPreservingAttempts(
                            gatewayUrl = record.gatewayUrl,
                            deviceKey = record.deviceKey,
                            deliveryId = record.deliveryId,
                            ackContract = record.ackContract,
                            source = record.source,
                            updatedAt = record.updatedAt,
                        ) == 1
                    ) { "provider ACK record disappeared during refresh" }
                }
            }
            shouldKickDrain
        }
    }

    suspend fun loadPendingAcks(limit: Int = 201): List<InboundDeliveryAckOutboxEntity> {
        return inboundDeliveryAckOutboxDao.loadPending(limit.coerceIn(1, 1_001))
    }

    /**
     * Loads a bounded ACK batch while reserving one head record for every visible
     * destination. The remaining capacity is filled in global age order. This keeps
     * a large or repeatedly failing Gateway backlog from hiding a newer destination,
     * without sacrificing throughput when only one destination is active.
     */
    suspend fun loadFairPendingAcks(limit: Int = 201): List<InboundDeliveryAckOutboxEntity> {
        val boundedLimit = limit.coerceIn(1, 1_001)
        val destinations = inboundDeliveryAckOutboxDao.loadPendingDestinations(boundedLimit)
        if (destinations.isEmpty()) return emptyList()

        val selected = ArrayList<InboundDeliveryAckOutboxEntity>(boundedLimit)
        val selectedKeys = HashSet<Triple<String, String, String>>(boundedLimit)
        destinations.forEach { destination ->
            inboundDeliveryAckOutboxDao.loadPendingForDestination(
                gatewayUrl = destination.gatewayUrl,
                deviceKey = destination.deviceKey,
                ackContract = destination.ackContract,
                limit = 1,
            ).firstOrNull()?.let { record ->
                selected += record
                selectedKeys += record.fairSelectionKey()
            }
        }
        if (selected.size < boundedLimit) {
            inboundDeliveryAckOutboxDao.loadPending(boundedLimit).forEach { record ->
                if (selected.size < boundedLimit && selectedKeys.add(record.fairSelectionKey())) {
                    selected += record
                }
            }
        }
        return selected.sortedWith(
            compareBy<InboundDeliveryAckOutboxEntity> { it.updatedAt }
                .thenBy { it.enqueuedAt }
                .thenBy { it.gatewayUrl }
                .thenBy { it.deviceKey }
                .thenBy { it.deliveryId }
        )
    }

    suspend fun loadPendingAcks(
        destination: ProviderAckDestination,
        contract: ProviderAckContract,
        limit: Int = 201,
    ): List<InboundDeliveryAckOutboxEntity> {
        return inboundDeliveryAckOutboxDao.loadPendingForDestination(
            gatewayUrl = ProviderAckIdentity.create(
                destination = destination,
                contract = contract,
                source = "provider_ack_lookup",
            )?.gatewayUrl ?: return emptyList(),
            deviceKey = destination.deviceKey.trim(),
            ackContract = contract.persistedValue,
            limit = limit.coerceIn(1, 1_001),
        )
    }

    suspend fun loadPendingAckIds(limit: Int = 200): List<String> {
        return loadPendingAcks(limit).map { it.deliveryId }
    }

    suspend fun beginAckAttempt(
        records: Collection<InboundDeliveryAckOutboxEntity>,
    ): List<InboundDeliveryAckOutboxEntity> {
        if (records.isEmpty()) return emptyList()
        val firstStartedAt = maxOf(
            System.currentTimeMillis(),
            records.maxOf { it.updatedAt } + 1,
        )
        return database.withTransaction {
            records.mapIndexed { index, record ->
                val attemptStartedAt = firstStartedAt + index
                check(
                    inboundDeliveryAckOutboxDao.beginAttemptIfUnchanged(
                        gatewayUrl = record.gatewayUrl,
                        deviceKey = record.deviceKey,
                        deliveryId = record.deliveryId,
                        expectedUpdatedAt = record.updatedAt,
                        attemptStartedAt = attemptStartedAt,
                    ) == 1
                ) { "provider ACK record changed before attempt" }
                record.copy(
                    updatedAt = attemptStartedAt,
                    attemptCount = record.attemptCount + 1,
                    // The database is set to true before the wire call so process death
                    // leaves durable ambiguity evidence. This in-memory claim deliberately
                    // keeps the pre-attempt value: a fresh, explicit legacy false must not
                    // be mistaken for evidence that this same attempt was interrupted.
                    lastAttemptUncertain = record.lastAttemptUncertain,
                )
            }
        }
    }

    suspend fun markAckRecordsAcked(records: Collection<InboundDeliveryAckOutboxEntity>) {
        if (records.isEmpty()) return
        database.withTransaction {
            val ackedAt = System.currentTimeMillis()
            records.forEach { record ->
                inboundDeliveryLedgerDao.updateAckState(
                    gatewayUrl = record.gatewayUrl,
                    deviceKey = record.deviceKey,
                    deliveryId = record.deliveryId,
                    ackState = INBOUND_DELIVERY_ACK_STATE_ACKED,
                    ackedAt = ackedAt,
                )
                inboundDeliveryAckOutboxDao.deleteIfUnchanged(
                    gatewayUrl = record.gatewayUrl,
                    deviceKey = record.deviceKey,
                    deliveryId = record.deliveryId,
                    updatedAt = record.updatedAt,
                )
            }
        }
    }

    suspend fun deferFailedAcks(
        records: Collection<InboundDeliveryAckOutboxEntity>,
        lastAttemptUncertain: Boolean,
    ) {
        if (records.isEmpty()) return
        var nextUpdatedAt = maxOf(
            System.currentTimeMillis(),
            records.maxOf { it.updatedAt } + 1,
        )
        records.forEach { record ->
            inboundDeliveryAckOutboxDao.deferIfUnchanged(
                gatewayUrl = record.gatewayUrl,
                deviceKey = record.deviceKey,
                deliveryId = record.deliveryId,
                expectedUpdatedAt = record.updatedAt,
                nextUpdatedAt = nextUpdatedAt++,
                lastAttemptUncertain = lastAttemptUncertain,
            )
        }
    }

    suspend fun markAcked(
        deliveryIds: Collection<String>,
        identity: ProviderAckIdentity? = null,
    ) = markAcked(deliveryIds, identity.inboundDeliveryScope())

    suspend fun markAcked(
        deliveryIds: Collection<String>,
        scope: InboundDeliveryScope?,
    ) {
        val normalized = io.ethan.pushgo.notifications.normalizePendingAckDeliveryIds(deliveryIds)
        if (normalized.isEmpty()) return
        val ackedAt = System.currentTimeMillis()
        database.withTransaction {
            normalized.forEach { deliveryId ->
                inboundDeliveryLedgerDao.updateAckState(
                    gatewayUrl = scope?.gatewayUrl.orEmpty(),
                    deviceKey = scope?.deviceKey.orEmpty(),
                    deliveryId = deliveryId,
                    ackState = INBOUND_DELIVERY_ACK_STATE_ACKED,
                    ackedAt = ackedAt,
                )
            }
        }
        // A private-stream ACK has no HTTP ACK destination snapshot here. Keep any
        // destination-scoped HTTP marker; its later idempotent ACK is safer than
        // deleting a same-ID marker that belongs to another Gateway.
    }

    suspend fun pruneAckedTombstones(
        nowEpochMs: Long = System.currentTimeMillis(),
        limit: Int = INBOUND_DELIVERY_PRUNE_BATCH_SIZE,
    ): Int {
        val retentionMillis = java.util.concurrent.TimeUnit.DAYS.toMillis(
            INBOUND_DELIVERY_TOMBSTONE_RETENTION_DAYS
        )
        return inboundDeliveryLedgerDao.pruneTerminalTombstones(
            ackedState = INBOUND_DELIVERY_ACK_STATE_ACKED,
            ackedBeforeOrAt = nowEpochMs - retentionMillis,
            limit = limit.coerceIn(1, INBOUND_DELIVERY_PRUNE_BATCH_SIZE),
        )
    }

    suspend fun clearAll() {
        legacyProviderIngressDao.deleteAll()
        inboundDeliveryAckOutboxDao.deleteAll()
        inboundDeliveryLedgerDao.deleteAll()
    }
}

private fun InboundDeliveryAckOutboxEntity.fairSelectionKey(): Triple<String, String, String> =
    Triple(gatewayUrl, deviceKey, deliveryId)

private fun PullItem.authoritativePayloadForStorage(): Map<String, String> =
    payload.toMutableMap().apply { this["delivery_id"] = deliveryId }
