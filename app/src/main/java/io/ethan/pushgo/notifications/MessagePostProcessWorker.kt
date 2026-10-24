package io.ethan.pushgo.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.POST_PROCESS_MEDIA
import io.ethan.pushgo.data.POST_PROCESS_NOTIFICATION
import io.ethan.pushgo.data.POST_PROCESS_PAGE_SIZE
import io.ethan.pushgo.data.POST_PROCESS_TABLES
import io.ethan.pushgo.data.PostProcessLocator
import io.ethan.pushgo.data.PostProcessSnapshot
import io.ethan.pushgo.data.MessageImageStore
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.finishPostProcessIfCurrent
import io.ethan.pushgo.data.loadPendingPostProcess
import io.ethan.pushgo.data.postProcessSnapshot
import io.ethan.pushgo.data.postProcessLocator
import io.ethan.pushgo.testing.QualityRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Existing worker owns execution; only the canonical pending bits own the obligation. */
class MessagePostProcessWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (QualityRuntime.currentSession() != null) return@withContext Result.success()
        val container = (applicationContext as? PushGoApp)?.containerOrNull() ?: return@withContext Result.retry()
        val generation = inputData.getString(KEY_GENERATION)
        if (generation != null) {
            val locator = PostProcessLocator(inputData.getInt(KEY_TABLE, -1), inputData.getLong(KEY_ROW, 0), generation, 0)
            val done = processPendingPostProcess(container.database, container.messageImageStore, locator) { publishPostProcessNotification(applicationContext, it) }
            return@withContext if (done) Result.success() else Result.retry()
        }
        val messageId = inputData.getString(KEY_MESSAGE_ID)
        if (messageId != null) {
            // v36 jobs may contain a URL hint. Deliberately ignore it: the current canonical
            // payload and its owner generation, not old Work Data, select the image.
            val message = container.messageRepository.getById(messageId) ?: return@withContext Result.success()
            val pending = container.messageRepository.postProcessLocator(messageId)
            if (pending != null) {
                val done = processPendingPostProcess(container.database, container.messageImageStore, pending) {
                    publishPostProcessNotification(applicationContext, it)
                }
                return@withContext if (done) Result.success() else Result.retry()
            }
            val url = container.messageImageStore.resolveRemoteImageUrl(message.rawPayloadJson, null)
                ?: return@withContext Result.success()
            val acquired = container.messageImageStore.acquire(container.messageImageStore.imageModel(message, url, true))
            acquired?.close()
            return@withContext if (acquired == null && runAttemptCount < 8) Result.retry() else Result.success()
        }

        // A recovery run registers one indexed page, then one cursor successor. It never
        // loads bodies, scans all history into RAM or creates a task per missing scan page.
        var table = inputData.getInt(KEY_TABLE, 0)
        var flags = inputData.getInt(KEY_AFTER_FLAGS, 0)
        var row = inputData.getLong(KEY_ROW, 0)
        try { while (table < POST_PROCESS_TABLES.size) {
            val page = container.database.loadPendingPostProcess(table, flags, row)
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
            for ((index, locator) in page.withIndex()) {
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0 && index > 0) {
                    val last = page[index - 1]
                    enqueueRecoveryPage(applicationContext, table, last.flags, last.rowId, ExistingWorkPolicy.APPEND_OR_REPLACE).result.get(5, TimeUnit.SECONDS)
                    return@withContext Result.success()
                }
                enqueueLocator(applicationContext, locator).result.get(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            }
            if (page.size == POST_PROCESS_PAGE_SIZE) {
                val last = page.last()
                enqueueRecoveryPage(applicationContext, table, last.flags, last.rowId, ExistingWorkPolicy.APPEND_OR_REPLACE).result.get(5, TimeUnit.SECONDS)
                return@withContext Result.success()
            }
            table++
            flags = 0
            row = 0
        } } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Registration may have committed before a timeout; KEEP and the durable
            // generation make repeating this bounded page safe.
            io.ethan.pushgo.util.SilentSink.w("MessagePostProcess", "registration deferred type=${error.javaClass.simpleName}")
            return@withContext Result.retry()
        }
        Result.success()
    }

    companion object {
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_IMAGE_URL = "image_url" // old queued Data only; never written or trusted now
        private const val KEY_GENERATION = "generation"
        private const val KEY_TABLE = "source_table"
        private const val KEY_ROW = "source_row"
        private const val KEY_AFTER_FLAGS = "after_flags"
        private const val RECOVERY_WORK_NAME = "message-post-process-recovery"

        fun enqueueRecovery(context: Context) {
            enqueueRecoveryPage(context.applicationContext, 0, 0, 0, ExistingWorkPolicy.KEEP)
        }

        private fun enqueueRecoveryPage(context: Context, table: Int, flags: Int, row: Long, policy: ExistingWorkPolicy) =
            WorkManager.getInstance(context).enqueueUniqueWork(RECOVERY_WORK_NAME, policy,
                OneTimeWorkRequestBuilder<MessagePostProcessWorker>()
                    .setInputData(workDataOf(KEY_TABLE to table, KEY_AFTER_FLAGS to flags, KEY_ROW to row))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())

        internal fun enqueueLocator(context: Context, locator: PostProcessLocator) =
            WorkManager.getInstance(context).enqueueUniqueWork(
                "message-post-process:${locator.tableIndex}:${locator.generation}", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MessagePostProcessWorker>()
                    .setInputData(pendingInputData(locator))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())

        internal fun pendingInputData(locator: PostProcessLocator) =
            workDataOf(KEY_TABLE to locator.tableIndex, KEY_ROW to locator.rowId, KEY_GENERATION to locator.generation)
    }
}

