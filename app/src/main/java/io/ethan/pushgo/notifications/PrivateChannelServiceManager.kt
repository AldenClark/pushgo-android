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
        val shouldRun = app.shouldRunPrivateChannelForegroundService()
        val intent = Intent(context, PrivateChannelForegroundService::class.java)
        return runCatching {
            if (shouldRun) {
                startServiceNow(context, app, intent)
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
            val shouldRun = app.shouldRunPrivateChannelForegroundService()
            val intent = Intent(applicationContext, PrivateChannelForegroundService::class.java)
            return runCatching {
                if (shouldRun) {
                    startServiceNow(applicationContext, app, intent)
                } else {
                    applicationContext.stopService(intent)
                }
                Result.success()
            }.getOrElse { Result.retry() }
        }
    }

    companion object {
        private const val WORK_NAME_ONCE = "pushgo-private-channel-service-refresh"
        private fun startServiceNow(
            context: Context,
            app: PushGoApp?,
            intent: Intent,
        ) {
            val appContext = context.applicationContext
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun refresh(context: Context) {
            PrivateChannelServiceManager(context.applicationContext).refresh()
        }

        fun refreshForMode(context: Context, useFcmChannel: Boolean) {
            if (PushGoAutomation.isSessionConfigured() || useFcmChannel) {
                stopNow(context)
            } else {
                refresh(context)
            }
        }

        fun enqueueRefresh(context: Context) {
            PrivateChannelServiceManager(context.applicationContext).enqueueRefresh()
        }

        fun startNow(context: Context) {
            val appContext = context.applicationContext
            val app = appContext as? PushGoApp
            startServiceNow(
                appContext,
                app,
                Intent(appContext, PrivateChannelForegroundService::class.java),
            )
        }

        fun stopNow(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, PrivateChannelForegroundService::class.java))
        }
    }
}
