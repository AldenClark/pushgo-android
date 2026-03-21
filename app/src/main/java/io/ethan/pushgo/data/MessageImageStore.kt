package io.ethan.pushgo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.ethan.pushgo.util.UrlValidators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max

class MessageImageStore(context: Context) {
    data class CachedImagePaths(
        val originalPath: String,
        val thumbnailPath: String,
    )

    data class ImageRefs(
        val remoteUrl: String?,
        val originalPath: String?,
        val thumbnailPath: String?,
    )

    private val appContext = context.applicationContext
    private val originalDir = File(appContext.cacheDir, "message_images/original")
    private val thumbnailDir = File(appContext.cacheDir, "message_images/thumbnail")

    init {
        originalDir.mkdirs()
        thumbnailDir.mkdirs()
    }

    fun clearAll() {
        originalDir.parentFile?.deleteRecursively()
        originalDir.mkdirs()
        thumbnailDir.mkdirs()
    }

    fun resolveImageRefs(rawPayloadJson: String): ImageRefs {
        val payload = parsePayload(rawPayloadJson)
        return resolveImageRefs(payload)
    }

    fun resolveListImageModel(rawPayloadJson: String): Any? {
        return resolveListImageModels(rawPayloadJson).firstOrNull()
    }

    fun resolveListImageModels(rawPayloadJson: String, maxItems: Int = 4): List<Any> {
        val payload = parsePayload(rawPayloadJson)
        val refs = resolveImageRefs(payload)
        val urls = resolveRemoteImageUrls(payload)
        val models = mutableListOf<Any>()
        refs.thumbnailPath?.let { models += File(it) }
            ?: refs.originalPath?.let { models += File(it) }
            ?: refs.remoteUrl?.let { models += it }
        urls.drop(1).forEach { models += it }
        return if (maxItems > 0) models.take(maxItems) else models
    }

    fun resolveDetailImageModel(rawPayloadJson: String): Any? {
        return resolveDetailImageModels(rawPayloadJson).firstOrNull()
    }

    fun resolveDetailImageModels(rawPayloadJson: String): List<Any> {
        val payload = parsePayload(rawPayloadJson)
        val refs = resolveImageRefs(payload)
        val urls = resolveRemoteImageUrls(payload)
        val models = mutableListOf<Any>()
        refs.originalPath?.let { models += File(it) }
            ?: refs.remoteUrl?.let { models += it }
        urls.drop(1).forEach { models += it }
        return models
    }

    fun resolveRemoteImageUrl(rawPayloadJson: String, preferredUrl: String?): String? {
        return resolveRemoteImageUrls(rawPayloadJson, preferredUrl?.let(::listOf) ?: emptyList())
            .firstOrNull()
    }

    fun resolveRemoteImageUrls(rawPayloadJson: String, preferredUrls: List<String> = emptyList()): List<String> {
        val urls = linkedSetOf<String>()
        preferredUrls.forEach { raw ->
            UrlValidators.normalizeHttpsUrl(raw)?.let { urls += it }
        }
        val payload = parsePayload(rawPayloadJson)
        resolveRemoteImageUrls(payload).forEach { urls += it }
        return urls.toList()
    }

    private fun parsePayload(rawPayloadJson: String): JSONObject? {
        return runCatching { JSONObject(rawPayloadJson) }.getOrNull()
    }

    private fun resolveImageRefs(payload: JSONObject?): ImageRefs {
        val remoteUrl = resolveRemoteImageUrls(payload).firstOrNull()
        val originalPath = payload?.optString(KEY_IMAGE_LOCAL_PATH, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { File(it).exists() }
        val thumbnailPath = payload?.optString(KEY_IMAGE_THUMBNAIL_LOCAL_PATH, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { File(it).exists() }
        return ImageRefs(
            remoteUrl = remoteUrl,
            originalPath = originalPath,
            thumbnailPath = thumbnailPath,
        )
    }

    suspend fun ensureCached(imageUrl: String): CachedImagePaths? = withContext(Dispatchers.IO) {
        val normalized = UrlValidators.normalizeHttpsUrl(imageUrl) ?: return@withContext null
        val key = sha256(normalized)

        val original = findExistingOriginal(key) ?: downloadOriginal(normalized, key) ?: return@withContext null
        val thumbnail = findThumbnail(key)
            ?: generateListThumbnail(original, key)
            ?: return@withContext null

        enforceDiskLimitIfNeeded(originalDir, ORIGINAL_DISK_LIMIT_BYTES)
        enforceDiskLimitIfNeeded(thumbnailDir, THUMBNAIL_DISK_LIMIT_BYTES)

        return@withContext CachedImagePaths(
            originalPath = original.absolutePath,
            thumbnailPath = thumbnail.absolutePath,
        )
    }

    private fun resolveRemoteImageUrls(payload: JSONObject?): List<String> {
        if (payload == null) return emptyList()
        val urls = linkedSetOf<String>()
        imageKeys.forEach { key ->
            appendResolvedUrls(payload.opt(key), urls)
        }
        val meta = payload.optJSONObject("meta")
        if (meta != null) {
            imageKeys.forEach { key ->
                appendResolvedUrls(meta.opt(key), urls)
            }
        }
        return urls.toList()
    }

    private fun appendResolvedUrls(raw: Any?, urls: MutableSet<String>) {
        when (raw) {
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) {
                    return
                }
                val parsed = runCatching { JSONArray(trimmed) }.getOrNull()
                if (parsed != null) {
                    for (index in 0 until parsed.length()) {
                        UrlValidators.normalizeHttpsUrl(parsed.optString(index, ""))?.let { urls += it }
                    }
                }
            }
        }
    }

