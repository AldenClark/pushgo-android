package io.ethan.pushgo.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.MessagePostProcessWorker
import io.ethan.pushgo.testing.QualityRuntime
import java.time.Instant
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual WorkManager recovery scanner and locator Worker, ordinary container/no QA session.
 * A deliberately expired alert responsibility suppresses OS publication without permission changes.
 * Container recreation is not a claim of process-death or normal external startup recovery. */
@RunWith(AndroidJUnit4::class)
class PostProcessWorkManagerDeviceTest {
    @Test
    fun actualRecoveryRegistersTwoPagesAndFinishesSeventyExpiredResponsibilitiesWithoutRemovingBodies() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PushGoApp>()
        assertNull(QualityRuntime.currentSession())
        app.releaseStorageForInstrumentationTest()
        val container = app.container
        // Only the known earlier task fixture may already exist. Never reset a default
        // container or adopt unrelated data merely to make a workload executable.
        val existingIds = container.database.openHelper.readableDatabase.query("SELECT id FROM messages").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue("default container contains only the prior named synthetic row", existingIds.all { it == "worker-canonical-synthetic" })
        val configuredGateway = container.database.openHelper.readableDatabase.query("SELECT server_address FROM app_settings WHERE id=1").use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
        assertTrue("normal container must not have an external Gateway", configuredGateway.isNullOrBlank())
        for (table in POST_PROCESS_TABLES.drop(1)) {
            assertEquals("no unrelated canonical rows in $table", 0, count(container.database, "SELECT COUNT(*) FROM $table"))
        }
        assertTrue("no previously unfinished obligation is adopted", POST_PROCESS_TABLES.indices.all {
            container.database.loadPendingPostProcess(it).isEmpty()
        })

        val prefix = "bulk-worker-${UUID.randomUUID()}-"
        val ids = (0 until 70).map { prefix + it.toString().padStart(3, '0') }
        val body = "Synthetic retained content; expired alert is not a content TTL"
        for (id in ids) {
            val raw = JSONObject().put("entity_type", "message").put("entity_id", id).put("message_id", id)
                .put("delivery_id", id).put("op_id", id).put("ttl", 1)
            val message = PushMessage(id, id, "Synthetic expired alert", body, "synthetic", null, false,
                Instant.now(), raw.toString(), MessageStatus.NORMAL, null, null, "synthetic")
            assertTrue(container.messageRepository.insertIncoming(message, postProcessFlags = POST_PROCESS_NOTIFICATION))
        }
        val first = container.database.loadPendingPostProcess(0)
        val boundary = first.last()
        val second = container.database.loadPendingPostProcess(0, boundary.flags, boundary.rowId)
        assertEquals(64, first.size)
        assertEquals(6, second.size)
        val locators = first + second
        assertEquals(70, locators.map { it.generation }.toSet().size)
        val samples = JSONArray()
        fun snapshot(stage: String, includeMemory: Boolean = true): JSONObject {
            val pending = count(container.database, "SELECT COUNT(*) FROM messages WHERE post_process_pending>0")
            return JSONObject().put("stage", stage).put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                .put("process_cpu_ms", Process.getElapsedCpuTime()).put("pending", pending).apply {
                    if (includeMemory) {
                        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                        put("pss_kib", memory.totalPss).put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize())
                        put("java_heap_used_bytes", Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                    }
                }.also { samples.put(it) }
        }
        val before = snapshot("before_enqueue")
        val start = SystemClock.elapsedRealtimeNanos()
        MessagePostProcessWorker.enqueueRecovery(app)
        val enqueueReturnMs = (SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
        withTimeout(45_000) {
            while (snapshot("recovering", includeMemory = false).getInt("pending") != 0) delay(100)
        }
        val pendingZeroMs = (SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
        val manager = WorkManager.getInstance(app)
        var recovery: List<WorkInfo> = emptyList()
        withTimeout(15_000) {
            do {
                recovery = manager.getWorkInfosForUniqueWork("message-post-process-recovery").get(5, TimeUnit.SECONDS)
                if (recovery.all { it.state.isFinished }) break
                delay(50)
            } while (true)
        }
        assertTrue("the actual scanner registered a continuation beyond its 64-row first page", recovery.size >= 2)
        assertTrue(recovery.all { it.state == WorkInfo.State.SUCCEEDED })
        var actualLocatorStates = JSONArray()
        withTimeout(15_000) {
            do {
                actualLocatorStates = JSONArray()
                for (locator in locators) {
                    val infos = manager.getWorkInfosForUniqueWork("message-post-process:${locator.tableIndex}:${locator.generation}")
                        .get(5, TimeUnit.SECONDS)
                    assertEquals(1, infos.size)
                    assertTrue("a registered obligation cannot fail silently", infos.single().state != WorkInfo.State.FAILED)
                    actualLocatorStates.put(infos.single().state.name)
                }
                if ((0 until actualLocatorStates.length()).all { actualLocatorStates.getString(it) == WorkInfo.State.SUCCEEDED.name }) break
                delay(100)
            } while (true)
        }
        for (id in ids) {
            assertEquals(body, container.messageRepository.getById(id)?.body)
            assertTrue(container.inboundDeliveryLedgerRepository.shouldAck(id, null))
        }
        val drained = snapshot("all_work_finished")
        delay(10_000)
        val idle = snapshot("idle_10s")
        assertEquals(0, idle.getInt("pending"))
        assertEquals(existingIds.size + 70, container.messageRepository.totalCount())
        val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: File(app.cacheDir, "post-process-bulk-output").absolutePath).apply { mkdirs() }
        File(output, "actual-workmanager-70.json").writeText(JSONObject()
            .put("kind", "actual WorkManager scanner and generation-locator workers, 70 expired notification facts")
            .put("rows", 70).put("page_sizes", JSONArray(listOf(64, 6)))
            .put("enqueue_call_return_ms", enqueueReturnMs).put("enqueue_to_pending_zero_ms", pendingZeroMs)
            .put("cpu_until_all_work_finished_ms", drained.getLong("process_cpu_ms")-before.getLong("process_cpu_ms"))
            .put("idle_cpu_ms", idle.getLong("process_cpu_ms")-drained.getLong("process_cpu_ms"))
            .put("scanner_chain_states", JSONArray(recovery.map { it.state.name }))
            .put("locator_states", actualLocatorStates).put("samples", samples)
            .put("limits", "Debug emulator; includes 100ms COUNT polling/measurement overhead; PSS only before, drained and idle snapshots, not peak; no notification publication, media download, wire ACK, process death or physical-power claim")
            .toString(2))
        // Keep the synthetic canonical rows and prior row as evidence. No database reset,
        // permission request, channel override or externally routed request is needed.
        app.releaseStorageForInstrumentationTest()
    }

