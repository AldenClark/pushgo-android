package io.ethan.pushgo.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.workDataOf
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.InboundPersistenceCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import io.ethan.pushgo.notifications.InboundPersistenceStatus
import io.ethan.pushgo.notifications.processPendingPostProcess
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room + production ingress/worker implementation. Publishers are controlled local
 * effects, not a claim of network ACK or real OS notification permission coverage. */
@RunWith(AndroidJUnit4::class)
class InboundPostProcessDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PushGoDatabase
    private lateinit var messages: MessageRepository
    private lateinit var entities: EntityRepository
    private lateinit var ledger: InboundDeliveryLedgerRepository
    private lateinit var settings: SettingsRepository
    private lateinit var store: MessageImageStore
    private val identity = requireNotNull(ProviderAckIdentity.create(
        ProviderAckDestination("https://post-process.invalid/gateway", "synthetic-device"), ProviderAckContract.V2_BATCH, "provider_pull"))

    @Before fun prepare() { context.deleteDatabase(DB); open() }
    @After fun cleanup() { store.close(); db.close(); context.deleteDatabase(DB) }

    @Test
    fun schedulingFailureAfterCommitStillAllowsAckAndPendingRecoversAfterReopen() = runBlocking {
        val image = "https://post-process.invalid/tiny.png?opaque=" + "x".repeat(41_000)
        // Defensive direct-ingress payload: duplicating a very long URL exceeds Work Data.
        // This is not a claim that the normal Gateway accepts URLs above its 2048-byte field limit.
        assertTrue(runCatching { workDataOf("image_url" to image).toByteArray() }.isFailure)
        val value = message("enqueue-failed", image)
        val failure = IllegalStateException("synthetic WorkManager registration unavailable")
        val outcome = persist(value, notify = false, handoff = { throw failure }, recovery = { throw failure })
        assertEquals(InboundPersistenceStatus.PERSISTED_MAIN, outcome.status)
        assertTrue(outcome.shouldAck)
        assertEquals(value.body, messages.getById(value.id)?.body)
        assertTrue(ledger.shouldAck(value.deliveryId, identity))
        assertEquals(POST_PROCESS_MEDIA, flags(value.id))
        reopen()
        val locator = requireNotNull(messages.postProcessLocator(value.id))
        val actualData = io.ethan.pushgo.notifications.MessagePostProcessWorker.pendingInputData(locator)
        assertTrue(actualData.toByteArray().size < 512)
        assertEquals(3, actualData.keyValueMap.size)
        assertFalse(actualData.keyValueMap.containsKey("image_url"))
        val fixture = MessageResourceStoreDeviceTest.Fixture { "Sat, 24 Oct 2026 00:00:00 GMT" }
        try {
            fixture.response = MessageResourceStoreDeviceTest.Response(
                java.io.ByteArrayOutputStream().use { out -> android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.RED); it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); it.recycle() }; out.toByteArray() },
                mapOf("Content-Type" to "image/png", "Cache-Control" to "max-age=60"))
            store.close()
            store = MessageImageStore(context, db, connectionOpener = {
                URL("http://127.0.0.1:${fixture.port}/tiny.png").openConnection() as HttpURLConnection
            })
            assertTrue(processPendingPostProcess(db, store, locator) { fail("media must not post a notification"); false })
            assertEquals(0, flags(value.id))
            assertEquals(1, fixture.requests.size)
            assertEquals(1, count("message_resource_bindings"))
        } finally { fixture.close() }
    }

    @Test
    fun notificationExceptionAndNegativeResultAreDurableButDoNotWithholdCommittedAck() = runBlocking {
        for ((id, throws) in listOf("notify-exception" to true, "notify-false" to false)) {
            val value = message(id)
            val outcome = persist(value, notify = true, publisher = { if (throws) error("synthetic notify failure") else false })
            assertTrue(outcome.shouldAck)
            assertFalse(outcome.notified)
            assertEquals(POST_PROCESS_NOTIFICATION, flags(id))
            reopen()
            var published = 0
            val locator = requireNotNull(messages.postProcessLocator(id))
            assertTrue(processPendingPostProcess(db, store, locator) { current ->
                published++
                assertEquals(value.body, current.message.body)
                assertEquals(value.deliveryId, current.message.deliveryId)
                true
            })
            assertEquals(1, published)
            assertEquals(0, flags(id))
            assertTrue(processPendingPostProcess(db, store, locator) { fail("completed work cannot publish twice"); true })
            assertTrue(ledger.shouldAck(value.deliveryId, identity))
        }
    }

    @Test
    fun replayWithAnotherLocalUuidKicksTheExistingCanonicalGenerationOnlyInItsScope() = runBlocking {
        val value = message("first-local-id", "https://post-process.invalid/image.png")
        val first = persist(value, false, recovery = { error("synthetic registration failure") })
        assertTrue(first.shouldAck)
        val canonical = requireNotNull(messages.postProcessLocator(value.id, identity.inboundDeliveryScope()))
        reopen()
        var scheduledGeneration: String? = null
        val replay = value.copy(id = "another-random-local-uuid")
        val outcome = InboundPersistenceCoordinator.persistAndNotify(context, messages, entities, ledger, settings,
            InboundPersistenceRequest.Message(replay, null, replay.imageUrl, false, identity),
            postProcessRecovery = { scheduledGeneration = it })
        assertEquals(InboundPersistenceStatus.DUPLICATE, outcome.status)
        assertTrue(outcome.shouldAck)
        assertEquals(canonical.generation, scheduledGeneration)
        assertEquals(1, count("messages"))
        assertNull(messages.getById(replay.id))
        assertNull(messages.postProcessLocator(value.id, InboundDeliveryScope.create("https://other.invalid", "another-device")))
    }

    @Test
    fun normalSuppressionCompletesOnlyNewObligationAndLegacyRowsAreNotReNotified() = runBlocking {
        val legacy = message("legacy")
        assertTrue(messages.insertIncoming(legacy, identity))
        assertEquals(0, flags(legacy.id))
        var calls = 0
        val current = message("suppressed")
        val outcome = persist(current, true, publisher = { calls++; true })
        assertTrue(outcome.shouldAck)
        assertEquals(1, calls)
        assertEquals(0, flags(current.id))
        assertTrue(db.loadPendingPostProcess(0).isEmpty())
        reopen()
        assertTrue(db.loadPendingPostProcess(0).isEmpty())
        assertNotNull(messages.getById(legacy.id))
    }

    @Test
    fun cancellationAfterCanonicalCommitLeavesRecoverableObligationAndNoInventedAckResult() = runBlocking {
        val value = message("cancel-after-commit", "https://post-process.invalid/a.png")
        var cancelled = false
        try { persist(value, false, handoff = { throw CancellationException("synthetic cancellation") }) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        reopen()
        assertNotNull(messages.getById(value.id))
        assertTrue(ledger.shouldAck(value.deliveryId, identity))
        assertEquals(POST_PROCESS_MEDIA, flags(value.id))
        // Replayed delivery is already durably handled. It cannot create a second body.
        val replay = persist(value, false)
        assertEquals(InboundPersistenceStatus.DUPLICATE, replay.status)
        assertTrue(replay.shouldAck)
        assertEquals(1, count("messages"))
    }

    @Test
    fun trueBusinessWriteFailureRollsBackIdentityAndCannotAck() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_synthetic_message BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END")
        val value = message("db-failed")
        val outcome = persist(value, true, publisher = { fail("uncommitted content cannot notify"); true })
        assertEquals(InboundPersistenceStatus.FAILED, outcome.status)
        assertFalse(outcome.shouldAck)
        assertNull(messages.getById(value.id))
        assertFalse(ledger.shouldAck(value.deliveryId, identity))
        assertTrue(db.loadPendingPostProcess(0).isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_synthetic_message")
        assertTrue(persist(value, true, publisher = { true }).shouldAck)
    }

    @Test
    fun pageVisibilityFailureCannotCommitOnlyTheBodyAndRetryEnablesThePage() = runBlocking {
        settings.setMessagePageEnabled(false)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_synthetic_settings BEFORE INSERT ON app_settings BEGIN SELECT RAISE(ABORT,'synthetic page visibility failure'); END")
        val value = message("visibility-failed")
        val failed = persist(value, false)
        assertFalse(failed.shouldAck)
        assertNull(messages.getById(value.id))
        assertFalse(ledger.shouldAck(value.deliveryId, identity))
        assertFalse(settings.getMessagePageEnabled())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_synthetic_settings")
        val retried = persist(value, false)
        assertTrue(retried.shouldAck)
        assertEquals(value.body, messages.getById(value.id)?.body)
        assertTrue(settings.getMessagePageEnabled())
        reopen()
        assertTrue(settings.getMessagePageEnabled())
        assertTrue(ledger.shouldAck(value.deliveryId, identity))
    }

    @Test
    fun preferencesProjectionFailureAfterCommitDoesNotLoseAckOrCanonicalPageVisibility() = runBlocking {
        val normal = context.getSharedPreferences("post-process-synthetic-settings", Context.MODE_PRIVATE)
        val failing = object : android.content.SharedPreferences by normal {
            override fun edit(): android.content.SharedPreferences.Editor {
                val delegate = normal.edit()
                return object : android.content.SharedPreferences.Editor by delegate {
                    override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor =
                        throw IllegalStateException("synthetic preferences projection failure")
                }
            }
        }
        settings = SettingsRepository(db.appSettingsDao(), AndroidKeystoreSecretStore(context, "post-process-synthetic-secrets"), failing)
        val value = message("cache-failed")
        assertTrue(persist(value, false).shouldAck)
        assertEquals(value.body, messages.getById(value.id)?.body)
        assertTrue(requireNotNull(db.appSettingsDao().get()).isMessagePageEnabled)
        assertTrue(ledger.shouldAck(value.deliveryId, identity))
        reopen()
        assertTrue(settings.getMessagePageEnabled())
    }

    @Test
    fun optionalBadMediaEndsPrefetchButNotificationFailureRemainsRetryable() = runBlocking {
        val value = message("media-bad", "https://post-process.invalid/bad.png")
        assertTrue(persist(value, true, publisher = { false }).shouldAck)
        val locator = requireNotNull(messages.postProcessLocator(value.id))
        store.close()
        var opened = 0
        store = MessageImageStore(context, db, connectionOpener = { opened++; throw java.io.IOException("synthetic unavailable media") })
        assertFalse(processPendingPostProcess(db, store, locator) { false })
        assertEquals(1, opened)
        assertEquals(POST_PROCESS_NOTIFICATION, flags(value.id))
        assertTrue(processPendingPostProcess(db, store, locator) { true })
        assertEquals(1, opened)
        assertEquals(0, flags(value.id))
        assertEquals(value.body, messages.getById(value.id)?.body)
    }

    @Test
    fun deferredEntityUsesCanonicalBodyAndGenerationAndDeletionCannotRepublishIt() = runBlocking {
        val raw = JSONObject().put("entity_type", "thing").put("entity_id", "thing-a").put("thing_id", "thing-a")
            .put("delivery_id", "thing-delivery").put("op_id", "thing-operation").toString()
        val record = IncomingEntityRecord("thing", "thing-a", "channel", "Thing title", "Thing body", raw,
            Instant.now(), "thing-operation", "thing-delivery", "synthetic-server", null, "thing-a", null, null, null)
        val outcome = InboundPersistenceCoordinator.persistAndNotify(context, messages, entities, ledger, settings,
            InboundPersistenceRequest.Entity(record, null, record.title, record.body, true, providerAckIdentity = identity),
            postProcessRecovery = {}, notificationPublisher = { _, _, _ -> false })
        assertTrue(outcome.shouldAck)
        val locator = db.loadPendingPostProcess(3).single()
        assertFalse(processPendingPostProcess(db, store, locator) { current ->
            assertEquals("thing", current.entityType); assertEquals(record.body, current.message.body); false
        })
        assertEquals("one canonical log plus its head", 2, entities.deleteThing("thing-a"))
        assertTrue(processPendingPostProcess(db, store, locator) { fail("deleted content must not notify"); true })
        assertTrue(db.loadPendingPostProcess(3).isEmpty())
        assertTrue(ledger.shouldAck("thing-delivery", identity))
    }

    private suspend fun persist(value: PushMessage, notify: Boolean, handoff: suspend () -> Unit = {},
        recovery: () -> Unit = {}, publisher: () -> Boolean = { true }) = InboundPersistenceCoordinator.persistAndNotify(
        context, messages, entities, ledger, settings,
        InboundPersistenceRequest.Message(value, null, value.imageUrl, notify, identity),
        postProcessRecovery = { recovery() }, notificationPublisher = { _, _, _ -> publisher() },
        beforeMessageNotify = { _, _ -> handoff() })

    private fun message(id: String, image: String? = null): PushMessage {
        val raw = JSONObject().put("entity_type", "message").put("entity_id", id).put("message_id", id)
            .put("delivery_id", id).put("op_id", "op-$id").put("channel_id", "channel")
        if (image != null) raw.put("images", image)
        return PushMessage(id, id, "Title $id", "Body $id", "channel", null, false, Instant.now(), raw.toString(),
            MessageStatus.NORMAL, null, null, "synthetic-server")
    }
    private fun flags(id: String): Int = db.openHelper.readableDatabase.query(
        "SELECT post_process_pending FROM messages WHERE id=?", arrayOf(id)).use { assertTrue(it.moveToFirst()); it.getInt(0) }
    private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
    private fun open() {
        db = PushGoDatabase.buildForTest(context, DB)
        entities = EntityRepository(db, db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.eventChangeLogDao(),
            db.thingChangeLogDao(), db.thingSubEventDao(), db.topLevelEventHeadDao(), db.thingHeadDao(), db.thingSubMessageDao())
        messages = MessageRepository(db, db.messageDao(), db.messageChannelStatsDao(), db.messageMetadataIndexDao(),
            db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.thingHeadDao(), db.thingSubMessageDao(), db.pendingThingMessageDao())
        ledger = InboundDeliveryLedgerRepository(db, db.inboundDeliveryLedgerDao(), db.inboundDeliveryAckOutboxDao(), db.legacyProviderIngressDao())
        settings = SettingsRepository(db.appSettingsDao(), AndroidKeystoreSecretStore(context, "post-process-synthetic-secrets"),
            context.getSharedPreferences("post-process-synthetic-settings", Context.MODE_PRIVATE))
        store = MessageImageStore(context, db)
    }
    private fun reopen() { store.close(); db.close(); open() }
    private companion object { const val DB = "inbound-post-process-device-test.db" }
}
