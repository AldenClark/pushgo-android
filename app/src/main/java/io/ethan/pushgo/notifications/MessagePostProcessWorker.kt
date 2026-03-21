package io.ethan.pushgo.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.MessageImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return@withContext Result.success()
        val imageUrlHint = inputData.getString(KEY_IMAGE_URL)

        val container = (applicationContext as PushGoApp).containerOrNull()
            ?: run {
                io.ethan.pushgo.util.SilentSink.e(TAG, "post process skipped: local storage unavailable")
                return@withContext Result.failure()
            }
        val repository = container.messageRepository
        val imageStore = container.messageImageStore

        val message = repository.getById(messageId) ?: return@withContext Result.success()
        val rawPayload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
            ?: return@withContext Result.success()

        val resolvedImageUrl = imageStore.resolveRemoteImageUrl(message.rawPayloadJson, imageUrlHint)
            ?: return@withContext Result.success()
        val cached = imageStore.ensureCached(resolvedImageUrl) ?: return@withContext Result.success()

        rawPayload.put(MessageImageStore.KEY_IMAGE_LOCAL_PATH, cached.originalPath)
        rawPayload.put(MessageImageStore.KEY_IMAGE_THUMBNAIL_LOCAL_PATH, cached.thumbnailPath)
        repository.updateRawPayload(messageId, rawPayload.toString())

        Result.success()
    }
}
