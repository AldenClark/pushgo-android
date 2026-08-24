package io.ethan.pushgo.ui

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ethan.pushgo.PushGoApp
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

class WorkManagerPendingLocalDeletionDrainScheduler(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : PendingLocalDeletionDrainScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    init {
        val safetyNet = PeriodicWorkRequestBuilder<PendingLocalDeletionDrainWorker>(
            SAFETY_NET_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).addTag(WORK_TAG).build()
        workManager.enqueueUniquePeriodicWork(
            SAFETY_NET_WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            safetyNet,
        )
    }

    override fun scheduleImmediate() {
        enqueue(delayMillis = 0L, force = true)
    }

    override fun scheduleAt(epochMillis: Long) {
        enqueue(delayMillis = (epochMillis - clock()).coerceAtLeast(0L), force = false)
    }

    private fun enqueue(delayMillis: Long, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<PendingLocalDeletionDrainWorker>()
            .addTag(WORK_TAG)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putBoolean(KEY_FORCE, force).build())
            .build()
        // Room is the source of truth and claims are atomic, so duplicate wakeups are
        // harmless. Do not use unique REPLACE work here: a running worker can discover
        // another due row and otherwise cancel itself while committing that row.
        workManager.enqueue(request)
    }

    companion object {
        const val WORK_TAG = "pending-local-deletion-drain"
        const val SAFETY_NET_WORK_NAME = "pending-local-deletion-drain-safety-net"
        const val KEY_FORCE = "force"
        const val SAFETY_NET_INTERVAL_MINUTES = 15L
    }
}

class PendingLocalDeletionDrainWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        activeWorkers.incrementAndGet()
        return try {
            val app = applicationContext as? PushGoApp ?: return Result.retry()
            val container = app.containerOrNull() ?: return Result.retry()
            container.pendingLocalDeletionCoordinator.drainRecoverable(
                force = inputData.getBoolean(WorkManagerPendingLocalDeletionDrainScheduler.KEY_FORCE, false)
            )
            // The coordinator registers the exact next due time; periodic work remains as the
            // durable fallback if an enqueue commit races process death.
            Result.success()
        } catch (error: CancellationException) {
            // WorkManager cancellation is control flow, not a transient delivery failure.
            // Retrying here can resurrect explicitly cancelled work and overlap storage
            // shutdown or a replacement process.
            throw error
        } catch (_: Throwable) {
            Result.retry()
        } finally {
            activeWorkers.decrementAndGet()
        }
    }

    companion object {
        private val activeWorkers = AtomicInteger(0)

        /** Instrumentation teardown barrier; production scheduling never depends on it. */
        internal fun activeWorkerCountForTesting(): Int = activeWorkers.get()
    }
}
