package io.ethan.pushgo.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.db.ThingSubEventEntity
import io.ethan.pushgo.notifications.InboundPersistenceCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import io.ethan.pushgo.notifications.InboundPersistenceStatus
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThingEventActivationDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PushGoDatabase
    private lateinit var entities: EntityRepository
    private lateinit var messages: MessageRepository
    private lateinit var ledger: InboundDeliveryLedgerRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context.deleteDatabase(DB)
        clearPreferences()
        open()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB)
        clearPreferences()
    }

    @Test
    fun childReopenDuplicateAndParentPreserveContentAndAck() = runBlocking {
        val identity = requireNotNull(ProviderAckIdentity.create(
            destination = ProviderAckDestination("https://child-gateway.example", "child-device"),
            contract = ProviderAckContract.LEGACY_SINGLE,
            source = "provider_direct",
        ))
        val child = record("event", "event", "thing", "child", 1)
        val pending = persist(child, notify = true, identity = identity)
        assertEquals(InboundPersistenceStatus.PERSISTED_PENDING, pending.status)
        assertTrue(pending.shouldAck)
        assertFalse(pending.notified)
        assertNull(entities.getEventProjectionDetail("event"))
        ledger.enqueueAcks(listOf("child"), identity)
        val originalAck = ledger.loadPendingAcks().single()
        assertEquals("child", originalAck.deliveryId)
        assertEquals("https://child-gateway.example", originalAck.gatewayUrl)
        assertEquals("child-device", originalAck.deviceKey)
        db.close()
        open()
        assertEquals(originalAck, ledger.loadPendingAcks().single())
        val duplicate = persist(child, notify = true, identity = identity)
        assertEquals(InboundPersistenceStatus.PERSISTED_PENDING, duplicate.status)
        assertTrue(duplicate.shouldAck)
        assertFalse(duplicate.notified)
        assertFalse(persist(parent("thing", "parent", 2)).notified)
        val childHistory = entities.getThingProjectionDetail("thing")!!.history.filter { it.eventId == "event" }
        assertEquals(1, childHistory.size)
        assertEquals(child.body, childHistory.single().body)
        assertEquals(child.title, childHistory.single().title)
        assertEquals(child.rawPayloadJson, childHistory.single().rawPayloadJson)
        assertEquals(child.receivedAt, childHistory.single().receivedAt)
        assertTrue(ledger.shouldAck("child", identity))
        assertEquals(originalAck, ledger.loadPendingAcks().single())
        assertFalse(entities.insertIncoming(child.copy(title = "Rejected duplicate", body = "Rejected duplicate"), identity))
        assertEquals(child.body, entities.getEventProjectionDetail("event")!!.history.single().body)
        ledger.markAckRecordsAcked(listOf(originalAck))
        assertTrue(ledger.loadPendingAcks().isEmpty())
        val ackedDuplicate = persist(child, notify = true, identity = identity)
        assertFalse(ackedDuplicate.shouldAck)
        assertFalse(ackedDuplicate.notified)
        assertEquals(child.body, entities.getEventProjectionDetail("event")!!.history.single().body)
    }

    @Test
    fun activeAndWaitingSameFallbackIdCoexistUntilGlobalActiveWins() = runBlocking {
        val active = record("event", "event", "other-thing", null, 1)
        seed(active, "shared", active = true)
        seed(record("event", "event", "thing", null, 2), "shared", active = false)
        assertEquals(active.body, db.thingSubEventDao().getById("shared")!!.body)
        assertEquals(1, rows("shared", active = false).size)
        db.close()
        open()
        entities.activateStoredEventsForThing("thing")
        assertTrue(rows("shared", active = false).isEmpty())
        assertEquals(active.body, db.thingSubEventDao().getById("shared")!!.body)
        assertNull(entities.getThingProjectionDetail("thing"))
        assertEquals(active.body, entities.getThingProjectionDetail("other-thing")!!.history.single().body)

        val fallbackWaiting = record("event", "fallback", "later", null, 3)
        assertFalse(entities.insertIncoming(fallbackWaiting))
        assertFalse(entities.insertIncoming(fallbackWaiting.copy(title = "Ignored retry", body = "Ignored retry")))
        assertTrue(entities.insertIncoming(parent("later", "later-parent", 4)))
        assertEquals(fallbackWaiting.body, entities.getEventProjectionDetail("fallback")!!.history.single().body)
        // No delivery identity means the old formal REPLACE behavior still applies.
        assertTrue(entities.insertIncoming(fallbackWaiting.copy(title = "Replacement", body = "Replacement")))
        assertEquals("Replacement", entities.getEventProjectionDetail("fallback")!!.history.single().body)

        // Real ingress can retain both contents when the parent channel is removed.
        assertTrue(entities.insertIncoming(parent("dual-thing", "dual-parent", 20).copy(channel = "parent-channel")))
        val activeChild = record("event", "dual-event", "dual-thing", null, 21, channel = "child-channel")
        assertTrue(entities.insertIncoming(activeChild))
        entities.deleteThings(channelId = "parent-channel")
        val waitingChild = activeChild.copy(title = "Waiting replacement", body = "Waiting replacement")
        assertFalse(entities.insertIncoming(waitingChild))
        val dualId = "dual-thing:dual-event:${activeChild.receivedAt.toEpochMilli()}"
        db.close()
        open()
        assertEquals(listOf(activeChild.body), rows(dualId, active = true))
        assertEquals(listOf(waitingChild.body), rows(dualId, active = false))
        // Deleting the active event first leaves the other slot eligible for activation.
        entities.deleteEvent("dual-event")
        assertTrue(entities.insertIncoming(parent("dual-thing", "dual-parent-new", 22)))
        assertEquals(waitingChild.body, entities.getEventProjectionDetail("dual-event")!!.history.single().body)
        assertTrue(rows(dualId, active = false).isEmpty())

        // The reverse ordering consumes the waiting collision before event deletion.
        seed(record("event", "after-event", "after-thing", null, 23), "after-id", active = true)
        seed(record("event", "after-event", "after-thing", null, 24).copy(body = "Consumed waiting"), "after-id", active = false)
        entities.activateStoredEventsForThing("after-thing")
        assertTrue(rows("after-id", active = false).isEmpty())
        entities.deleteEvent("after-event")
        entities.activateStoredEventsForThing("after-thing")
        assertNull(entities.getEventProjectionDetail("after-event"))
        assertTrue(rows("after-id", active = true).isEmpty())
    }

    @Test
    fun eventAndThingDeletesRetainTheirDifferentWaitingScopes() = runBlocking {
        val retained = record("event", "retained", "thing-retained", "retained", 1)
        assertFalse(entities.insertIncoming(retained))
        entities.deleteEvent("retained")
        entities.deleteEvents(listOf("retained"))
        entities.deleteEvents(channelId = "channel")
        entities.deleteEvents(channelId = null)
        assertTrue(entities.insertIncoming(parent("thing-retained", "retained-parent", 2)))
        assertEquals(retained.body, entities.getEventProjectionDetail("retained")!!.history.single().body)
        entities.deleteEvents(listOf("retained"))
        assertNull(entities.getEventProjectionDetail("retained"))
        assertFalse(entities.insertIncoming(retained))

        val byId = record("event", "by-id", "id-thing", "id-child", 3)
        val byBatch = record("event", "by-batch", "batch-thing", "batch-child", 4)
        val byChannel = record("event", "by-channel", "channel-thing", "channel-child", 5, channel = "remove")
        val survivor = record("event", "survivor", "channel-thing", "survivor", 6, channel = "keep")
        for (child in listOf(byId, byBatch, byChannel, survivor)) assertFalse(entities.insertIncoming(child))
        entities.deleteThing("id-thing")
        entities.deleteThings(listOf("batch-thing"))
        entities.deleteThings(channelId = "remove")
        for ((thing, delivery) in listOf("id-thing" to "id-parent", "batch-thing" to "batch-parent", "channel-thing" to "channel-parent")) {
            assertTrue(entities.insertIncoming(parent(thing, delivery, 7)))
        }
        for (id in listOf("by-id", "by-batch", "by-channel")) assertNull(entities.getEventProjectionDetail(id))
        assertEquals(survivor.body, entities.getEventProjectionDetail("survivor")!!.history.single().body)
        assertFalse(entities.insertIncoming(byId))
        assertTrue(ledger.shouldAckDelivery("id-child", null))
        assertFalse(entities.insertIncoming(byId.copy(deliveryId = "new-delivery-same-op")))
        val newOp = record("event", "by-id", "id-thing", "new-operation", 8)
        assertTrue(entities.insertIncoming(newOp))
        assertEquals(newOp.body, entities.getEventProjectionDetail("by-id")!!.history.single().body)

        val automation = record("event", "automation", "automation-thing", "automation", 9)
        assertFalse(entities.insertIncoming(automation))
        entities.deleteAll()
        assertNull(entities.getEventProjectionDetail("survivor"))
        assertTrue(entities.insertIncoming(parent("automation-thing", "automation-parent", 10)))
        assertEquals(automation.body, entities.getEventProjectionDetail("automation")!!.history.single().body)
        val clear = record("event", "clear", "clear-thing", "clear", 11)
        assertFalse(entities.insertIncoming(clear))
        entities.deleteThings(channelId = null)
        assertTrue(entities.insertIncoming(parent("clear-thing", "clear-parent", 12)))
        assertNull(entities.getEventProjectionDetail("clear"))
        assertNull(entities.getEventProjectionDetail("automation"))
    }

    @Test
    fun headlessHistorySurvivesCrossChannelParentDeletionAndDuplicateParentActivates() = runBlocking {
        val originalParent = parent("thing", "parent", 1).copy(channel = "parent-channel")
        assertTrue(entities.insertIncoming(originalParent))
        val child = record("event", "event", "thing", "child", 2, channel = "child-channel")
        assertTrue(entities.insertIncoming(child))
        entities.deleteEvents("parent-channel")
        entities.deleteThings("parent-channel")
        assertNull(db.thingHeadDao().getByThingId("thing"))
        val detail = entities.getThingProjectionDetail("thing")!!
        assertNull(detail.head)
        assertEquals(child.body, detail.history.single().body)
        val waiting = record("event", "waiting", "thing", "waiting", 3)
        assertFalse(entities.insertIncoming(waiting))
        val duplicate = persist(originalParent)
        assertEquals(InboundPersistenceStatus.DUPLICATE, duplicate.status)
        assertTrue(duplicate.shouldAck)
        assertFalse(duplicate.notified)
        assertNull(db.thingHeadDao().getByThingId("thing"))
        assertEquals(waiting.body, entities.getEventProjectionDetail("waiting")!!.history.single().body)
    }

    @Test
    fun hiddenProjectionIsolationActivationAndLaterBidirectionalPatches() = runBlocking {
        val hidden = record("event", "event", "thing", "hidden", 1)
        assertFalse(entities.insertIncoming(hidden))
        assertTrue(entities.insertIncoming(record("event", "event", null, "top", 2, state = "closed")))
        assertTrue(db.thingSubEventDao().getByEventId("event").isEmpty())
        assertTrue(entities.insertIncoming(parent("thing", "parent", 3)))
        assertEquals("open", db.thingSubEventDao().getByEventId("event").single().eventState)
        assertEquals(hidden.body, db.thingSubEventDao().getByEventId("event").single().body)
        assertTrue(entities.insertIncoming(record("event", "event", "thing", "child-patch", 4, state = "acknowledged")))
        assertEquals("acknowledged", db.topLevelEventHeadDao().getByEventId("event")!!.eventState)
        assertTrue(entities.insertIncoming(record("event", "event", null, "top-patch", 5, state = "closed")))
        assertEquals("closed", db.thingSubEventDao().getByEventId("event").maxBy { it.receivedAt }.eventState)
        assertTrue(entities.insertIncoming(record("event", "other", null, "other-top", 6)))
        assertFalse(entities.insertIncoming(record("event", "other", "other-thing", "other-hidden", 7, state = "closed")))
        assertEquals("open", db.topLevelEventHeadDao().getByEventId("other")!!.eventState)
        assertTrue(entities.insertIncoming(parent("other-thing", "other-parent", 8)))
        assertEquals("open", db.topLevelEventHeadDao().getByEventId("other")!!.eventState)
        assertEquals("closed", entities.getEventProjectionDetail("other")!!.history.first { it.thingId == "other-thing" }.eventState)
    }

    @Test
    fun activationPreservesRawIdsTrimmedLookupEmptyIdReplaceAndOrderedAliases() = runBlocking {
        val canonical = record("event", "canonical", "elsewhere", null, 1)
        seed(canonical, "x", active = true)
        seed(record("event", "alias", "thing", null, 2), " x ", active = false)
        entities.activateStoredEventsForThing("thing")
        assertTrue(rows(" x ", active = false).isEmpty())
        assertNull(db.thingSubEventDao().getById(" x "))
        assertEquals(canonical.body, db.thingSubEventDao().getById("x")!!.body)

        // Lookup trims the ID, but the actual primary key remains the raw ID.
        seed(record("event", "raw-old", "elsewhere", null, 2), " raw ", active = true)
        val rawReplacement = record("event", "raw-new", "raw-thing", null, 3)
        seed(rawReplacement, " raw ", active = false)
        assertNull(db.thingSubEventDao().getById("raw"))
        entities.activateStoredEventsForThing("raw-thing")
        assertEquals(rawReplacement.body, db.thingSubEventDao().getById(" raw ")!!.body)
        assertTrue(rows(" raw ", active = false).isEmpty())

        seed(record("event", "empty-old", "elsewhere", null, 3), "", active = true)
        val emptyNew = record("event", "empty-new", "empty-thing", null, 4)
        seed(emptyNew, "", active = false)
        entities.activateStoredEventsForThing("empty-thing")
        assertEquals(emptyNew.body, db.thingSubEventDao().getById("")!!.body)

        val normalFirst = record("event", "normal-first", "ordered", null, 5)
        seed(normalFirst, "ordered", active = false)
        seed(record("event", "alias-later", "ordered", null, 6), " ordered ", active = false)
        entities.activateStoredEventsForThing("ordered")
        assertEquals(normalFirst.body, db.thingSubEventDao().getById("ordered")!!.body)
        assertNull(db.thingSubEventDao().getById(" ordered "))
        seed(record("event", "alias-first", "reverse", null, 7), " reverse ", active = false)
        seed(record("event", "normal-later", "reverse", null, 8), "reverse", active = false)
        entities.activateStoredEventsForThing("reverse")
        assertEquals("Body alias-first", db.thingSubEventDao().getById(" reverse ")!!.body)
        assertEquals("Body normal-later", db.thingSubEventDao().getById("reverse")!!.body)

        val unicode = record("event", "unicode", "unicode-thing", null, 9)
        db.thingSubEventDao().insert(ThingSubEventEntity.fromIncoming(unicode).copy(
            id = " unicode ", activated = false, opId = "\u2003 op \u2003", deliveryId = "\u2003 \u2003",
            eventState = "\u2003 closed \u2003", eventId = "\u2003 \u2003",
        ))
        entities.activateStoredEventsForThing("unicode-thing")
        val activated = db.thingSubEventDao().getById(" unicode ")!!
        assertEquals("op", activated.opId)
        assertNull(activated.deliveryId)
        assertEquals("closed", activated.eventState)
        assertEquals(unicode.entityId, activated.eventId)
        assertEquals(unicode.rawPayloadJson, activated.rawPayloadJson)
    }

    private suspend fun seed(record: IncomingEntityRecord, id: String, active: Boolean) {
        db.thingSubEventDao().insert(ThingSubEventEntity.fromIncoming(record).copy(id = id, activated = active))
    }

    private fun rows(id: String, active: Boolean): List<String> {
        val bodies = mutableListOf<String>()
        db.openHelper.readableDatabase.query("SELECT body FROM thing_sub_events WHERE id = ? AND activated = ?",
            arrayOf<Any>(id, if (active) 1 else 0)).use { cursor ->
            while (cursor.moveToNext()) bodies += cursor.getString(0)
        }
        return bodies
    }

    private suspend fun persist(record: IncomingEntityRecord, notify: Boolean = false, identity: ProviderAckIdentity? = null) =
        InboundPersistenceCoordinator.persistAndNotify(context, messages, entities, ledger, settings,
            InboundPersistenceRequest.Entity(record, null, record.title, record.body, notify, providerAckIdentity = identity))

    private fun open() {
        db = PushGoDatabase.buildForTest(context, DB)
        entities = EntityRepository(db, db.inboundDeliveryLedgerDao(), db.operationLedgerDao(),
            db.eventChangeLogDao(), db.thingChangeLogDao(), db.thingSubEventDao(), db.topLevelEventHeadDao(),
            db.thingHeadDao(), db.thingSubMessageDao())
        messages = MessageRepository(database = db, dao = db.messageDao(), channelStatsDao = db.messageChannelStatsDao(),
            metadataIndexDao = db.messageMetadataIndexDao(), inboundDeliveryLedgerDao = db.inboundDeliveryLedgerDao(),
            operationLedgerDao = db.operationLedgerDao(), thingHeadDao = db.thingHeadDao(),
            thingSubMessageDao = db.thingSubMessageDao(), pendingThingMessageDao = db.pendingThingMessageDao())
        ledger = InboundDeliveryLedgerRepository(db, db.inboundDeliveryLedgerDao(), db.inboundDeliveryAckOutboxDao(), db.legacyProviderIngressDao())
        settings = SettingsRepository(db.appSettingsDao(), AndroidKeystoreSecretStore(context, SECRET_PREFS),
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE))
    }

    private fun parent(thing: String, delivery: String, time: Long) = record("thing", thing, thing, delivery, time)

    private fun record(type: String, id: String, thing: String?, delivery: String?, time: Long,
        channel: String = "channel", state: String = "open"): IncomingEntityRecord {
        val epoch = 1_800_000_000_000L + time * 1_000
        val name = delivery ?: id
        val raw = JSONObject().put("entity_type", type).put("entity_id", id).put("title", "Title $name")
            .put("body", "Body $name").put("event_time", Instant.ofEpochMilli(epoch).toString())
            .put("metadata", JSONObject().put("origin", name))
        if (delivery != null) raw.put("delivery_id", delivery).put("op_id", "op-$delivery")
        if (type == "event") raw.put("event_id", id).put("event_state", state)
        if (thing != null) raw.put("thing_id", thing)
        return IncomingEntityRecord(type, id, channel, "Title $name", "Body $name", raw.toString(),
            Instant.ofEpochMilli(epoch), delivery?.let { "op-$it" }, delivery, "test-server",
            if (type == "event") id else null, thing, if (type == "event") state else null,
            epoch, if (type == "thing") epoch else null)
    }

    private fun clearPreferences() {
        for (name in listOf(SECRET_PREFS, CACHE_PREFS)) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val DB = "thing-event-activation-device-test.db"
        const val SECRET_PREFS = "thing-event-activation-device-test-secrets"
        const val CACHE_PREFS = "thing-event-activation-device-test-settings"
    }
}
