package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.ProviderAckContract
import io.ethan.pushgo.data.ProviderAckAttemptResult
import io.ethan.pushgo.data.ProviderAckDestination
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxEntity

internal data class ProviderAckDrainResult(
    val attempted: List<InboundDeliveryAckOutboxEntity>,
    val acked: List<InboundDeliveryAckOutboxEntity>,
    val failed: List<InboundDeliveryAckOutboxEntity>,
) {
    val attemptedIds: List<String>
        get() = attempted.map { it.deliveryId }
    val ackedIds: List<String>
        get() = acked.map { it.deliveryId }
    val failedIds: List<String>
        get() = failed.map { it.deliveryId }
    val hasFailures: Boolean
        get() = failed.isNotEmpty()
}

internal object ProviderAckDrainCoordinator {
    suspend fun drainPendingAcks(
        loadPendingAcks: suspend (limit: Int) -> List<InboundDeliveryAckOutboxEntity>,
        ackMessages: suspend (
            destination: ProviderAckDestination,
            contract: ProviderAckContract,
            deliveryIds: List<String>,
        ) -> ProviderAckAttemptResult,
        markAcked: suspend (records: Collection<InboundDeliveryAckOutboxEntity>) -> Unit,
        limit: Int = MAX_ACK_BATCH_SIZE,
    ): ProviderAckDrainResult {
        val requestedLimit = limit.coerceIn(1, MAX_ACK_BATCH_SIZE)
        val pending = loadPendingAcks(requestedLimit + 1)
        if (pending.isEmpty()) {
            return ProviderAckDrainResult(
                attempted = emptyList(),
                acked = emptyList(),
                failed = emptyList(),
            )
        }
        val attempted = mutableListOf<InboundDeliveryAckOutboxEntity>()
        val acked = mutableListOf<InboundDeliveryAckOutboxEntity>()
        val failed = mutableListOf<InboundDeliveryAckOutboxEntity>()
        pending.groupBy { Triple(it.gatewayUrl, it.deviceKey, it.ackContract) }
            .values
            .forEach { records ->
                val batch = records.take(requestedLimit)
                attempted += batch
                val first = batch.first()
                val contract = ProviderAckContract.fromPersistedValue(first.ackContract)
                val deliveryIds = normalizePendingAckDeliveryIds(batch.map { it.deliveryId })
                if (contract == null || deliveryIds.isEmpty()) {
                    failed += batch
                    return@forEach
                }
                val destination = ProviderAckDestination(
                    baseUrl = first.gatewayUrl,
                    deviceKey = first.deviceKey,
                )
                when (contract) {
                    ProviderAckContract.V2_BATCH -> {
                        val result = runCatching {
                            ackMessages(destination, contract, deliveryIds)
                        }
                        if (result.isSuccess) {
                            markAcked(batch)
                            acked += batch
                        } else {
                            failed += batch
                        }
                    }
                    ProviderAckContract.LEGACY_SINGLE -> batch.forEach { record ->
                        val result = runCatching {
                            ackMessages(destination, contract, listOf(record.deliveryId))
                        }.getOrNull()
                        val removed = result?.removedCount == 1
                        val exactRetryAlreadyRemoved = result?.removedCount == 0 && record.attemptCount > 0
                        if (removed || exactRetryAlreadyRemoved) {
                            markAcked(listOf(record))
                            acked += record
                        } else {
                            failed += record
                        }
                    }
                }
            }

        return ProviderAckDrainResult(
            attempted = attempted,
            acked = acked,
            failed = failed,
        )
    }

    private const val MAX_ACK_BATCH_SIZE = 200
}
