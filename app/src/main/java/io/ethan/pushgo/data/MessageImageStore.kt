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
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
    private val metadataStore = ImageAssetMetadataStore.get(appContext)

    init {
        originalDir.mkdirs()
        thumbnailDir.mkdirs()
    }

    fun clearAll() {
        originalDir.parentFile?.deleteRecursively()
        originalDir.mkdirs()
        thumbnailDir.mkdirs()
    }

    fun purgeExpired(): Int {
        var removed = 0
        removed += purgeExpiredInDirectory(originalDir)
        removed += purgeExpiredInDirectory(thumbnailDir)
        return removed
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
            ?.takeIf { File(it).let(::isCacheFileFresh) }
        val thumbnailPath = payload?.optString(KEY_IMAGE_THUMBNAIL_LOCAL_PATH, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { File(it).let(::isCacheFileFresh) }
        return ImageRefs(
            remoteUrl = remoteUrl,
            originalPath = originalPath,
            thumbnailPath = thumbnailPath,
        )
    }

    suspend fun ensureCached(imageUrl: String): CachedImagePaths? = withContext(Dispatchers.IO) {
        val originalPath = ensureOriginalCached(imageUrl) ?: return@withContext null
        val original = File(originalPath)
        val normalized = canonicalImageCacheUrl(imageUrl) ?: return@withContext null
        val key = sha256(normalized)
        val thumbnail = findThumbnail(key)
            ?: generateListThumbnail(original, key, readCacheMetadata(original)?.expiresAtEpochMillis)

        enforceDiskLimitIfNeeded(thumbnailDir, THUMBNAIL_DISK_LIMIT_BYTES)

        return@withContext CachedImagePaths(
            originalPath = original.absolutePath,
            thumbnailPath = thumbnail?.absolutePath ?: original.absolutePath,
        )
    }

    suspend fun ensureOriginalCached(imageUrl: String): String? = withContext(Dispatchers.IO) {
        val normalized = UrlValidators.normalizeHttpsUrl(imageUrl) ?: return@withContext null
        val key = sha256(canonicalImageCacheUrl(normalized) ?: normalized)
        val existing = findExistingOriginal(key)
        val original = existing ?: downloadOriginal(normalized, key)?.file ?: return@withContext null
        ensureImageMetadata(
            imageUrl = normalized,
            file = original,
            responseContentType = null,
            responseEtag = null,
            responseLastModified = null,
        )
        enforceDiskLimitIfNeeded(originalDir, ORIGINAL_DISK_LIMIT_BYTES)
        return@withContext original.absolutePath
    }

    suspend fun imageAspectRatioFromMetadata(imageUrl: String): Float? = withContext(Dispatchers.IO) {
        val normalized = UrlValidators.normalizeHttpsUrl(imageUrl) ?: return@withContext null
        val metadata = metadataStore.findByUrl(normalized) ?: return@withContext null
        val width = metadata.pixelWidth
        val height = metadata.pixelHeight
        if (width <= 0 || height <= 0) return@withContext null
        return@withContext (width.toFloat() / height.toFloat()).coerceAtLeast(0.1f)
    }

    suspend fun preheatDetailAssets(rawPayloadJson: String, bodyText: String) = withContext(Dispatchers.IO) {
        val urls = LinkedHashSet<String>()
        resolveRemoteImageUrls(rawPayloadJson).forEach { urls += it }
        extractMarkdownImageUrls(bodyText).forEach { urls += it }
        urls.forEach { url ->
            if (metadataStore.findByUrl(url) == null) {
                ensureOriginalCached(url)
            }
        }
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
                } else {
                    UrlValidators.normalizeHttpsUrl(trimmed)?.let { urls += it }
                }
            }
        }
    }

    private fun extractMarkdownImageUrls(markdown: String): List<String> {
        if (markdown.isBlank()) return emptyList()
        val urls = LinkedHashSet<String>()
        val patterns = listOf(
            Regex("!\\[[^\\]]*\\]\\(([^)]+)\\)", setOf(RegexOption.IGNORE_CASE)),
            Regex("<img[^>]+src\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]*>", setOf(RegexOption.IGNORE_CASE)),
            Regex("\\[[^\\]]+\\]:\\s*(https?://\\S+)", setOf(RegexOption.IGNORE_CASE)),
            Regex("(https?://\\S+\\.(?:png|jpe?g|gif|webp|avif|heic|heif|bmp)(?:\\?\\S*)?)", setOf(RegexOption.IGNORE_CASE)),
        )
        patterns.forEach { regex ->
            regex.findAll(markdown).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (raw.isEmpty()) return@forEach
                val normalized = normalizeExternalMarkdownImageUrl(raw) ?: return@forEach
                urls += normalized
            }
        }
        return urls.toList()
    }

    private fun normalizeExternalMarkdownImageUrl(raw: String): String? {
        return UrlValidators.normalizeHttpsUrl(
            raw
                .trim()
                .trim('<', '>', '"', '\'')
                .trimEnd(')', ']', '}', '.', ',', ';')
        )
    }

    private fun findExistingOriginal(hash: String): File? {
        val files = originalDir.listFiles() ?: return null
        return files.firstOrNull { file ->
            file.isFile && !file.name.endsWith(METADATA_EXTENSION) && file.name.startsWith("$hash.")
        }?.takeIf { file ->
            if (isCacheFileFresh(file)) {
                true
            } else {
                deleteCachePair(file)
                false
            }
        }
    }

    private fun findThumbnail(hash: String): File? {
        val file = File(thumbnailDir, "$hash.jpg")
        return if (file.exists() && isCacheFileFresh(file)) {
            file
        } else {
            if (file.exists()) deleteCachePair(file)
            null
        }
    }

    private data class DownloadedOriginal(
        val file: File,
        val contentType: String?,
        val etag: String?,
        val lastModified: String?,
    )

    private fun downloadOriginal(url: String, hash: String): DownloadedOriginal? {
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
            writeCacheMetadata(file, cacheExpirationEpochMillis(connection))
            completed = true
            val metadata = ensureImageMetadata(
                imageUrl = url,
                file = file,
                responseContentType = connection.contentType,
                responseEtag = connection.getHeaderField("ETag"),
                responseLastModified = connection.getHeaderField("Last-Modified"),
            )
            DownloadedOriginal(
                file = file,
                contentType = metadata?.mimeType ?: connection.contentType,
                etag = connection.getHeaderField("ETag"),
                lastModified = connection.getHeaderField("Last-Modified"),
            )
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

    private fun ensureImageMetadata(
        imageUrl: String,
        file: File,
        responseContentType: String?,
        responseEtag: String?,
        responseLastModified: String?,
    ): ImageAssetMetadataStore.Metadata? {
        val normalized = UrlValidators.normalizeHttpsUrl(imageUrl) ?: return null
        val existing = metadataStore.findByUrl(normalized)
        if (existing != null && existing.byteSize == file.length()) {
            return existing
        }
        val imageInfo = extractImageInfo(file, responseContentType) ?: return null
        val metadata = ImageAssetMetadataStore.Metadata(
            url = normalized,
            pixelWidth = imageInfo.width,
            pixelHeight = imageInfo.height,
            aspectRatio = imageInfo.width.toDouble() / imageInfo.height.toDouble(),
            mimeType = imageInfo.mimeType,
            isAnimated = imageInfo.isAnimated,
            frameCount = imageInfo.frameCount,
            byteSize = file.length(),
            etag = responseEtag,
            lastModified = responseLastModified,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        metadataStore.upsert(metadata)
        return metadata
    }

    private data class ImageInfo(
        val width: Int,
        val height: Int,
        val mimeType: String?,
        val isAnimated: Boolean,
        val frameCount: Int?,
    )

    private fun extractImageInfo(file: File, fallbackContentType: String?): ImageInfo? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) {
            return null
        }
        val mimeType = options.outMimeType ?: fallbackContentType?.substringBefore(';')?.trim()
        val animationProbe = probeAnimation(file, mimeType)
        return ImageInfo(
            width = width,
            height = height,
            mimeType = mimeType,
            isAnimated = animationProbe.first,
            frameCount = animationProbe.second,
        )
    }

    private fun probeAnimation(file: File, mimeType: String?): Pair<Boolean, Int?> {
        val mime = mimeType?.lowercase(Locale.US).orEmpty()
        if (mime.contains("gif")) {
            return true to null
        }
        val header = runCatching {
            file.inputStream().use { input ->
                val bytes = ByteArray(128)
                val read = input.read(bytes)
                if (read <= 0) ByteArray(0) else bytes.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
        if (header.size >= 6) {
            val signature = String(header.copyOfRange(0, 6))
            if (signature == "GIF87a" || signature == "GIF89a") {
                return true to null
            }
        }
        val isAnimatedWebp = mime.contains("webp") &&
            String(header, Charsets.ISO_8859_1).contains("ANIM")
        if (isAnimatedWebp) {
            return true to null
        }
        return false to null
    }

    private fun generateListThumbnail(
        original: File,
        hash: String,
        expiresAtEpochMillis: Long? = readCacheMetadata(original)?.expiresAtEpochMillis,
    ): File? {
        val target = File(thumbnailDir, "$hash.jpg")
        if (target.exists() && isCacheFileFresh(target)) return target

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
            writeCacheMetadata(
                target,
                expiresAtEpochMillis ?: System.currentTimeMillis() + DEFAULT_CACHE_TTL_MS,
            )
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
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(METADATA_EXTENSION) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return

        val sorted = files.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (total <= limitBytes) break
            val size = file.length()
            if (deleteCachePair(file)) {
                total -= size
            }
        }
    }

    private data class CacheMetadata(val expiresAtEpochMillis: Long)

    private fun cacheMetadataFile(file: File): File = File(file.parentFile, "${file.name}$METADATA_EXTENSION")

    private fun readCacheMetadata(file: File): CacheMetadata? {
        val metadataFile = cacheMetadataFile(file)
        if (!metadataFile.exists()) return null
        return runCatching {
            val json = JSONObject(metadataFile.readText())
            CacheMetadata(expiresAtEpochMillis = json.optLong("expiresAtEpochMillis", Long.MIN_VALUE))
        }.getOrNull()
    }

    private fun writeCacheMetadata(file: File, expiresAtEpochMillis: Long) {
        runCatching {
            cacheMetadataFile(file).writeText(
                JSONObject()
                    .put("expiresAtEpochMillis", expiresAtEpochMillis)
                    .toString(),
            )
        }
    }

    private fun isCacheFileFresh(file: File, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!file.exists() || !file.isFile) return false
        val metadata = readCacheMetadata(file) ?: return false
        return metadata.expiresAtEpochMillis > nowMs
    }

    private fun deleteCachePair(file: File): Boolean {
        cacheMetadataFile(file).delete()
        return file.delete()
    }

    private fun purgeExpiredInDirectory(directory: File): Int {
        val nowMs = System.currentTimeMillis()
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(METADATA_EXTENSION) } ?: return 0
        var removed = 0
        files.forEach { file ->
            if (!isCacheFileFresh(file, nowMs) && deleteCachePair(file)) {
                removed += 1
            }
        }
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(METADATA_EXTENSION) }
            ?.forEach { metadataFile ->
                val imageName = metadataFile.name.removeSuffix(METADATA_EXTENSION)
                if (!File(directory, imageName).exists()) metadataFile.delete()
            }
        return removed
    }

    private fun cacheExpirationEpochMillis(connection: HttpURLConnection): Long {
        val now = System.currentTimeMillis()
        val cacheControl = connection.getHeaderField("Cache-Control")
        if (!cacheControl.isNullOrBlank()) {
            if (hasCacheControlDirective(cacheControl, "no-store") ||
                hasCacheControlDirective(cacheControl, "no-cache")
            ) {
                return now
            }
            cacheControlMaxAge(cacheControl)?.let { maxAge ->
                val ttl = (maxAge * 1000L).coerceIn(0L, MAX_CACHE_TTL_MS)
                return now + ttl
            }
        }
        connection.getHeaderField("Expires")?.takeIf { it.isNotBlank() }?.let { expires ->
            parseHttpDateMillis(expires)?.let { expiresAt ->
                return expiresAt.coerceIn(now, now + MAX_CACHE_TTL_MS)
            }
        }
        return now + DEFAULT_CACHE_TTL_MS
    }

    private fun hasCacheControlDirective(cacheControl: String, directive: String): Boolean {
        return cacheControl.split(',').any { part ->
            part.trim().substringBefore('=').trim().equals(directive, ignoreCase = true)
        }
    }

    private fun cacheControlMaxAge(cacheControl: String): Long? {
        return cacheControl.split(',').firstNotNullOfOrNull { part ->
            val pieces = part.trim().split('=', limit = 2)
            if (pieces.size != 2 || !pieces[0].trim().equals("max-age", ignoreCase = true)) {
                null
            } else {
                pieces[1].trim().trim('"').toLongOrNull()
            }
        }
    }

    private fun parseHttpDateMillis(value: String): Long? {
        return HTTP_DATE_FORMATS.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }.parse(value)?.time
            }.getOrNull()
        }
    }

    private fun canonicalImageCacheUrl(raw: String): String? {
        val normalized = UrlValidators.normalizeHttpsUrl(raw) ?: return null
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return normalized.substringBefore('#')
        val pairs = uri.rawQuery
            ?.split('&')
            ?.filter { it.isNotEmpty() }
            ?.map {
                val pieces = it.split('=', limit = 2)
                pieces[0] to pieces.getOrElse(1) { "" }
            }
            .orEmpty()
        val filtered = filterCacheKeyQueryPairs(pairs)
        val query = if (filtered.isEmpty()) {
            null
        } else {
            val ordered = if (filtered.map { it.first.lowercase(Locale.US) }.distinct().size == filtered.size) {
                filtered.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            } else {
                filtered
            }
            ordered.joinToString("&") { (name, value) -> if (value.isEmpty()) name else "$name=$value" }
        }
        val port = when {
            uri.scheme.equals("https", ignoreCase = true) && uri.port == 443 -> -1
            uri.scheme.equals("http", ignoreCase = true) && uri.port == 80 -> -1
            else -> uri.port
        }
        return runCatching {
            URI(
                uri.scheme?.lowercase(Locale.US),
                uri.userInfo,
                uri.host?.lowercase(Locale.US),
                port,
                uri.rawPath,
                query,
                null,
            ).toASCIIString()
        }.getOrDefault(normalized.substringBefore('#'))
    }

    private fun filterCacheKeyQueryPairs(pairs: List<Pair<String, String>>): List<Pair<String, String>> {
        val names = pairs.map { it.first.lowercase(Locale.US) }
        val signedStorage = names.any { name ->
            name.startsWith("x-amz-") ||
                name.startsWith("x-goog-") ||
                name.startsWith("x-oss-") ||
                name.startsWith("x-cos-") ||
                name in setOf("signature", "awsaccesskeyid", "key-pair-id", "policy")
        }
        val azureSas = "sv" in names && "se" in names && "sig" in names
        return pairs.filterNot { (name, _) -> isNonResourceQueryParameter(name, signedStorage, azureSas) }
    }

    private fun isNonResourceQueryParameter(
        name: String,
        signedStorage: Boolean,
        azureSas: Boolean,
    ): Boolean {
        val normalized = name.lowercase(Locale.US)
        if (normalized.startsWith("utm_") ||
            normalized in setOf(
                "_hsenc", "_hsmi", "dclid", "fbclid", "gbraid", "gclid", "igshid",
                "mc_cid", "mc_eid", "mkt_tok", "msclkid", "wbraid", "yclid",
            )
        ) {
            return true
        }
        if (signedStorage &&
            (normalized.startsWith("x-amz-") ||
                normalized.startsWith("x-goog-") ||
                normalized.startsWith("x-oss-") ||
                normalized.startsWith("x-cos-") ||
                normalized in setOf("awsaccesskeyid", "expires", "key-pair-id", "policy", "signature", "x-id"))
        ) {
            return true
        }
        return azureSas && normalized in setOf(
            "se", "sig", "skoid", "sks", "skt", "sktid", "ske", "skv", "sp", "spr", "sr", "st", "sv",
        )
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
        private const val DEFAULT_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L
        private const val METADATA_EXTENSION = ".meta.json"

        private val HTTP_DATE_FORMATS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
        )
        private val imageKeys = listOf("images")
    }
}
