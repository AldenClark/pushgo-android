package io.ethan.pushgo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import androidx.room.withTransaction
import io.ethan.pushgo.data.db.MessageResourceBinding
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageListItem
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.util.UrlValidators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Immutable acquired originals. Room owns business references; HTTP rows are only lookups. */
class MessageImageStore internal constructor(
    context: Context,
    private val database: PushGoDatabase? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectionOpener: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : java.io.Closeable {
    data class ImageRefs(val remoteUrl: String?, val originalPath: String?, val thumbnailPath: String?)
    data class ImageRequestModel(val url: String, val ownerId: String? = null, val ownerVersion: String? = null, val ownerScope: String = "public", val thumbnail: Boolean = false, val ownerSelector: String? = null, internal val generation: String? = null) {
        override fun toString(): String = "ImageRequestModel(owned=${ownerId != null},thumbnail=$thumbnail)"
    }
    class AcquiredImage(val file: File?, val bytes: ByteArray?, val mimeType: String?, val isAnimated: Boolean, val stale: Boolean, val width: Int, val height: Int, val frameCount: Int?, val animationAllowed: Boolean = true, private val onClose: () -> Unit = {}) : java.io.Closeable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) onClose() }
    }
    private data class Obtained(val file: File?, val bytes: ByteArray?, val hash: String?, val stale: Boolean, val noStoreOwner: String? = null, val lease: java.io.Closeable? = null)
    private data class Flight(val result: kotlinx.coroutines.Deferred<Obtained?>, var subscribers: Int, val sharedLease: java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>)
    private data class OpenedConnection(val connection: HttpURLConnection, val logicalTargetHash: String, val redirected: Boolean)
    private data class Info(val width: Int, val height: Int, val mimeType: String?, val animated: Boolean, val frames: Int?, val animationAllowed: Boolean)
    private val appContext = context.applicationContext
    private val originalDir = File(appContext.filesDir, "message_resources/original").apply { mkdirs() }
    private val thumbnailDir = File(appContext.cacheDir, "message_resources/thumbnail").apply { mkdirs() }
    private val legacyDir = File(appContext.cacheDir, "message_images/original")
    private val metadataStore = ImageAssetMetadataStore.get(appContext)
    private val flights = mutableMapOf<String, Flight>()
    private val activeReads = mutableMapOf<String, Int>()
    private val fileAccess = Any()
    private var activeAcquisitions = 0
    private var transientBytes = 0L
    private var gcDirectory: java.nio.file.DirectoryStream<java.nio.file.Path>? = null
    private var gcIterator: Iterator<java.nio.file.Path>? = null
    private val networkSlots = Semaphore(4)
    private val headerSlots = java.util.concurrent.Semaphore(2)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { if (database != null) synchronized(instances) { instances[appContext] = this } }

    fun imageModel(message: PushMessage, url: String, thumbnail: Boolean = false) = ImageRequestModel(url, message.id, resourceOwnerVersion(message.serverId, message.receivedAt.toEpochMilli()), message.serverId ?: "local", thumbnail, resourceOwnerSelector(message.rawPayloadJson))
    fun imageModel(message: MessageListItem, url: String, thumbnail: Boolean = false) = ImageRequestModel(url, message.id, resourceOwnerVersion(message.serverId, message.receivedAt.toEpochMilli()), message.serverId ?: "local", thumbnail)

    fun resolveImageRefs(rawPayloadJson: String): ImageRefs {
        val payload = parsePayload(rawPayloadJson)
        fun existing(key: String) = payload?.optString(key)?.takeIf { path -> File(path).isFile && safeLocalFile(File(path)) }
        return ImageRefs(resolveRemoteImageUrls(payload).firstOrNull(), existing(KEY_IMAGE_LOCAL_PATH), existing(KEY_IMAGE_THUMBNAIL_LOCAL_PATH))
    }
    fun resolveListImageModels(message: MessageListItem, maxItems: Int = 4): List<Any> = resolvedResourceUrls(message.listPayloadJson).take(maxItems).map { imageModel(message, it, true) }
    fun resolveListImageModels(message: PushMessage, maxItems: Int = 4): List<Any> = resolvedResourceUrls(message.rawPayloadJson).take(maxItems).map { imageModel(message, it, true) }
    fun resolveDetailImageModels(message: PushMessage): List<Any> = resolvedResourceUrls(message.rawPayloadJson).map { imageModel(message, it) }
    fun resolveRemoteImageUrl(rawPayloadJson: String, preferredUrl: String?): String? = resolveRemoteImageUrls(rawPayloadJson, preferredUrl?.let(::listOf) ?: emptyList()).firstOrNull()
    fun resolveRemoteImageUrls(rawPayloadJson: String, preferredUrls: List<String> = emptyList()): List<String> = linkedSetOf<String>().apply {
        preferredUrls.forEach { UrlValidators.normalizeHttpsUrl(it)?.let(::add) }; addAll(resolveRemoteImageUrls(parsePayload(rawPayloadJson)))
    }.toList()
    private fun resolvedResourceUrls(raw: String): List<String> {
        val urls = resolveRemoteImageUrls(raw)
        if (urls.isNotEmpty()) return urls
        return resolveImageRefs(raw).originalPath?.let { listOf(localResourceIdentity(it)) }.orEmpty()
    }
    private fun localResourceIdentity(path: String): String = "pushgo-local:" + sha256(path.toByteArray())
    private fun parsePayload(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()

    /** A frozen binding wins before HTTP freshness: reopening history never refreshes its version. */
    suspend fun acquire(request: ImageRequestModel): AcquiredImage? {
        var pending: AcquiredImage? = null
        var handedOff = false
        try {
            val acquired = withContext(Dispatchers.IO) { acquireOnIo(request).also { pending = it } }
            handedOff = true
            return acquired
        } finally {
            // withContext can reject an already-created result when the caller is cancelled
            // before its dispatcher resumes. That result never reaches the decoder's close.
            if (!handedOff) pending?.close()
        }
    }

    private suspend fun acquireOnIo(request: ImageRequestModel): AcquiredImage? {
        val model = freezeOwner(request) ?: return null
        synchronized(fileAccess) { if (activeAcquisitions >= MAX_CONSUMERS) return null; activeAcquisitions++ }
        var operationLease: java.io.Closeable? = null
        try {
        val normalized = if (model.ownerId != null && model.url.startsWith("pushgo-local:")) model.url else normalizeResourceUrl(model.url) ?: return null
        val key = requestKey(model.ownerScope, normalized)
        val bindingKey = requestKey("binding", normalized)
        bound(model, bindingKey)?.let { return display(it, model.thumbnail, false) }
        if (model.ownerId != null && model.ownerVersion != null && database?.messageResourceDao()?.find(model.ownerId, requireNotNull(model.generation), bindingKey) != null) return null
        migrateLegacyOwner(model, bindingKey)?.let { return display(it, model.thumbnail, false) }
        if (!ownerCurrent(model) || normalized.startsWith("pushgo-local:")) return null
        val consumer = model.ownerId?.let { "$it:${model.generation}" } ?: "unbound:${UUID.randomUUID()}"
        val result = sharedObtain(key, normalized, consumer) ?: return null
        // A no-store response belongs only to the initiating consumer, even when GET began shared.
        operationLease = result.lease
        val actual = if (result.noStoreOwner != null && result.noStoreOwner != consumer) networkSlots.withPermit { obtain(key, normalized, consumer) } else result
        operationLease = actual?.lease
        if (actual == null || !ownerCurrent(model)) return null
        if (actual.hash != null && !actual.stale && model.ownerId != null && model.ownerVersion != null) {
            val db = database ?: return null
            val selected = db.withTransaction {
                if (!ownerCurrent(model)) return@withTransaction null
                db.messageResourceDao().bind(MessageResourceBinding(model.ownerId, requireNotNull(model.generation), bindingKey, actual.hash, clock()))
                db.messageResourceDao().find(model.ownerId, requireNotNull(model.generation), bindingKey)?.contentHash
            } ?: return null
            return display(File(originalDir, selected), model.thumbnail, false)
        }
        return actual.file?.let { display(it, model.thumbnail, actual.stale) } ?: actual.bytes?.let { bytes ->
            val info = imageInfo(bytes) ?: return null
            val lease = retainTransient(bytes.size.toLong()) ?: return null
            AcquiredImage(null, bytes, info.mimeType, info.animated, false, info.width, info.height, info.frames, info.animationAllowed) { lease.close() }
        }
        } finally { operationLease?.close(); synchronized(fileAccess) { activeAcquisitions-- } }
    }

    private suspend fun freezeOwner(model: ImageRequestModel): ImageRequestModel? {
        if (model.ownerId == null) return model
        val rows = database?.messageResourceDao()?.owners(model.ownerId).orEmpty().filter {
            resourceOwnerVersion(it.serverId, it.receivedAt) == model.ownerVersion &&
                (model.ownerSelector == null || resourceOwnerSelector(it.rawPayloadJson) == model.ownerSelector)
        }
        val owner = rows.singleOrNull() ?: return null
        return model.copy(generation = owner.generation, ownerScope = owner.originScope ?: "owner:${owner.generation}")
    }

    private suspend fun bound(model: ImageRequestModel, key: String): File? {
        val db = database ?: return null
        val id = model.ownerId ?: return null
        val version = model.generation ?: return null
        if (!ownerCurrent(model)) return null
        val ref = db.messageResourceDao().find(id, version, key) ?: return null
        return File(originalDir, ref.contentHash).takeIf { usable(it) }
    }
    private suspend fun ownerCurrent(model: ImageRequestModel): Boolean {
        if (model.ownerId == null) return true
        val rows = database?.messageResourceDao()?.owners(model.ownerId) ?: return false
        return rows.any { it.generation == model.generation && resourceOwnerVersion(it.serverId, it.receivedAt) == model.ownerVersion && (it.originScope ?: "owner:${it.generation}") == model.ownerScope }
    }
    private suspend fun migrateLegacyOwner(model: ImageRequestModel, key: String): File? {
        val db = database ?: return null
        val id = model.ownerId ?: return null
        val version = model.generation ?: return null
        val row = db.messageResourceDao().owners(id).singleOrNull { it.generation == version } ?: return null
        if (!ownerCurrent(model)) return null
        val refs = resolveImageRefs(row.rawPayloadJson)
        if (refs.remoteUrl != model.url && !(refs.remoteUrl == null && refs.originalPath?.let(::localResourceIdentity) == model.url)) return null
        val source = refs.originalPath?.let(::File)?.takeIf { usable(it) && safeLocalFile(it) } ?: return null
        val bytes = source.readBytes()
        if (!validFirstFrame(bytes)) return null
        val hash = sha256(bytes)
        val lease = retainHash(hash)
        return try {
            val published = publish(bytes, hash)
            db.withTransaction {
                if (!ownerCurrent(model)) return@withTransaction null
                db.messageResourceDao().bind(MessageResourceBinding(id, version, key, hash, clock()))
                published
            }
        } finally { lease.close() }
    }

    private suspend fun sharedObtain(key: String, url: String, owner: String): Obtained? {
        val flight = synchronized(flights) {
            flights[key]?.also { it.subscribers++ } ?: run {
                if (flights.size >= MAX_PENDING_DOWNLOADS) return null
                val sharedLease = java.util.concurrent.atomic.AtomicReference<java.io.Closeable?>()
                Flight(ioScope.async {
                    val result = networkSlots.withPermit { obtain(key, url, owner) }
                    sharedLease.set(result?.lease)
                    synchronized(flights) { if (flights[key]?.sharedLease !== sharedLease) sharedLease.getAndSet(null)?.close() }
                    result
                }, 1, sharedLease).also { flights[key] = it }
            }
        }
        try {
            val result = flight.result.await() ?: return null
            return result.copy(lease = result.file?.let { retainHash(it.name) })
        } finally {
            synchronized(flights) {
                flight.subscribers--
                if (flight.subscribers == 0) {
                    if (!flight.result.isCompleted) flight.result.cancel()
                    if (flights[key] === flight) flights.remove(key)
                    flight.sharedLease.getAndSet(null)?.close()
                }
            }
        }
    }

    private suspend fun obtain(key: String, url: String, owner: String, unconditionalRetry: Boolean = false): Obtained? {
        val previous = if (unconditionalRetry) null else metadataStore.representation(key)?.takeIf { candidate ->
            val sameTarget = jsonHeaders(candidate.headersJson)[FINAL_TARGET_KEY] == sha256(url.toByteArray())
            if (!sameTarget) metadataStore.forgetRepresentation(key)
            sameTarget
        }
        val oldLease = previous?.let { retainHash(it.hash) }
        val oldFile = previous?.let { File(originalDir, it.hash) }?.takeIf(::usable)
        val oldHeaders = previous?.headersJson?.let(::jsonHeaders).orEmpty()
        val oldPolicy = previous?.let { ImageHttpPolicy.from(oldHeaders, it.requestAt, it.responseAt) }
        if (oldFile != null && oldPolicy?.fresh(clock()) == true && reusableVary(oldPolicy)) {
            return obtainedFile(oldFile, previous.hash, false).also { oldLease?.close() }
        }
        val requestAt = clock()
        val deadline = System.nanoTime() + 45_000_000_000L
        var connection: HttpURLConnection? = null
        try {
            val opened = openConnection(url, oldHeaders.takeIf { oldFile != null }.orEmpty(), deadline) ?: return stale(oldFile, oldPolicy)
            connection = opened.connection
            val responseAt = clock()
            val responseHeaders = connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase(Locale.ROOT) }.mapValues { it.value.joinToString(", ") }.toMutableMap().apply { put(FINAL_TARGET_KEY, opened.logicalTargetHash) }
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                if (oldFile == null || previous == null) {
                    metadataStore.forgetRepresentation(key)
                    connection.disconnect()
                    return if (unconditionalRetry) null else obtain(key, url, owner, true)
                }
                val merged = oldHeaders.toMutableMap().apply { putAll(responseHeaders); if ("age" !in responseHeaders) remove("age") }
                val policy = ImageHttpPolicy.from(merged, requestAt, responseAt)
                if (policy.noStore) { metadataStore.forgetRepresentation(key); return null }
                if (!opened.redirected && reusableVary(policy)) metadataStore.putRepresentation(ImageAssetMetadataStore.Representation(key, previous.hash, JSONObject(merged).toString(), requestAt, responseAt)) else metadataStore.forgetRepresentation(key)
                return obtainedFile(oldFile, previous.hash, false)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return stale(oldFile, oldPolicy)
            if (connection.contentEncoding?.trim()?.lowercase(Locale.ROOT)?.let { it != "identity" } == true) return null
            if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(minOf(connection.contentLength.coerceAtLeast(0), 64 * 1024))
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() >= deadline) throw java.net.SocketTimeoutException("Image request deadline")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_DOWNLOAD_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            imageInfo(bytes) ?: return null
            if (!validFirstFrame(bytes)) return null
            val policy = ImageHttpPolicy.from(responseHeaders, requestAt, responseAt)
            if (policy.noStore) {
                metadataStore.forgetRepresentation(key)
                return Obtained(null, bytes, null, false, owner)
            }
            val hash = sha256(bytes)
            val publishLease = retainHash(hash)
            try {
            val file = publish(bytes, hash)
            if (!opened.redirected && reusableVary(policy)) metadataStore.putRepresentation(ImageAssetMetadataStore.Representation(key, hash, JSONObject(responseHeaders).toString(), requestAt, responseAt)) else metadataStore.forgetRepresentation(key)
            return obtainedFile(file, hash, false)
            } finally { publishLease.close() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return stale(oldFile, oldPolicy) }
        finally { connection?.disconnect(); oldLease?.close() }
    }
    private fun retainTransient(size: Long): java.io.Closeable? {
        synchronized(fileAccess) { if (transientBytes + size > MAX_TRANSIENT_BYTES) return null; transientBytes += size }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return java.io.Closeable { if (closed.compareAndSet(false, true)) synchronized(fileAccess) { transientBytes -= size } }
    }
    private fun retainHash(hash: String): java.io.Closeable {
        synchronized(fileAccess) { synchronized(activeReads) { activeReads[hash] = (activeReads[hash] ?: 0) + 1 } }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return java.io.Closeable { if (closed.compareAndSet(false, true)) synchronized(activeReads) {
            val count = (activeReads[hash] ?: 1) - 1; if (count <= 0) activeReads.remove(hash) else activeReads[hash] = count
        } }
    }
    private fun obtainedFile(file: File, hash: String?, stale: Boolean) = Obtained(file, null, hash, stale, lease = retainHash(file.name))
    private fun reusableVary(policy: ImageHttpPolicy): Boolean = policy.vary.all { vary -> REQUEST_HEADERS.keys.any { it.equals(vary, true) } }
    private fun stale(file: File?, policy: ImageHttpPolicy?): Obtained? = if (file != null && policy?.mayShowStale() == true) obtainedFile(file, null, true) else null
    private fun openConnection(url: String, validators: Map<String, String>, deadline: Long): OpenedConnection? {
        var current = URL(url)
        var redirected = false
        repeat(4) {
            if (normalizeResourceUrl(current.toExternalForm()) == null) return null
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (remaining <= 0) return null
            val connection = connectionOpener(current).apply {
                connectTimeout = minOf(10_000, remaining); readTimeout = minOf(15_000, remaining); instanceFollowRedirects = false; useCaches = false
                REQUEST_HEADERS.forEach { (name, value) -> setRequestProperty(name, value) }
                if (validators[FINAL_TARGET_KEY] == sha256(current.toExternalForm().toByteArray())) {
                    validators["etag"]?.let { setRequestProperty("If-None-Match", it) } ?: validators["last-modified"]?.let { setRequestProperty("If-Modified-Since", it) }
                }
            }
            val code = try { connection.responseCode } catch (_: Exception) { connection.disconnect(); return null }
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location"); connection.disconnect()
                if (location.isNullOrBlank()) return null
                current = URL(current, location)
                redirected = true
            } else return OpenedConnection(connection, sha256(current.toExternalForm().toByteArray()), redirected)
        }
        return null
    }
    private fun publish(bytes: ByteArray, hash: String): File {
        val target = File(originalDir, hash)
        if (target.isFile && hashFile(target) == hash) return target
        val temp = File(originalDir, ".${UUID.randomUUID()}.part")
        try {
            FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
            if (!temp.renameTo(target) && !target.isFile) throw java.io.IOException("Image publication failed")
        } finally { temp.delete() }
        return target
    }
    private fun validFirstFrame(bytes: ByteArray): Boolean = runCatching {
        headerSlots.acquire()
        try {
            val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val ratio = minOf(1.0, 256.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            bitmap.recycle()
            true
        } finally { headerSlots.release() }
    }.getOrDefault(false)
    private fun imageInfo(bytes: ByteArray): Info? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_DIMENSION || bounds.outHeight > MAX_DIMENSION || bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return null
        val frames = animationFrameCount(bytes, bounds.outMimeType)
        val animated = frames != null
        return Info(bounds.outWidth, bounds.outHeight, bounds.outMimeType, animated, frames,
            (!animated || (frames!! <= MAX_ANIMATION_FRAMES && bounds.outMimeType != "image/png")) && bounds.outWidth.toLong() * bounds.outHeight * 8 <= MAX_ANIMATION_FRAME_BYTES)
    }
    /** Container metadata only. Skip compressed sub-blocks; never decode all frames to count them. */
    private fun animationFrameCount(bytes: ByteArray, mime: String?): Int? {
        fun unsigned(index: Int) = bytes[index].toInt() and 255
        fun little(index: Int): Long = (0..3).fold(0L) { value, offset -> value or (unsigned(index + offset).toLong() shl (8 * offset)) }
        fun big(index: Int): Long = (0..3).fold(0L) { value, offset -> (value shl 8) or unsigned(index + offset).toLong() }
        val signature = bytes.take(12).toByteArray().toString(Charsets.ISO_8859_1)
        if (signature.startsWith("GIF87a") || signature.startsWith("GIF89a")) {
            if (bytes.size < 13) return null
            var cursor = 13
            if (unsigned(10) and 128 != 0) cursor += 3 * (1 shl ((unsigned(10) and 7) + 1))
            var frames = 0
            while (cursor < bytes.size) {
                when (unsigned(cursor++)) {
                    0x3b -> return frames.takeIf { it > 1 }
                    0x21 -> { if (cursor >= bytes.size) return null; cursor++ }
                    0x2c -> {
                        if (cursor + 9 > bytes.size) return null
                        val flags = unsigned(cursor + 8); cursor += 9
                        if (flags and 128 != 0) cursor += 3 * (1 shl ((flags and 7) + 1))
                        if (cursor >= bytes.size) return null
                        cursor++ // LZW code size
                        frames++
                        if (frames > MAX_ANIMATION_FRAMES) return frames
                    }
                    else -> return null
                }
                while (cursor < bytes.size) { val length = unsigned(cursor++); if (length == 0) break; cursor += length }
            }
            return frames.takeIf { it > 1 }
        }
        if (mime == "image/png" && bytes.size >= 8) {
            var cursor = 8
            while (cursor + 12 <= bytes.size) {
                val length = big(cursor)
                if (length > bytes.size - cursor - 12L) return null
                val type = bytes.copyOfRange(cursor + 4, cursor + 8).toString(Charsets.ISO_8859_1)
                if (type == "acTL" && length >= 8) return big(cursor + 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().takeIf { it > 1 }
                if (type == "IDAT" || type == "IEND") return null
                cursor += length.toInt() + 12
            }
        }
        if (signature.startsWith("RIFF") && signature.endsWith("WEBP")) {
            var cursor = 12; var frames = 0
            while (cursor + 8 <= bytes.size) {
                val length = little(cursor + 4)
                if (length > bytes.size - cursor - 8L) return null
                if (bytes.copyOfRange(cursor, cursor + 4).toString(Charsets.ISO_8859_1) == "ANMF") frames++
                if (frames > MAX_ANIMATION_FRAMES) return frames
                cursor += length.toInt() + 8 + (length.toInt() and 1)
            }
            return frames.takeIf { it > 1 }
        }
        return null
    }
    private fun usable(file: File): Boolean {
        if (!file.isFile || file.length() !in 1..MAX_DOWNLOAD_BYTES) return false
        return runCatching {
            headerSlots.acquire()
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth <= MAX_DIMENSION && bounds.outHeight <= MAX_DIMENSION && bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS &&
                    (file.parentFile != originalDir || hashFile(file) == file.name)
            } finally { headerSlots.release() }
        }.getOrDefault(false)
    }
    private fun display(file: File, thumbnail: Boolean, stale: Boolean): AcquiredImage? {
        val lease = retainHash(file.name)
        var thumbnailLease: java.io.Closeable? = null
        var transferred = false
        try {
            if (!usable(file)) return null
            headerSlots.acquire()
            try {
                val info = imageInfo(file.readBytes()) ?: return null
                val chosen = if (thumbnail) {
                    // Acquire before checking or publishing the derivative, using the same
                    // lock as capacity eviction. The returned file stays leased until decode closes.
                    thumbnailLease = retainHash(thumbnailFile(file.name).name)
                    thumbnail(file) ?: file
                } else file
                if (chosen == file) { thumbnailLease?.close(); thumbnailLease = null }
                val displayedLease = thumbnailLease
                transferred = true
                return AcquiredImage(chosen, null, if (thumbnail && chosen != file) "image/png" else info.mimeType, !thumbnail && info.animated, stale, info.width, info.height, info.frames, info.animationAllowed) {
                    try { displayedLease?.close() } finally { lease.close() }
                }
            } finally { headerSlots.release() }
        } finally { if (!transferred) { thumbnailLease?.close(); lease.close() } }
    }
    private fun thumbnailFile(hash: String): File = File(thumbnailDir, "$hash-128-v1.png")
    private fun thumbnail(original: File): File? {
        val target = thumbnailFile(original.name)
        if (target.isFile) { metadataStore.touchThumbnail(original.name, target.length(), clock()); return target }
        val bitmap = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(original)) { decoder, info, _ ->
            val ratio = minOf(1.0, 256.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val cropped = centerCropSquare(bitmap); val scaled = cropped.scale(128, 128, true)
        val temp = File(thumbnailDir, ".${UUID.randomUUID()}.part")
        return try {
            FileOutputStream(temp).use { output -> scaled.compress(Bitmap.CompressFormat.PNG, 100, output); output.fd.sync() }
            if (!temp.renameTo(target) && !target.exists()) return null
            metadataStore.touchThumbnail(original.name, target.length(), clock()); target
        } finally { temp.delete(); if (scaled !== cropped) scaled.recycle(); if (cropped !== bitmap) cropped.recycle(); bitmap.recycle() }
    }
    suspend fun metadataFor(request: ImageRequestModel): ImageAssetMetadataStore.Metadata? = withContext(Dispatchers.IO) {
        synchronized(fileAccess) { if (activeAcquisitions >= MAX_CONSUMERS) return@withContext null; activeAcquisitions++ }
        try { runCatching {
            val model = freezeOwner(request) ?: return@withContext null
            val resource = if (model.url.startsWith("pushgo-local:")) model.url else normalizeResourceUrl(model.url) ?: return@withContext null
            val file = bound(model, requestKey("binding", resource)) ?: return@withContext null
            display(file, false, false)?.use { image ->
                ImageAssetMetadataStore.Metadata(model.url, image.width, image.height, image.width.toDouble()/image.height, image.mimeType, image.isAnimated && image.animationAllowed, image.frameCount, file.length(), null, null, file.lastModified())
            }
        }.getOrNull() } finally { synchronized(fileAccess) { activeAcquisitions-- } }
    }
    /** HTTP expiry never reclaims an original; a URL lookup alone is not a business reference. */
    suspend fun purgeExpired(): Int = withContext(Dispatchers.IO) {
        val db = database ?: return@withContext 0
        var removed = 0
        val candidates = synchronized(fileAccess) {
            if (gcIterator == null) {
                gcDirectory = java.nio.file.Files.newDirectoryStream(originalDir.toPath())
                gcIterator = gcDirectory!!.iterator()
            }
            val batch = mutableListOf<File>()
            val iterator = gcIterator!!
            repeat(64) { if (iterator.hasNext()) batch += iterator.next().toFile() }
            if (!iterator.hasNext()) { gcDirectory?.close(); gcDirectory = null; gcIterator = null }
            batch
        }
        for (file in candidates) {
            if (!file.isFile || clock() - file.lastModified() <= ORPHAN_GRACE_MS) continue
            db.withTransaction {
                if (!db.messageResourceDao().isReferenced(file.name)) synchronized(fileAccess) {
                    if (synchronized(activeReads) { file.name !in activeReads }) {
                        metadataStore.forgetHash(file.name)
                        if (file.delete()) removed++
                    }
                }
            }
        }
        if (metadataStore.thumbnailBytes() > THUMBNAIL_DISK_LIMIT_BYTES) {
            metadataStore.oldestThumbnails(64).forEach { hash ->
                val file = thumbnailFile(hash)
                synchronized(fileAccess) {
                    if (synchronized(activeReads) { file.name !in activeReads } &&
                        (file.delete() || !file.exists())) metadataStore.forgetThumbnail(hash)
                }
            }
        }
        removed
    }
    override fun close() {
        ioScope.cancel()
        synchronized(fileAccess) { gcDirectory?.close(); gcDirectory = null; gcIterator = null }
        synchronized(instances) { if (instances[appContext] === this) instances.remove(appContext) }
    }
    fun clearAll() { /* Referenced originals are released by canonical deletion, not a cache reset. */ }
    private fun safeLocalFile(file: File): Boolean = runCatching { file.canonicalPath.startsWith(legacyDir.canonicalPath + File.separator) || file.canonicalPath.startsWith(originalDir.canonicalPath + File.separator) || file.canonicalPath.startsWith(thumbnailDir.canonicalPath + File.separator) }.getOrDefault(false)
    private fun requestKey(scope: String, url: String): String = sha256((scope + "\u0000" + url + "\u0000" + REQUEST_HEADERS.entries.joinToString("\u0000") { "${it.key}:${it.value}" }).toByteArray())
    private fun normalizeResourceUrl(raw: String): String? = UrlValidators.normalizeHttpsUrl(raw)?.let { value -> runCatching { URL(value).takeIf { it.protocol == "https" && it.host.isNotBlank() && it.userInfo == null }?.toExternalForm() }.getOrNull() }
    private fun jsonHeaders(raw: String): Map<String, String> = runCatching { val json = JSONObject(raw); json.keys().asSequence().associateWith { json.getString(it) } }.getOrDefault(emptyMap())
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(16 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
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

    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        if (bitmap.width == bitmap.height) {
            return bitmap
        }
        val size = minOf(bitmap.width, bitmap.height)
        val offsetX = (bitmap.width - size) / 2
        val offsetY = (bitmap.height - size) / 2
        return Bitmap.createBitmap(bitmap, offsetX, offsetY, size, size)
    }

    companion object {
        const val KEY_IMAGE_LOCAL_PATH = "image_local_path"
        const val KEY_IMAGE_THUMBNAIL_LOCAL_PATH = "image_thumbnail_local_path"
        const val MAX_DOWNLOAD_BYTES = 5L * 1024 * 1024
        private const val MAX_DIMENSION = 32768
        private const val MAX_PIXELS = 32L * 1024 * 1024
        private const val MAX_ANIMATION_FRAMES = 256
        private const val MAX_ANIMATION_FRAME_BYTES = 64L * 1024 * 1024
        private const val MAX_PENDING_DOWNLOADS = 16
        private const val MAX_CONSUMERS = 64
        private const val MAX_TRANSIENT_BYTES = 32L * 1024 * 1024
        private const val THUMBNAIL_DISK_LIMIT_BYTES = 64L * 1024 * 1024
        private const val ORPHAN_GRACE_MS = 24L * 60 * 60 * 1000
        private const val FINAL_TARGET_KEY = "@pushgo-final-target"
        private val REQUEST_HEADERS = linkedMapOf("Accept" to "image/*,*/*;q=0.8", "Accept-Encoding" to "identity", "User-Agent" to "PushGoImage/1")
        private val imageKeys = listOf("images")
        private val instances = java.util.WeakHashMap<Context, MessageImageStore>()
        fun shared(context: Context): MessageImageStore = synchronized(instances) { instances[context.applicationContext] ?: MessageImageStore(context).also { instances[context.applicationContext] = it } }
        fun resourceOwnerSelector(rawPayloadJson: String): String {
            val json = runCatching { JSONObject(rawPayloadJson) }.getOrNull()
            val fields = listOf("entity_type", "entity_id", "delivery_id", "op_id", "event_id", "thing_id", "occurred_at", "event_time", "observed_at", "event_head")
            val value = fields.joinToString("\u0000") { json?.optString(it).orEmpty() }
            return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        internal fun originScope(scope: InboundDeliveryScope): String = MessageDigest.getInstance("SHA-256").digest((scope.gatewayUrl + "\u0000" + scope.deviceKey).toByteArray()).joinToString("") { "%02x".format(it) }
        fun resourceOwnerVersion(serverId: String?, receivedAt: Long): String = (serverId ?: "local") + ":" + receivedAt
    }
}
