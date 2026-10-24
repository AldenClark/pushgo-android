package io.ethan.pushgo.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.db.ThingSubMessageEntity
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native Room: business-order equivalence, bounded full-payload residency and the original
 * all-or-nothing promotion transaction. Fault rows are deliberate synthetic injections. */
@RunWith(AndroidJUnit4::class)
class PendingMessageReplayPagingDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PushGoDatabase
    private lateinit var messages: MessageRepository
    @Before fun prepare() { context.deleteDatabase(DB); open() }
    @After fun cleanup() { db.close(); context.deleteDatabase(DB) }

    @Test
    fun threePagesPreserveVisibleOrderBodyIdentityAndTimestampsWithoutRetaining137Payloads() = runBlocking {
        seed()
        val expected = db.pendingThingMessageDao().loadByThingId("parent").asReversed().map { Triple(it.id, it.receivedAt, digest(it.body)) }
        val sizes = mutableListOf<Int>()
        messages.replayPendingForThing("parent", onReplayPage = sizes::add)
        assertEquals(listOf(64, 64, 9), sizes)
        assertTrue(db.pendingThingMessageDao().loadByThingId("parent").isEmpty())
        val actual = db.thingSubMessageDao().getByThingId("parent").map { Triple(it.id, it.receivedAt, digest(it.body)) }
        assertEquals(expected, actual)
        assertEquals(137, actual.size)
        val plan = explain()
        android.util.Log.i("PostProcessPlan", "pending-promotion=$plan")
        assertTrue("Actual native query plan: $plan", plan.all { !it.contains("TEMP B-TREE") && !it.startsWith("SCAN ") })
        assertTrue(plan.any { it.contains("index_pending_thing_messages_thing_id_id") })
        reopen()
        assertEquals(actual, db.thingSubMessageDao().getByThingId("parent").map { Triple(it.id, it.receivedAt, digest(it.body)) })
    }

    @Test
    fun secondPageStorageFailureRollsBackEveryPromotionAndRetainsOriginalPendingResponsibility() = runBlocking {
        seed()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_replay_page BEFORE INSERT ON thing_sub_messages WHEN NEW.id='pending-080' BEGIN SELECT RAISE(ABORT,'synthetic second page storage failure'); END")
        val sizes = mutableListOf<Int>()
        val failed = runCatching { messages.replayPendingForThing("parent", onReplayPage = sizes::add) }.exceptionOrNull()
        assertNotNull(failed)
        assertTrue(requireNotNull(failed).message.orEmpty().contains("synthetic second page storage failure"))
        assertEquals(listOf(64, 64), sizes)
        assertEquals(0, scalar("SELECT COUNT(*) FROM thing_sub_messages"))
        assertEquals(137, scalar("SELECT COUNT(*) FROM pending_thing_messages"))
        reopen()
        assertEquals(137, scalar("SELECT COUNT(*) FROM pending_thing_messages"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_replay_page")
        messages.replayPendingForThing("parent")
        assertEquals(137, scalar("SELECT COUNT(*) FROM thing_sub_messages"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM pending_thing_messages"))
    }

    @Test
    fun canonicalIdConflictAndPageCancellationKeepTheOriginalAtomicRollbackBoundary() = runBlocking {
        seed()
        val conflicting = message(80).copy(messageId = "different-existing-business-id", body = "Existing body must survive")
        db.thingSubMessageDao().insert(ThingSubMessageEntity.fromModel(conflicting))
        assertTrue(runCatching { messages.replayPendingForThing("parent") }.isFailure)
        assertEquals(137, scalar("SELECT COUNT(*) FROM pending_thing_messages"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM thing_sub_messages"))
        assertEquals(conflicting.body, db.thingSubMessageDao().getById(conflicting.id)?.body)
        db.thingSubMessageDao().deleteByIds(listOf(conflicting.id))
        var pages = 0
        var cancelled = false
        try { messages.replayPendingForThing("parent", onReplayPage = { if (++pages == 2) throw CancellationException("synthetic second page cancellation") }) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, scalar("SELECT COUNT(*) FROM thing_sub_messages"))
        assertEquals(137, scalar("SELECT COUNT(*) FROM pending_thing_messages"))
        messages.replayPendingForThing("parent")
        assertEquals(137, scalar("SELECT COUNT(*) FROM thing_sub_messages"))
    }

    private suspend fun seed() {
        // No parent exists yet: the normal repository stores and claims each waiting row.
        repeat(137) { assertFalse(messages.insertIncoming(message(it))) }
        assertEquals(137, scalar("SELECT COUNT(*) FROM pending_thing_messages"))
    }
    private fun message(index: Int): PushMessage {
        val id = "pending-${index.toString().padStart(3, '0')}"
        val raw = JSONObject().put("entity_type", "message").put("entity_id", id).put("thing_id", "parent")
            .put("delivery_id", id).put("message_id", id).put("op_id", "op-$id").put("occurred_at", 1_700_000_000L+(137-index)%4)
        return PushMessage(id, id, "Same time $index", "x".repeat(24*1024)+index, "channel", null, false,
            Instant.ofEpochMilli(1_800_000_000_000L), raw.toString(), MessageStatus.NORMAL, null, null, "synthetic")
    }
    private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun scalar(sql: String) = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }
    private fun explain() = db.openHelper.readableDatabase.query(
        "EXPLAIN QUERY PLAN SELECT * FROM pending_thing_messages INDEXED BY index_pending_thing_messages_thing_id_id WHERE thing_id=? AND id>? ORDER BY id LIMIT 64",
        arrayOf("parent", "pending-063"),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) } }
    private fun open() { db = PushGoDatabase.buildForTest(context, DB); messages = MessageRepository(db,
        db.messageDao(), db.messageChannelStatsDao(), db.messageMetadataIndexDao(), db.inboundDeliveryLedgerDao(),
        db.operationLedgerDao(), db.thingHeadDao(), db.thingSubMessageDao(), db.pendingThingMessageDao()) }
    private fun reopen() { db.close(); open() }
    private companion object { const val DB = "pending-message-replay-paging-synthetic.db" }
}
