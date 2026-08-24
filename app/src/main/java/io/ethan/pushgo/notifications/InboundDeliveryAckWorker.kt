package io.ethan.pushgo.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.NetworkType
import androidx.work.workDataOf
import io.ethan.pushgo.PushGoApp
import java.util.concurrent.TimeUnit

class InboundDeliveryAckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? PushGoApp ?: return Result.retry()
        val container = app.containerOrNull() ?: return Result.retry()

        runCatching {
            pruneAckedTombstonesInBatches(
                pruneBatch = { limit ->
                    container.inboundDeliveryLedgerRepository.pruneAckedTombstones(limit = limit)
                },
            )
        }.onFailure { error ->
            io.ethan.pushgo.util.SilentSink.w(TAG, "provider ACK tombstone prune failed", error)
        }

        repeat(MAX_DRAIN_BATCHES_PER_RUN) {
            val result = runCatching {
                ProviderAckDrainCoordinator.drainPendingAcks(
                    loadPendingAcks = container.inboundDeliveryLedgerRepository::loadFairPendingAcks,
                    beginAttempt = container.inboundDeliveryLedgerRepository::beginAckAttempt,
                    ackMessages = container.channelRepository::ackMessages,
                    markAcked = container.inboundDeliveryLedgerRepository::markAckRecordsAcked,
                )
            }.onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "provider ack drain failed",
                    error,
                )
            }.getOrElse {
                return Result.retry()
            }

            if (result.hasFailures) {
                container.inboundDeliveryLedgerRepository.deferFailedAcks(
                    result.uncertainFailures,
                    lastAttemptUncertain = true,
                )
                container.inboundDeliveryLedgerRepository.deferFailedAcks(
                    result.definitiveFailures,
                    lastAttemptUncertain = false,
                )
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "provider ack drain retained ${result.failedIds.size} pending deliveries",
                )
                return Result.retry()
            }
            if (result.attempted.isEmpty()) {
                return Result.success()
            }
        }
        return Result.retry()
    }

    companion object {
        const val KEY_DELIVERY_ID = "delivery_id"
        private const val TAG = "InboundDeliveryAck"
        private const val UNIQUE_WORK_NAME = "pushgo-provider-ack-drain"
        private const val RECOVERY_WORK_NAME = "pushgo-provider-ack-recovery"
        private const val MAX_DRAIN_BATCHES_PER_RUN = 100

        fun enqueue(context: Context, deliveryId: String) {
            val normalized = deliveryId.trim()
            if (normalized.isEmpty()) {
                return
            }
            enqueueDrain(context, normalized)
        }

        fun enqueueDrain(context: Context, deliveryId: String? = null) {
            enqueueDrainWithPolicy(context, deliveryId, UNIQUE_WORK_NAME, ACK_DRAIN_WORK_POLICY)
        }

        /**
         * Repairs a possible DB-to-scheduler handoff gap on an independent unique-work lane,
         * so it cannot cancel the primary drain after that worker's final empty read.
         */
        fun ensureDrain(context: Context, deliveryId: String? = null) {
            enqueueDrainWithPolicy(
                context,
                deliveryId,
                RECOVERY_WORK_NAME,
                ACK_DRAIN_RECOVERY_WORK_POLICY,
            )
        }

        private fun enqueueDrainWithPolicy(
            context: Context,
            deliveryId: String?,
            uniqueWorkName: String,
            policy: ExistingWorkPolicy,
        ) {
            val normalized = deliveryId?.trim()?.takeIf { it.isNotEmpty() }
            val input = if (normalized == null) {
                workDataOf()
            } else {
                workDataOf(KEY_DELIVERY_ID to normalized)
            }
            val request = OneTimeWorkRequestBuilder<InboundDeliveryAckWorker>()
                .setInputData(input)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    uniqueWorkName,
                    policy,
                    request,
                )
        }

        // enqueueAcks only requests work on the durable outbox's empty -> nonempty edge.
        // REPLACE therefore closes the worker-exit race without cancelling every delivery.
        internal val ACK_DRAIN_WORK_POLICY = ExistingWorkPolicy.REPLACE
        internal val ACK_DRAIN_RECOVERY_WORK_POLICY = ExistingWorkPolicy.KEEP
    }
}

internal suspend fun pruneAckedTombstonesInBatches(
    batchSize: Int = 200,
    maxBatches: Int = 100,
    pruneBatch: suspend (limit: Int) -> Int,
): Int {
    require(batchSize > 0)
    require(maxBatches > 0)
    var total = 0
    repeat(maxBatches) {
        val pruned = pruneBatch(batchSize).coerceAtLeast(0)
        total += pruned
        if (pruned < batchSize) return total
    }
    return total
}
