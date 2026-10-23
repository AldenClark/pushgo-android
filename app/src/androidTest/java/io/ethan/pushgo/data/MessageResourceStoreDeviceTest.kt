package io.ethan.pushgo.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.MessageEntity
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTTP request counts, platform image headers and production Room/file publication.
 * The explicit test connection maps synthetic HTTPS identities to a local cleartext fixture;
 * production HTTPS trust, app permissions and real servers are not modified or claimed tested.
 */
@RunWith(AndroidJUnit4::class)
class MessageResourceStoreDeviceTest {
    private lateinit var context: Context
    private lateinit var db: PushGoDatabase
    private lateinit var store: MessageImageStore
    private lateinit var fixture: Fixture
    private var now = System.currentTimeMillis() / 1000 * 1000
    private lateinit var root: File
    private val url = "https://images.invalid/image?signature=synthetic-a"
    private val imageA get() = png(0xffff0000.toInt())
    private val imageB get() = png(0xff00ff00.toInt())

    @Before fun prepare() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "resource-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = File(root, "db/$name").apply { parentFile?.mkdirs() }
        }
        db = PushGoDatabase.buildForTest(context, "resource.db")
        fixture = Fixture()
        newStore()
        fixture.response = Response(imageA, mapOf("Cache-Control" to "max-age=60", "ETag" to "\"a\""))
    }
    private fun newStore() {
        store = MessageImageStore(context, db, clock = { now }, connectionOpener = { original ->
            (URL("http://127.0.0.1:${fixture.port}${original.file}").openConnection() as HttpURLConnection)
        })
    }
    @After fun finish() { store.close(); db.close(); fixture.close(); ImageAssetMetadataStore.closeForTest(context); root.deleteRecursively() }

    @Test fun freshNewMessageMakesZeroGetAndOldBindingNeverRefreshes() = runBlocking {
        val first = message("one"); save(first)
        val oldPath = requireNotNull(store.acquire(store.imageModel(first, url))).use { requireNotNull(it.file).path }
        val second = message("two"); save(second)
        val secondPath = requireNotNull(store.acquire(store.imageModel(second, url))).use { requireNotNull(it.file).path }
        assertEquals(oldPath, secondPath); assertEquals(1, fixture.requests.size)
        now += 61_000
        fixture.response = Response(imageB, mapOf("Cache-Control" to "max-age=60", "ETag" to "\"b\""))
        val third = message("three"); save(third)
        val newPath = requireNotNull(store.acquire(store.imageModel(third, url))).use { requireNotNull(it.file).path }
        assertNotEquals(oldPath, newPath); assertEquals(2, fixture.requests.size)
        requireNotNull(store.acquire(store.imageModel(first, url))).use { assertEquals(oldPath, it.file?.path); assertArrayEquals(imageA, it.file?.readBytes()) }
        assertEquals(2, fixture.requests.size)
        assertEquals("\"a\"", fixture.requests.last()["if-none-match"])
    }

    @Test fun expired304UpdatesMetadataOnlyWhenOriginalStillExists() = runBlocking {
        val first = message("first"); save(first); store.acquire(store.imageModel(first, url))?.close()
        now += 61_000
        fixture.response = Response(ByteArray(0), mapOf("Cache-Control" to "max-age=120"), 304)
        val second = message("second"); save(second)
        requireNotNull(store.acquire(store.imageModel(second, url))).close()
        assertEquals(2, fixture.requests.size)
        assertEquals("\"a\"", fixture.requests.last()["if-none-match"])
        val third = message("third"); save(third); requireNotNull(store.acquire(store.imageModel(third, url))).close()
        assertEquals(2, fixture.requests.size)
    }

    @Test fun sameBytes200ShareOneOriginalAndMissingFileForNewOwnerIsRefetched() = runBlocking {
        fixture.response = Response(imageA, mapOf("Cache-Control" to "max-age=0", "Last-Modified" to date(now - 60_000)))
        val one = message("one"); save(one)
        val original = requireNotNull(store.acquire(store.imageModel(one, url))).use { requireNotNull(it.file) }
        val two = message("two"); save(two); requireNotNull(store.acquire(store.imageModel(two, url))).use { assertEquals(original, it.file) }
        assertEquals(2, fixture.requests.size); assertEquals(date(now - 60_000), fixture.requests.last()["if-modified-since"])
        assertEquals(1, originals().size)
        assertTrue(original.delete())
        val three = message("three"); save(three); requireNotNull(store.acquire(store.imageModel(three, url))).close()
        assertEquals(3, fixture.requests.size); assertNull(fixture.requests.last()["if-modified-since"])
        assertArrayEquals(imageA, original.readBytes())
    }

    @Test fun noStoreIsVolatileAndNeverCreatesBodyReferenceOrSharedReuse() = runBlocking {
        fixture.response = Response(imageA, mapOf("Cache-Control" to "no-store,max-age=3600"))
        val one = message("one"); save(one)
        repeat(2) { requireNotNull(store.acquire(store.imageModel(one, url))).use { image -> assertNull(image.file); assertArrayEquals(imageA, image.bytes) } }
        assertEquals(2, fixture.requests.size); assertTrue(originals().isEmpty())
        assertEquals(0, scalar("SELECT COUNT(*) FROM message_resource_bindings"))
    }

    @Test fun noCacheAndMustRevalidateFailureCannotBindStaleOrRefreshExpiry() = runBlocking {
        for (directive in listOf("no-cache", "must-revalidate,max-age=0")) {
            val source = "$url&case=$directive"
            fixture.response = Response(imageA, mapOf("Cache-Control" to directive, "ETag" to "\"a\""))
            val one = message("one-$directive"); save(one); requireNotNull(store.acquire(store.imageModel(one, source))).close()
            fixture.response = Response(ByteArray(0), emptyMap(), 503)
            val two = message("two-$directive"); save(two)
            assertNull(store.acquire(store.imageModel(two, source)))
            assertNull(store.acquire(store.imageModel(two, source)))
            assertEquals(0, scalar("SELECT COUNT(*) FROM message_resource_bindings WHERE owner_id='two-$directive'"))
        }
        assertEquals(6, fixture.requests.size)
    }

    @Test fun failureStaleIsDisplayOnlyNotAFinalBindingAndNotFreshness() = runBlocking {
        fixture.response = Response(imageA, mapOf("Cache-Control" to "max-age=0"))
        val one = message("one"); save(one); store.acquire(store.imageModel(one, url))?.close()
        fixture.response = Response(ByteArray(0), emptyMap(), 503)
        val two = message("two"); save(two)
        repeat(2) { requireNotNull(store.acquire(store.imageModel(two, url))).use { assertTrue(it.stale) } }
        assertEquals(3, fixture.requests.size)
        assertEquals(0, scalar("SELECT COUNT(*) FROM message_resource_bindings WHERE owner_id='two'"))
    }

    @Test fun fullSignedUrlScopeAndVaryStarDoNotAlias() = runBlocking {
        val one = message("one"); save(one); store.acquire(store.imageModel(one, url))?.close()
        val two = message("two"); save(two); store.acquire(store.imageModel(two, url.replace("synthetic-a", "synthetic-b")))?.close()
        val other = message("other", server = "other-gateway"); save(other); store.acquire(store.imageModel(other, url))?.close()
        assertEquals(3, fixture.requests.size)
        fixture.response = Response(imageA, mapOf("Cache-Control" to "max-age=60", "Vary" to "*"))
        for (id in listOf("vary-one", "vary-two")) { val message = message(id); save(message); store.acquire(store.imageModel(message, "$url&vary=1"))?.close() }
        assertEquals(5, fixture.requests.size)
        assertTrue(fixture.requests.all { it["authorization"] == null && it["cookie"] == null })
    }

    @Test fun redirectRetargetCannotValidateNewRepresentationWithOldTargetsEtag() = runBlocking {
        val phase = AtomicInteger(0)
        fixture.route = { target, headers ->
            when {
                target.startsWith("/alias") -> Response(ByteArray(0), mapOf("Location" to "https://images.invalid/target-${if (phase.get() == 0) "a" else "b"}"), 307)
                target == "/target-a" -> Response(imageA, mapOf("ETag" to "\"same-opaque-tag\"", "Cache-Control" to "max-age=60"))
                target == "/target-b" && headers["if-none-match"] == "\"same-opaque-tag\"" -> Response(ByteArray(0), emptyMap(), 304)
                else -> Response(imageB, mapOf("ETag" to "\"same-opaque-tag\"", "Cache-Control" to "max-age=60"))
            }
        }
        val one = message("one"); save(one)
        val old = requireNotNull(store.acquire(store.imageModel(one, "https://images.invalid/alias"))).use { requireNotNull(it.file) }
        val same = message("same-target"); save(same)
        requireNotNull(store.acquire(store.imageModel(same, "https://images.invalid/alias"))).close()
        assertEquals("Final target freshness cannot make an unvalidated redirect alias fresh", 4, fixture.requests.size)
        phase.incrementAndGet()
        val two = message("two"); save(two)
        val fresh = requireNotNull(store.acquire(store.imageModel(two, "https://images.invalid/alias"))).use { requireNotNull(it.file) }
        assertNotEquals(old, fresh); assertArrayEquals(imageA, old.readBytes()); assertArrayEquals(imageB, fresh.readBytes())
        assertEquals(6, fixture.requests.size)
        assertTrue(fixture.requests.filter { it["@target"] in listOf("/alias", "/target-b") }.all { it["if-none-match"] == null })
    }

    @Test fun ageDateAndVaryUseActualRequestConditions() = runBlocking {
        fixture.response = Response(imageA, mapOf("Cache-Control" to "max-age=60", "Age" to "60", "Vary" to "Accept,Accept-Encoding"))
        for (id in listOf("one", "two")) { val message = message(id); save(message); store.acquire(store.imageModel(message, url))?.close() }
        assertEquals(2, fixture.requests.size)
        assertEquals("identity", fixture.requests.first()["accept-encoding"])
        assertEquals(fixture.requests.first()["accept"], fixture.requests.last()["accept"])
        fixture.response = Response(imageA, mapOf("Cache-Control" to "max-age=0,max-age=60", "ETag" to "\"duplicate-age\""))
        for (id in listOf("duplicate-one", "duplicate-two")) { val message = message(id); save(message); store.acquire(store.imageModel(message, "$url&duplicate-age=1"))?.close() }
        assertEquals(4, fixture.requests.size)
        assertEquals("\"duplicate-age\"", fixture.requests.last()["if-none-match"])
    }

    @Test fun sharedReferencesDeleteAndLateDownloadCannotResurrectBinding() = runBlocking {
        val one = message("one"); val two = message("two"); save(one); save(two)
        val original = requireNotNull(store.acquire(store.imageModel(one, url))).use { requireNotNull(it.file) }
        store.acquire(store.imageModel(two, url))?.close()
        db.messageDao().deleteById(one.id); original.setLastModified(now - 25 * 60 * 60 * 1000)
        store.purgeExpired(); assertTrue(original.exists())
        db.messageDao().deleteById(two.id); store.purgeExpired(); assertFalse(original.exists())
        val late = message("late"); save(late)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.entered = entered; fixture.release = release
        coroutineScope {
            val pending = async(Dispatchers.IO) { store.acquire(store.imageModel(late, "$url&late=1")) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); db.messageDao().deleteById(late.id); release.countDown()
            assertNull(pending.await())
        }
        assertEquals(0, scalar("SELECT COUNT(*) FROM message_resource_bindings"))
    }

    @Test fun laterProvenOriginDoesNotChangeHistoricalBindingOrRequireGet() = runBlocking {
        val old = message("unknown")
        db.messageDao().insert(MessageEntity.fromModel(old))
        val file = requireNotNull(store.acquire(store.imageModel(old, url))).use { requireNotNull(it.file) }
        db.recordResourceOrigin(old.id, old.receivedAt.toEpochMilli(), requireNotNull(InboundDeliveryScope.create("https://gateway.invalid", "device")))
        fixture.response = Response(imageB, mapOf("Cache-Control" to "max-age=60"))
        requireNotNull(store.acquire(store.imageModel(old, url))).use { assertEquals(file, it.file); assertArrayEquals(imageA, it.file?.readBytes()) }
        assertEquals(1, fixture.requests.size)
    }

    @Test fun cancellingOneSharedConsumerDoesNotCancelAnotherOrDuplicateGet() = runBlocking {
        val one = message("one"); val two = message("two"); save(one); save(two)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.entered = entered; fixture.release = release
        coroutineScope {
            val first = async(Dispatchers.IO) { store.acquire(store.imageModel(one, url)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) { store.acquire(store.imageModel(two, url)) }
            withTimeout(5000) {
                while (true) {
                    val field = MessageImageStore::class.java.getDeclaredField("flights").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST") val values = field.get(store) as Map<String, Any>
                    val subscribers = synchronized(values) { values.values.firstOrNull()?.let { flight -> flight.javaClass.getDeclaredField("subscribers").apply { isAccessible = true }.getInt(flight) } ?: 0 }
                    if (subscribers == 2) break
                    yield()
                }
            }
            first.cancel(); first.join(); release.countDown()
            requireNotNull(second.await()).close()
        }
        assertEquals(1, fixture.requests.size)
        assertEquals(1, scalar("SELECT COUNT(*) FROM message_resource_bindings"))
    }

    @Test fun resourceFailureLeavesAcceptedBodyAndExplicitAckEligibilityIntact() = runBlocking {
        val identity = requireNotNull(ProviderAckIdentity.create(ProviderAckDestination("https://gateway.invalid/prefix", "device"), ProviderAckContract.V2_BATCH, "provider_pull"))
        val repository = MessageRepository(db, db.messageDao(), db.messageChannelStatsDao(), db.messageMetadataIndexDao(), db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.thingHeadDao(), db.thingSubMessageDao(), db.pendingThingMessageDao())
        val ledger = InboundDeliveryLedgerRepository(db, db.inboundDeliveryLedgerDao(), db.inboundDeliveryAckOutboxDao(), db.legacyProviderIngressDao())
        val message = message("persisted").let { it.copy(rawPayloadJson = JSONObject(it.rawPayloadJson).put("delivery_id", "persisted-delivery").put("op_id", "persisted-op").toString()) }
        assertTrue(repository.insertIncoming(message, identity))
        assertTrue(ledger.shouldAck("persisted-delivery", identity))
        fixture.response = Response("broken".toByteArray(), emptyMap())
        assertNull(store.acquire(store.imageModel(message, url)))
        assertEquals(message.body, repository.getById(message.id)?.body)
        assertTrue(ledger.shouldAck("persisted-delivery", identity))
        assertTrue(ledger.enqueueAcks(listOf("persisted-delivery"), identity))
        assertEquals(1, ledger.loadPendingAcks().size)
    }

    @Test fun sameIdAndTimestampRecreatedDuringDownloadCannotAdoptOldCallback() = runBlocking {
        val original = message("same"); save(original)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.entered = entered; fixture.release = release
        coroutineScope {
            val pending = async(Dispatchers.IO) { store.acquire(store.imageModel(original, url)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            db.messageDao().deleteById(original.id)
            save(original)
            release.countDown()
            assertNull(pending.await())
        }
        assertEquals(0, scalar("SELECT COUNT(*) FROM message_resource_bindings"))
        fixture.entered = null; fixture.release = null
        requireNotNull(store.acquire(store.imageModel(original, url))).close()
        assertEquals(1, scalar("SELECT COUNT(*) FROM message_resource_bindings"))
    }

    @Test fun changedValidImageAtHashPathIsNotShownAsHistoricalContent() = runBlocking {
        val one = message("one"); save(one)
        val original = requireNotNull(store.acquire(store.imageModel(one, url))).use { requireNotNull(it.file) }
        original.writeBytes(imageB)
        assertNull(store.acquire(store.imageModel(one, url)))
        assertEquals(1, fixture.requests.size)
    }

    @Test fun legacyMessageLocalFileSurvivesExpiryReopenAndDoesNotBecomeUrlReuse() = runBlocking {
        val legacy = File(context.cacheDir, "message_images/original/old.png").apply { parentFile?.mkdirs(); writeBytes(imageA) }
        val one = message("one", local = legacy.path); save(one)
        val original = requireNotNull(store.acquire(store.imageModel(one, url))).use { requireNotNull(it.file) }
        assertEquals(0, fixture.requests.size); assertTrue(legacy.exists()); assertTrue(original.exists())
        store.close(); db.close(); db = PushGoDatabase.buildForTest(context, "resource.db"); newStore()
        requireNotNull(store.acquire(store.imageModel(one, url))).use { assertEquals(original, it.file) }
        val two = message("two"); save(two); store.acquire(store.imageModel(two, url))?.close()
        assertEquals(1, fixture.requests.size)
    }

    @Test fun schema35CopyMigratesWithoutRemovingBodyOrLegacyOriginal() = runBlocking {
        store.close(); db.close()
        val legacy = File(context.cacheDir, "message_images/original/old-migration.png").apply { parentFile?.mkdirs(); writeBytes(imageA) }
        val old = message("migration", local = legacy.path)
        // Explicit schema-fixture copy, not a claim of running an old APK or a user DB.
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("35.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath("v35-copy.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO messages(id,message_id,title,body,is_read,received_at,raw_payload_json,status,body_preview,entity_type,server_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(old.id, old.messageId, old.title, old.body, 0, old.receivedAt.toEpochMilli(), old.rawPayloadJson, "NORMAL", "", "message", old.serverId))
            sql.version = 35
        }
        db = PushGoDatabase.buildForTest(context, "v35-copy.db"); newStore()
        assertEquals(old.body, db.messageDao().getById(old.id)?.body)
        assertEquals(36, scalar("PRAGMA user_version"))
        requireNotNull(store.acquire(store.imageModel(old, url))).use { assertArrayEquals(imageA, it.file?.readBytes()) }
        assertTrue(legacy.exists()); assertEquals(0, fixture.requests.size)
        assertEquals(1, scalar("SELECT COUNT(*) FROM message_resource_owners WHERE owner_id='migration'"))
    }

    @Test fun unknownLengthOversizeCorruptAndHugePixelHeadersAreRejectedBeforePublication() = runBlocking {
        fixture.response = Response(ByteArray((MessageImageStore.MAX_DOWNLOAD_BYTES + 1).toInt()) { 1 }, emptyMap(), chunked = true)
        val one = message("one"); save(one); assertNull(store.acquire(store.imageModel(one, url)))
        fixture.response = Response("not an image".toByteArray(), emptyMap())
        val two = message("two"); save(two); assertNull(store.acquire(store.imageModel(two, "$url&corrupt=1")))
        val enormous = validPng(33_000, 1)
        assertTrue(enormous.size < MessageImageStore.MAX_DOWNLOAD_BYTES)
        assertEquals(33_000, java.nio.ByteBuffer.wrap(enormous, 16, 4).int)
        fixture.response = Response(enormous, emptyMap())
        val three = message("three"); save(three)
        assertNull(store.acquire(store.imageModel(three, "$url&dimension=1")))
        assertTrue(originals().isEmpty()); assertEquals(0, scalar("SELECT COUNT(*) FROM message_resource_bindings"))
    }

    private suspend fun save(message: PushMessage) {
        db.messageDao().insert(MessageEntity.fromModel(message))
        db.recordResourceOrigin(message.id, message.receivedAt.toEpochMilli(), requireNotNull(InboundDeliveryScope.create("https://${message.serverId}.invalid", "synthetic-device")))
    }
    private fun message(id: String, server: String = "gateway", local: String? = null) = PushMessage(id, id, "Image", "saved independently of resource", null, null, false, Instant.ofEpochMilli(now), JSONObject().put("entity_type", "message").put("images", url).apply { local?.let { put(MessageImageStore.KEY_IMAGE_LOCAL_PATH, it) } }.toString(), MessageStatus.NORMAL, null, null, server)
    private fun originals(): List<File> = File(context.filesDir, "message_resources/original").listFiles().orEmpty().filter { !it.name.startsWith('.') }
    private fun scalar(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }
    private fun png(color: Int): ByteArray = ByteArrayOutputStream().use { out -> Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(color); it.compress(Bitmap.CompressFormat.PNG, 100, out); it.recycle() }; out.toByteArray() }
    private fun validPng(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val data = java.io.DataOutputStream(output)
        data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        fun chunk(type: String, payload: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            data.writeInt(payload.size); data.write(name); data.write(payload)
            val crc = java.util.zip.CRC32().apply { update(name); update(payload) }
            data.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream().also { bytes -> java.io.DataOutputStream(bytes).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 6, 0, 0, 0)) } }.toByteArray()
        chunk("IHDR", header)
        val compressed = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(compressed).use { deflater -> val row = ByteArray(width * 4 + 1); repeat(height) { deflater.write(row) } }
        chunk("IDAT", compressed.toByteArray()); chunk("IEND", ByteArray(0))
        return output.toByteArray()
    }
    private fun date(value: Long) = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(value))
    private data class Response(val bytes: ByteArray, val headers: Map<String, String>, val code: Int = 200, val chunked: Boolean = false)
    private inner class Fixture : java.io.Closeable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val requests = CopyOnWriteArrayList<Map<String, String>>()
        @Volatile var response = Response(ByteArray(0), emptyMap())
        @Volatile var route: ((String, Map<String, String>) -> Response)? = null
        @Volatile var entered: CountDownLatch? = null
        @Volatile var release: CountDownLatch? = null
        private val thread = Thread {
            while (!server.isClosed) {
                try { server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    val target = requestLine.split(' ').getOrElse(1) { "/" }
                    val headers = mutableMapOf<String, String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; val split = line.indexOf(':'); if (split > 0) headers[line.substring(0, split).lowercase(Locale.ROOT)] = line.substring(split + 1).trim() }
                    requests += headers + ("@target" to target)
                    entered?.countDown(); release?.await(5, TimeUnit.SECONDS)
                    val value = route?.invoke(target, headers) ?: response
                    val output = socket.getOutputStream()
                    val head = buildString {
                        append("HTTP/1.1 ${value.code} Result\r\nDate: ${date(now)}\r\nConnection: close\r\n")
                        value.headers.forEach { (key, content) -> append("$key: $content\r\n") }
                        if (value.chunked) append("Transfer-Encoding: chunked\r\n") else append("Content-Length: ${value.bytes.size}\r\n")
                        append("\r\n")
                    }
                    output.write(head.toByteArray())
                    if (value.chunked) { value.bytes.asSequence().chunked(16384).forEach { chunk -> output.write("${chunk.size.toString(16)}\r\n".toByteArray()); output.write(chunk.toByteArray()); output.write("\r\n".toByteArray()) }; output.write("0\r\n\r\n".toByteArray()) } else output.write(value.bytes)
                    output.flush()
                } } catch (_: Exception) { if (server.isClosed) break }
            }
        }.apply { isDaemon = true; name = "resource-http-fixture"; start() }
        override fun close() { release?.countDown(); server.close(); thread.join(2000) }
    }
}
