package io.ethan.pushgo.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.util.UrlValidators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MessagePostProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return@withContext Result.success()
        val ringMode = inputData.getString(KEY_RING_MODE)
        val ringtoneId = inputData.getString(KEY_RINGTONE_ID)
        val imageUrl = inputData.getString(KEY_IMAGE_URL)
        val iconUrl = inputData.getString(KEY_ICON_URL)

        if (ringMode?.trim()?.equals("long", ignoreCase = true) == true) {
            LongRingtoneManager.ensureLongRingtone(applicationContext, ringtoneId)
        }

        val repository = (applicationContext as PushGoApp).container.messageRepository
        val message = repository.getById(messageId) ?: return@withContext Result.success()
        val rawPayload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
            ?: return@withContext Result.success()
        val updated = mutableMapOf<String, String>()

        imageUrl?.let { url ->
            downloadToCache(url, "image_$messageId")?.let { file ->
                updated["image_local_path"] = file.absolutePath
            }
        }
        iconUrl?.let { url ->
            downloadToCache(url, "icon_$messageId")?.let { file ->
                updated["icon_local_path"] = file.absolutePath
            }
        }

        if (updated.isNotEmpty()) {
            updated.forEach { (key, value) -> rawPayload.put(key, value) }
            repository.updateRawPayload(messageId, rawPayload.toString())
        }

        Result.success()
    }

    private fun downloadToCache(url: String, prefix: String): File? {
        val normalized = UrlValidators.normalizeHttpsUrl(url) ?: return null
        val connection = openHttpsConnection(normalized) ?: return null
        var outputFile: File? = null
        var exceededLimit = false
        var completed = false
        val result = try {
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_DOWNLOAD_BYTES) return null
            val cacheDir = applicationContext.cacheDir
            val safePrefix = sanitizeFileComponent(prefix)
            val file = File(cacheDir, "${safePrefix}_${System.currentTimeMillis()}")
            outputFile = file
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) {
                            exceededLimit = true
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            completed = true
            file
        } catch (_: Exception) {
            null
        } finally {
            val fileToDelete = outputFile
            if (!completed && fileToDelete != null && fileToDelete.exists()) {
                fileToDelete.delete()
            }
            connection.disconnect()
        }
        return result
    }

    private fun openHttpsConnection(url: String): HttpURLConnection? {
        // Follow redirects manually to prevent HTTPS downgrade.
        var current = runCatching { URL(url) }.getOrNull() ?: return null
        repeat(MAX_REDIRECTS + 1) {
            if (!current.protocol.equals("https", ignoreCase = true)) return null
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
            }
            val code = runCatching { connection.responseCode }.getOrNull() ?: run {
                connection.disconnect()
                return null
            }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) return null
                current = runCatching { URL(current, location) }.getOrNull() ?: return null
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                return null
            }
            return connection
        }
        return null
    }

    private fun sanitizeFileComponent(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "asset"
        return trimmed.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024L
        private const val MAX_REDIRECTS = 3
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_RING_MODE = "ring_mode"
        const val KEY_RINGTONE_ID = "ringtone_id"
        const val KEY_IMAGE_URL = "image_url"
        const val KEY_ICON_URL = "icon_url"
    }
}
