package io.ethan.pushgo.testing

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.ethan.pushgo.notifications.InboundMessagePayloadCodec
import io.ethan.pushgo.notifications.InboundMessageWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QualityThingEventActivationUiTest : QualityAppJourneyTestCase() {
    /**
     * Purpose: make an early child Event hidden from the Events entry point, then show that exact
     * stored history under its Thing when the parent arrives, without posting a child alert.
     * Independent defect: catches incorrect Room activation/query ownership, list/detail routing,
     * and notification replay. A repository-only test cannot prove the existing navigation entry
     * point hides the pending record or that the composed Thing detail renders the real history.
     * Expected cost: one instrumentation launch, two WorkManager deliveries, and two UI navigations.
     */
    @Test
    fun pendingChildEventBecomesThingHistoryWhenParentArrivesWithoutAnotherNotification() = runBlocking {
        val thingId = "quality-activation-thing"
        val thingTitle = "Quality activation reactor"
        val thingBody = "The parent Thing activates its stored Event history."
        val eventId = "quality-activation-child-event"
        val eventTitle = "Quality pressure threshold crossed"
        val eventBody = "Pressure reached 2.4 bar in the reactor loop."
        val channelId = "01H00000000000000000000000"

        configureAndLaunch(fixture = QualityFixture.EMPTY_CLEAN)
        NotificationPermissionTestSupport.requireEnabled(app)
        val notificationManager = app.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()

        enqueueAndAwaitSuccess(
            payload = linkedMapOf(
                "entity_type" to "event",
                "entity_id" to eventId,
                "event_id" to eventId,
                "thing_id" to thingId,
                "delivery_id" to "quality-activation-event-delivery",
                "op_id" to "quality-activation-event-op",
                "event_state" to "ONGOING",
                "event_time" to System.currentTimeMillis().toString(),
                "title" to eventTitle,
                "body" to eventBody,
                "description" to eventBody,
                "message" to "Inspect the pressure sensor readings.",
                "severity" to "high",
                "channel_id" to channelId,
                "sent_at" to System.currentTimeMillis().toString(),
            ),
            transportMessageId = "quality-activation-event-transport",
        )

        val entities = checkNotNull(app.containerOrNull()).entityRepository
        assertNull(entities.getThingProjectionDetail(thingId))
        assertNull(entities.getEventProjectionDetail(eventId))
        assertTrue(entities.getEventProjectionMessages().none { it.eventId == eventId })
        assertFalse(notificationManager.activeNotifications.any { hasNotificationTitle(it, eventTitle) })

        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("screen.events.list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("event.row.$eventId").assertDoesNotExist()
        composeRule.onNodeWithText(eventTitle).assertDoesNotExist()

        enqueueAndAwaitSuccess(
            payload = linkedMapOf(
                "entity_type" to "thing",
                "entity_id" to thingId,
                "thing_id" to thingId,
                "delivery_id" to "quality-activation-thing-delivery",
                "op_id" to "quality-activation-thing-op",
                "observed_at" to System.currentTimeMillis().toString(),
                "title" to thingTitle,
                "body" to thingBody,
                "description" to thingBody,
                "state" to "active",
                "severity" to "normal",
                "channel_id" to channelId,
                "sent_at" to System.currentTimeMillis().toString(),
            ),
            transportMessageId = "quality-activation-thing-transport",
        )

        val history = checkNotNull(entities.getThingProjectionDetail(thingId))
            .history
            .filter { it.eventId == eventId }
        assertEquals(1, history.size)
        assertEquals(eventTitle, history.single().title)
        assertEquals(eventBody, history.single().body)

        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("thing.row.$thingId"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("thing.row.$thingId").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("sheet.thing.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("tab.thing.detail.events").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("event.row.$eventId", useUnmergedTree = true)
            .assertIsDisplayed()
            .assert(hasAnyDescendant(hasText(eventTitle)))
            .assert(hasAnyDescendant(hasText(eventBody)))

        val notificationTitles = notificationManager.activeNotifications.mapNotNull {
            it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        }
        assertFalse(
            "Activating stored history posted a child Event notification: $notificationTitles",
            eventTitle in notificationTitles,
        )
        assertEquals(1, notificationTitles.count { it == thingTitle })
    }

    private fun enqueueAndAwaitSuccess(
        payload: Map<String, String>,
        transportMessageId: String,
    ) {
        val encoded = InboundMessagePayloadCodec.encode(payload)
        val uniqueName = InboundMessageWorker.buildUniqueWorkName(transportMessageId, encoded)
        InboundMessageWorker.enqueueForQualitySession(app, payload, transportMessageId)
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var states = emptyList<WorkInfo.State>()
        do {
            states = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork(uniqueName)
                .get(3, TimeUnit.SECONDS)
                .map(WorkInfo::state)
            if (states.isNotEmpty() && states.all(WorkInfo.State::isFinished)) break
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertTrue("Inbound worker did not finish for $uniqueName: $states", states.isNotEmpty())
        assertTrue("Inbound worker failed for $uniqueName: $states", states.all { it == WorkInfo.State.SUCCEEDED })
    }

    private fun hasNotificationTitle(status: android.service.notification.StatusBarNotification, title: String): Boolean =
        status.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
}
