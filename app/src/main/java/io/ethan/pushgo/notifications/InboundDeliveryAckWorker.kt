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
        val deliveryId = inputData.getString(KEY_DELIVERY_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return Result.success()
        val app = applicationContext as? PushGoApp ?: return Result.success()
        val container = app.containerOrNull() ?: return Result.success()

        runCatching {
            container.channelRepository.ackMessage(deliveryId)
        }.onFailure { error ->
            io.ethan.pushgo.util.SilentSink.w(
                TAG,
                "provider direct ack failed deliveryId=$deliveryId",
                error,
            )
        }
        return Result.success()
    }

    companion object {
        const val KEY_DELIVERY_ID = "delivery_id"
        private const val TAG = "InboundDeliveryAck"
        private const val UNIQUE_WORK_PREFIX = "pushgo-provider-direct-ack-"

        fun enqueue(context: Context, deliveryId: String) {
            val normalized = deliveryId.trim()
            if (normalized.isEmpty()) {
                return
            }
            val input = workDataOf(KEY_DELIVERY_ID to normalized)
            val request = OneTimeWorkRequestBuilder<InboundDeliveryAckWorker>()
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    "$UNIQUE_WORK_PREFIX$normalized",
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
        }
    }
}