    @Test
    fun currentRecoveryAddsSeventyExpiredResponsibilitiesAndPreservesTheKnownSeventyOneBodies() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PushGoApp>()
        assertNull(QualityRuntime.currentSession())
        app.releaseStorageForInstrumentationTest()
        val container = app.container
        val database = container.database
        assertEquals(39, count(database, "PRAGMA user_version"))
        val idsBefore = database.openHelper.readableDatabase.query("SELECT id FROM messages").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        // Exact earlier task fixture: one named row plus one complete, single-UUID
        // 000..069 batch. An arbitrary real database or partial old run is not adopted.
        assertEquals(71, idsBefore.size); assertTrue("worker-canonical-synthetic" in idsBefore)
        val pattern = Regex("^bulk-worker-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-([0-9]{3})$")
        val priorBatch = idsBefore.filter { it != "worker-canonical-synthetic" }.map { requireNotNull(pattern.matchEntire(it)) }
        assertEquals(1, priorBatch.map { it.groupValues[1] }.toSet().size)
        assertEquals((0 until 70).toSet(), priorBatch.map { it.groupValues[2].toInt() }.toSet())
        val original = database.messageDao().getByIds(idsBefore.toList()).associateBy { it.id }
        for ((id, row) in original) {
            val one = id == "worker-canonical-synthetic"
            assertEquals(if (one) "This body must remain after alert suppression" else "Synthetic retained content; expired alert is not a content TTL", row.body)
            assertEquals(if (one) "Expired alert, durable body" else "Synthetic expired alert", row.title)
            assertEquals("synthetic", row.channel); assertEquals("synthetic", row.serverId)
            assertNull(row.url); assertEquals("NORMAL", row.status); assertEquals(0, row.postProcessPending)
            val raw = JSONObject(row.rawPayloadJson)
            assertEquals(setOf("entity_type", "entity_id", "message_id", "delivery_id", "op_id", "ttl"), raw.keys().asSequence().toSet())
            for (key in listOf("entity_id", "message_id", "delivery_id", "op_id")) assertEquals(id, raw.getString(key))
            assertEquals("message", raw.getString("entity_type")); assertEquals(1L, raw.getLong("ttl"))
        }
        val configuredGateway = database.openHelper.readableDatabase.query("SELECT server_address FROM app_settings WHERE id=1").use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
        assertTrue("no external Gateway is permitted", configuredGateway.isNullOrBlank())
        for (table in POST_PROCESS_TABLES.drop(1)) assertEquals(0, count(database, "SELECT COUNT(*) FROM $table"))
        assertTrue(POST_PROCESS_TABLES.indices.all { database.loadPendingPostProcess(it).isEmpty() })
        val manager = WorkManager.getInstance(app)
        val priorRecovery = manager.getWorkInfosForUniqueWork("message-post-process-recovery").get(5, TimeUnit.SECONDS)
        assertTrue("do not adopt an active earlier scanner", priorRecovery.all { it.state.isFinished })
        val priorRecoveryIds = priorRecovery.map { it.id }.toSet()
        val scope = requireNotNull(InboundDeliveryScope.create("https://bulk-worker-current.invalid", "current-bulk-synthetic-device"))
        val prefix = "bulk-worker-current-${UUID.randomUUID()}-"
        val ids = (0 until 70).map { prefix + it.toString().padStart(3, '0') }
        val body = "Synthetic current retained content; expired alert is not a content TTL"
        for (id in ids) {
            val raw = JSONObject().put("entity_type", "message").put("entity_id", id).put("message_id", id)
                .put("delivery_id", id).put("op_id", id).put("ttl", 1)
            val message = PushMessage(id, id, "Current synthetic expired alert", body, "synthetic", null, false,
                Instant.now(), raw.toString(), MessageStatus.NORMAL, null, null, "synthetic")
            assertTrue(container.messageRepository.insertIncoming(message, deliveryScope = scope, postProcessFlags = POST_PROCESS_NOTIFICATION))
        }
        val first = database.loadPendingPostProcess(0); val last = first.last()
        val second = database.loadPendingPostProcess(0, last.flags, last.rowId)
        assertEquals(64, first.size); assertEquals(6, second.size)
        val locators = first + second
        assertEquals(70, locators.map { it.generation }.toSet().size)
        val samples = JSONArray()
        fun snapshot(stage: String, memory: Boolean = true): JSONObject = JSONObject().put("stage", stage)
            .put("elapsed_realtime_ms", SystemClock.elapsedRealtime()).put("process_cpu_ms", Process.getElapsedCpuTime())
            .put("pending", count(database, "SELECT COUNT(*) FROM messages WHERE post_process_pending>0")).apply {
                if (memory) {
                    val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                    put("pss_kib", info.totalPss); put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize())
                    put("java_heap_used_bytes", Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                }
            }.also { samples.put(it) }
        val before = snapshot("before_enqueue"); val start = SystemClock.elapsedRealtimeNanos()
        MessagePostProcessWorker.enqueueRecovery(app)
        val enqueueReturnMs = (SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
        withTimeout(45_000) { while (snapshot("recovering", false).getInt("pending") != 0) delay(100) }
        val pendingZeroMs = (SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0
        var recovery: List<WorkInfo> = emptyList()
        withTimeout(15_000) {
            while (true) {
                recovery = manager.getWorkInfosForUniqueWork("message-post-process-recovery").get(5, TimeUnit.SECONDS).filter { it.id !in priorRecoveryIds }
                if (recovery.isNotEmpty() && recovery.all { it.state.isFinished }) break
                delay(50)
            }
        }
        assertTrue("the new real scanner must continue past one bounded page", recovery.size >= 2)
        assertTrue(recovery.all { it.state == WorkInfo.State.SUCCEEDED })
        var states = JSONArray()
        withTimeout(15_000) {
            while (true) {
                states = JSONArray()
                for (locator in locators) {
                    val work = manager.getWorkInfosForUniqueWork("message-post-process:${locator.tableIndex}:${locator.generation}").get(5, TimeUnit.SECONDS)
                    assertEquals(1, work.size); assertNotEquals(WorkInfo.State.FAILED, work.single().state)
                    states.put(work.single().state.name)
                }
                if ((0 until states.length()).all { states.getString(it) == WorkInfo.State.SUCCEEDED.name }) break
                delay(100)
            }
        }
        val drained = snapshot("all_work_finished")
        for ((id, row) in original) assertEquals("old fixture $id must remain byte-for-byte", row, database.messageDao().getById(id))
        for (id in ids) {
            assertEquals(body, container.messageRepository.getById(id)?.body)
            assertTrue(container.inboundDeliveryLedgerRepository.shouldAckDelivery(id, scope))
        }
        assertEquals(141, count(database, "SELECT COUNT(*) FROM messages"))
        val idleStart = snapshot("before_idle_10s", false)
        delay(10_000); val idle = snapshot("idle_10s"); assertEquals(0, idle.getInt("pending"))
        val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: File(app.cacheDir, "post-process-current-bulk-output").absolutePath).apply { mkdirs() }
        File(output, "actual-current-workmanager-70.json").writeText(JSONObject()
            .put("kind", "actual current Worker recovery appended to a strictly verified 71-row synthetic baseline")
            .put("baseline_ids", JSONArray(idsBefore.sorted())).put("new_ids", JSONArray(ids)).put("baseline_body_rows", 71).put("new_rows", 70).put("retained_body_rows", 141)
            .put("preview_page_sizes", JSONArray(listOf(64, 6))).put("actual_new_scanner_states", JSONArray(recovery.map { it.state.name }))
            .put("actual_new_scanner_ids", JSONArray(recovery.map { it.id.toString() })).put("locator_states", states)
            .put("enqueue_call_return_ms", enqueueReturnMs).put("enqueue_to_pending_zero_ms", pendingZeroMs)
            .put("cpu_until_all_work_finished_ms", drained.getLong("process_cpu_ms")-before.getLong("process_cpu_ms"))
            .put("idle_cpu_ms", idle.getLong("process_cpu_ms")-idleStart.getLong("process_cpu_ms"))
            .put("idle_elapsed_ms", idle.getLong("elapsed_realtime_ms")-idleStart.getLong("elapsed_realtime_ms")).put("samples", samples)
            .put("limits", "Current Debug emulator actual DoWork; 64/6 is an indexed preview, actual scanner IDs/states separately observed. COUNT100ms and WorkManager/background overhead included; PSS is three points, not peak, performance comparison or energy. No media, OS publication, wire ACK or process death.").toString(2))
        app.releaseStorageForInstrumentationTest()
    }

