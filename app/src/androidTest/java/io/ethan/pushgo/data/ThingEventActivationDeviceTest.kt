package io.ethan.pushgo.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.db.ThingSubEventEntity
import io.ethan.pushgo.data.db.ThingChangeLogEntity
import io.ethan.pushgo.data.db.ThingHeadEntity
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
    // Explicit synthetic authenticated origin for the original positive fixtures.
    private val fixtureIdentity = scopeIdentity("https://activation-fixture.invalid", "fixture-device")

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
    fun foreignGatewayNewRevisionsCannotReplaceExistingThingOrEventHeads() = runBlocking {
        val original = scopeIdentity("https://entity-origin-a.invalid", "old-device")
        val foreign = scopeIdentity("https://entity-origin-b.invalid", "new-device")
        for (type in listOf("thing", "event")) {
            val id = "shared-$type-revision"
            val first = record(type, id, id.takeIf { type == "thing" }, "$type-original", 1)
            assertTrue(persist(first, identity = original).shouldAck)
            val oldHead = if (type == "thing") db.thingHeadDao().getByThingId(id) else db.topLevelEventHeadDao().getByEventId(id)
            val sourceId = if (type == "thing") db.thingHeadDao().getByThingId(id)!!.sourceId else db.topLevelEventHeadDao().getByEventId(id)!!.sourceId
            val oldOwner = ownerFacts(sourceId)
            assertEquals("the seeded canonical revision has one proven owner", 1, oldOwner.size)
            val incoming = record(type, id, id.takeIf { type == "thing" }, "$type-foreign-revision", 2)
            val outcome = persist(incoming, identity = foreign)
            assertFalse("new foreign delivery must not adopt a bare business head", outcome.shouldAck)
            assertEquals(oldHead, if (type == "thing") db.thingHeadDao().getByThingId(id) else db.topLevelEventHeadDao().getByEventId(id))
            assertEquals(oldOwner, ownerFacts(sourceId))
            assertEquals(0, scopedDeliveryClaims(foreign, requireNotNull(incoming.deliveryId)))
            assertEquals(0, operationClaims(requireNotNull(incoming.opId)))
            assertEquals(1, if (type == "thing") db.thingChangeLogDao().countAll() else db.eventChangeLogDao().countAll())
        }
    }

    @Test
    fun foreignGatewayChildCannotAttachToAnExistingThingHead() = runBlocking {
        val original = scopeIdentity("https://parent-origin-a.invalid", "parent-device")
        val foreign = scopeIdentity("https://parent-origin-b.invalid", "child-device")
        assertTrue(persist(parent("shared-parent", "original-parent", 1), identity = original).shouldAck)
        val head = db.thingHeadDao().getByThingId("shared-parent")!!
        val owner = ownerFacts(head.sourceId)
        assertEquals(1, owner.size)
        val child = record("event", "foreign-child", "shared-parent", "foreign-child-delivery", 2)
        val outcome = persist(child, identity = foreign)
        assertFalse("matching thing_id alone does not prove this parent belongs to the child", outcome.shouldAck)
        assertEquals(head, db.thingHeadDao().getByThingId("shared-parent"))
        assertEquals(owner, ownerFacts(head.sourceId))
        assertTrue(db.thingSubEventDao().getByThingIdIncludingWaiting("shared-parent").isEmpty())
        assertNull(entities.getEventProjectionDetail("foreign-child"))
        assertEquals(0, scopedDeliveryClaims(foreign, requireNotNull(child.deliveryId)))
        assertEquals(0, operationClaims(requireNotNull(child.opId)))
    }

    @Test
    fun sameGatewayNewDeviceCanAdvanceBusinessRevisionsAndAttachItsOwnChild() = runBlocking {
        val original = scopeIdentity("https://same-entity-gateway.invalid", "old-device")
        val replacement = scopeIdentity("https://same-entity-gateway.invalid", "new-device")
        for (type in listOf("thing", "event")) {
            val id = "same-gateway-$type"
            val first = record(type, id, id.takeIf { type == "thing" }, "$type-device-old", 1)
            assertTrue(persist(first, identity = original).shouldAck)
            val sourceId = if (type == "thing") db.thingHeadDao().getByThingId(id)!!.sourceId else db.topLevelEventHeadDao().getByEventId(id)!!.sourceId
            val oldOwner = ownerFacts(sourceId)
            // A new entity revision is allowed to change content; it is not an immutable Message replay.
            val revision = record(type, id, id.takeIf { type == "thing" }, "$type-device-new-revision", 2)
            val outcome = persist(revision, identity = replacement)
            assertEquals(InboundPersistenceStatus.PERSISTED_MAIN, outcome.status)
            assertTrue(outcome.shouldAck)
            assertEquals(revision.body, if (type == "thing") db.thingHeadDao().getByThingId(id)!!.body else db.topLevelEventHeadDao().getByEventId(id)!!.body)
            assertEquals(oldOwner, ownerFacts(sourceId))
            assertEquals(1, scopedDeliveryClaims(replacement, requireNotNull(revision.deliveryId)))
            assertEquals(2, if (type == "thing") db.thingChangeLogDao().countAll() else db.eventChangeLogDao().countAll())
        }
        val child = record("event", "same-gateway-child", "same-gateway-thing", "same-gateway-child-revision", 3)
        val childOutcome = persist(child, identity = replacement)
        assertEquals(InboundPersistenceStatus.PERSISTED_MAIN, childOutcome.status)
        assertTrue(childOutcome.shouldAck)
        assertEquals(child.body, db.thingSubEventDao().getByThingId("same-gateway-thing").single().body)
        db.close(); open()
        assertEquals(child.body, entities.getEventProjectionDetail("same-gateway-child")!!.history.single().body)
    }

    @Test
    fun acceptedWaitingChildCannotBeActivatedByAnotherGatewaysParent() = runBlocking {
        val waitingOrigin = scopeIdentity("https://waiting-origin-b.invalid", "waiting-device")
        val foreignParentOrigin = scopeIdentity("https://waiting-origin-a.invalid", "parent-device")
        val child = record("event", "waiting-foreign-child", "waiting-shared-parent", "waiting-foreign-delivery", 1)
        val accepted = persist(child, identity = waitingOrigin)
        assertEquals(InboundPersistenceStatus.PERSISTED_PENDING, accepted.status)
        assertTrue(accepted.shouldAck)
        val waiting = db.thingSubEventDao().getByThingIdIncludingWaiting("waiting-shared-parent").single()
        assertFalse(waiting.activated)
        db.close(); open()
        // The new parent may be retained or rejected by policy, but it cannot consume
        // another Gateway's already accepted waiting body or make that body active.
        persist(parent("waiting-shared-parent", "foreign-parent-arrived", 2), identity = foreignParentOrigin)
        assertEquals(listOf(waiting), db.thingSubEventDao().getByThingIdIncludingWaiting("waiting-shared-parent"))
        assertTrue(db.thingSubEventDao().getByThingId("waiting-shared-parent").isEmpty())
        assertNull(entities.getEventProjectionDetail("waiting-foreign-child"))
        assertTrue(ledger.shouldAck(requireNotNull(child.deliveryId), waitingOrigin))
    }

    @Test
    fun confirmedThingDeletionReleasesOnlyItsProvenGatewayAndPreservesOtherWaitingFacts() = runBlocking {
        val target = scopeIdentity("https://confirmed-parent-a.invalid", "parent-device")
        val foreign = scopeIdentity("https://confirmed-parent-b.invalid", "foreign-device")
        val thing = "confirmed-shared-parent"
        val foreignChild = record("event", "retained-foreign-event", thing, "retained-foreign-delivery", 1)
        val ownChild = record("event", "deleted-own-event", thing, "deleted-own-delivery", 2)
        assertEquals(InboundPersistenceStatus.PERSISTED_PENDING, persist(foreignChild, identity = foreign).status)
        assertEquals(InboundPersistenceStatus.PERSISTED_PENDING, persist(ownChild, identity = target).status)
        // Explicit legacy-shaped fixture: no authenticated scope is invented for it.
        val unknownChild = record("event", "retained-unknown-event", thing, "retained-unknown-delivery", 3)
        assertEquals(InboundPersistenceStatus.PERSISTED_PENDING, persist(unknownChild, identity = null).status)
        val oldEvents = db.thingSubEventDao().getByThingIdIncludingWaiting(thing)
            .filter { it.eventId != ownChild.eventId }.associateBy { it.eventId }
        for ((id, scope) in listOf("deleted-own-message" to target.inboundDeliveryScope(),
            "retained-foreign-message" to foreign.inboundDeliveryScope(), "retained-unknown-message" to null)) {
            val raw = JSONObject().put("entity_type", "message").put("entity_id", id).put("thing_id", thing)
                .put("message_id", id).put("delivery_id", id).put("op_id", "op-$id").put("channel_id", "channel")
            val message = io.ethan.pushgo.data.model.PushMessage(id, id, "Title $id", "Body $id", "channel", null, false,
                Instant.ofEpochMilli(1_800_000_000_000L), raw.toString(), io.ethan.pushgo.data.model.MessageStatus.NORMAL,
                null, null, "test-server")
            assertFalse(messages.insertIncoming(message, deliveryScope = scope))
        }
        val oldMessages = db.pendingThingMessageDao().loadByThingId(thing).filter { it.id != "deleted-own-message" }
            .associateBy { it.id }
        assertTrue(persist(parent(thing, "confirmed-parent-a", 4), identity = target).shouldAck)
        assertEquals(listOf(ownChild.eventId), db.thingSubEventDao().getByThingId(thing).map { it.eventId })
        assertEquals("Body deleted-own-message", messages.getByMessageId("deleted-own-message")?.body)
        assertEquals(oldMessages, db.pendingThingMessageDao().loadByThingId(thing).associateBy { it.id })
        val confirmation = OrdinaryDeletionConfirmation(db)
        val operation = PendingLocalDeletionOperation.things(listOf(thing))
        val captured = confirmation.capture(operation)
        db.withTransaction { confirmation.validate(captured, operation); entities.deleteThings(listOf(thing)) }
        assertNull(db.thingHeadDao().getByThingId(thing))
        assertTrue(db.thingSubEventDao().getByThingId(thing).isEmpty())
        assertNull(messages.getByMessageId("deleted-own-message"))
        assertEquals(oldEvents, db.thingSubEventDao().getByThingIdIncludingWaiting(thing).associateBy { it.eventId })
        assertEquals(oldMessages, db.pendingThingMessageDao().loadByThingId(thing).associateBy { it.id })
        assertTrue(ledger.shouldAck(requireNotNull(foreignChild.deliveryId), foreign))
        assertTrue(ledger.shouldAck("retained-foreign-message", foreign))
        assertTrue(ledger.shouldAck("deleted-own-message", target))
        // Check the exact new metadata paging shape on native SQLite. LIMIT alone
        // does not prove physical work; require a forward rowid range and no sort.
        for ((table, key) in listOf("event_change_logs" to "event_id", "thing_change_logs" to "thing_id",
            "thing_sub_events" to "thing_id", "thing_sub_messages" to "thing_id", "pending_thing_messages" to "thing_id")) {
            val slot = if (table == "thing_sub_events") "t.activated" else "0"
            val plan = db.openHelper.readableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT t.rowid,t.id,$slot FROM $table t NOT INDEXED WHERE t.$key=? AND t.rowid>? ORDER BY t.rowid LIMIT 64",
                arrayOf<Any>(thing, Long.MIN_VALUE),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) } }
            assertTrue("metadata scan must move along rowid: $plan", plan.any { it.contains("INTEGER PRIMARY KEY", true) && it.contains("rowid>", true) })
            assertFalse("metadata paging must not repeatedly sort: $plan", plan.any { it.contains("TEMP B-TREE", true) })
        }
        db.close(); open()
        assertEquals(oldEvents, db.thingSubEventDao().getByThingIdIncludingWaiting(thing).associateBy { it.eventId })
        assertEquals(oldMessages, db.pendingThingMessageDao().loadByThingId(thing).associateBy { it.id })
    }

    @Test
    fun confirmedChildOnlyEventDeletionUsesItsProvenGatewayWithoutATopLevelHead() = runBlocking {
        val identity = scopeIdentity("https://child-only-event.invalid", "child-device")
        val thing = "child-only-parent"
        assertTrue(persist(parent(thing, "child-only-parent-delivery", 1), identity = identity).shouldAck)
        val parentHead = db.thingHeadDao().getByThingId(thing)
        val child = record("event", "child-only-selected-event", thing, "child-only-event-delivery", 2)
        assertTrue(persist(child, identity = identity).shouldAck)
        assertNull(db.topLevelEventHeadDao().getByEventId(child.entityId))
        assertEquals(child.body, entities.getEventProjectionDetail(child.entityId)!!.history.single().body)
        val confirmation = OrdinaryDeletionConfirmation(db)
        val operation = PendingLocalDeletionOperation.events(listOf(child.entityId))
        val captured = confirmation.capture(operation)
        db.withTransaction { confirmation.validate(captured, operation); entities.deleteEvents(listOf(child.entityId)) }
        assertNull(entities.getEventProjectionDetail(child.entityId))
        assertEquals(parentHead, db.thingHeadDao().getByThingId(thing))
        assertTrue(ledger.shouldAck(requireNotNull(child.deliveryId), identity))
        db.close(); open()
        assertNull(entities.getEventProjectionDetail(child.entityId))
        assertEquals(parentHead, db.thingHeadDao().getByThingId(thing))
    }

    @Test
    fun acceptedEntityReplayAfterConfirmedDeletionDoesNotAdoptTheReplacementGateway() = runBlocking {
        val old = scopeIdentity("https://accepted-entity-old.invalid", "old-device")
        val replacement = scopeIdentity("https://accepted-entity-new.invalid", "new-device")
        for (type in listOf("thing", "event")) {
            val id = "deleted-accepted-$type"
            val first = record(type, id, id.takeIf { type == "thing" }, "accepted-old-$type", 1)
            assertTrue(persist(first, identity = old).shouldAck)
            assertEquals(true, acceptanceProof(old, requireNotNull(first.deliveryId)))
            if (type == "thing") entities.deleteThing(id) else entities.deleteEvent(id)
            val next = record(type, id, id.takeIf { type == "thing" }, "accepted-replacement-$type", 2)
            assertTrue(persist(next, identity = replacement).shouldAck)
            val before = if (type == "thing") db.thingHeadDao().getByThingId(id) else db.topLevelEventHeadDao().getByEventId(id)
            val beforeCount = if (type == "thing") db.thingChangeLogDao().countAll() else db.eventChangeLogDao().countAll()
            db.close(); open()
            val replay = persist(first, identity = old)
            assertTrue("a proved historical acceptance is ACKable without consulting a replacement head", replay.shouldAck)
            assertEquals(before, if (type == "thing") db.thingHeadDao().getByThingId(id) else db.topLevelEventHeadDao().getByEventId(id))
            assertEquals(beforeCount, if (type == "thing") db.thingChangeLogDao().countAll() else db.eventChangeLogDao().countAll())
            assertEquals(true, acceptanceProof(old, requireNotNull(first.deliveryId)))
        }
    }

    @Test
    fun oldNullEntityAcceptanceRequiresAnUnchangedProvenCanonicalSource() = runBlocking {
        val identity = scopeIdentity("https://legacy-entity-proof.invalid", "device")
        for (type in listOf("thing", "event")) {
            val id = "old-null-$type"
            val original = record(type, id, id.takeIf { type == "thing" }, "old-null-delivery-$type", 1)
            val item = if (type == "thing") original.copy(thingId = null,
                rawPayloadJson = org.json.JSONObject(original.rawPayloadJson).apply { remove("thing_id") }.toString()) else original
            assertTrue(persist(item, identity = identity).shouldAck)
            // Artificial migrated-ledger shape; the real canonical source and its
            // authenticated origin remain unchanged and independently inspectable.
            clearAcceptanceProof(identity, requireNotNull(item.deliveryId))
            assertNull(acceptanceProof(identity, requireNotNull(item.deliveryId)))
            val replay = persist(item, identity = identity)
            assertTrue(replay.shouldAck)
            assertEquals(true, acceptanceProof(identity, requireNotNull(item.deliveryId)))
            assertEquals(item.body, if (type == "thing") db.thingHeadDao().getByThingId(id)!!.body else db.topLevelEventHeadDao().getByEventId(id)!!.body)
        }
    }

    @Test
    fun oldNullEntityAcceptanceWithoutItsSourceIsNotCertifiedByANewClaim() = runBlocking {
        val identity = scopeIdentity("https://unverifiable-entity-proof.invalid", "device")
        val item = record("event", "missing-proof-event", null, "missing-proof-delivery", 1)
        assertTrue(persist(item, identity = identity).shouldAck)
        clearAcceptanceProof(identity, requireNotNull(item.deliveryId))
        entities.deleteEvent(item.entityId)
        db.close(); open()
        val replay = persist(item, identity = identity)
        assertFalse(replay.shouldAck)
        assertNull(acceptanceProof(identity, requireNotNull(item.deliveryId)))
        assertNull(entities.getEventProjectionDetail(item.entityId))
        assertEquals(1, scopedDeliveryClaims(identity, requireNotNull(item.deliveryId)))
    }

    @Test
    fun operationOnlyEntityFactCannotCertifyAnotherDeliveryAfterItsSourceWasLost() = runBlocking {
        val identity = scopeIdentity("https://operation-only-entity.invalid", "device")
        val item = record("event", "operation-only-event", null, "operation-only-original", 1)
        assertTrue(persist(item, identity = identity).shouldAck)
        entities.deleteEvent(item.entityId)
        // Artificial incomplete history: only the scoped operation fact remains;
        // it cannot prove acceptance of a newly arriving delivery obligation.
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM inbound_delivery_ledger WHERE gateway_url=? AND device_key=? AND delivery_id=?",
            arrayOf(identity.gatewayUrl, identity.deviceKey, requireNotNull(item.deliveryId)),
        )
        val retry = item.copy(deliveryId = "operation-only-new-delivery",
            rawPayloadJson = JSONObject(item.rawPayloadJson).put("delivery_id", "operation-only-new-delivery").toString())
        val outcome = persist(retry, identity = identity)
        assertFalse(outcome.shouldAck)
        assertEquals(0, scopedDeliveryClaims(identity, requireNotNull(retry.deliveryId)))
        assertEquals(1, operationClaims(requireNotNull(item.opId)))
        assertNull(entities.getEventProjectionDetail(item.entityId))
    }

    @Test
    fun provedEntityAcceptanceDoesNotAuthorizeDifferentDeliveryMetadata() = runBlocking {
        val identity = scopeIdentity("https://entity-metadata-proof.invalid", "device")
        val item = parent("metadata-original", "metadata-delivery", 1)
        assertTrue(persist(item, identity = identity).shouldAck)
        val original = db.thingHeadDao().getByThingId(item.entityId)
        val reused = item.copy(entityId = "metadata-other", thingId = "metadata-other", channel = "different-channel",
            rawPayloadJson = JSONObject(item.rawPayloadJson).put("entity_id", "metadata-other").put("thing_id", "metadata-other").toString())
        assertFalse(persist(reused, identity = identity).shouldAck)
        assertEquals(original, db.thingHeadDao().getByThingId(item.entityId))
        assertNull(db.thingHeadDao().getByThingId("metadata-other"))
        assertEquals(true, acceptanceProof(identity, requireNotNull(item.deliveryId)))
        assertEquals(1, scopedDeliveryClaims(identity, requireNotNull(item.deliveryId)))
    }

    @Test
    fun acceptedWaitingEntityCarriesItsProofBeforeTheParentArrives() = runBlocking {
        val identity = scopeIdentity("https://waiting-entity-proof.invalid", "device")
        val item = record("event", "proof-waiting-event", "proof-later-parent", "proof-waiting-delivery", 1)
        val outcome = persist(item, identity = identity)
        assertEquals(InboundPersistenceStatus.PERSISTED_PENDING, outcome.status)
        assertTrue(outcome.shouldAck)
        assertEquals(true, acceptanceProof(identity, requireNotNull(item.deliveryId)))
        clearAcceptanceProof(identity, requireNotNull(item.deliveryId))
        db.close(); open()
        assertTrue(persist(item, identity = identity).shouldAck)
        assertEquals(true, acceptanceProof(identity, requireNotNull(item.deliveryId)))
        assertFalse(db.thingSubEventDao().getByThingIdIncludingWaiting("proof-later-parent").single().activated)
        assertNull(entities.getEventProjectionDetail(item.entityId))
    }

    private fun acceptanceProof(identity: ProviderAckIdentity, deliveryId: String): Boolean? = db.openHelper.readableDatabase.query(
        "SELECT acceptance_proven FROM inbound_delivery_ledger WHERE gateway_url=? AND device_key=? AND delivery_id=?",
        arrayOf(identity.gatewayUrl, identity.deviceKey, deliveryId),
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) != 0 else null }

    private fun clearAcceptanceProof(identity: ProviderAckIdentity, deliveryId: String) {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE inbound_delivery_ledger SET acceptance_proven=NULL WHERE gateway_url=? AND device_key=? AND delivery_id=?",
            arrayOf(identity.gatewayUrl, identity.deviceKey, deliveryId),
        )
    }

    private fun scopeIdentity(gateway: String, device: String) = requireNotNull(ProviderAckIdentity.create(
        ProviderAckDestination(gateway, device), ProviderAckContract.V2_BATCH, "provider_pull"))

    private fun scopedDeliveryClaims(identity: ProviderAckIdentity, deliveryId: String): Int = db.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM inbound_delivery_ledger WHERE gateway_url=? AND device_key=? AND delivery_id=?",
        arrayOf(identity.gatewayUrl, identity.deviceKey, deliveryId),
    ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun operationClaims(opId: String): Int = db.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM operation_ledger WHERE op_id=?", arrayOf(opId),
    ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun ownerFacts(sourceId: String): List<List<String?>> = db.openHelper.readableDatabase.query(
        "SELECT * FROM message_resource_owners WHERE owner_id=? ORDER BY source_table,owner_slot", arrayOf(sourceId),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
    } }

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
        assertFalse(persist(parent("thing", "parent", 2), identity = identity).notified)
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
        assertTrue(entities.getThingProjectionDetail("thing")!!.history.none { it.eventId != null })
        assertEquals(active.body, entities.getThingProjectionDetail("other-thing")!!.history.single().body)

        val fallbackWaiting = record("event", "fallback", "later", null, 3)
        assertFalse(insertFixture(fallbackWaiting))
        assertFalse(insertFixture(fallbackWaiting.copy(title = "Ignored retry", body = "Ignored retry")))
        assertTrue(insertFixture(parent("later", "later-parent", 4)))
        assertEquals(fallbackWaiting.body, entities.getEventProjectionDetail("fallback")!!.history.single().body)
        // No delivery identity means the old formal REPLACE behavior still applies.
        assertTrue(insertFixture(fallbackWaiting.copy(title = "Replacement", body = "Replacement")))
        assertEquals("Replacement", entities.getEventProjectionDetail("fallback")!!.history.single().body)

        // Real ingress can retain both contents when the parent channel is removed.
        assertTrue(insertFixture(parent("dual-thing", "dual-parent", 20).copy(channel = "parent-channel")))
        val activeChild = record("event", "dual-event", "dual-thing", null, 21, channel = "child-channel")
        assertTrue(insertFixture(activeChild))
        entities.deleteThings(channelId = "parent-channel")
        val waitingChild = activeChild.copy(title = "Waiting replacement", body = "Waiting replacement")
        assertFalse(insertFixture(waitingChild))
        val dualId = "dual-thing:dual-event:${activeChild.receivedAt.toEpochMilli()}"
        db.close()
        open()
        assertEquals(listOf(activeChild.body), rows(dualId, active = true))
        assertEquals(listOf(waitingChild.body), rows(dualId, active = false))
        // Deleting the active event first leaves the other slot eligible for activation.
        entities.deleteEvent("dual-event")
        assertTrue(insertFixture(parent("dual-thing", "dual-parent-new", 22)))
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
        assertFalse(insertFixture(retained))
        entities.deleteEvent("retained")
        entities.deleteEvents(listOf("retained"))
        entities.deleteEvents(channelId = "channel")
        entities.deleteEvents(channelId = null)
        assertTrue(insertFixture(parent("thing-retained", "retained-parent", 2)))
        assertEquals(retained.body, entities.getEventProjectionDetail("retained")!!.history.single().body)
        entities.deleteEvents(listOf("retained"))
        assertNull(entities.getEventProjectionDetail("retained"))
        assertFalse(insertFixture(retained))

        val byId = record("event", "by-id", "id-thing", "id-child", 3)
        val byBatch = record("event", "by-batch", "batch-thing", "batch-child", 4)
        val byChannel = record("event", "by-channel", "channel-thing", "channel-child", 5, channel = "remove")
        val survivor = record("event", "survivor", "channel-thing", "survivor", 6, channel = "keep")
        for (child in listOf(byId, byBatch, byChannel, survivor)) assertFalse(insertFixture(child))
        // Artificial restored shape: accepted pending children plus an authenticated
        // parent source, without running the arrival activation before confirmation.
        seedActivationParent("id-thing")
        seedActivationParent("batch-thing")
        entities.deleteThing("id-thing")
        entities.deleteThings(listOf("batch-thing"))
        entities.deleteThings(channelId = "remove")
        for ((thing, delivery) in listOf("id-thing" to "id-parent", "batch-thing" to "batch-parent", "channel-thing" to "channel-parent")) {
            assertTrue(insertFixture(parent(thing, delivery, 7)))
        }
        for (id in listOf("by-id", "by-batch", "by-channel")) assertNull(entities.getEventProjectionDetail(id))
        assertEquals(survivor.body, entities.getEventProjectionDetail("survivor")!!.history.single().body)
        assertFalse(insertFixture(byId))
        assertTrue(ledger.shouldAck("id-child", fixtureIdentity))
        assertFalse(insertFixture(byId.copy(deliveryId = "new-delivery-same-op")))
        val newOp = record("event", "by-id", "id-thing", "new-operation", 8)
        assertTrue(insertFixture(newOp))
        assertEquals(newOp.body, entities.getEventProjectionDetail("by-id")!!.history.single().body)

        val automation = record("event", "automation", "automation-thing", "automation", 9)
        assertFalse(insertFixture(automation))
        entities.deleteAll()
        assertNull(entities.getEventProjectionDetail("survivor"))
        assertTrue(insertFixture(parent("automation-thing", "automation-parent", 10)))
        assertEquals(automation.body, entities.getEventProjectionDetail("automation")!!.history.single().body)
        val clear = record("event", "clear", "clear-thing", "clear", 11)
        assertFalse(insertFixture(clear))
        entities.deleteThings(channelId = null)
        assertTrue(insertFixture(parent("clear-thing", "clear-parent", 12)))
        assertNull(entities.getEventProjectionDetail("clear"))
        assertNull(entities.getEventProjectionDetail("automation"))
    }

    @Test
    fun headlessHistorySurvivesCrossChannelParentDeletionAndDuplicateParentActivates() = runBlocking {
        val originalParent = parent("thing", "parent", 1).copy(channel = "parent-channel")
        assertTrue(insertFixture(originalParent))
        val child = record("event", "event", "thing", "child", 2, channel = "child-channel")
        assertTrue(insertFixture(child))
        entities.deleteEvents("parent-channel")
        entities.deleteThings("parent-channel")
        assertNull(db.thingHeadDao().getByThingId("thing"))
        val detail = entities.getThingProjectionDetail("thing")!!
        assertNull(detail.head)
        assertEquals(child.body, detail.history.single().body)
        val waiting = record("event", "waiting", "thing", "waiting", 3)
        assertFalse(insertFixture(waiting))
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
        assertFalse(insertFixture(hidden))
        assertTrue(insertFixture(record("event", "event", null, "top", 2, state = "closed")))
        assertTrue(db.thingSubEventDao().getByEventId("event").isEmpty())
        assertTrue(insertFixture(parent("thing", "parent", 3)))
        assertEquals("open", db.thingSubEventDao().getByEventId("event").single().eventState)
        assertEquals(hidden.body, db.thingSubEventDao().getByEventId("event").single().body)
        assertTrue(insertFixture(record("event", "event", "thing", "child-patch", 4, state = "acknowledged")))
        assertEquals("acknowledged", db.topLevelEventHeadDao().getByEventId("event")!!.eventState)
        assertTrue(insertFixture(record("event", "event", null, "top-patch", 5, state = "closed")))
        assertEquals("closed", db.thingSubEventDao().getByEventId("event").maxBy { it.receivedAt }.eventState)
        assertTrue(insertFixture(record("event", "other", null, "other-top", 6)))
        assertFalse(insertFixture(record("event", "other", "other-thing", "other-hidden", 7, state = "closed")))
        assertEquals("open", db.topLevelEventHeadDao().getByEventId("other")!!.eventState)
        assertTrue(insertFixture(parent("other-thing", "other-parent", 8)))
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
        seedActivationParent("unicode-thing")
        db.withTransaction {
            db.thingSubEventDao().insert(ThingSubEventEntity.fromIncoming(unicode).copy(
                id = " unicode ", activated = false, opId = "\u2003 op \u2003", deliveryId = "\u2003 \u2003",
                eventState = "\u2003 closed \u2003", eventId = "\u2003 \u2003",
            ))
            db.recordResourceOrigin(" unicode ", unicode.receivedAt.toEpochMilli(), fixtureIdentity.inboundDeliveryScope())
        }
        entities.activateStoredEventsForThing("unicode-thing")
        val activated = db.thingSubEventDao().getById(" unicode ")!!
        assertEquals("op", activated.opId)
        assertNull(activated.deliveryId)
        assertEquals("closed", activated.eventState)
        assertEquals(unicode.entityId, activated.eventId)
        assertEquals(unicode.rawPayloadJson, activated.rawPayloadJson)
    }

    private suspend fun insertFixture(record: IncomingEntityRecord) = entities.insertIncoming(record, fixtureIdentity)

    /** Explicit restored canonical fixture; no runtime path fabricates a parent or its origin. */
    private suspend fun seedActivationParent(thingId: String) {
        if (db.thingHeadDao().getByThingId(thingId) != null) return
        val scope = requireNotNull(fixtureIdentity.inboundDeliveryScope())
        val incoming = parent(thingId, "fixture-parent-$thingId", 0).let {
            it.copy(localDeliveryKey = scope.scopedDeliveryStorageKey(requireNotNull(it.deliveryId)))
        }
        db.withTransaction {
            val source = ThingChangeLogEntity.fromIncoming(incoming)
            db.thingChangeLogDao().insert(source)
            db.thingHeadDao().upsert(ThingHeadEntity.fromIncoming(incoming))
            db.recordResourceOrigin(source.id, source.receivedAt, scope)
        }
    }

    private suspend fun seed(record: IncomingEntityRecord, id: String, active: Boolean) {
        if (!active) seedActivationParent(requireNotNull(record.thingId))
        db.withTransaction {
            db.thingSubEventDao().insert(ThingSubEventEntity.fromIncoming(record).copy(id = id, activated = active))
            db.recordResourceOrigin(id, record.receivedAt.toEpochMilli(), fixtureIdentity.inboundDeliveryScope())
        }
    }

    private fun rows(id: String, active: Boolean): List<String> {
        val bodies = mutableListOf<String>()
        db.openHelper.readableDatabase.query("SELECT body FROM thing_sub_events WHERE id = ? AND activated = ?",
            arrayOf<Any>(id, if (active) 1 else 0)).use { cursor ->
            while (cursor.moveToNext()) bodies += cursor.getString(0)
        }
        return bodies
    }

    private suspend fun persist(record: IncomingEntityRecord, notify: Boolean = false, identity: ProviderAckIdentity? = fixtureIdentity) =
        InboundPersistenceCoordinator.persistAndNotify(context, messages, entities, ledger, settings,
            InboundPersistenceRequest.Entity(record, null, record.title, record.body, notify, providerAckIdentity = identity),
            deliveryScope = identity.inboundDeliveryScope())

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
