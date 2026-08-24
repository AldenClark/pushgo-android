package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.ProviderAckContract
import io.ethan.pushgo.data.ProviderAckAttemptResult
import io.ethan.pushgo.data.ProviderAckDestination
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProviderAckDrainResult(
    val attempted: List<InboundDeliveryAckOutboxEntity>,
    val acked: List<InboundDeliveryAckOutboxEntity>,
    val failed: List<InboundDeliveryAckOutboxEntity>,
    val uncertainFailures: List<InboundDeliveryAckOutboxEntity>,
    val definitiveFailures: List<InboundDeliveryAckOutboxEntity>,
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
        beginAttempt: suspend (
            records: Collection<InboundDeliveryAckOutboxEntity>,
        ) -> List<InboundDeliveryAckOutboxEntity> = { records ->
            records.map { it.copy(attemptCount = it.attemptCount + 1) }
        },
        ackMessages: suspend (
            destination: ProviderAckDestination,
            contract: ProviderAckContract,
            deliveryIds: List<String>,
        ) -> ProviderAckAttemptResult,
        markAcked: suspend (records: Collection<InboundDeliveryAckOutboxEntity>) -> Unit,
        limit: Int = MAX_ACK_BATCH_SIZE,
    ): ProviderAckDrainResult = drainMutex.withLock {
        val requestedLimit = limit.coerceIn(1, MAX_ACK_BATCH_SIZE)
        val pending = loadPendingAcks(requestedLimit + 1)
        if (pending.isEmpty()) {
            return ProviderAckDrainResult(
                attempted = emptyList(),
                acked = emptyList(),
                failed = emptyList(),
                uncertainFailures = emptyList(),
                definitiveFailures = emptyList(),
            )
        }
        val attempted = mutableListOf<InboundDeliveryAckOutboxEntity>()
        val acked = mutableListOf<InboundDeliveryAckOutboxEntity>()
        val failed = mutableListOf<InboundDeliveryAckOutboxEntity>()
        val uncertainFailures = mutableListOf<InboundDeliveryAckOutboxEntity>()
        val definitiveFailures = mutableListOf<InboundDeliveryAckOutboxEntity>()
        pending.groupBy { Triple(it.gatewayUrl, it.deviceKey, it.ackContract) }
            .values
            .forEach destinationLoop@ { records ->
                val batch = records.take(requestedLimit)
                val first = batch.first()
                val contract = ProviderAckContract.fromPersistedValue(first.ackContract)
                val deliveryIds = normalizePendingAckDeliveryIds(batch.map { it.deliveryId })
                if (contract == null || deliveryIds.isEmpty()) {
                    attempted += batch
                    failed += batch
                    definitiveFailures += batch
                    return@destinationLoop
                }
                val claimed = runCatching { beginAttempt(batch) }.getOrElse {
                    attempted += batch
                    failed += batch
                    definitiveFailures += batch
                    return@destinationLoop
                }
                if (claimed.size != batch.size) {
                    attempted += batch
                    failed += batch
                    definitiveFailures += batch
                    return@destinationLoop
                }
                attempted += claimed
                val destination = ProviderAckDestination(
                    baseUrl = claimed.first().gatewayUrl,
                    deviceKey = claimed.first().deviceKey,
                )
                when (contract) {
                    ProviderAckContract.V2_BATCH -> {
                        val attempt = runCatching {
                            ackMessages(destination, contract, deliveryIds)
                        }
                        val result = attempt.getOrNull()
                        if (attempt.isFailure) {
                            failed += claimed
                            uncertainFailures += claimed
                            return@destinationLoop
                        }
                        // v2 is an exact delete-all operation for this immutable
                        // Gateway/device/ID set. A structurally valid response means
                        // every requested row is absent after the transaction: 0 is an
                        // idempotent replay and a partial count means the remainder was
                        // already absent before this request.
                        val terminalDeleteAll = result?.requestedCount == deliveryIds.size &&
                            result.removedCount in 0..deliveryIds.size
                        if (terminalDeleteAll) {
                            markAcked(claimed)
                            acked += claimed
                        } else {
                            failed += claimed
                            definitiveFailures += claimed
                        }
                    }
                    ProviderAckContract.LEGACY_SINGLE -> claimed.forEach recordLoop@ { record ->
                        val attempt = runCatching {
                            ackMessages(destination, contract, listOf(record.deliveryId))
                        }
                        val result = attempt.getOrNull()
                        if (attempt.isFailure) {
                            failed += record
                            uncertainFailures += record
                            return@recordLoop
                        }
                        val removed = result?.removedCount == 1
                        val exactRetryAlreadyRemoved = result?.removedCount == 0 &&
                            record.lastAttemptUncertain
                        if (removed || exactRetryAlreadyRemoved) {
                            markAcked(listOf(record))
                            acked += record
                        } else {
                            failed += record
                            definitiveFailures += record
                        }
                    }
                }
            }

        return ProviderAckDrainResult(
            attempted = attempted,
            acked = acked,
            failed = failed,
            uncertainFailures = uncertainFailures,
            definitiveFailures = definitiveFailures,
        )
    }

    private const val MAX_ACK_BATCH_SIZE = 200
    private val drainMutex = Mutex()
}
