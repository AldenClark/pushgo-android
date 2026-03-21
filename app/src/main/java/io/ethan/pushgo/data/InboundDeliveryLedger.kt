package io.ethan.pushgo.data

import io.ethan.pushgo.data.db.InboundDeliveryLedgerDao
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxDao
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxEntity
import io.ethan.pushgo.data.db.InboundDeliveryLedgerEntity

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
): Boolean {
    val normalizedDeliveryId = deliveryId?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    val normalizedType = canonicalEntityTypeOrEmpty(entityType)
    val normalizedEntityId = entityId?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedChannel = channelId?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedOpId = opId?.trim()?.takeIf { it.isNotEmpty() }
    val inserted = inboundDeliveryLedgerDao.insertOrIgnore(
        InboundDeliveryLedgerEntity(
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
    private val inboundDeliveryLedgerDao: InboundDeliveryLedgerDao,
    private val inboundDeliveryAckOutboxDao: InboundDeliveryAckOutboxDao,
) {
    suspend fun shouldAck(deliveryId: String?): Boolean {
        val normalized = deliveryId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return inboundDeliveryLedgerDao.getAckState(normalized) == INBOUND_DELIVERY_ACK_STATE_PENDING
    }

    suspend fun enqueueAcks(
        deliveryIds: Collection<String>,
        source: String = "private_wakeup_pull",
    ) {
        val normalized = io.ethan.pushgo.notifications.normalizePendingAckDeliveryIds(deliveryIds)
        if (normalized.isEmpty()) return
        val now = System.currentTimeMillis()
        inboundDeliveryAckOutboxDao.upsert(
            normalized.map { deliveryId ->
                InboundDeliveryAckOutboxEntity(
                    deliveryId = deliveryId,
                    source = source,
                    enqueuedAt = now,
                    updatedAt = now,
                )
            }
        )
    }

    suspend fun loadPendingAckIds(limit: Int = 200): List<String> {
        return inboundDeliveryAckOutboxDao.loadPendingDeliveryIds(limit.coerceIn(1, 500))
    }

    suspend fun markAcked(deliveryIds: Collection<String>) {
        val normalized = io.ethan.pushgo.notifications.normalizePendingAckDeliveryIds(deliveryIds)
        if (normalized.isEmpty()) return
        inboundDeliveryLedgerDao.updateAckState(
            deliveryIds = normalized,
            ackState = INBOUND_DELIVERY_ACK_STATE_ACKED,
            ackedAt = System.currentTimeMillis(),
        )
        inboundDeliveryAckOutboxDao.deleteByDeliveryIds(normalized)
    }

    suspend fun clearAll() {
        inboundDeliveryAckOutboxDao.deleteAll()
        inboundDeliveryLedgerDao.deleteAll()
    }
}
