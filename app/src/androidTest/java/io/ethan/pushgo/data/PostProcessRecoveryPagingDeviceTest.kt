package io.ethan.pushgo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real canonical Room rows and recovery helpers; synthetic fixtures, no OS notification or WorkManager. */
@RunWith(AndroidJUnit4::class)
class PostProcessRecoveryPagingDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PushGoDatabase
    private lateinit var messages: MessageRepository

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        open()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB)
    }

    @Test
    fun schema38RuntimeIndexesAndLegacyScopeMetadataSurviveMigrationAndReopen() = runBlocking {
        db.close()
        context.deleteDatabase(DB)
        seedSchema38CopyWithRuntimeIndexes()
        open()
        assertEquals(39, scalar("PRAGMA user_version"))
        fun assertLegacyRetained() {
            assertEquals(1, scalar("SELECT COUNT(*) FROM messages WHERE id='scope-old-message' AND body='Retained canonical body'"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM pending_thing_messages WHERE id='scope-old-pending' AND body='Retained pending body' AND authenticated_gateway_url IS NULL AND authenticated_device_key IS NULL"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM thing_sub_events WHERE id='scope-old-waiting-event' AND activated=0 AND body='Retained waiting body'"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM message_resource_owners WHERE owner_id='scope-old-message' AND generation='0123456789abcdef0123456789abcdef' AND origin_scope='legacy-origin-hash-kept' AND authenticated_gateway_url IS NULL AND authenticated_device_key IS NULL"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM inbound_delivery_ledger WHERE delivery_id='scope-old-pending-delivery' AND ack_state='acked' AND applied_at=$RECEIVED_AT AND acked_at=$RECEIVED_AT AND acceptance_proven IS NULL"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM inbound_delivery_ledger WHERE delivery_id='scope-uncertain' AND ack_state='pending' AND applied_at=$RECEIVED_AT AND acked_at IS NULL AND acceptance_proven IS NULL"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM inbound_delivery_ack_outbox WHERE delivery_id='scope-uncertain' AND attempt_count=2 AND last_attempt_uncertain=1"))
            for (name in listOf("index_messages_read_received_id", "index_messages_channel_key_read_received_id", "index_messages_channel_key_received_id")) {
                assertEquals(1, scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='$name'"))
            }
        }
        assertLegacyRetained()
        db.openHelper.readableDatabase.query("PRAGMA table_info(inbound_delivery_ledger)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "acceptance_proven") {
                found = true
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                assertNull(cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
            }
            assertTrue("Legacy acceptance is nullable and not inferred by a default", found)
        }
        val scope = requireNotNull(InboundDeliveryScope.create("https://scope-new.invalid", "scope-new-device"))
        val fresh = message("scope-new-message", "scope-new-delivery")
        assertTrue(messages.insertIncoming(fresh, deliveryScope = scope)) // No image is required to record authenticated origin.
        val pending = message("scope-new-pending", "scope-new-pending-delivery").let { current ->
            current.copy(rawPayloadJson = JSONObject(current.rawPayloadJson).put("thing_id", "missing-scope-parent")
                .put("occurred_at", RECEIVED_AT.toString()).toString())
        }
        assertFalse(messages.insertIncoming(pending, deliveryScope = scope))
        val ledger = db.inboundDeliveryLedgerDao()
        val accepted = requireNotNull(ledger.get(scope.gatewayUrl, scope.deviceKey, "scope-new-delivery"))
        val pendingAccepted = requireNotNull(ledger.get(scope.gatewayUrl, scope.deviceKey, "scope-new-pending-delivery"))
        assertEquals(true, accepted.acceptanceProven)
        assertEquals(true, pendingAccepted.acceptanceProven)
        assertNull(ledger.get(scope.gatewayUrl, "foreign-device", accepted.deliveryId))
        assertEquals(0, ledger.markAcceptanceProven(scope.gatewayUrl, "foreign-device", accepted.deliveryId))
        // Repeated proof changes no acceptance/ACK timestamps and cannot renew retention.
        assertEquals(1, ledger.markAcceptanceProven(scope.gatewayUrl, scope.deviceKey, accepted.deliveryId))
        assertEquals(accepted, ledger.get(scope.gatewayUrl, scope.deviceKey, accepted.deliveryId))
        val generation = db.messageResourceDao().owners(fresh.id).single().generation
        val hash = MessageImageStore.originScope(scope)
        // The existing generation/hash and literals cannot be reassigned to a foreign origin.
        db.messageResourceDao().setOrigin(fresh.id, generation, hash, "https://foreign.invalid", "foreign-device")
        db.messageResourceDao().setOrigin(fresh.id, "stale-generation", hash, scope.gatewayUrl, scope.deviceKey)
        reopen()
        assertLegacyRetained()
        assertEquals(accepted, db.inboundDeliveryLedgerDao().get(scope.gatewayUrl, scope.deviceKey, accepted.deliveryId))
        assertEquals(pendingAccepted, db.inboundDeliveryLedgerDao().get(scope.gatewayUrl, scope.deviceKey, pendingAccepted.deliveryId))
        assertEquals(1, scalar("SELECT COUNT(*) FROM message_resource_owners WHERE owner_id='scope-new-message' AND authenticated_gateway_url='https://scope-new.invalid' AND authenticated_device_key='scope-new-device'"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM pending_thing_messages WHERE id='scope-new-pending' AND authenticated_gateway_url='https://scope-new.invalid' AND authenticated_device_key='scope-new-device' AND body='Same exact durable body'"))
        // A real replacement of the canonical incarnation clears both old identity forms.
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET received_at=received_at+1 WHERE id='scope-new-message'")
        assertEquals(1, scalar("SELECT COUNT(*) FROM message_resource_owners WHERE owner_id='scope-new-message' AND origin_scope IS NULL AND authenticated_gateway_url IS NULL AND authenticated_device_key IS NULL"))
        assertNotEquals(generation, db.messageResourceDao().owners(fresh.id).single().generation)
        assertEquals(fresh.body, messages.getById(fresh.id)?.body)
    }

    @Test
    fun schema36RowsKeepTheirContentAndStartWithNoHistoricalPostProcessingClaim() = runBlocking {
        db.close()
        context.deleteDatabase(DB)
        seedSchema36Copy()
        open()

        assertEquals(39, scalar("PRAGMA user_version"))
        for (name in listOf("index_messages_read_received_id", "index_messages_channel_key_read_received_id", "index_messages_channel_key_received_id")) {
            assertEquals("onOpen restores runtime performance index $name", 1,
                scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='$name'"))
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM inbound_delivery_ledger WHERE delivery_id='migration-acked' AND ack_state='acked' AND acked_at=$RECEIVED_AT"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM inbound_delivery_ledger WHERE delivery_id='migration-pending' AND ack_state='pending' AND acked_at IS NULL"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM inbound_delivery_ack_outbox WHERE delivery_id='migration-pending' AND attempt_count=2 AND last_attempt_uncertain=1"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM message_resource_owners WHERE source_table='messages' AND owner_id='old-messages' AND generation='0123456789abcdef0123456789abcdef' AND origin_scope IS NULL"))
        for (table in POST_PROCESS_TABLES) {
            val info = db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                val required = cursor.getColumnIndexOrThrow("notnull")
                val default = cursor.getColumnIndexOrThrow("dflt_value")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(name) == "post_process_pending") {
                        found = true
                        assertEquals(1, cursor.getInt(required))
                        assertEquals("0", cursor.getString(default))
                    }
                }
                found
            }
            assertTrue("$table has its concrete pending field", info)
            assertEquals(1, scalar("SELECT COUNT(*) FROM $table WHERE id='old-$table' AND body='Kept historical body' AND post_process_pending=0"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_${table}_post_process_pending'"))
            assertTrue(db.loadPendingPostProcess(POST_PROCESS_TABLES.indexOf(table)).isEmpty())
        }

        val fresh = message("fresh", "fresh-delivery")
        assertTrue(messages.insertIncoming(fresh, postProcessFlags = POST_PROCESS_NOTIFICATION))
        assertEquals(POST_PROCESS_NOTIFICATION, db.messageDao().getById(fresh.id)?.postProcessPending)
        reopen()
        assertEquals(fresh.body, messages.getById(fresh.id)?.body)
        assertEquals(listOf(fresh.id), db.loadPendingPostProcess(0).map { requireNotNull(db.postProcessSnapshot(it)).message.id })
    }

    @Test
    fun indexedKeysetPassesARecordedFailureAndCompletesBothPagesOfHealthyRows() = runBlocking {
        repeat(70) { index ->
            val id = "queued-${index.toString().padStart(3, '0')}"
            assertTrue(messages.insertIncoming(message(id, "delivery-$id"), postProcessFlags = POST_PROCESS_NOTIFICATION))
        }
        val first = db.loadPendingPostProcess(0)
        assertEquals(POST_PROCESS_PAGE_SIZE, first.size)
        assertEquals(70, scalar("SELECT COUNT(*) FROM messages WHERE post_process_pending=$POST_PROCESS_NOTIFICATION"))
        val plan = explainPendingMessagePage()
        android.util.Log.i("PostProcessPlan", "postprocess-recovery=$plan")
        assertTrue("Actual native query plan: $plan", plan.any { it.contains("index_messages_post_process_pending") })

        val failed = first.first()
        var failedAttempts = 0
        assertFalse(db.finishPostProcessIfCurrent(failed, POST_PROCESS_NOTIFICATION) {
            failedAttempts++
            false
        })
        val completed = linkedSetOf<String>()
        for (locator in first.drop(1)) {
            assertTrue(db.finishPostProcessIfCurrent(locator, POST_PROCESS_NOTIFICATION) { snapshot ->
                completed += snapshot.message.id
                true
            })
        }
        // Progress is the previous page boundary, not a repeated LIMIT from the failed first row.
        val boundary = first.last()
        val second = db.loadPendingPostProcess(0, boundary.flags, boundary.rowId)
        assertEquals(6, second.size)
        for (locator in second) {
            assertTrue(db.finishPostProcessIfCurrent(locator, POST_PROCESS_NOTIFICATION) { snapshot ->
                completed += snapshot.message.id
                true
            })
        }
        assertEquals(69, completed.size)
        assertEquals(1, failedAttempts)
        assertTrue(db.loadPendingPostProcess(0, second.last().flags, second.last().rowId).isEmpty())
        assertEquals(1, scalar("SELECT COUNT(*) FROM messages WHERE post_process_pending>0"))
        assertEquals(failed.generation, db.loadPendingPostProcess(0).single().generation)

        reopen()
        val recovered = db.loadPendingPostProcess(0).single()
        assertEquals(failed.generation, recovered.generation)
        assertTrue(db.finishPostProcessIfCurrent(recovered, POST_PROCESS_NOTIFICATION))
        assertTrue(db.loadPendingPostProcess(0).isEmpty())
        assertEquals(70, scalar("SELECT COUNT(*) FROM messages"))
    }

    @Test
    fun staleGenerationCannotPublishOrClearSameIdAndTimestampRecreation() = runBlocking {
        val old = message("same-local-row", "old-delivery")
        assertTrue(messages.insertIncoming(old, postProcessFlags = POST_PROCESS_NOTIFICATION))
        val stale = requireNotNull(db.postProcessLocator("messages", old.id))
        messages.deleteById(old.id)
        // Deliberate physical-row ABA fixture: a new operation keeps only the local ID/time.
        val fresh = message(old.id, "new-delivery").copy(messageId = "new-business-message")
        assertEquals(old.receivedAt, fresh.receivedAt)
        assertTrue(messages.insertIncoming(fresh, postProcessFlags = POST_PROCESS_NOTIFICATION))
        val current = requireNotNull(db.postProcessLocator("messages", fresh.id))
        assertEquals("The deleted last SQLite rowid is reused by this fixture", stale.rowId, current.rowId)
        assertNotEquals(stale.generation, current.generation)

        var obsoletePublisherCalls = 0
        assertTrue(db.finishPostProcessIfCurrent(stale, POST_PROCESS_NOTIFICATION) {
            obsoletePublisherCalls++
            true
        })
        assertEquals(0, obsoletePublisherCalls)
        assertNull(db.postProcessSnapshot(stale))
        assertEquals(POST_PROCESS_NOTIFICATION, db.messageDao().getById(fresh.id)?.postProcessPending)
        reopen()
        assertEquals(current.generation, db.loadPendingPostProcess(0).single().generation)
        assertEquals("new-business-message", messages.getById(fresh.id)?.messageId)
    }

    @Test
    fun deletingTheLastCanonicalRowEndsItsPendingResponsibilityWithoutAnotherQueue() = runBlocking {
        val message = message("last", "last-delivery")
        assertTrue(messages.insertIncoming(message, postProcessFlags = POST_PROCESS_NOTIFICATION or POST_PROCESS_MEDIA))
        val locator = requireNotNull(db.postProcessLocator("messages", message.id))
        assertNotNull(db.postProcessSnapshot(locator))
        messages.deleteById(message.id)
        assertEquals(0, scalar("SELECT COUNT(*) FROM messages"))
        assertTrue(db.loadPendingPostProcess(0).isEmpty())
        assertNull(db.postProcessSnapshot(locator))
        var publisherCalls = 0
        assertTrue(db.finishPostProcessIfCurrent(locator, POST_PROCESS_NOTIFICATION) {
            publisherCalls++
            true
        })
        assertEquals(0, publisherCalls)
        reopen()
        assertNull(messages.getById(message.id))
        assertTrue(db.loadPendingPostProcess(0).isEmpty())
        assertEquals(0, scalar("SELECT COUNT(*) FROM message_resource_owners WHERE owner_id='last'"))
    }

    private fun explainPendingMessagePage(): List<String> = db.openHelper.readableDatabase.query(
        """EXPLAIN QUERY PLAN SELECT t.rowid,o.generation,t.post_process_pending FROM messages AS t INDEXED BY index_messages_post_process_pending
            CROSS JOIN message_resource_owners o ON o.source_table=? AND o.owner_id=t.id AND o.owner_slot=0
            WHERE t.post_process_pending>0 AND (t.post_process_pending,t.rowid)>(?,?)
            ORDER BY t.post_process_pending,t.rowid LIMIT ?""".trimIndent(),
        arrayOf<Any>("messages", 0, 0L, POST_PROCESS_PAGE_SIZE),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }

    private fun seedSchema38CopyWithRuntimeIndexes() {
        // Explicit exported v38 schema plus its real runtime-only index definitions;
        // no user database, inferred identity, old binary or migrated timestamp claim.
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("38.json").bufferedReader()
            .use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(DB).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            sql.beginTransaction()
            try {
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (i in 0 until indices.length()) {
                        sql.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) sql.execSQL(setup.getString(index))
                sql.execSQL("CREATE INDEX index_messages_read_received_id ON messages(is_read,received_at DESC,id DESC)")
                sql.execSQL("CREATE INDEX index_messages_channel_key_read_received_id ON messages(COALESCE(NULLIF(TRIM(channel), ''), ''),is_read,received_at DESC,id DESC)")
                sql.execSQL("CREATE INDEX index_messages_channel_key_received_id ON messages(COALESCE(NULLIF(TRIM(channel), ''), ''),received_at DESC,id DESC)")
                sql.execSQL("INSERT INTO messages(id,message_id,title,body,is_read,received_at,raw_payload_json,status,body_preview,entity_type) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>("scope-old-message", "scope-old-message-id", "Historical", "Retained canonical body", 0, RECEIVED_AT, "{\"entity_type\":\"message\"}", "NORMAL", "Historical", "message"))
                sql.execSQL("INSERT INTO pending_thing_messages(id,message_id,title,body,received_at,raw_payload_json,status,body_preview,entity_type,thing_id) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>("scope-old-pending", "scope-old-pending-id", "Historical", "Retained pending body", RECEIVED_AT, "{\"entity_type\":\"message\",\"delivery_id\":\"scope-old-pending-delivery\"}", "NORMAL", "Historical", "message", "old-scope-thing"))
                sql.execSQL("INSERT INTO thing_sub_events(id,entity_id,title,body,raw_payload_json,received_at,event_id,thing_id,activated) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf<Any>("scope-old-waiting-event", "scope-old-event", "Historical", "Retained waiting body", "{\"entity_type\":\"event\"}", RECEIVED_AT, "scope-old-event", "old-scope-thing", 0))
                sql.execSQL("INSERT INTO message_resource_owners(source_table,owner_id,owner_slot,generation,origin_scope) VALUES(?,?,?,?,?)", arrayOf<Any>("messages", "scope-old-message", 0, "0123456789abcdef0123456789abcdef", "legacy-origin-hash-kept"))
                sql.execSQL("INSERT INTO inbound_delivery_ledger(gateway_url,device_key,delivery_id,entity_type,applied_at,ack_state,acked_at) VALUES(?,?,?,?,?,?,?)", arrayOf<Any>("https://scope-old.invalid", "scope-old-device", "scope-old-pending-delivery", "message", RECEIVED_AT, "acked", RECEIVED_AT))
                sql.execSQL("INSERT INTO inbound_delivery_ledger(gateway_url,device_key,delivery_id,entity_type,applied_at,ack_state) VALUES(?,?,?,?,?,?)", arrayOf<Any>("https://scope-old.invalid", "scope-old-device", "scope-uncertain", "message", RECEIVED_AT, "pending"))
                sql.execSQL("INSERT INTO inbound_delivery_ack_outbox(delivery_id,gateway_url,device_key,ack_contract,source,enqueued_at,updated_at,attempt_count,last_attempt_uncertain) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf<Any>("scope-uncertain", "https://scope-old.invalid", "scope-old-device", "v2_batch", "provider_pull", RECEIVED_AT, RECEIVED_AT, 2, 1))
                sql.version = 38
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
        }
    }

    private fun seedSchema36Copy() {
        // Explicit exported-schema fixture, not an old APK result or a user database.
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("36.json").bufferedReader()
            .use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(DB).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (i in 0 until indices.length()) {
                    sql.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sql.execSQL(setup.getString(index))
            // Explicit schema fixture plus the three non-unique indexes installed
            // by the real v36 onOpen path. This is not claimed as an old APK artifact.
            sql.execSQL("CREATE INDEX index_messages_read_received_id ON messages(is_read,received_at DESC,id DESC)")
            sql.execSQL("CREATE INDEX index_messages_channel_key_read_received_id ON messages(COALESCE(NULLIF(TRIM(channel), ''), ''),is_read,received_at DESC,id DESC)")
            sql.execSQL("CREATE INDEX index_messages_channel_key_received_id ON messages(COALESCE(NULLIF(TRIM(channel), ''), ''),received_at DESC,id DESC)")
            sql.execSQL("INSERT INTO messages(id,message_id,title,body,is_read,received_at,raw_payload_json,status,body_preview,entity_type) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>("old-messages", "old-message", "Historical", "Kept historical body", 0, RECEIVED_AT, "{\"entity_type\":\"message\"}", "NORMAL", "Historical", "message"))
            sql.execSQL("INSERT INTO thing_sub_messages(id,message_id,title,body,received_at,raw_payload_json,status,body_preview,entity_type,thing_id) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>("old-thing_sub_messages", "old-child-message", "Historical", "Kept historical body", RECEIVED_AT, "{\"entity_type\":\"message\",\"thing_id\":\"old-thing\"}", "NORMAL", "Historical", "message", "old-thing"))
            sql.execSQL("INSERT INTO event_change_logs(id,entity_id,title,body,raw_payload_json,received_at,event_id) VALUES(?,?,?,?,?,?,?)", arrayOf<Any>("old-event_change_logs", "old-event", "Historical", "Kept historical body", "{\"entity_type\":\"event\"}", RECEIVED_AT, "old-event"))
            sql.execSQL("INSERT INTO thing_change_logs(id,entity_id,title,body,raw_payload_json,received_at,thing_id) VALUES(?,?,?,?,?,?,?)", arrayOf<Any>("old-thing_change_logs", "old-thing", "Historical", "Kept historical body", "{\"entity_type\":\"thing\"}", RECEIVED_AT, "old-thing"))
            sql.execSQL("INSERT INTO thing_sub_events(id,entity_id,title,body,raw_payload_json,received_at,event_id,thing_id) VALUES(?,?,?,?,?,?,?,?)", arrayOf<Any>("old-thing_sub_events", "old-child-event", "Historical", "Kept historical body", "{\"entity_type\":\"event\",\"thing_id\":\"old-thing\"}", RECEIVED_AT, "old-child-event", "old-thing"))
            sql.execSQL("INSERT INTO inbound_delivery_ledger(gateway_url,device_key,delivery_id,entity_type,applied_at,ack_state,acked_at) VALUES(?,?,?,?,?,?,?)", arrayOf<Any>("https://migration.invalid", "synthetic-device", "migration-acked", "message", RECEIVED_AT, "acked", RECEIVED_AT))
            sql.execSQL("INSERT INTO inbound_delivery_ledger(gateway_url,device_key,delivery_id,entity_type,applied_at,ack_state) VALUES(?,?,?,?,?,?)", arrayOf<Any>("https://migration.invalid", "synthetic-device", "migration-pending", "message", RECEIVED_AT, "pending"))
            sql.execSQL("INSERT INTO inbound_delivery_ack_outbox(delivery_id,gateway_url,device_key,ack_contract,source,enqueued_at,updated_at,attempt_count,last_attempt_uncertain) VALUES(?,?,?,?,?,?,?,?,?)", arrayOf<Any>("migration-pending", "https://migration.invalid", "synthetic-device", "v2_batch", "provider_pull", RECEIVED_AT, RECEIVED_AT, 2, 1))
            sql.execSQL("INSERT INTO message_resource_owners(source_table,owner_id,owner_slot,generation) VALUES(?,?,?,?)", arrayOf<Any>("messages", "old-messages", 0, "0123456789abcdef0123456789abcdef"))
            sql.version = 36
        }
    }

    private fun scalar(sql: String): Int = db.openHelper.readableDatabase.query(sql).use {
        check(it.moveToFirst())
        it.getInt(0)
    }

    private fun message(id: String, delivery: String): PushMessage {
        val raw = JSONObject().put("entity_type", "message").put("message_id", id)
            .put("delivery_id", delivery).put("op_id", "op-$delivery").put("channel_id", "post-process-fixture")
        return PushMessage(id, id, "Synthetic pending work", "Same exact durable body", "post-process-fixture",
            null, false, Instant.ofEpochMilli(RECEIVED_AT), raw.toString(), MessageStatus.NORMAL, null, null, "synthetic-server")
    }

    private fun open() {
        db = PushGoDatabase.buildForTest(context, DB)
        messages = MessageRepository(db, db.messageDao(), db.messageChannelStatsDao(), db.messageMetadataIndexDao(),
            db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.thingHeadDao(), db.thingSubMessageDao(), db.pendingThingMessageDao())
    }

    private fun reopen() {
        db.close()
        open()
    }

    private companion object {
        const val DB = "post-process-recovery-paging-device-test.db"
        const val RECEIVED_AT = 1_800_000_000_000L
    }
}
