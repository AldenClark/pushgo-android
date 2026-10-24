package io.ethan.pushgo.data

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.InboundPersistenceCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** One fixed production-module load. Emulator/Debug, local disposition is NOT wire ACK,
 * synthetic 48KiB canonical body is NOT the HTTP JSON boundary. No notification/network. */
@RunWith(AndroidJUnit4::class)
class DeliveryBoundedLoadDeviceTest {
    @Test
    fun fixedSameProcessLoadAndFalloff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "bounded-delivery-load-synthetic.db"
        context.deleteDatabase(name)
        var db = PushGoDatabase.buildForTest(context, name)
        fun repositories(database: PushGoDatabase) = Components(context, database)
        var components = repositories(db)
        val identity = requireNotNull(ProviderAckIdentity.create(ProviderAckDestination("https://bounded-load.invalid", "synthetic-device"), ProviderAckContract.V2_BATCH, "provider_pull"))
        val rows = JSONArray(); val samples = JSONArray(); val observations = JSONArray()
        val beginning = SystemClock.elapsedRealtimeNanos()
        var offered = 0; var accepted = 0; var safe = 0; var postCommit = 0; var replayed = 0
        fun observation(stage: String) {
            val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
            observations.put(JSONObject().put("stage", stage).put("elapsed_ms", (SystemClock.elapsedRealtimeNanos()-beginning)/1_000_000.0)
                .put("cpu_ms", android.os.Process.getElapsedCpuTime()).put("pss_kib", info.totalPss)
                .put("private_dirty_kib", info.totalPrivateDirty).put("java_heap_used_bytes", Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                .put("jvm_threads_not_async_tasks", Thread.getAllStackTraces().size))
        }
        suspend fun offer(id: Int, stage: String, replay: Boolean = false, pendingAck: Boolean = false) {
            val operation = "load-$id"
            val text = if (id in 10..12) "x".repeat(48*1024) else "body-$id".repeat(16)
            val raw = JSONObject().put("entity_type", "message").put("entity_id", operation).put("message_id", operation)
                .put("delivery_id", operation).put("op_id", "op-$operation").toString()
            val message = PushMessage(operation, operation, "Title $id", text, "synthetic", null, false,
                Instant.ofEpochMilli(1_800_000_000_000L+id), raw, MessageStatus.NORMAL, null, null, "synthetic-server")
            offered++
            val started = SystemClock.elapsedRealtimeNanos(); var committed: Long? = null
            val result = InboundPersistenceCoordinator.persistAndNotify(context, components.messages, components.entities,
                components.ledger, components.settings, InboundPersistenceRequest.Message(message, null, null, false, identity),
                beforeMessageNotify = { _, _ -> committed = SystemClock.elapsedRealtimeNanos() })
            val ended = SystemClock.elapsedRealtimeNanos()
            assertTrue("accepted business content must ACK safely", result.shouldAck)
            assertEquals(text, components.messages.getById(operation)?.body)
            safe++
            if (replay) replayed++ else accepted++
            if (committed != null) postCommit++
            samples.put(JSONObject().put("id", operation).put("stage", stage).put("replay", replay)
                .put("return_ack_disposition_ms", (ended-started)/1_000_000.0)
                .put("post_commit_hook_upper_bound_ms", committed?.let { (it-started)/1_000_000.0 } ?: JSONObject.NULL))
            if (pendingAck) components.ledger.enqueueAcks(listOf(operation), identity)
        }
        try {
            observation("cold_open"); delay(20_000); observation("idle_20s")
            for (id in 0 until 8) { offer(id, "low"); delay(250) }; observation("after_low")
            for (id in 8 until 40) offer(id, "burst"); observation("after_burst")
            for (id in 40 until 72) { offer(id, "sustained"); delay(100) }; observation("after_sustained")
            for (id in 72 until 88) offer(id, "offline_ack_pending", pendingAck = true)
            assertEquals(16, components.ledger.loadPendingAcks().size); observation("pending_ack_16")
            db.close(); db = PushGoDatabase.buildForTest(context, name); components = repositories(db)
            assertEquals(16, components.ledger.loadPendingAcks().size)
            for (id in 72 until 88) offer(id, "reopen_replay", replay = true)
            val ackRows = components.ledger.loadPendingAcks(); components.ledger.markAckRecordsAcked(ackRows)
            assertTrue(components.ledger.loadPendingAcks().isEmpty()); assertEquals(88L, components.messages.totalCount().toLong())
            observation("after_recovery"); delay(20_000); observation("falloff_20s")
            val record = JSONObject().put("test", "same-process-debug-room-load-v1").put("offered", offered).put("new_canonical", accepted)
                .put("replayed", replayed).put("ack_safe_dispositions_not_network_ack", safe).put("post_commit_hooks", postCommit)
                .put("failed", 0).put("canonical_rows", 88).put("pending_ack_after", 0)
                .put("samples", samples).put("observations", observations)
                .put("limits", "Debug API37 ARM64 emulator; 20s idle does not cover six-hour maintenance/pool timeouts; no HTTP/network throughput, real background OS, physical energy or release speed claim")
            val output = java.io.File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: java.io.File(context.externalMediaDirs.first(), "additional_test_output").absolutePath).apply { mkdirs() }
            java.io.File(output, "delivery-bounded-load-${java.util.UUID.randomUUID()}.json").writeText(record.toString(2))
            println("DELIVERY_BOUNDED_LOAD offered=$offered accepted=$accepted replayed=$replayed safe=$safe")
            assertEquals(104, offered); assertEquals(104, safe); assertEquals(88, accepted)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private class Components(context: Context, db: PushGoDatabase) {
        val messages = MessageRepository(db, db.messageDao(), db.messageChannelStatsDao(), db.messageMetadataIndexDao(),
            db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.thingHeadDao(), db.thingSubMessageDao(), db.pendingThingMessageDao())
        val entities = EntityRepository(db, db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.eventChangeLogDao(),
            db.thingChangeLogDao(), db.thingSubEventDao(), db.topLevelEventHeadDao(), db.thingHeadDao(), db.thingSubMessageDao())
        val ledger = InboundDeliveryLedgerRepository(db, db.inboundDeliveryLedgerDao(), db.inboundDeliveryAckOutboxDao(), db.legacyProviderIngressDao())
        val settings = SettingsRepository(db.appSettingsDao(), AndroidKeystoreSecretStore(context, "bounded-load-synthetic-secrets"),
            context.getSharedPreferences("bounded-load-synthetic-settings", Context.MODE_PRIVATE))
    }
}
