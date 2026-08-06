package io.ethan.pushgo.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.ethan.pushgo.PushGoApp

class InboundDeliveryAckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? PushGoApp ?: return Result.retry()
        val container = app.containerOrNull() ?: return Result.retry()

        repeat(MAX_DRAIN_BATCHES_PER_RUN) {
            val result = runCatching {
                ProviderAckDrainCoordinator.drainPendingAcks(
                    loadPendingAcks = container.inboundDeliveryLedgerRepository::loadPendingAcks,
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
                container.inboundDeliveryLedgerRepository.deferFailedAcks(result.failed)
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
        private const val MAX_DRAIN_BATCHES_PER_RUN = 100

        fun enqueue(context: Context, deliveryId: String) {
            val normalized = deliveryId.trim()
            if (normalized.isEmpty()) {
                return
            }
            enqueueDrain(context, normalized)
        }

        fun enqueueDrain(context: Context, deliveryId: String? = null) {
            val normalized = deliveryId?.trim()?.takeIf { it.isNotEmpty() }
            val input = if (normalized == null) {
                workDataOf()
            } else {
                workDataOf(KEY_DELIVERY_ID to normalized)
            }
            val request = OneTimeWorkRequestBuilder<InboundDeliveryAckWorker>()
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
                )
        }
    }
}
