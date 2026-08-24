package io.ethan.pushgo.data

import io.ethan.pushgo.data.db.OperationLedgerDao
import io.ethan.pushgo.data.db.OperationLedgerEntity

internal suspend fun claimOperationScope(
    operationLedgerDao: OperationLedgerDao,
    channelId: String?,
    entityType: String?,
    entityId: String?,
    opId: String?,
    deliveryId: String?,
    appliedAt: Long,
    providerAckIdentity: ProviderAckIdentity? = null,
    deliveryScope: InboundDeliveryScope? = providerAckIdentity.inboundDeliveryScope(),
): Boolean {
    val normalizedOpId = opId?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    val normalizedType = canonicalEntityTypeOrEmpty(entityType)
    val normalizedEntityId = entityId?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedChannel = channelId?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedDeliveryId = deliveryId?.trim()?.takeIf { it.isNotEmpty() }
    val scopeKey = deliveryScope.scopedDeliveryStorageKey(composeOperationScopeKey(
        channelId = normalizedChannel,
        entityType = normalizedType,
        entityId = normalizedEntityId,
        opId = normalizedOpId,
    ))
    val inserted = operationLedgerDao.insertOrIgnore(
        OperationLedgerEntity(
            scopeKey = scopeKey,
            opId = normalizedOpId,
            channelId = normalizedChannel,
            entityType = normalizedType,
            entityId = normalizedEntityId,
            deliveryId = normalizedDeliveryId,
            appliedAt = appliedAt,
        )
    )
    return inserted != -1L
}
