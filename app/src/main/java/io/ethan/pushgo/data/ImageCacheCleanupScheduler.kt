package io.ethan.pushgo.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ethan.pushgo.automation.PushGoAutomation
import java.util.concurrent.TimeUnit

object ImageCacheCleanupScheduler {
    private const val PERIODIC_WORK_NAME = "pushgo-image-cache-cleanup-periodic"
    private const val ONE_TIME_WORK_NAME = "pushgo-image-cache-cleanup-once"
    private const val REPEAT_HOURS = 6L

    fun refreshSchedule(context: Context) {
        val appContext = context.applicationContext
        val workManager = WorkManager.getInstance(appContext)
        if (PushGoAutomation.isSessionConfigured()) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(ONE_TIME_WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<ImageCacheCleanupWorker>(
            REPEAT_HOURS,
            TimeUnit.HOURS,
        ).setConstraints(constraints).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )

        val once = OneTimeWorkRequestBuilder<ImageCacheCleanupWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP, once)
    }

    class ImageCacheCleanupWorker(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            if (PushGoAutomation.isSessionConfigured()) {
                return Result.success()
            }
            MessageImageStore.shared(applicationContext).purgeExpired()
            // Reuse the existing startup/periodic entry. Pending canonical bits, not
            // an in-memory notification map or HTTP cache, survive a failed handoff.
            io.ethan.pushgo.notifications.MessagePostProcessWorker.enqueueRecovery(applicationContext)
            return Result.success()
        }
    }
}
