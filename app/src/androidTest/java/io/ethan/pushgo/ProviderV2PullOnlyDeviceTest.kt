package io.ethan.pushgo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.*
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.ProviderIngressCoordinator
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native Room + HttpURLConnection receipts, without a UI launch or external Gateway.
 * Four bounded journeys detect page ACK reordering, premature ACK after SQL failure,
 * and loss/unbounded replay of receipts already accepted by a pre-V2 app.
 * Expected cost: a handful of loopback requests and database reopen, below a UI journey.
 */
@RunWith(AndroidJUnit4::class)
class ProviderV2PullOnlyDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PushGoDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var messages: MessageRepository
    private lateinit var entities: EntityRepository
    private lateinit var ledger: InboundDeliveryLedgerRepository
    private lateinit var channels: ChannelSubscriptionRepository
    private val secrets = MemorySecrets()
    private val servers = mutableListOf<Gateway>()

    @Before fun setUp() { context.deleteDatabase(DB_NAME); open() }
    @After fun tearDown() {
        servers.forEach { it.close() }
        db.close()
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences(DB_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun v2PagingAcknowledgesCommittedPageBeforeNextPull() = runBlocking {
        var pulls = 0
        val gateway = gateway { path, body ->
            when (path) {
                PULL -> {
                    pulls++
                    if (pulls == 2) assertEquals("acked", ackState("outer-1"))
                    200 to page("outer-$pulls", hasMore = pulls == 1)
                }
                ACK -> {
                    val id = body.getJSONArray("delivery_ids").getString(0)
                    assertEquals("device-a", body.getString("device_key"))
                    assertEquals(pulls.toLong(), runBlocking { messages.totalCount() }.toLong())
                    assertEquals("pending", ackState(id))
                    assertEquals(1, sqlCount("inbound_delivery_ack_outbox"))
                    200 to ackReceipt()
                }
                else -> error("Unexpected endpoint $path")
            }
        }
        configure(gateway)
        assertEquals(2, pull())
        assertEquals(listOf(PULL, ACK, PULL, ACK), gateway.paths())
        assertEquals(0, sqlCount("legacy_provider_ingress"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        assertEquals("acked", ackState("outer-2"))
        assertEquals(0, sqlCount("inbound_delivery_ledger", "delivery_id = 'payload-spoof'"))
        db.close(); open()
        assertEquals(2L, messages.totalCount().toLong())
        assertEquals("acked", ackState("outer-1"))
        assertCanonical("outer-1")
        assertCanonical("outer-2")
        gateway.assertHealthy()
    }

    @Test
    fun missingV2AckRoutePreservesCommittedReceiptAndStopsPaging() = runBlocking {
        val gateway = gateway { path, _ -> when (path) {
            PULL -> 200 to page("outer-unsupported-ack", hasMore = true)
            ACK -> {
                assertEquals(1, sqlCount("messages"))
                assertEquals(1, sqlCount("inbound_delivery_ack_outbox"))
                404 to JSONObject("""{"success":false,"error_code":"route_not_found","problem":{"code":"route_not_found","category":"not_found","status":404,"retryable":false}}""")
            }
            else -> error("Legacy fallback escaped: $path")
        } }
        configure(gateway)
        val failure = runCatching { pull() }.exceptionOrNull()
        assertTrue(failure is ChannelSubscriptionException)
        failure as ChannelSubscriptionException
        assertEquals("gateway_v2_required", failure.code)
        assertEquals(GatewayErrorCategory.FEATURE_DISABLED, failure.category)
        assertEquals("route_not_found", (failure.cause as ChannelSubscriptionException).code)
        assertEquals(listOf(PULL, ACK), gateway.paths())
        assertEquals("pending", ackState("outer-unsupported-ack"))
        db.close(); open()
        assertEquals(1, sqlCount("messages"))
        assertEquals(1, sqlCount("inbound_delivery_ack_outbox"))
        assertEquals("pending", ackState("outer-unsupported-ack"))
        assertCanonical("outer-unsupported-ack")
        assertEquals(0, sqlCount("legacy_provider_ingress"))
        gateway.assertHealthy()
    }

    @Test
    fun sqlFailureDoesNotAckAndRetryUsesCapturedGatewayAfterConfigSwitch() = runBlocking {
        val replacement = gateway { path, _ -> error("Old delivery escaped to replacement Gateway: $path") }
        val original = gateway { path, _ -> when (path) {
            PULL -> 200 to page("outer-retry", false)
            ACK -> {
                assertEquals(1, sqlCount("messages"))
                assertEquals("pending", ackState("outer-retry"))
                200 to ackReceipt()
            }
            else -> error("Unexpected endpoint $path")
        } }
        configure(original)
        rejectMessageWrites()
        assertTrue(runCatching { pull() }.exceptionOrNull() != null)
        assertEquals(listOf(PULL), original.paths())
        assertEquals(0, sqlCount("messages"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        assertEquals(0, sqlCount("inbound_delivery_ledger"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_provider_write")
        assertEquals(1, pull {
            // Configuration changes after canonical persistence, before ACK. The
            // outbox must still own the page's original destination and device key.
            settings.setServerAddress(replacement.baseUrl)
            settings.setDeviceKey("device-b")
        })
        assertEquals(listOf(PULL, PULL, ACK), original.paths())
        assertTrue(replacement.paths().isEmpty())
        assertEquals("acked", ackState("outer-retry"))
        configure(original)
        assertEquals(1, pull()) // replay is counted as complete, not another content row
        assertEquals(1, sqlCount("messages"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        assertCanonical("outer-retry")
        original.assertHealthy(); replacement.assertHealthy()
    }

    @Test
    fun v27AcceptedLegacyReceiptSurvivesMigrationFailureThenDrainsOnceWithoutLegacyHttp() = runBlocking {
        db.close(); context.deleteDatabase(DB_NAME)
        seedV27()
        open()
        val gateway = gateway { path, _ ->
            assertEquals(PULL, path)
            assertEquals(0, sqlCount("legacy_provider_ingress"))
            200 to JSONObject().put("success", true).put("data", JSONObject().put("items", JSONArray()).put("has_more", false))
        }
        configure(gateway)
        val originalPayload = ledger.loadPendingLegacyPull().single().payloadJson
        rejectMessageWrites()
        assertTrue(runCatching { pull() }.exceptionOrNull() != null)
        assertEquals(originalPayload, ledger.loadPendingLegacyPull().single().payloadJson)
        assertTrue(gateway.paths().isEmpty())
        db.close(); open()
        assertEquals(originalPayload, ledger.loadPendingLegacyPull().single().payloadJson)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_provider_write")
        assertEquals(1, pull())
        assertEquals(0, sqlCount("legacy_provider_ingress"))
        assertEquals(1, sqlCount("messages"))
        assertEquals("acked", ackState("old-accepted"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        db.close(); open()
        assertEquals(0, pull())
        assertEquals(1, sqlCount("messages"))
        assertEquals(listOf(PULL, PULL), gateway.paths())
        assertCanonical("old-accepted")
        val migratedScope = db.openHelper.readableDatabase.query(
            "SELECT gateway_url, device_key, ack_state FROM inbound_delivery_ledger WHERE delivery_id = ?",
            arrayOf("old-accepted"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2))
        }
        assertEquals(listOf("https://old-gateway.example", "old-device", "acked"), migratedScope)
        gateway.assertHealthy()
    }

    private suspend fun pull(beforeNotify: suspend () -> Unit = {}): Int =
        ProviderIngressCoordinator.pullAndPersist(context, channels, messages, entities, ledger, settings,
            beforeMessageNotify = { _, _ -> beforeNotify() })

    private suspend fun configure(gateway: Gateway) {
        settings.setServerAddress(gateway.baseUrl)
        settings.setUseFcmChannel(false)
        settings.setDeviceKey("device-a")
    }

    private fun open() {
        db = PushGoDatabase.buildForTest(context, DB_NAME)
        settings = SettingsRepository(db.appSettingsDao(), secrets,
            context.getSharedPreferences(DB_NAME, Context.MODE_PRIVATE))
        messages = MessageRepository(db, db.messageDao(), db.messageChannelStatsDao(), db.messageMetadataIndexDao(),
            db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.thingHeadDao(), db.thingSubMessageDao(), db.pendingThingMessageDao())
        entities = EntityRepository(db, db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.eventChangeLogDao(),
            db.thingChangeLogDao(), db.thingSubEventDao(), db.topLevelEventHeadDao(), db.thingHeadDao(), db.thingSubMessageDao())
        ledger = InboundDeliveryLedgerRepository(db, db.inboundDeliveryLedgerDao(), db.inboundDeliveryAckOutboxDao(), db.legacyProviderIngressDao())
        channels = ChannelSubscriptionRepository(ChannelSubscriptionStore(db.channelSubscriptionDao(), secrets), settings,
            MessageStateCoordinator(context, messages), messages, entities, db,
            object : PushTokenProvider { override suspend fun fetchToken(timeoutMs: Long): String? = null })
    }

    private fun rejectMessageWrites() = db.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER reject_provider_write BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'provider canonical store failure'); END")

    private fun sqlCount(table: String, condition: String = "1 = 1"): Int = db.openHelper.readableDatabase
        .query("SELECT count(*) FROM $table WHERE $condition").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun assertCanonical(id: String) {
        val content = db.openHelper.readableDatabase.query(
            "SELECT title, body FROM messages WHERE message_id = ?", arrayOf(id),
        ).use { cursor ->
            assertTrue("Canonical message $id missing", cursor.moveToFirst())
            listOf(cursor.getString(0), cursor.getString(1))
        }
        assertEquals(listOf("retained-$id", "canonical-body-$id"), content)
    }

    private fun ackState(id: String): String? = db.openHelper.readableDatabase
        .query("SELECT ack_state FROM inbound_delivery_ledger WHERE delivery_id = ?", arrayOf(id))
        .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun payload(id: String) = JSONObject().put("entity_type", "message").put("message_id", id)
        .put("title", "retained-$id").put("body", "canonical-body-$id").put("delivery_id", "payload-spoof")
        .put("ttl", "1") // Already expired alerts still persist and settle, without OS notification dependence.

    private fun page(id: String, hasMore: Boolean) = JSONObject().put("success", true).put("data", JSONObject()
        .put("items", JSONArray().put(JSONObject().put("delivery_id", id).put("payload", payload(id))))
        .put("has_more", hasMore))

    private fun ackReceipt() = JSONObject().put("success", true).put("data", JSONObject()
        .put("requested_count", 1).put("removed_count", 1))

    private fun seedV27() {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("27.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            sqlite.beginTransaction()
            try {
                val tables = schema.getJSONArray("entities")
                for (i in 0 until tables.length()) {
                    val table = tables.getJSONObject(i)
                    val name = table.getString("tableName")
                    sqlite.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", name))
                    table.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
                    }
                    table.optJSONArray("contentSyncTriggers")?.let { triggers ->
                        for (j in 0 until triggers.length()) sqlite.execSQL(triggers.getString(j))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO legacy_provider_ingress(gateway_url,device_key,delivery_id,payload_json,enqueued_at) VALUES(?,?,?,?,?)",
                    arrayOf<Any>("https://old-gateway.example", "old-device", "old-accepted", payload("old-accepted").toString(), 1L))
                sqlite.execSQL("PRAGMA user_version = 27")
                sqlite.setTransactionSuccessful()
            } finally { sqlite.endTransaction() }
        }
    }

    private fun gateway(handler: (String, JSONObject) -> Pair<Int, JSONObject>): Gateway = Gateway(handler).also { servers += it }

    private class Gateway(val handler: (String, JSONObject) -> Pair<Int, JSONObject>) : AutoCloseable {
        private val socket = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        private val paths = Collections.synchronizedList(mutableListOf<String>())
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(name = "provider-v2-native-gateway", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { connection ->
                        connection.soTimeout = 5_000
                        val reader = connection.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        var length = 0
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        val content = CharArray(length)
                        var read = 0
                        while (read < length) { val n = reader.read(content, read, length - read); check(n >= 0); read += n }
                        paths += path
                        val response = try { handler(path, JSONObject(String(content))) } catch (error: Throwable) {
                            failure.compareAndSet(null, error)
                            500 to JSONObject().put("success", false).put("error", "test request oracle failed")
                        }
                        val bytes = response.second.toString().toByteArray()
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 ${response.first} Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (error: Throwable) { if (!socket.isClosed && error !is SocketException) failure.compareAndSet(null, error) }
            }
        }
        fun paths(): List<String> = synchronized(paths) { paths.toList() }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("Gateway request oracle failed", it) } }
        override fun close() { socket.close(); worker.join(1_000); assertHealthy() }
    }

    private class MemorySecrets : SecureSecretStore {
        private val values = mutableMapOf<String, String?>()
        private var key: ByteArray? = null
        override fun gatewayToken() = values["gateway"]
        override fun setGatewayToken(token: String?) { values["gateway"] = token }
        override fun gatewayAckToken(gatewayUrl: String) = values["ack:$gatewayUrl"]
        override fun setGatewayAckToken(gatewayUrl: String, token: String?) { values["ack:$gatewayUrl"] = token }
        override fun fcmToken() = values["fcm"]
        override fun setFcmToken(token: String?) { values["fcm"] = token }
        override fun gatewayTransitionJournal() = values["journal"]
        override fun setGatewayTransitionJournal(journal: String?) { values["journal"] = journal }
        override fun deviceKey() = values["device"]
        override fun setDeviceKey(deviceKey: String?) { values["device"] = deviceKey }
        override fun notificationKeyBytes() = key
        override fun setNotificationKeyBytes(value: ByteArray?) { key = value }
        override fun channelPassword(gatewayUrl: String, channelId: String) = values["channel:$gatewayUrl:$channelId"]
        override fun setChannelPassword(gatewayUrl: String, channelId: String, password: String?) { values["channel:$gatewayUrl:$channelId"] = password }
        override fun removeChannelPassword(gatewayUrl: String, channelId: String) { values.remove("channel:$gatewayUrl:$channelId") }
        override fun clearAll() { values.clear(); key = null }
    }

    companion object {
        private const val DB_NAME = "provider_v2_native.db"
        private const val PULL = "/v2/messages/pull"
        private const val ACK = "/v2/messages/ack"
    }
}