    private fun count(database: io.ethan.pushgo.data.db.PushGoDatabase, sql: String): Int =
        database.openHelper.readableDatabase.query(sql).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    @Test
    fun existingScannerAndActualWorkerRecoverCommittedCanonicalPendingAfterContainerRecreation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PushGoApp>()
        assertNull(QualityRuntime.currentSession())
        app.releaseStorageForInstrumentationTest()
        val container = app.container
        // This task-owned APK is installed on an empty synthetic AVD profile by UTP.
        // Never reset or silently adopt a database containing unrelated content.
        assertEquals(0L, container.messageRepository.totalCount().toLong())
        val id = "worker-canonical-synthetic"
        val raw = JSONObject().put("entity_type", "message").put("entity_id", id).put("message_id", id)
            .put("delivery_id", id).put("op_id", id).put("ttl", 1)
        val message = PushMessage(id, id, "Expired alert, durable body", "This body must remain after alert suppression",
            "synthetic", null, false, Instant.now(), raw.toString(), MessageStatus.NORMAL, null, null, "synthetic")
        // Explicit delayed-work fixture: a once-accepted alert is now past its absolute TTL.
        assertTrue(container.messageRepository.insertIncoming(message, postProcessFlags = POST_PROCESS_NOTIFICATION))
        assertEquals(POST_PROCESS_NOTIFICATION, container.database.messageDao().getById(id)?.postProcessPending)
        app.releaseStorageForInstrumentationTest()
        val reopened = app.container
        assertEquals(message.body, reopened.messageRepository.getById(id)?.body)
        assertEquals(POST_PROCESS_NOTIFICATION, reopened.database.messageDao().getById(id)?.postProcessPending)
        MessagePostProcessWorker.enqueueRecovery(app)
        withTimeout(15_000) {
            while (reopened.database.messageDao().getById(id)?.postProcessPending != 0) delay(25)
        }
        assertEquals(message.body, reopened.messageRepository.getById(id)?.body)
        assertTrue(reopened.inboundDeliveryLedgerRepository.shouldAck(id, null))
        assertTrue(reopened.database.loadPendingPostProcess(0).isEmpty())
        app.releaseStorageForInstrumentationTest()
    }
}
