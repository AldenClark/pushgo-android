package io.ethan.pushgo.update

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
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.AppConstants
import io.ethan.pushgo.util.SilentSink
import java.util.concurrent.TimeUnit

object UpdateCheckScheduler {
    private const val TAG = "UpdateCheckScheduler"
    private const val PERIODIC_WORK_NAME = "pushgo-update-check-periodic"
    private const val ONE_TIME_WORK_NAME = "pushgo-update-check-once"

    fun refreshSchedule(context: Context) {
        val appContext = context.applicationContext
        val app = appContext as? PushGoApp
        val container = app?.containerOrNull()
        if (PushGoAutomation.isSessionConfigured()) {
            WorkManager.getInstance(appContext).cancelUniqueWork(PERIODIC_WORK_NAME)
            WorkManager.getInstance(appContext).cancelUniqueWork(ONE_TIME_WORK_NAME)
            return
        }
        val autoEnabled = runCatching {
            container?.settingsRepository?.getCachedUpdateAutoCheckEnabled() ?: true
        }.getOrDefault(true)
        val workManager = WorkManager.getInstance(appContext)
        if (!autoEnabled) {
            SilentSink.i(TAG, "auto-check disabled, cancel periodic work")
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val repeatSeconds = runCatching {
            container?.settingsRepository?.getCachedUpdateScheduledCheckIntervalSeconds()
                ?: AppConstants.updateCheckIntervalSeconds
        }.getOrDefault(AppConstants.updateCheckIntervalSeconds).coerceAtLeast(15 * 60L)
        val flexSeconds = (repeatSeconds / 3).coerceAtLeast(15 * 60L)
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            repeatSeconds,
            TimeUnit.SECONDS,
            flexSeconds,
            TimeUnit.SECONDS,
        ).setConstraints(constraints).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        SilentSink.i(TAG, "periodic schedule refreshed repeatSeconds=$repeatSeconds")
    }

    fun enqueueImmediateProbe(context: Context) {
        val appContext = context.applicationContext
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>().build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    class UpdateCheckWorker(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            if (PushGoAutomation.isSessionConfigured()) {
                return Result.success()
            }
            val app = applicationContext as? PushGoApp ?: return Result.failure()
            val container = app.containerOrNull() ?: return Result.success()
            val autoEnabled = runCatching { container.settingsRepository.getUpdateAutoCheckEnabled() }
                .getOrDefault(true)
            if (!autoEnabled) {
                return Result.success()
            }
            val evaluation = container.updateManager.evaluate(manual = false)
            val candidate = evaluation.visibleCandidate
            if (candidate != null) {
                SilentSink.i(TAG, "background candidate version=${candidate.versionName}(${candidate.versionCode})")
                UpdateNotifier.showUpdateAvailable(applicationContext, candidate)
                container.updateManager.recordBackgroundReminderShown(candidate.versionCode)
            }
            refreshSchedule(applicationContext)
            return Result.success()
        }
    }
}