    private fun findExistingOriginal(hash: String): File? {
        val files = originalDir.listFiles() ?: return null
        return files.firstOrNull { file ->
            file.isFile && file.name.startsWith("$hash.")
        }
    }

    private fun findThumbnail(hash: String): File? {
        val file = File(thumbnailDir, "$hash.jpg")
        return if (file.exists()) file else null
    }

    private fun downloadOriginal(url: String, hash: String): File? {
        val connection = openHttpsConnection(url) ?: return null
        var outputFile: File? = null
        var completed = false
        val result = try {
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_DOWNLOAD_BYTES) return null
            val extension = inferExtension(url, connection.contentType)
            val file = File(originalDir, "$hash.$extension")
            outputFile = file
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) {
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

    private fun generateListThumbnail(original: File, hash: String): File? {
        val target = File(thumbnailDir, "$hash.jpg")
        if (target.exists()) return target

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, LIST_THUMBNAIL_SIZE * 2)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(original.absolutePath, decodeOptions) ?: return null
        val cropped = centerCropSquare(decoded)
        if (cropped !== decoded) {
            decoded.recycle()
        }

        val scaled = if (cropped.width != LIST_THUMBNAIL_SIZE || cropped.height != LIST_THUMBNAIL_SIZE) {
            Bitmap.createScaledBitmap(cropped, LIST_THUMBNAIL_SIZE, LIST_THUMBNAIL_SIZE, true)
        } else {
            cropped
        }
        if (scaled !== cropped) {
            cropped.recycle()
        }

        return try {
            FileOutputStream(target).use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, LIST_THUMBNAIL_QUALITY, output)
            }
            scaled.recycle()
            target
        } catch (_: Exception) {
            scaled.recycle()
            target.delete()
            null
        }
    }

    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        if (bitmap.width == bitmap.height) {
            return bitmap
        }
        val size = minOf(bitmap.width, bitmap.height)
        val offsetX = (bitmap.width - size) / 2
        val offsetY = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, offsetX, offsetY, size, size)
    }

    private fun calculateSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth > target || currentHeight > target) {
            sample *= 2
            currentWidth = max(1, currentWidth / 2)
            currentHeight = max(1, currentHeight / 2)
        }
        return sample.coerceAtLeast(1)
    }

    private fun openHttpsConnection(url: String): HttpURLConnection? {
        var current = runCatching { URL(url) }.getOrNull() ?: return null
        repeat(MAX_REDIRECTS + 1) {
            if (!current.protocol.equals("https", ignoreCase = true)) return null
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
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

    private fun inferExtension(url: String, contentType: String?): String {
        val fromUrl = runCatching { URL(url).path }
            .getOrNull()
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= 8 && it.all { ch -> ch.isLetterOrDigit() } }
        if (fromUrl != null) {
            return fromUrl
        }
        return when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            else -> "img"
        }
    }

    private fun enforceDiskLimitIfNeeded(directory: File, limitBytes: Long) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return

        val sorted = files.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (total <= limitBytes) break
            val size = file.length()
            if (file.delete()) {
                total -= size
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEY_IMAGE_LOCAL_PATH = "image_local_path"
        const val KEY_IMAGE_THUMBNAIL_LOCAL_PATH = "image_thumbnail_local_path"

        private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024L
        private const val MAX_REDIRECTS = 3
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val LIST_THUMBNAIL_SIZE = 128
        private const val LIST_THUMBNAIL_QUALITY = 84
        private const val ORIGINAL_DISK_LIMIT_BYTES = 512L * 1024L * 1024L
        private const val THUMBNAIL_DISK_LIMIT_BYTES = 256L * 1024L * 1024L

        private val imageKeys = listOf("images")
    }
}
