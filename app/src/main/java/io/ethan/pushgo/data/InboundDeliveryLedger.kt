package io.ethan.pushgo.data

import androidx.room.withTransaction
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxDao
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxEntity
import io.ethan.pushgo.data.db.InboundDeliveryLedgerDao
import io.ethan.pushgo.data.db.InboundDeliveryLedgerEntity
import io.ethan.pushgo.data.db.PushGoDatabase

internal const val INBOUND_DELIVERY_ACK_STATE_PENDING = "pending"
internal const val INBOUND_DELIVERY_ACK_STATE_ACKED = "acked"

internal suspend fun claimInboundDelivery(
    inboundDeliveryLedgerDao: InboundDeliveryLedgerDao,
    channelId: String?,
    entityType: String?,
    entityId: String?,
    deliveryId: String?,
    opId: String?,
    appliedAt: Long,
    providerAckIdentity: ProviderAckIdentity? = null,
): Boolean {
    val normalizedDeliveryId = deliveryId?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    val normalizedType = canonicalEntityTypeOrEmpty(entityType)
    val normalizedEntityId = entityId?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedChannel = channelId?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedOpId = opId?.trim()?.takeIf { it.isNotEmpty() }
    val inserted = inboundDeliveryLedgerDao.insertOrIgnore(
        InboundDeliveryLedgerEntity(
            gatewayUrl = providerAckIdentity?.gatewayUrl.orEmpty(),
            deviceKey = providerAckIdentity?.deviceKey.orEmpty(),
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
) {
    suspend fun shouldAck(
        deliveryId: String?,
        identity: ProviderAckIdentity? = null,
    ): Boolean {
        val normalized = deliveryId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return inboundDeliveryLedgerDao.getAckState(
            gatewayUrl = identity?.gatewayUrl.orEmpty(),
            deviceKey = identity?.deviceKey.orEmpty(),
            deliveryId = normalized,
        ) == INBOUND_DELIVERY_ACK_STATE_PENDING
    }

    suspend fun enqueueAcks(
        deliveryIds: Collection<String>,
        identity: ProviderAckIdentity,
    ) {
        val normalized = io.ethan.pushgo.notifications.normalizePendingAckDeliveryIds(deliveryIds)
        if (normalized.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            normalized.forEach { deliveryId ->
                inboundDeliveryAckOutboxDao.upsertPreservingAttempts(
                    gatewayUrl = identity.gatewayUrl,
                    deviceKey = identity.deviceKey,
                    deliveryId = deliveryId,
                    ackContract = identity.contract.persistedValue,
                    source = identity.source,
                    enqueuedAt = now,
                    updatedAt = now,
                )
            }
        }
    }

    suspend fun loadPendingAcks(limit: Int = 201): List<InboundDeliveryAckOutboxEntity> {
        return inboundDeliveryAckOutboxDao.loadPending(limit.coerceIn(1, 1_001))
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

    suspend fun deferFailedAcks(records: Collection<InboundDeliveryAckOutboxEntity>) {
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
            )
        }
    }

    suspend fun markAcked(
        deliveryIds: Collection<String>,
        identity: ProviderAckIdentity? = null,
    ) {
        val normalized = io.ethan.pushgo.notifications.normalizePendingAckDeliveryIds(deliveryIds)
        if (normalized.isEmpty()) return
        val ackedAt = System.currentTimeMillis()
        database.withTransaction {
            normalized.forEach { deliveryId ->
                inboundDeliveryLedgerDao.updateAckState(
                    gatewayUrl = identity?.gatewayUrl.orEmpty(),
                    deviceKey = identity?.deviceKey.orEmpty(),
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

    suspend fun clearAll() {
        inboundDeliveryAckOutboxDao.deleteAll()
        inboundDeliveryLedgerDao.deleteAll()
    }
}
