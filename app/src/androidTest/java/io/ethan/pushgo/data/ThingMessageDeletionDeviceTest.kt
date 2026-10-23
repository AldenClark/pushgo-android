package io.ethan.pushgo.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.InboundPersistenceCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import io.ethan.pushgo.notifications.InboundPersistenceStatus
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real production Room/coordinator boundaries, no network, permissions, notification or UI changes. */
@RunWith(AndroidJUnit4::class)
class ThingMessageDeletionDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PushGoDatabase
    private lateinit var entities: EntityRepository
    private lateinit var messages: MessageRepository
    private lateinit var ledger: InboundDeliveryLedgerRepository
    private lateinit var settings: SettingsRepository
    private val identity = requireNotNull(ProviderAckIdentity.create(
        ProviderAckDestination("https://lifecycle.example/prefix", "test-device"),
        ProviderAckContract.V2_BATCH, "provider_pull",
    ))

    @Before fun setUp() { context.deleteDatabase(DB); clearPreferences(); open() }
    @After fun tearDown() { db.close(); context.deleteDatabase(DB); clearPreferences() }

    @Test
    fun acceptedPendingDeletionSurvivesReopenWithoutLosingAckAndNewDeliveryCanRebuild() = runBlocking {
        val old = message("thing", "old")
        assertEquals(InboundPersistenceStatus.PERSISTED_PENDING, persist(old).status)
        assertTrue(ledger.shouldAck("old", identity))
        assertTrue(ledger.enqueueAcks(listOf("old"), identity))
        val ack = ledger.loadPendingAcks().single()
        val unrelated = message("other", "control")
        persist(unrelated)
        confirmedDelete("thing")
        reopen()
        assertTrue(db.pendingThingMessageDao().loadByThingId("thing").isEmpty())
        assertEquals(listOf("control"), db.pendingThingMessageDao().loadByThingId("other").map { it.id })
        assertEquals(ack, ledger.loadPendingAcks().single())
        assertTrue(persist(old).shouldAck)
        assertTrue(db.pendingThingMessageDao().loadByThingId("thing").isEmpty())
        assertTrue(persist(parent("thing", "parent-new")).shouldAck)
        assertTrue(db.thingSubMessageDao().getByThingId("thing").isEmpty())
        val fresh = old.copy(id = "fresh", messageId = "fresh", rawPayloadJson = message("thing", "fresh").rawPayloadJson)
        assertTrue(persist(fresh).shouldAck)
        assertEquals(listOf("fresh"), db.thingSubMessageDao().getByThingId("thing").map { it.id })
        assertEquals(old.body, db.thingSubMessageDao().getByThingId("thing").single().body)
    }

    @Test
    fun singleCollectionChannelAndAllThingDeletesIncludeWaitingMessages() = runBlocking {
        for ((thing, channel) in listOf("single" to "remove", "batch" to "remove", "channel" to "remove", "control" to "keep")) {
            persist(message(thing, thing, channel))
        }
        assertEquals(1, entities.deleteThing("single"))
        assertEquals(1, entities.deleteThings(listOf("batch")))
        assertEquals(1, entities.deleteThings(channelId = "remove"))
        for (thing in listOf("single", "batch", "channel")) {
            assertTrue(db.pendingThingMessageDao().loadByThingId(thing).isEmpty())
        }
        assertEquals(1, db.pendingThingMessageDao().loadByThingId("control").size)
        assertEquals(1, entities.deleteThings(channelId = null))
        assertTrue(db.pendingThingMessageDao().loadByThingId("control").isEmpty())
    }

    @Test
    fun newlyAcceptedPendingMessageInvalidatesOldThingConfirmation() = runBlocking {
        persist(message("thing", "first-pending"))
        val confirmation = OrdinaryDeletionConfirmation(db)
        val operation = PendingLocalDeletionOperation.things(listOf("thing"))
        val captured = confirmation.capture(operation)
        persist(message("thing", "new-pending"))
        var rejected = false
        try { db.withTransaction { confirmation.validate(captured, operation); entities.deleteThing("thing") } }
        catch (_: OrdinaryDeletionTargetChangedException) { rejected = true }
        assertTrue(rejected)
        assertEquals(2, db.pendingThingMessageDao().loadByThingId("thing").size)
        confirmedDelete("thing")
        assertTrue(db.pendingThingMessageDao().loadByThingId("thing").isEmpty())
    }

    @Test
    fun preDeleteParentEventAndMessageTicketsCannotClaimOrWriteAfterCommit() = runBlocking {
        persist(parent("thing", "parent"))
        val parentTicket = requireNotNull(entities.beginThingWrite("thing", "channel"))
        val eventTicket = requireNotNull(entities.beginThingWrite("thing", "channel"))
        val messageTicket = requireNotNull(entities.beginThingWrite("thing", "channel"))
        try {
            confirmedDelete("thing")
            for (outcome in listOf(
                persistRegistered(parent("thing", "late-parent"), parentTicket),
                persistRegistered(childEvent("thing", "late-event"), eventTicket),
                persistRegistered(message("thing", "late-message"), messageTicket),
            )) {
                assertEquals(InboundPersistenceStatus.FAILED, outcome.status)
                assertFalse(outcome.shouldAck)
                assertFalse(outcome.notified)
            }
            for (id in listOf("late-parent", "late-event", "late-message")) assertFalse(ledger.shouldAck(id, identity))
            assertTrue(ledger.loadPendingAcks().isEmpty())
            reopen()
            assertNull(db.thingHeadDao().getByThingId("thing"))
            assertTrue(db.thingSubMessageDao().getByThingId("thing").isEmpty())
            assertTrue(db.pendingThingMessageDao().loadByThingId("thing").isEmpty())
            assertTrue(db.thingSubEventDao().getByThingIdIncludingWaiting("thing").isEmpty())
            // Not-yet-accepted work is cancelled, not a permanent server-delivery tombstone.
            assertTrue(persist(message("thing", "late-message")).shouldAck)
            assertEquals(1, db.pendingThingMessageDao().loadByThingId("thing").size)
        } finally { parentTicket.close(); eventTicket.close(); messageTicket.close() }
    }

    @Test
    fun staleParentReplayCannotActivateFreshLifecycleChildren() = runBlocking {
        val ticket = requireNotNull(entities.beginThingWrite("thing", "channel"))
        try {
            assertTrue(entities.insertIncoming(parent("thing", "parent"), identity, thingWriteTicket = ticket))
            confirmedDelete("thing")
            assertEquals(InboundPersistenceStatus.PERSISTED_PENDING, persist(message("thing", "fresh-message")).status)
            assertEquals(InboundPersistenceStatus.PERSISTED_PENDING, persist(childEvent("thing", "fresh-event")).status)
            expectInvalidated { messages.replayPendingForThing("thing", ticket) }
            expectInvalidated { entities.activateStoredEventsForThing("thing", ticket) }
            assertFalse(InboundPersistenceCoordinator.replayPendingThingChildren(
                messages, entities,
                InboundPersistenceRequest.Entity(parent("thing", "parent"), null, "Title", "Body", false, providerAckIdentity = identity),
                ticket,
            ))
            assertEquals(1, db.pendingThingMessageDao().loadByThingId("thing").size)
            assertEquals(1, db.thingSubEventDao().getByThingIdIncludingWaiting("thing").count { !it.activated })
            assertTrue(persist(parent("thing", "fresh-parent")).shouldAck)
            assertEquals(listOf("fresh-message"), db.thingSubMessageDao().getByThingId("thing").map { it.id })
            assertTrue(db.pendingThingMessageDao().loadByThingId("thing").isEmpty())
            assertEquals(1, db.thingSubEventDao().getByThingIdIncludingWaiting("thing").count { it.activated })
        } finally { ticket.close() }
    }

    @Test
    fun rolledBackDeletionCancelsOldTicketButNormalDuplicateParentRecoversPendingResponsibility() = runBlocking {
        val parent = parent("thing", "parent")
        assertTrue(persist(message("thing", "surviving-child")).shouldAck)
        val ticket = requireNotNull(entities.beginThingWrite("thing", "channel"))
        try {
            // Actual boundary between the parent's canonical commit and coordinator replay.
            assertTrue(entities.insertIncoming(parent, identity, thingWriteTicket = ticket))
            val interruption = IllegalStateException("test interruption before deletion COMMIT")
            try { db.withTransaction { entities.deleteThing("thing"); throw interruption } }
            catch (error: IllegalStateException) { assertTrue(error === interruption) }
            assertNotNull(db.thingHeadDao().getByThingId("thing"))
            assertEquals(1, db.pendingThingMessageDao().loadByThingId("thing").size)
            expectInvalidated { messages.replayPendingForThing("thing", ticket) }
            reopen()
            val recovered = persist(parent)
            assertEquals(InboundPersistenceStatus.DUPLICATE, recovered.status)
            assertTrue(recovered.shouldAck)
            assertEquals(listOf("surviving-child"), db.thingSubMessageDao().getByThingId("thing").map { it.id })
            assertTrue(db.pendingThingMessageDao().loadByThingId("thing").isEmpty())
        } finally { ticket.close() }
    }

    @Test
    fun registrationOrderedAfterDeleteCommitStartsFreshLifecycle() = runBlocking {
        coroutineScope {
            persist(parent("thing", "parent"))
            val removed = CompletableDeferred<Unit>()
            val finishDelete = CompletableDeferred<Unit>()
            val deletion = async(Dispatchers.IO) {
                db.withTransaction { entities.deleteThing("thing"); removed.complete(Unit); finishDelete.await() }
            }
            removed.await()
            val registration = async(Dispatchers.IO) { entities.beginThingWrite("thing", "channel") }
            finishDelete.complete(Unit)
            deletion.await()
            requireNotNull(registration.await()).use { fresh ->
                assertTrue(entities.insertIncoming(parent("thing", "fresh-parent"), identity, thingWriteTicket = fresh))
            }
            assertNotNull(db.thingHeadDao().getByThingId("thing"))
        }
    }

    private suspend fun confirmedDelete(thing: String) {
        val confirmation = OrdinaryDeletionConfirmation(db)
        val operation = PendingLocalDeletionOperation.things(listOf(thing))
        val captured = confirmation.capture(operation)
        db.withTransaction { confirmation.validate(captured, operation); entities.deleteThing(thing) }
    }

    private suspend fun expectInvalidated(block: suspend () -> Unit) {
        var rejected = false
        try { block() } catch (_: ThingWriteInvalidatedException) { rejected = true }
        assertTrue("old operation must fail before claims or content writes", rejected)
    }

    private suspend fun persist(value: PushMessage) = InboundPersistenceCoordinator.persistAndNotify(
        context, messages, entities, ledger, settings,
        InboundPersistenceRequest.Message(value, null, null, false, identity),
    )

    private suspend fun persist(value: IncomingEntityRecord) = InboundPersistenceCoordinator.persistAndNotify(
        context, messages, entities, ledger, settings,
        InboundPersistenceRequest.Entity(value, null, value.title, value.body, false, providerAckIdentity = identity),
    )

    private suspend fun persistRegistered(value: PushMessage, ticket: ThingWriteTicket) =
        InboundPersistenceCoordinator.persistRegisteredAndNotify(
            context, messages, entities, ledger, settings,
            InboundPersistenceRequest.Message(value, null, null, false, identity), null, ticket,
        )

    private suspend fun persistRegistered(value: IncomingEntityRecord, ticket: ThingWriteTicket) =
        InboundPersistenceCoordinator.persistRegisteredAndNotify(
            context, messages, entities, ledger, settings,
            InboundPersistenceRequest.Entity(value, null, value.title, value.body, false, providerAckIdentity = identity), null, ticket,
        )

    private fun message(thing: String, id: String, channel: String = "channel"): PushMessage {
        val raw = JSONObject().put("entity_type", "message").put("entity_id", id).put("thing_id", thing)
            .put("message_id", id).put("delivery_id", id).put("op_id", "op-$id").put("channel_id", channel)
        return PushMessage(id, id, "Title", "Same body", channel, null, false, Instant.ofEpochMilli(1_800_000_000_000L),
            raw.toString(), MessageStatus.NORMAL, null, null, "test-server")
    }

    private fun parent(thing: String, id: String) = record("thing", thing, thing, id)
    private fun childEvent(thing: String, id: String) = record("event", id, thing, id)
    private fun record(type: String, entity: String, thing: String, id: String): IncomingEntityRecord {
        val raw = JSONObject().put("entity_type", type).put("entity_id", entity).put("thing_id", thing)
            .put("delivery_id", id).put("op_id", "op-$id")
        return IncomingEntityRecord(type, entity, "channel", "Title", "Body", raw.toString(),
            Instant.ofEpochMilli(1_800_000_000_000L), "op-$id", id, "test-server", if (type == "event") entity else null,
            thing, null, null, null)
    }

    private fun open() {
        db = PushGoDatabase.buildForTest(context, DB)
        entities = EntityRepository(db, db.inboundDeliveryLedgerDao(), db.operationLedgerDao(),
            db.eventChangeLogDao(), db.thingChangeLogDao(), db.thingSubEventDao(), db.topLevelEventHeadDao(),
            db.thingHeadDao(), db.thingSubMessageDao())
        messages = MessageRepository(db, db.messageDao(), db.messageChannelStatsDao(), db.messageMetadataIndexDao(),
            db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.thingHeadDao(), db.thingSubMessageDao(), db.pendingThingMessageDao())
        ledger = InboundDeliveryLedgerRepository(db, db.inboundDeliveryLedgerDao(), db.inboundDeliveryAckOutboxDao(), db.legacyProviderIngressDao())
        settings = SettingsRepository(db.appSettingsDao(), AndroidKeystoreSecretStore(context, SECRET_PREFS),
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE))
    }
    private fun reopen() { db.close(); open() }
    private fun clearPreferences() {
        for (name in listOf(SECRET_PREFS, CACHE_PREFS)) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }
    private companion object {
        const val DB = "thing-message-deletion-device-test.db"
        const val SECRET_PREFS = "thing-message-deletion-test-secrets"
        const val CACHE_PREFS = "thing-message-deletion-test-settings"
    }
}