/** Actual worker implementation, also exercised with real Room and a controlled publisher. */
internal suspend fun processPendingPostProcess(
    database: PushGoDatabase,
    imageStore: MessageImageStore,
    locator: PostProcessLocator,
    publish: (PostProcessSnapshot) -> Boolean,
): Boolean {
    var retry = false
    try {
        if (!database.finishPostProcessIfCurrent(locator, POST_PROCESS_NOTIFICATION, publish)) retry = true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        io.ethan.pushgo.util.SilentSink.w("MessagePostProcess", "notification remains pending type=${error.javaClass.simpleName}")
        retry = true
    }
    val snapshot = database.postProcessSnapshot(locator) ?: return true
    if (snapshot.locator.flags and POST_PROCESS_MEDIA != 0) {
        try {
            val url = imageStore.resolveRemoteImageUrl(snapshot.message.rawPayloadJson, null)
            val acquired = if (url == null) null else imageStore.acquire(imageStore.imageModel(snapshot.message, url, true))
            acquired?.close()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            io.ethan.pushgo.util.SilentSink.w("MessagePostProcess", "optional media prefetch failed type=${error.javaClass.simpleName}")
        }
        // This fact means one optional prefetch was attempted, not that the URL will
        // succeed. UI reads/retry retain the canonical URL; bad media cannot keep a
        // worker alive or delay notification recovery indefinitely.
        database.finishPostProcessIfCurrent(locator, POST_PROCESS_MEDIA)
    }
    return !retry
}

private fun publishPostProcessNotification(context: Context, snapshot: PostProcessSnapshot): Boolean {
    val message = snapshot.message
    val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
    val ttl = io.ethan.pushgo.util.PayloadTimeNormalizer.epochSecondsFromJson(payload, "ttl")
    // Same late-replay rule as ingress: retain content/ACK facts but do not alert expired content.
    if (ttl != null && System.currentTimeMillis() / 1000 > ttl) return true
    val level = payload?.optString("severity")?.takeIf(String::isNotBlank)
    return if (snapshot.entityType == "message") {
        NotificationHelper.showMessageReplayNotificationSilently(context, message, level)
    } else NotificationHelper.showEntityReplayNotificationSilently(context, snapshot.entityType, snapshot.entityId,
        message.channel, snapshot.eventId, snapshot.thingId, message.title, message.body, level)
}
