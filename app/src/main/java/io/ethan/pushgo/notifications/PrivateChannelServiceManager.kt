package io.ethan.pushgo.notifications

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.automation.PushGoAutomation
import kotlinx.coroutines.runBlocking

class PrivateChannelServiceManager(private val context: Context) {
    fun refresh() {
        if (tryRefreshNow()) {
            return
        }
        enqueueRefresh()
    }

    fun enqueueRefresh() {
        val work = OneTimeWorkRequestBuilder<ServiceRefreshWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME_ONCE, ExistingWorkPolicy.REPLACE, work)
    }

    private fun tryRefreshNow(): Boolean {
        val app = context as? PushGoApp ?: return false
        val container = app.containerOrNull() ?: return false
        val shouldRun = runCatching {
            !PushGoAutomation.isSessionConfigured() && !runBlocking {
                container.settingsRepository.getUseFcmChannel()
            }
        }.getOrElse { return false }
        val intent = Intent(context, PrivateChannelForegroundService::class.java)
        return runCatching {
            if (shouldRun) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
            true
        }.getOrDefault(false)
    }

    class ServiceRefreshWorker(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val app = applicationContext as? PushGoApp ?: return Result.failure()
            val container = app.containerOrNull() ?: return Result.retry()
            val shouldRun = !PushGoAutomation.isSessionConfigured()
                && !container.settingsRepository.getUseFcmChannel()
            val intent = Intent(applicationContext, PrivateChannelForegroundService::class.java)
            return runCatching {
                if (shouldRun) {
                    ContextCompat.startForegroundService(applicationContext, intent)
                } else {
                    applicationContext.stopService(intent)
                }
                Result.success()
            }.getOrElse { Result.retry() }
        }
    }

    companion object {
        private const val WORK_NAME_ONCE = "pushgo-private-channel-service-refresh"

        fun refresh(context: Context) {
            PrivateChannelServiceManager(context.applicationContext).refresh()
        }

        fun enqueueRefresh(context: Context) {
            PrivateChannelServiceManager(context.applicationContext).enqueueRefresh()
        }
    }
}
