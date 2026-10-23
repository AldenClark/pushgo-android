package io.ethan.pushgo.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.testing.QualityRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MessagePostProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_IMAGE_URL = "image_url"
        private const val TAG = "MessagePostProcess"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (QualityRuntime.currentSession() != null) return@withContext Result.success()
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return@withContext Result.success()
        val imageUrlHint = inputData.getString(KEY_IMAGE_URL)

        val container = (applicationContext as PushGoApp).containerOrNull()
            ?: run {
                io.ethan.pushgo.util.SilentSink.e(TAG, "post process skipped: local storage unavailable")
                return@withContext Result.retry()
            }
        val repository = container.messageRepository
        val imageStore = container.messageImageStore

        val message = repository.getById(messageId) ?: return@withContext Result.success()
        val resolvedImageUrl = imageStore.resolveRemoteImageUrl(message.rawPayloadJson, imageUrlHint)
            ?: return@withContext Result.success()
        val acquired = imageStore.acquire(imageStore.imageModel(message, resolvedImageUrl, thumbnail = true)) ?: return@withContext if (runAttemptCount < 8) {
            Result.retry()
        } else {
            Result.success()
        }

        // Acquisition publishes and binds under Room's current-owner transaction. Do not
        // write an old payload snapshot back after delete/recreate or decryption recovery.
        // no-store is a successful transient response, not eight failed precache retries.
        acquired.close()
        Result.success()
    }
}
