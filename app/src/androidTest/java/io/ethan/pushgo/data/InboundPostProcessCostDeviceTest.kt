package io.ethan.pushgo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.db.EventChangeLogEntity
import io.ethan.pushgo.data.db.ThingChangeLogEntity
import io.ethan.pushgo.data.db.ThingSubEventEntity
import io.ethan.pushgo.data.db.ThingSubMessageEntity
import io.ethan.pushgo.data.db.MessageEntity
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.InboundPersistenceCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Low-volume SQL attribution on real Room/production ingress. The opt-in synchronous
 * QueryCallback is absent from the paired timing workload and all normal database opens. */
@RunWith(AndroidJUnit4::class)
class InboundPostProcessCostDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val queries = QueryCounts()
    private lateinit var db: PushGoDatabase
    private lateinit var messages: MessageRepository
    private lateinit var entities: EntityRepository
    private lateinit var ledger: InboundDeliveryLedgerRepository
    private lateinit var settings: SettingsRepository
    private lateinit var defaultDatabaseState: JSONObject
    private val identity = requireNotNull(ProviderAckIdentity.create(
        ProviderAckDestination("https://post-process-cost.invalid", "synthetic-device"),
        ProviderAckContract.V2_BATCH, "provider_pull"))

    @Before fun prepare() {
        defaultDatabaseState = readOnlyDefaultDatabaseState()
        context.deleteDatabase(DB)
        db = PushGoDatabase.buildForTest(context, DB, queries)
        messages = MessageRepository(db, db.messageDao(), db.messageChannelStatsDao(), db.messageMetadataIndexDao(),
            db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.thingHeadDao(), db.thingSubMessageDao(), db.pendingThingMessageDao())
        entities = EntityRepository(db, db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.eventChangeLogDao(),
            db.thingChangeLogDao(), db.thingSubEventDao(), db.topLevelEventHeadDao(), db.thingHeadDao(), db.thingSubMessageDao())
        ledger = InboundDeliveryLedgerRepository(db, db.inboundDeliveryLedgerDao(), db.inboundDeliveryAckOutboxDao(), db.legacyProviderIngressDao())
        settings = SettingsRepository(db.appSettingsDao(), AndroidKeystoreSecretStore(context, "post-process-cost-synthetic-secrets"),
            context.getSharedPreferences("post-process-cost-synthetic-settings", Context.MODE_PRIVATE))
        db.openHelper.writableDatabase // Exclude schema creation/onOpen from per-operation accounting.
        queries.clear()
    }

    @After fun cleanup() {
        db.close()
        context.deleteDatabase(DB)
    }

    @Test fun locatorUsesOneStatementSnapshotWithoutStartingASqlTransaction() = runBlocking {
        val plain = message("locator-empty")
        assertTrue(persist(plain).shouldAck)
        queries.clear()
        assertNull(messages.postProcessLocator(plain.id, identity.inboundDeliveryScope()))
        val empty = queries.snapshot()

        val pending = message("locator-pending")
        assertTrue(persist(pending, "https://post-process-cost.invalid/image.png").shouldAck)
        queries.clear()
        val locator = messages.postProcessLocator(pending.id, identity.inboundDeliveryScope())
        assertNotNull(locator)
        assertEquals(POST_PROCESS_MEDIA, locator!!.flags)
        val nonempty = queries.snapshot()
        emit("locator-read-contract", JSONObject().put("empty", empty).put("pending", nonempty))

        assertEquals(2, empty.getInt("locator_selects"))
        assertEquals(1, nonempty.getInt("locator_selects"))
        // This stable oracle intentionally exposes the old redundant SQL transaction
        // wrappers before the production correction; generation-finalization stays transactional.
        assertEquals("empty read-only hints must not run inside new SQL transactions", 0, empty.getInt("locator_selects_in_transaction"))
        assertEquals("pending read-only hint must not run inside a new SQL transaction", 0, nonempty.getInt("locator_selects_in_transaction"))
    }

    @Test fun acceptedReplayAndPendingIngressRetainDurableOutcomesWhileSqlIsRecorded() = runBlocking {
        val stages = JSONArray()
        queries.clear()
        val begin = SystemClock.elapsedRealtimeNanos()
        repeat(100) { index ->
            val value = message("accepted-$index")
            val outcome = persist(value)
            assertTrue(outcome.shouldAck)
        }
        val acceptedElapsed = (SystemClock.elapsedRealtimeNanos()-begin)/1_000_000.0
        val acceptedSql = queries.snapshot()
        // Verification reads are outside the observed ingress SQL/time window.
        repeat(100) { index -> assertEquals(message("accepted-$index").body, messages.getById("accepted-$index")?.body) }
        stages.put(JSONObject().put("stage", "100-new-no-notification-no-media")
            .put("elapsed_ms", acceptedElapsed).put("sql", acceptedSql))

        val canonical = requireNotNull(messages.getById("accepted-99"))
        queries.clear()
        val replay = persist(canonical.copy(id = "different-incoming-local-id"))
        val replaySql = queries.snapshot()
        assertTrue(replay.shouldAck)
        assertNull(messages.getById("different-incoming-local-id"))
        assertEquals(canonical.body, messages.getById(canonical.id)?.body)
        stages.put(JSONObject().put("stage", "same-delivery-replay-new-local-id").put("sql", replaySql))

        queries.clear()
        val pending = message("ingress-pending")
        var registered = 0
        val result = InboundPersistenceCoordinator.persistAndNotify(context, messages, entities, ledger, settings,
            InboundPersistenceRequest.Message(pending, null, "https://post-process-cost.invalid/image.png", false, identity),
            deliveryScope = identity.inboundDeliveryScope(),
            postProcessRecovery = { registered++ })
        val pendingSql = queries.snapshot()
        assertTrue(result.shouldAck)
        assertEquals(1, registered)
        assertEquals(pending.body, messages.getById(pending.id)?.body)
        stages.put(JSONObject().put("stage", "durable-media-pending-registration-hint").put("sql", pendingSql))
        emit("ingress-sql-attribution", JSONObject().put("stages", stages).put("canonical_rows", messages.totalCount()))
        assertEquals(101, messages.totalCount())
    }

    @Test fun locatorInAnExistingTransactionSeesUncommittedPendingWithoutLeakingRollback() = runBlocking {
        val value = message("outer-transaction")
        assertTrue(persist(value).shouldAck)
        val rollback = IllegalStateException("synthetic rollback after hint observation")
        val observed = runCatching {
            db.withTransaction {
                db.markPostProcessPending("messages", value.id, POST_PROCESS_MEDIA)
                val locator = messages.postProcessLocator(value.id, identity.inboundDeliveryScope())
                assertNotNull("an existing writer must see its own uncommitted pending fact", locator)
                assertEquals(POST_PROCESS_MEDIA, locator!!.flags)
                throw rollback
            }
        }.exceptionOrNull()
        assertSame(rollback, observed)
        assertNull(messages.postProcessLocator(value.id, identity.inboundDeliveryScope()))
        assertTrue(ledger.shouldAck(value.deliveryId, identity))
    }

    @Test fun originRecordingKeepsAllDomainTimeAndOwnerFencesAndReportsSharedQueryCost() = runBlocking {
        val id = "origin-same-id-across-domains"
        val received = 1_800_000_000_000L
        val largeBody = "x".repeat(40 * 1024)
        val ordinary = message(id).copy(body = largeBody,
            rawPayloadJson = JSONObject().put("body", largeBody).toString())
        val record = IncomingEntityRecord("event", id, "synthetic", "Synthetic", largeBody,
            ordinary.rawPayloadJson, Instant.ofEpochMilli(received), null, id, "synthetic",
            "synthetic-event", "synthetic-thing", "open", null, null)
        db.messageDao().insert(MessageEntity.fromModel(ordinary))
        db.thingSubMessageDao().insert(ThingSubMessageEntity.fromModel(ordinary.copy(
            rawPayloadJson = JSONObject(ordinary.rawPayloadJson).put("thing_id", "synthetic-thing").toString(),
            receivedAt = Instant.ofEpochMilli(received + 1))))
        db.eventChangeLogDao().insert(EventChangeLogEntity.fromIncoming(record))
        db.thingChangeLogDao().insert(ThingChangeLogEntity.fromIncoming(record.copy(entityType = "thing")))
        db.thingSubEventDao().insert(ThingSubEventEntity.fromIncoming(record, activated = false))
        db.thingSubEventDao().insert(ThingSubEventEntity.fromIncoming(record.copy(
            receivedAt = Instant.ofEpochMilli(received + 2)), activated = true))
        val owners = db.messageResourceDao().owners(id)
        assertEquals(6, owners.size) // Five concrete domains, including both Event lifecycle slots.
        val expected = owners.filter { it.receivedAt == received }.map { it.generation }.toSet()
        assertEquals(4, expected.size)
        val foreign = requireNotNull(InboundDeliveryScope.create("https://foreign-cost.invalid", "foreign-device"))
        val retainedForeign = owners.first { it.sourceTable == "event_change_logs" }.generation
        db.messageResourceDao().setOrigin(id, retainedForeign, MessageImageStore.originScope(foreign),
            foreign.gatewayUrl, foreign.deviceKey)
        val bodyRowsBefore = bodyRows(id)
        queries.clear()
        db.withTransaction { db.recordResourceOrigin(id, received, identity.inboundDeliveryScope()) }
        val sql = queries.snapshot()
        val scope = requireNotNull(identity.inboundDeliveryScope())
        db.openHelper.readableDatabase.query(
            "SELECT generation,origin_scope,authenticated_gateway_url,authenticated_device_key FROM message_resource_owners WHERE owner_id=?",
            arrayOf(id),
        ).use { cursor ->
            var rows = 0
            while (cursor.moveToNext()) {
                rows++
                val generation = cursor.getString(0)
                when {
                    generation == retainedForeign -> {
                        assertEquals(MessageImageStore.originScope(foreign), cursor.getString(1))
                        assertEquals(foreign.gatewayUrl, cursor.getString(2)); assertEquals(foreign.deviceKey, cursor.getString(3))
                    }
                    generation in expected -> {
                        assertEquals(MessageImageStore.originScope(scope), cursor.getString(1))
                        assertEquals(scope.gatewayUrl, cursor.getString(2)); assertEquals(scope.deviceKey, cursor.getString(3))
                    }
                    else -> for (column in 1..3) assertTrue("different receive time stays unclaimed", cursor.isNull(column))
                }
            }
            assertEquals(6, rows)
        }
        assertEquals(bodyRowsBefore, bodyRows(id))
        emit("origin-fences-and-query-cost", JSONObject().put("sql", sql).put("canonical_domains", 5)
            .put("owner_rows", owners.size).put("time_matched_generations", expected.size)
            .put("foreign_generation_retained", true)
            .put("wide_body_bytes_returned_by_shared_query", owners.sumOf { it.rawPayloadJson.toByteArray(Charsets.UTF_8).size })
            .put("scope", "Shared-query identity/time correctness and diagnostic cost; returned bytes are not allocated-heap or performance benefits"))
        assertEquals("origin registration uses the one shared owner query; this count is diagnostic, not a performance benefit",
            1, sql.getInt("wide_owner_projection_selects"))
    }

    private fun bodyRows(id: String): Map<String, String> = buildMap {
        for (table in POST_PROCESS_TABLES) db.openHelper.readableDatabase.query(
            "SELECT raw_payload_json FROM $table WHERE id=? ORDER BY raw_payload_json", arrayOf(id),
        ).use { cursor ->
            var slot = 0
            while (cursor.moveToNext()) put("$table:${slot++}", cursor.getString(0))
        }
    }

    private suspend fun persist(message: PushMessage, image: String? = null) =
        InboundPersistenceCoordinator.persistAndNotify(context, messages, entities, ledger, settings,
            InboundPersistenceRequest.Message(message, null, image, false, identity),
            deliveryScope = identity.inboundDeliveryScope(),
            postProcessRecovery = { /* Execution is not part of this cost attribution. */ })

    private fun message(id: String): PushMessage {
        val text = "x".repeat(1024)
        val raw = JSONObject().put("entity_type", "message").put("entity_id", id)
            .put("message_id", id).put("delivery_id", id).put("op_id", "op-$id").put("body", text).toString()
        return PushMessage(id, id, "Synthetic", text, "synthetic", null, false,
            Instant.ofEpochMilli(1_800_000_000_000L), raw, MessageStatus.NORMAL, null, null, "synthetic")
    }

    private fun emit(kind: String, content: JSONObject) {
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: File(context.cacheDir, "post-process-cost-output").absolutePath).apply { mkdirs() }
        File(directory, "$kind-${UUID.randomUUID()}.json").writeText(content.put("kind", kind)
            .put("variant", InstrumentationRegistry.getArguments().getString("costVariant", "unspecified"))
            .put("default_db_readonly_precondition", defaultDatabaseState)
            .put("scope", "QueryCallback diagnostic, no wire ACK/notification/network/paired performance claim; bound values are never recorded").toString(2))
    }

    private fun readOnlyDefaultDatabaseState(): JSONObject {
        val path = context.getDatabasePath("pushgo.db")
        if (!path.exists()) {
            val legacy = context.databaseList().count { it.matches(Regex("pushgo-v\\d+\\.db")) }
            return JSONObject().put("state", if (legacy == 0) "ABSENT" else "UNKNOWN_LEGACY")
                .put("canonical_rows", if (legacy == 0) 0 else JSONObject.NULL).put("gateway_class", "ABSENT")
                .put("legacy_candidates", legacy)
        }
        return try {
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { existing ->
                existing.rawQuery("SELECT (SELECT COUNT(*) FROM messages)+(SELECT COUNT(*) FROM thing_sub_messages)," +
                    "(SELECT server_address FROM app_settings WHERE id=1)", null).use { cursor ->
                    check(cursor.moveToFirst())
                    val count = cursor.getLong(0)
                    // Classify only routing safety. Never export a URL, token or device key.
                    val gateway = if (cursor.isNull(1)) null else cursor.getString(1)
                    val host = gateway?.let { android.net.Uri.parse(it).host }
                    val route = when {
                        gateway.isNullOrBlank() -> "ABSENT"
                        host in setOf("127.0.0.1", "localhost", "::1", "10.0.2.2") -> "LOOPBACK_FIXTURE"
                        host?.endsWith(".invalid") == true -> "RESERVED_INVALID_FIXTURE"
                        else -> "OTHER_OR_UNKNOWN"
                    }
                    val indexes = JSONArray()
                    existing.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='index' AND name IN " +
                        "('index_messages_read_received_id','index_messages_channel_key_read_received_id','index_messages_channel_key_received_id') ORDER BY name", null).use { rows ->
                        while (rows.moveToNext()) indexes.put(JSONObject().put("name", rows.getString(0)).put("sql", rows.getString(1)))
                    }
                    JSONObject().put("state", if (count == 0L) "EMPTY" else "NONEMPTY").put("canonical_rows", count)
                        .put("gateway_class", route)
                        .put("user_version", existing.version).put("runtime_index_master", indexes)
                        .put("readonly", true).put("deleted_or_reset", false)
                }
            }
        } catch (error: Exception) {
            JSONObject().put("state", "UNKNOWN").put("readonly", true).put("error_type", error.javaClass.simpleName)
        }
    }

    private class QueryCounts : RoomDatabase.QueryCallback {
        private data class ObservedSql(val text: String, val thread: Long)
        private val sql = mutableListOf<ObservedSql>()
        override fun onQuery(query: String, bindArgs: List<Any?>) = synchronized(sql) {
            check(sql.size < 20_000) { "fixed diagnostic SQL observation budget exceeded" }
            sql.add(ObservedSql(query.trim().replace(Regex("\\s+"), " "), Thread.currentThread().id))
            Unit
        }
        fun clear() = synchronized(sql) { sql.clear() }
        fun snapshot(): JSONObject = synchronized(sql) {
            val grouped = sql.groupingBy { it.text }.eachCount()
            val depths = mutableMapOf<Long, Int>()
            var locatorInTransaction = 0
            val trace = JSONArray()
            sql.forEach { event ->
                val depth = depths[event.thread] ?: 0
                if (event.text.startsWith("SELECT t.rowid,o.generation,t.post_process_pending") && depth > 0) {
                    locatorInTransaction++
                }
                if (sql.size <= 64) trace.put(JSONObject().put("sql", event.text).put("thread", event.thread).put("depth_before", depth))
                when {
                    event.text.startsWith("BEGIN", true) -> depths[event.thread] = depth + 1
                    event.text.startsWith("END TRANSACTION", true) || event.text.startsWith("COMMIT", true) -> depths[event.thread] = (depth - 1).coerceAtLeast(0)
                }
            }
            JSONObject().put("total", sql.size)
                .put("begins", sql.count { it.text.startsWith("BEGIN", true) })
                .put("ends", sql.count { it.text.startsWith("END TRANSACTION", true) || it.text.startsWith("COMMIT", true) })
                .put("locator_selects", sql.count { it.text.startsWith("SELECT t.rowid,o.generation,t.post_process_pending") })
                .put("locator_selects_in_transaction", locatorInTransaction)
                .put("wide_owner_projection_selects", sql.count { it.text.contains("raw_payload_json AS rawPayloadJson") && it.text.contains("JOIN message_resource_owners") })
                .put("small_thread_trace", trace)
                .put("boundary_scope", "Room/SQLite API labels, including nested calls; not physical COMMIT/fsync counts. Thread-local intervals separate unrelated invalidation work.")
                .put("statements_without_bind_values", JSONObject(grouped))
        }
    }

    companion object { private const val DB = "post-process-cost-synthetic.db" }
}
