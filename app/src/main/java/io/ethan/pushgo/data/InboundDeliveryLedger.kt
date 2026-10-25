package io.ethan.pushgo.data

import androidx.room.withTransaction
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxDao
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxEntity
import io.ethan.pushgo.data.db.InboundDeliveryLedgerDao
import io.ethan.pushgo.data.db.InboundDeliveryLedgerEntity
import io.ethan.pushgo.data.db.LegacyProviderIngressDao
import io.ethan.pushgo.data.db.LegacyProviderIngressEntity
import io.ethan.pushgo.data.db.PushGoDatabase

internal const val INBOUND_DELIVERY_ACK_STATE_PENDING = "pending"
internal const val INBOUND_DELIVERY_ACK_STATE_ACKED = "acked"
// Keep the existing 36-day local terminal-fact window for retained deliveries.
// Ordinary Gateway body (7 days) and operation identity (30 days) are separate:
// a new stable entity/delivery after server identity GC has a different local key.
// Neither replay nor acceptance certification restarts this existing window.
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
    acceptanceProven: Boolean? = null,
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
            acceptanceProven = acceptanceProven,
        )
    )
    return inserted != -1L
}

/** Allows the existing destructive-Pull receipt to retry business acceptance; never proves it accepted. */
internal fun isLegacyStagingRecovery(
    database: PushGoDatabase,
    scope: InboundDeliveryScope?,
    deliveryId: String?,
    identity: ProviderAckIdentity?,
): Boolean {
    if (identity?.source != "legacy_provider_staging" || scope == null) return false
    val id = deliveryId?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return database.openHelper.readableDatabase.query(
        "SELECT 1 FROM legacy_provider_ingress WHERE gateway_url=? AND device_key=? AND delivery_id=? LIMIT 1",
        arrayOf(scope.gatewayUrl, scope.deviceKey, id),
    ).use { it.moveToFirst() }
}

/** Exact processed facts survive content deletion; legacy claims alone prove no acceptance. */
internal suspend fun hasProvenInboundAcceptance(
    database: PushGoDatabase,
    scope: InboundDeliveryScope?,
    deliveryId: String?,
    channelId: String?,
    entityType: String?,
    entityId: String?,
    opId: String?,
): Boolean {
    val id = deliveryId?.trim()?.takeIf(String::isNotEmpty) ?: return false
    val fact = database.inboundDeliveryLedgerDao().get(scope?.gatewayUrl.orEmpty(), scope?.deviceKey.orEmpty(), id)
        ?: return false
    if (fact.channelId != channelId?.trim()?.takeIf(String::isNotEmpty) ||
        fact.entityType != canonicalEntityTypeOrEmpty(entityType) ||
        fact.entityId != entityId?.trim()?.takeIf(String::isNotEmpty) ||
        fact.opId != opId?.trim()?.takeIf(String::isNotEmpty)) {
        throw InboundIdentityConflictException(InboundIdentityConflictReason.DIFFERENT_CONTENT)
    }
    return fact.acceptanceProven == true
}

/** Only an earlier, different delivery can prove an already accepted operation. */
internal suspend fun hasProvenOperationAcceptance(
    database: PushGoDatabase,
    scope: InboundDeliveryScope?,
    currentDeliveryId: String?,
    channelId: String?,
    entityType: String?,
    entityId: String?,
    opId: String?,
): Boolean {
    val operation = opId?.trim()?.takeIf(String::isNotEmpty) ?: return false
    val key = scope.scopedDeliveryStorageKey(composeOperationScopeKey(channelId?.trim()?.takeIf(String::isNotEmpty),
        canonicalEntityTypeOrEmpty(entityType), entityId?.trim()?.takeIf(String::isNotEmpty), operation))
    val previous = database.openHelper.readableDatabase.query(
        "SELECT delivery_id FROM operation_ledger WHERE scope_key=?", arrayOf(key),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: return false
    // A newly inserted current claim is never evidence for the prior operation.
    if (previous == currentDeliveryId?.trim()) return false
    return hasProvenInboundAcceptance(database, scope, previous, channelId, entityType, entityId, operation)
}

internal suspend fun proveInboundAcceptance(
    database: PushGoDatabase,
    scope: InboundDeliveryScope?,
    deliveryId: String?,
) {
    val id = deliveryId?.trim()?.takeIf(String::isNotEmpty) ?: return
    check(database.inTransaction())
    check(database.inboundDeliveryLedgerDao().markAcceptanceProven(scope?.gatewayUrl.orEmpty(), scope?.deviceKey.orEmpty(), id) == 1) {
        "Accepted delivery fact disappeared before commit"
    }
}

class InboundDeliveryLedgerRepository(
    private val database: PushGoDatabase,
    private val inboundDeliveryLedgerDao: InboundDeliveryLedgerDao,
    private val inboundDeliveryAckOutboxDao: InboundDeliveryAckOutboxDao,
    private val legacyProviderIngressDao: LegacyProviderIngressDao,
) {
    // Upgrade-only recovery: no new network Pull writes this table. Keep the
    // consumer until previously destructive legacy receipts have settled locally.
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
        return inboundDeliveryLedgerDao.getProvenAckState(
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
            // The eligible empty -> nonempty edge is the durable drain signal.
            // Isolated old claims remain stored but cannot hide a new sendable ACK.
            // Producers that
            // arrive while a worker is active do not replace/cancel it; a producer that
            // races after the worker's final empty read observes empty and kicks a successor.
            val shouldKickDrain = !inboundDeliveryAckOutboxDao.hasEligiblePending()
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
            shouldKickDrain && inboundDeliveryAckOutboxDao.hasEligiblePending()
        }
    }

    suspend fun hasEligiblePendingAcks(): Boolean = inboundDeliveryAckOutboxDao.hasEligiblePending()

    /** All retained rows, including isolated legacy claims, for diagnostics/recovery accounting. */
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
        val destinations = inboundDeliveryAckOutboxDao.loadEligiblePendingDestinations(boundedLimit)
        if (destinations.isEmpty()) return emptyList()

        val selected = ArrayList<InboundDeliveryAckOutboxEntity>(boundedLimit)
        val selectedKeys = HashSet<Triple<String, String, String>>(boundedLimit)
        destinations.forEach { destination ->
            inboundDeliveryAckOutboxDao.loadEligiblePendingForDestination(
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
            inboundDeliveryAckOutboxDao.loadEligiblePending(boundedLimit).forEach { record ->
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
        return inboundDeliveryAckOutboxDao.loadEligiblePendingForDestination(
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
