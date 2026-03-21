package io.ethan.pushgo.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ethan.pushgo.PushGoApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object PrivateAckOutboxWorkScheduler {
    private const val WORK_NAME = "private-ack-outbox-drain"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<PrivateAckOutboxWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}

class PrivateAckOutboxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    companion object {
        private const val TAG = "PrivateAckOutbox"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as? PushGoApp)?.containerOrNull()
            ?: run {
                io.ethan.pushgo.util.SilentSink.e(TAG, "ack worker skipped: local storage unavailable")
                return@withContext Result.failure()
            }

        when (container.privateChannelClient.drainAckOutboxNow()) {
            AckDrainOutcome.IDLE,
            AckDrainOutcome.DRAINED,
            -> Result.success()

            AckDrainOutcome.PARTIAL,
            AckDrainOutcome.FAILED,
            -> Result.retry()
        }
    }
}
