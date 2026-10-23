package io.ethan.pushgo.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.db.AppSettingsEntity
import io.ethan.pushgo.data.db.MessageEntity
import io.ethan.pushgo.data.db.ThingSubEventEntity
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.AlertPlaybackController
import io.ethan.pushgo.notifications.InboundPersistenceCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import io.ethan.pushgo.notifications.InboundPersistenceStatus
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.testing.NotificationPermissionTestSupport
import io.ethan.pushgo.testing.QualityFaults
import io.ethan.pushgo.testing.QualityFixture
import io.ethan.pushgo.testing.QualityRuntime
import io.ethan.pushgo.testing.QualitySessionDescriptor
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room/coordinator/OS coverage for stale confirmations and committed notification cleanup.
 * JVM doubles cannot establish transaction rollback, Android notification instances, or startup
 * composition. No Activity is needed: these focused cases cost only local writes and bounded OS
 * observation, with no network, broad fixture, or repeated UI navigation.
 */
@RunWith(AndroidJUnit4::class)
class OrdinaryDeletionConsistencyDeviceTest {
    private val app: PushGoApp
        get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var session: QualitySessionDescriptor
    private val notificationOwners = linkedSetOf<Pair<String, String>>()

    @Before
    fun setUp() {
        app.releaseStorageForInstrumentationTest()
        session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "ordinary-delete-${System.nanoTime()}",
            fixture = QualityFixture.EMPTY_CLEAN,
            faults = QualityFaults(),
        )
        QualityRuntime.configure(QualityRuntime.encode(session))
        assertEquals(session, QualityRuntime.currentSession())
        QualityRuntime.installOrdinaryDeletionAfterCommitHookForTest(null)
        NotificationHelper.installNotificationObservationHiddenForTest(false)
        NotificationPermissionTestSupport.grantAndVerify(app)
    }

    @After
    fun tearDown() {
        QualityRuntime.installOrdinaryDeletionAfterCommitHookForTest(null)
        NotificationHelper.installNotificationObservationHiddenForTest(false)
        if (::session.isInitialized) {
            // Use the production cancellation owners so submitted-but-unobserved entries are
            // retired too. Leave shared channels and unrelated notifications untouched.
            NotificationHelper.cancelMessageNotifications(app,
                notificationOwners.filter { it.first == "message" }.map { it.second })
            NotificationHelper.cancelEntityNotifications(app,
                notificationOwners.filter { it.first != "message" })
            NotificationHelper.reconcileActiveNotificationGroups(app)
            val sessionRoot = QualityRuntime.sessionRoot(app)
            app.releaseStorageForInstrumentationTest()
            app.deleteDatabase(session.databaseName)
            app.deleteSharedPreferences(session.securePreferencesName)
            app.deleteSharedPreferences(session.settingsCachePreferencesName)
            app.deleteSharedPreferences(session.reminderSnoozePreferencesName)
            sessionRoot?.deleteRecursively()
        }
        QualityRuntime.configure(null)
    }

    @Test
    fun cancellingConfirmationPreservesMessageNotificationAndCreatesNoDurableIntent() = runBlocking {
        val container = app.container
        val target = seedMessage(container, "cancel-target")
        val control = seedMessage(container, "cancel-control")
        val targetNotification = post(target)
        val controlNotification = post(control)

        val result = answer(container, PendingLocalDeletionOperation.messages(setOf(target.id)), accept = false) {
            assertEquals(target.body, container.database.messageDao().getById(target.id)?.body)
            assertNoDeletionRows(container)
            assertPresent(targetNotification)
        }

        assertFalse(result.succeeded)
        assertNull("Cancel must not invoke the deletion completion", result.completion)
        assertNoDeletionRows(container)
        val reopened = reopen()
        assertEquals(MessageEntity.fromModel(target), reopened.database.messageDao().getById(target.id))
        assertEquals(MessageEntity.fromModel(control), reopened.database.messageDao().getById(control.id))
        assertPresent(targetNotification)
        assertPresent(controlNotification)
        assertNoDeletionRows(reopened)
    }

    @Test
    fun sameIdAndReceivedAtMessageReplacementRejectsOldConfirmation() = runBlocking {
        val container = app.container
        val target = seedMessage(container, "message-replacement")
        val replacement = target.copy(body = "Replacement body requiring a new confirmation")
        val notification = post(target)

        val result = answer(container, PendingLocalDeletionOperation.messages(setOf(target.id))) {
            val before = checkNotNull(container.database.messageDao().getById(target.id))
            assertEquals(1, container.database.messageDao().update(before.copy(body = replacement.body)))
        }

        assertStale(result)
        val reopened = reopen()
        val stored = checkNotNull(reopened.database.messageDao().getById(target.id))
        assertEquals(target.id, stored.id)
        assertEquals(target.receivedAt.toEpochMilli(), stored.receivedAt)
        assertEquals(replacement.body, stored.body)
        assertFalse(stored.isRead)
        assertPresent(notification)
        assertNoDeletionRows(reopened)
    }

    @Test
    fun canonicalTitleIsShownAndReadCacheAndJsonOrderingChangesStillAllowDeletion() = runBlocking {
        val container = app.container
        val target = seedMessage(container, "non-material-target")
        val payload = JSONObject(target.rawPayloadJson)
            .put("metadata", JSONObject().put("first", "one").put("second", "two"))
        val current = checkNotNull(container.database.messageDao().getById(target.id)).copy(
            title = "Fresh canonical title", rawPayloadJson = payload.toString(),
        )
        assertEquals(1, container.database.messageDao().update(current))
        val targetNotification = post(current.asModel())
        val control = seedMessage(container, "non-material-control")
        val controlNotification = post(control)

        val result = answer(
            container,
            PendingLocalDeletionOperation.messages(setOf(target.id)),
            summary = "Outdated caller title",
            onConfirmation = { assertEquals(current.title, it) },
        ) {
            val reordered = JSONObject()
            payload.keys().asSequence().toList().reversed().forEach { key ->
                reordered.put(key, payload.get(key))
            }
            assertNotEquals("Exercise JSON key ordering independently of added cache fields",
                current.rawPayloadJson, reordered.toString())
            reordered.put(MessageImageStore.KEY_IMAGE_LOCAL_PATH, "quality/cache/new-image")
            reordered.put(MessageImageStore.KEY_IMAGE_THUMBNAIL_LOCAL_PATH, "quality/cache/new-thumbnail")
            assertEquals(1, container.database.messageDao().update(
                current.copy(isRead = true, rawPayloadJson = reordered.toString()),
            ))
            assertTrue(checkNotNull(container.database.messageDao().getById(target.id)).isRead)
        }

        assertSucceeded(result)
        val reopened = reopen()
        assertNull(reopened.database.messageDao().getById(target.id))
        assertEquals(control.body, reopened.database.messageDao().getById(control.id)?.body)
        awaitAbsent(targetNotification)
        assertPresent(controlNotification)
        assertNoDeletionRows(reopened)
    }

    @Test
    fun sameEventIdentityAndTimestampContentReplacementRejectsOldConfirmation() = runBlocking {
        val container = app.container
        val event = seedEntity(container, "event", "event-replacement")
        val notification = post(event)
        val originalHead = checkNotNull(container.database.topLevelEventHeadDao().getByEventId(event.entityId))
        val originalLog = container.database.eventChangeLogDao().getByEventId(event.entityId).single()

        val result = answer(container, PendingLocalDeletionOperation.events(setOf(event.entityId))) {
            container.database.withTransaction {
                container.database.topLevelEventHeadDao().upsert(originalHead.copy(body = "Updated event content"))
                container.database.eventChangeLogDao().insert(originalLog.copy(body = "Updated event content"))
            }
        }

        assertStale(result)
        val reopened = reopen()
        assertEquals(originalHead.copy(body = "Updated event content"),
            reopened.database.topLevelEventHeadDao().getByEventId(event.entityId))
        assertEquals(listOf(originalLog.copy(body = "Updated event content")),
            reopened.database.eventChangeLogDao().getByEventId(event.entityId))
        assertPresent(notification)
        assertNoDeletionRows(reopened)
    }

    @Test
    fun newThingChildMessageAndWaitingSubeventEachInvalidateTheirOwnConfirmation() = runBlocking {
        val container = app.container
        val thing = seedEntity(container, "thing", "thing-footprint")
        val notification = post(thing)
        val operation = PendingLocalDeletionOperation.things(setOf(thing.entityId))
        val child = message("new-child", thingId = thing.entityId)

        val childResult = answer(container, operation) {
            assertTrue(container.messageRepository.insertIncoming(child))
        }
        assertStale(childResult)
        assertEquals(child.body, container.database.thingSubMessageDao().getById(child.id)?.body)
        assertNoDeletionRows(container)

        // A new confirmation includes the child. Only the waiting row changes this time.
        val waiting = ThingSubEventEntity.fromIncoming(
            entity("event", "new-waiting-event", thingId = thing.entityId), activated = false,
        )
        val waitingResult = answer(container, operation) {
            container.database.thingSubEventDao().insertWaiting(waiting)
        }
        assertStale(waitingResult)
        val reopened = reopen()
        assertNotNull(reopened.database.thingHeadDao().getByThingId(thing.entityId))
        assertEquals(child.body, reopened.database.thingSubMessageDao().getById(child.id)?.body)
        assertTrue(reopened.database.thingSubEventDao().getByThingIdIncludingWaiting(thing.entityId).contains(waiting))
        assertPresent(notification)
        assertNoDeletionRows(reopened)
    }

    @Test
    fun eachRelevantConfigurationChangeRejectsOldConfirmationAndAllowsFreshConfirmation() = runBlocking {
        val container = app.container
        val dao = container.database.appSettingsDao()
        dao.upsert(AppSettingsEntity(
            serverAddress = "https://before.invalid", token = null, notificationKeyUpdatedAt = 100L,
            fcmToken = null, useFcmChannel = true,
        ))
        val control = seedMessage(container, "config-control")
        val controlNotification = post(control)
        val changes: List<Pair<String, (AppSettingsEntity) -> AppSettingsEntity>> = listOf(
            "server" to { settings: AppSettingsEntity -> settings.copy(serverAddress = "https://after.invalid") },
            "transport" to { settings: AppSettingsEntity -> settings.copy(useFcmChannel = !settings.useFcmChannel) },
            "key-version" to { settings: AppSettingsEntity -> settings.copy(notificationKeyUpdatedAt = 101L) },
        )
        for ((name, change) in changes) {
            val target = seedMessage(container, "config-$name")
            val notification = post(target)
            val before = checkNotNull(dao.get())
            val operation = PendingLocalDeletionOperation.messages(setOf(target.id))
            val stale = answer(container, operation) { dao.upsert(change(before)) }
            assertStale(stale)
            assertEquals(target.body, container.database.messageDao().getById(target.id)?.body)
            assertPresent(notification)
            assertNoDeletionRows(container)

            assertSucceeded(answer(container, operation))
            assertNull(container.database.messageDao().getById(target.id))
            awaitAbsent(notification)
            assertPresent(controlNotification)
            assertNoDeletionRows(container)
        }
        val reopened = reopen()
        assertEquals(control.body, reopened.database.messageDao().getById(control.id)?.body)
        assertEquals("https://after.invalid", reopened.database.appSettingsDao().get()?.serverAddress)
        assertEquals(false, reopened.database.appSettingsDao().get()?.useFcmChannel)
        assertEquals(101L, reopened.database.appSettingsDao().get()?.notificationKeyUpdatedAt)
        assertNoDeletionRows(reopened)
    }

    @Test
    fun committedMessageDeletionRecoversExactNotificationCleanupOnStartupAndIsIdempotent() = runBlocking {
        val container = app.container
        val target = seedMessage(container, "crash-target")
        val control = seedMessage(container, "crash-control")
        val targetNotification = post(target)
        val controlNotification = post(control)

        commitAndStop(container, PendingLocalDeletionOperation.messages(setOf(target.id)))
        assertNull(container.database.messageDao().getById(target.id))
        assertEquals(control.body, container.database.messageDao().getById(control.id)?.body)
        assertReceipts(container, listOf(targetNotification))
        assertPresent(targetNotification)
        assertPresent(controlNotification)

        repeat(2) {
            val reopened = reopen()
            assertNull(reopened.database.messageDao().getById(target.id))
            assertEquals(control.body, reopened.database.messageDao().getById(control.id)?.body)
            awaitAbsent(targetNotification)
            assertPresent(controlNotification)
            assertNoDeletionRows(reopened)
        }
    }

    @Test
    fun committedThingDeletionRecoversChildMessageAndRelatedEntityNotifications() = runBlocking {
        val container = app.container
        val thing = seedEntity(container, "thing", "cascade-thing")
        val child = seedMessage(container, "cascade-child", thingId = thing.entityId)
        val event = seedEntity(container, "event", "cascade-event", thingId = thing.entityId)
        val targets = listOf(post(thing), post(child), post(event))
        val control = seedMessage(container, "cascade-control")
        val controlNotification = post(control)

        commitAndStop(container, PendingLocalDeletionOperation.things(setOf(thing.entityId)))
        assertNull(container.database.thingHeadDao().getByThingId(thing.entityId))
        assertNull(container.database.thingSubMessageDao().getById(child.id))
        assertTrue(container.database.thingSubEventDao().getByThingIdIncludingWaiting(thing.entityId).isEmpty())
        assertReceipts(container, targets)
        targets.forEach { assertPresent(it) }

        val reopened = reopen()
        assertNull(reopened.database.thingHeadDao().getByThingId(thing.entityId))
        assertNull(reopened.database.thingSubMessageDao().getById(child.id))
        targets.forEach { awaitAbsent(it) }
        assertPresent(controlNotification)
        assertEquals(control.body, reopened.database.messageDao().getById(control.id)?.body)
        assertNoDeletionRows(reopened)
    }

    @Test
    fun eventCleanupRemovesAssociatedThingNotificationWhileThingStillExists() = runBlocking {
        val container = app.container
        val thing = seedEntity(container, "thing", "retained-thing")
        val event = seedEntity(container, "event", "deleted-event", thingId = thing.entityId)
        val eventNotification = post(event)
        val thingNotification = post(thing.copy(eventId = event.entityId))
        val control = seedMessage(container, "association-control")
        val controlNotification = post(control)

        commitAndStop(container, PendingLocalDeletionOperation.events(setOf(event.entityId)))
        assertTrue(container.database.thingSubEventDao().getByEventId(event.entityId).isEmpty())
        assertNotNull(container.database.thingHeadDao().getByThingId(thing.entityId))
        assertReceipts(container, listOf(eventNotification, thingNotification))
        assertPresent(eventNotification)
        assertPresent(thingNotification)

        val reopened = reopen()
        assertTrue(reopened.database.thingSubEventDao().getByEventId(event.entityId).isEmpty())
        assertEquals(thing.body, reopened.database.thingHeadDao().getByThingId(thing.entityId)?.body)
        awaitAbsent(eventNotification)
        awaitAbsent(thingNotification)
        assertPresent(controlNotification)
        assertNoDeletionRows(reopened)
    }

    @Test
    fun startupCleanupPreservesNewGenerationAtTheSameNumericNotificationId() = runBlocking {
        val container = app.container
        val target = seedMessage(container, "reused-numeric-id")
        val control = seedMessage(container, "generation-control")
        val oldNotification = post(target)
        val controlNotification = post(control)
        commitAndStop(container, PendingLocalDeletionOperation.messages(setOf(target.id)))
        assertNull(container.database.messageDao().getById(target.id))
        assertReceipts(container, listOf(oldNotification))

        NotificationHelper.installNotificationObservationHiddenForTest(true)
        try {
            val replacement = target.copy(body = "New generation after the committed deletion")
            container.database.messageDao().insert(MessageEntity.fromModel(replacement))
            val replacementNotification = post(replacement)
            assertEquals(oldNotification.id, replacementNotification.id)
            assertNotNull(oldNotification.tag)
            assertNotNull(replacementNotification.tag)
            assertNotEquals(oldNotification.tag, replacementNotification.tag)
            assertNotEquals(oldNotification.key, replacementNotification.key)
            assertNotEquals(generation(oldNotification), generation(replacementNotification))

            // Recovery sees no active instances through the helper. The receipt must still
            // address only the old tag, even though the new instance reuses its numeric ID.
            repeat(2) {
                val reopened = reopen()
                assertEquals(replacement.body, reopened.database.messageDao().getById(replacement.id)?.body)
                awaitAbsent(oldNotification)
                assertPresent(replacementNotification)
                assertPresent(controlNotification)
                assertNoDeletionRows(reopened)
            }
        } finally {
            NotificationHelper.installNotificationObservationHiddenForTest(false)
            NotificationHelper.cancelMessageNotifications(app, listOf(target.id, control.id))
        }
    }

    @Test
    fun startupCleanupCancelsSubmittedNotificationWhenHelperObservationIsHidden() = runBlocking {
        val container = app.container
        val control = seedMessage(container, "unobserved-control")
        val controlNotification = post(control)
        val target = seedMessage(container, "unobserved-target")
        NotificationHelper.installNotificationObservationHiddenForTest(true)
        try {
            // The OS really accepts the child. Only the helper's observation is hidden, so its
            // deletion capture must use the submitted instance rather than an active snapshot.
            awaitAlarmPlayback(expected = false)
            assertTrue(NotificationHelper.showMessageNotification(app, target, level = "critical"))
            val targetNotification = awaitPosted("message", target.id, target.title, target.body)
            awaitAlarmPlayback(expected = true)
            commitAndStop(container, PendingLocalDeletionOperation.messages(setOf(target.id)))
            assertNull(container.database.messageDao().getById(target.id))
            assertEquals(control.body, container.database.messageDao().getById(control.id)?.body)
            val receipt = container.database.deletionNotificationCleanupDao().loadAll().single()
            assertNotNull(targetNotification.tag)
            assertEquals(targetNotification.tag, receipt.tag)
            assertEquals(targetNotification.id, receipt.notificationId)
            assertEquals(generation(targetNotification), receipt.generation)
            assertEquals("Submitted instance has no helper-observed OS post time", 0L, receipt.postTime)
            assertPresent(targetNotification)
            assertPresent(controlNotification)
            awaitAlarmPlayback(expected = true)

            // Keep helper observation hidden through the actual production startup recovery.
            val reopened = reopen()
            assertNull(reopened.database.messageDao().getById(target.id))
            awaitAbsent(targetNotification)
            awaitAlarmPlayback(expected = false)
            assertPresent(controlNotification)
            assertEquals(control.body, reopened.database.messageDao().getById(control.id)?.body)
            assertNoDeletionRows(reopened)
        } finally {
            NotificationHelper.installNotificationObservationHiddenForTest(false)
            try {
                NotificationHelper.cancelMessageNotifications(app, listOf(target.id, control.id))
            } finally {
                AlertPlaybackController.stopAll(app)
            }
        }
    }

    @Test
    fun actualInboundPublishesCurrentMessageEventAndThingButRejectsAMissingDeliveryRow() = runBlocking {
        val container = app.container
        val message = message("inbound-positive-message")
        val messageOutcome = InboundPersistenceCoordinator.persistAndNotify(
            context = app,
            messageRepository = container.messageRepository,
            entityRepository = container.entityRepository,
            inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
            settingsRepository = container.settingsRepository,
            inbound = InboundPersistenceRequest.Message(
                message = message, level = "low", imageUrl = null, shouldNotify = true,
            ),
        )
        assertEquals(InboundPersistenceStatus.PERSISTED_MAIN, messageOutcome.status)
        assertTrue(messageOutcome.notified)
        val messageNotification = awaitPosted("message", message.id, message.title, message.body)
        assertEquals(message.body, container.database.messageDao().getById(message.id)?.body)

        val event = entity("event", "inbound-positive-event")
        val thing = entity("thing", "inbound-positive-thing")
        val entityNotifications = listOf(event, thing).map { entity ->
            val outcome = InboundPersistenceCoordinator.persistAndNotify(
                context = app,
                messageRepository = container.messageRepository,
                entityRepository = container.entityRepository,
                inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
                settingsRepository = container.settingsRepository,
                inbound = InboundPersistenceRequest.Entity(
                    record = entity, level = "low", notificationTitle = entity.title,
                    notificationBody = entity.body, shouldNotify = true,
                ),
            )
            assertEquals(InboundPersistenceStatus.PERSISTED_MAIN, outcome.status)
            assertTrue("Current ${entity.entityType} must reach real OS publication", outcome.notified)
            awaitPosted(entity.entityType, entity.entityId, entity.title, entity.body)
        }
        val eventHead = checkNotNull(container.database.topLevelEventHeadDao().getByEventId(event.entityId))
        assertEquals(thing.body, container.database.thingHeadDao().getByThingId(thing.entityId)?.body)

        // A semantic head alone is not the concrete accepted delivery to be published.
        assertEquals(1, container.database.eventChangeLogDao().deleteByEventId(event.entityId))
        assertEquals(eventHead, container.database.topLevelEventHeadDao().getByEventId(event.entityId))
        var callbackInvoked = false
        val rejected = container.entityRepository.publishNotificationIfCurrent(
            event, deliveryScope = null, replay = false,
        ) {
            callbackInvoked = true
            true
        }
        assertNull(rejected)
        assertFalse("Missing delivery log must prevent the publication callback", callbackInvoked)
        assertPresent(messageNotification)
        entityNotifications.forEach { assertPresent(it) }
        assertNoDeletionRows(container)
    }

    @Test
    fun messageDeletedAfterPersistenceCannotPublishItsDelayedInboundNotification() = runBlocking {
        val container = app.container
        val target = message("late-inbound")
        val control = seedMessage(container, "late-inbound-control")
        val controlNotification = post(control)
        val persisted = CompletableDeferred<Unit>()
        val releaseNotification = CompletableDeferred<Unit>()
        val inbound = async {
            InboundPersistenceCoordinator.persistAndNotify(
                context = app,
                messageRepository = container.messageRepository,
                entityRepository = container.entityRepository,
                inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
                settingsRepository = container.settingsRepository,
                inbound = InboundPersistenceRequest.Message(
                    message = target, level = "low", imageUrl = null, shouldNotify = true,
                ),
                beforeMessageNotify = { candidate, _ ->
                    assertEquals(target.id, candidate.id)
                    persisted.complete(Unit)
                    releaseNotification.await()
                },
            )
        }
        try {
            withTimeout(5_000L) { persisted.await() }
            assertEquals(target.body, container.database.messageDao().getById(target.id)?.body)
            assertTrue(manager.activeNotifications.none { it.notification.extras.getString(OWN_ID) == target.id })
            assertSucceeded(answer(container, PendingLocalDeletionOperation.messages(setOf(target.id))))
            assertNull(container.database.messageDao().getById(target.id))
            // There was no OS instance to capture: a startup sweep cannot repair a future post.
            assertNoDeletionRows(container)
            releaseNotification.complete(Unit)
            val outcome = withTimeout(5_000L) { inbound.await() }
            assertEquals(InboundPersistenceStatus.PERSISTED_MAIN, outcome.status)
            assertFalse("The completed inbound path must suppress its stale notification", outcome.notified)
            assertTrue(manager.activeNotifications.none { it.notification.extras.getString(OWN_ID) == target.id })
            assertPresent(controlNotification)
            val reopened = reopen()
            assertNull(reopened.database.messageDao().getById(target.id))
            assertTrue(manager.activeNotifications.none { it.notification.extras.getString(OWN_ID) == target.id })
            assertPresent(controlNotification)
            assertNoDeletionRows(reopened)
        } finally {
            releaseNotification.complete(Unit)
            inbound.cancelAndJoin()
        }
    }

    @Test
    fun room34To35MigrationPreservesExistingMessagesAndAddsEmptyCleanupReceipts() = runBlocking {
        seedVersion34Database()
        val container = app.container
        val stored = checkNotNull(container.database.messageDao().getById(id("legacy-message")))
        assertEquals("Existing v34 title", stored.title)
        assertEquals("Existing v34 body", stored.body)
        assertEquals(RECEIVED_AT, stored.receivedAt)
        assertFalse(stored.isRead)
        assertEquals(35, container.database.openHelper.readableDatabase.version)
        assertNoDeletionRows(container)
        val reopened = reopen()
        assertEquals(stored, reopened.database.messageDao().getById(stored.id))
        assertEquals(35, reopened.database.openHelper.readableDatabase.version)
        assertNoDeletionRows(reopened)
    }

    private data class Outcome(val succeeded: Boolean, val completion: Result<Unit>?)

    private suspend fun answer(
        container: AppContainer,
        operation: PendingLocalDeletionOperation,
        accept: Boolean = true,
        summary: String = "Delete ${operation.targetIds.single()}",
        onConfirmation: (String) -> Unit = {},
        whilePresented: suspend () -> Unit = {},
    ): Outcome = coroutineScope {
        val coordinator = container.pendingLocalDeletionCoordinator
        var completion: Result<Unit>? = null
        val pending = async { coordinator.confirmAndDelete(summary, operation) { completion = it } }
        try {
            val confirmation = withTimeout(5_000L) { coordinator.confirmation.filterNotNull().first() }
            onConfirmation(confirmation.summary)
            whilePresented()
            if (accept) coordinator.confirm(confirmation.id) else coordinator.cancel(confirmation.id)
            val succeeded = withTimeout(5_000L) { pending.await() }
            assertNull(coordinator.confirmation.value)
            Outcome(succeeded, completion)
        } finally {
            pending.cancelAndJoin()
        }
    }

    private class StoppedAfterCommit : IllegalStateException("Test stop after Room commit, before OS cleanup")

    private suspend fun commitAndStop(container: AppContainer, operation: PendingLocalDeletionOperation) {
        val stop = StoppedAfterCommit()
        QualityRuntime.installOrdinaryDeletionAfterCommitHookForTest { throw stop }
        try {
            val result = answer(container, operation)
            assertFalse(result.succeeded)
            assertSame("Must reach the exact post-commit boundary", stop, result.completion?.exceptionOrNull())
        } finally {
            QualityRuntime.installOrdinaryDeletionAfterCommitHookForTest(null)
        }
        assertNoPendingIntent(container)
    }

    private fun assertStale(result: Outcome) {
        assertFalse(result.succeeded)
        assertTrue("Expected stale confirmation, got ${result.completion}",
            result.completion?.exceptionOrNull() is OrdinaryDeletionTargetChangedException)
    }

    private fun assertSucceeded(result: Outcome) {
        assertTrue("Deletion completion: ${result.completion}", result.succeeded)
        assertTrue(result.completion?.isSuccess == true)
    }

    private suspend fun assertNoPendingIntent(container: AppContainer) {
        assertTrue(container.database.pendingLocalDeletionDao().loadActive().isEmpty())
        assertTrue(container.database.pendingLocalDeletionDao().loadFailures().isEmpty())
    }

    private suspend fun assertNoDeletionRows(container: AppContainer) {
        assertNoPendingIntent(container)
        assertTrue(container.database.deletionNotificationCleanupDao().loadAll().isEmpty())
    }

    private suspend fun assertReceipts(container: AppContainer, targets: List<StatusBarNotification>) {
        val receipts = container.database.deletionNotificationCleanupDao().loadAll()
        assertEquals(targets.size, receipts.size)
        assertEquals(targets.map { it.tag to it.id }.toSet(), receipts.map { it.tag to it.notificationId }.toSet())
        targets.forEach { target ->
            val receipt = receipts.single { it.tag == target.tag && it.notificationId == target.id }
            assertEquals(target.postTime, receipt.postTime)
            assertEquals(generation(target), receipt.generation)
        }
    }

    private fun reopen(): AppContainer {
        app.releaseStorageForInstrumentationTest()
        return app.container.also { assertNull(app.startupStorageErrorMessage()) }
    }

    private suspend fun seedMessage(container: AppContainer, suffix: String, thingId: String? = null): PushMessage {
        val message = message(suffix, thingId)
        assertTrue(container.messageRepository.insertIncoming(message))
        return message
    }

    private fun message(suffix: String, thingId: String? = null): PushMessage {
        val messageId = id(suffix)
        notificationOwners += "message" to messageId
        return PushMessage(
            id = messageId, messageId = messageId, title = "Title $messageId", body = "Body $messageId",
            channel = id("channel"), url = null, isRead = false,
            receivedAt = Instant.ofEpochMilli(RECEIVED_AT),
            rawPayloadJson = JSONObject().put("entity_type", "message").put("entity_id", messageId)
                .put("thing_id", thingId).toString(),
            status = MessageStatus.NORMAL, decryptionState = null, notificationId = null, serverId = null,
        )
    }

    private suspend fun seedEntity(
        container: AppContainer, kind: String, suffix: String, thingId: String? = null,
    ): IncomingEntityRecord = entity(kind, suffix, thingId).also {
        assertTrue(container.entityRepository.insertIncoming(it))
    }

    private fun entity(kind: String, suffix: String, thingId: String? = null): IncomingEntityRecord {
        val entityId = id(suffix)
        notificationOwners += kind to entityId
        val eventId = entityId.takeIf { kind == "event" }
        val relatedThingId = if (kind == "thing") entityId else thingId
        return IncomingEntityRecord(
            entityType = kind, entityId = entityId, channel = id("channel"),
            title = "Title $entityId", body = "Body $entityId",
            rawPayloadJson = JSONObject().put("entity_type", kind).put("entity_id", entityId)
                .put("event_id", eventId).put("thing_id", relatedThingId).toString(),
            receivedAt = Instant.ofEpochMilli(RECEIVED_AT), opId = null, deliveryId = id("delivery-$suffix"),
            serverId = null, eventId = eventId, thingId = relatedThingId, eventState = "open",
            eventTimeEpoch = RECEIVED_AT, observedTimeEpoch = null,
        )
    }

    private suspend fun post(message: PushMessage): StatusBarNotification {
        assertTrue(NotificationHelper.showMessageNotification(app, message, level = "low"))
        return awaitPosted("message", message.id, message.title, message.body)
    }

    private suspend fun post(entity: IncomingEntityRecord): StatusBarNotification {
        assertTrue(NotificationHelper.showEntityNotification(
            app, entity.entityType, entity.entityId, entity.channel, entity.eventId, entity.thingId,
            entity.title, entity.body, level = "low",
        ))
        return awaitPosted(entity.entityType, entity.entityId, entity.title, entity.body)
    }

    private suspend fun awaitPosted(kind: String, ownId: String, title: String, body: String): StatusBarNotification {
        fun matches() = manager.activeNotifications.filter {
            it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                it.notification.extras.getString(OWN_KIND) == kind &&
                it.notification.extras.getString(OWN_ID) == ownId &&
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title &&
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == body
        }
        withTimeout(5_000L) { while (matches().isEmpty()) delay(25L) }
        return matches().single().also { generation(it) }
    }

    private fun generation(notification: StatusBarNotification): String =
        checkNotNull(notification.notification.extras.getString(GENERATION)).also { assertTrue(it.isNotBlank()) }

    private fun assertPresent(expected: StatusBarNotification) {
        val current = manager.activeNotifications.singleOrNull { it.key == expected.key }
        assertNotNull("Notification ${expected.key} must remain", current)
        current ?: return
        assertEquals(generation(expected), generation(current))
        assertEquals(expected.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            current.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(expected.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            current.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    private suspend fun awaitAbsent(expected: StatusBarNotification) {
        withTimeout(5_000L) {
            while (manager.activeNotifications.any { it.key == expected.key }) delay(25L)
        }
    }

    // Same OS oracle as the system-notification journey: the app UID's real alarm player,
    // rather than service flags or a test callback. Critical playback has no expiry timeout.
    private suspend fun awaitAlarmPlayback(expected: Boolean) {
        val appUid = android.os.Process.myUid()
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        do {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("dumpsys audio")
            val audio = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader()
                .use { it.readText() }
            val playing = audio.lineSequence().any { line ->
                line.contains("AudioPlaybackConfiguration") && line.contains("u/pid:$appUid/") &&
                    line.contains("state:started") && line.contains("usage=USAGE_ALARM")
            }
            if (playing == expected) return
            delay(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("System USAGE_ALARM playback for PushGo UID $appUid did not become $expected")
    }

    private fun seedVersion34Database() {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("34.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        assertEquals(34, schema.getInt("version"))
        val file = app.getDatabasePath(session.databaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.beginTransaction()
            try {
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val table = entity.getString("tableName")
                    database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (entry in 0 until indices.length()) {
                            database.execSQL(indices.getJSONObject(entry).getString("createSql")
                                .replace("\${TABLE_NAME}", table))
                        }
                    }
                    entity.optJSONArray("contentSyncTriggers")?.let { triggers ->
                        for (entry in 0 until triggers.length()) database.execSQL(triggers.getString(entry))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) database.execSQL(setup.getString(index))
                database.execSQL(
                    """INSERT INTO messages(
                        id, message_id, title, body, channel, url, is_read, received_at, raw_payload_json,
                        status, decryption_state, notification_id, server_id, body_preview, entity_type,
                        entity_id, event_id, thing_id, event_state, event_time_epoch, occurred_at_epoch,
                        list_payload_json
                    ) VALUES(?, ?, 'Existing v34 title', 'Existing v34 body', NULL, NULL, 0, ?, '{}',
                        'NORMAL', NULL, NULL, NULL, 'Existing v34 body', 'message', ?, NULL, NULL,
                        NULL, NULL, NULL, '{}')""".trimIndent(),
                    arrayOf<Any>(id("legacy-message"), id("legacy-message"), RECEIVED_AT, id("legacy-message")),
                )
                database.version = 34
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun id(suffix: String) = "${session.sessionId}-$suffix"

    companion object {
        private const val RECEIVED_AT = 1_760_000_000_000L
        private const val OWN_KIND = "io.ethan.pushgo.notification.own_kind"
        private const val OWN_ID = "io.ethan.pushgo.notification.own_id"
        private const val GENERATION = "io.ethan.pushgo.notification.generation"
    }
}
