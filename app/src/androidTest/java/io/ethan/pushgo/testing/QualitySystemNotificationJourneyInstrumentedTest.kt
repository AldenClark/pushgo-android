package io.ethan.pushgo.testing

import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.ethan.pushgo.data.model.MessageListItem
import io.ethan.pushgo.notifications.InboundMessagePayloadCodec
import io.ethan.pushgo.notifications.InboundMessageWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Purpose-level notification journey.
 *
 * Unlike the legacy Runtime command test, this starts at the production durable worker and
 * crosses NotificationManager + the system shade + the real PendingIntent before asserting the
 * exact product detail, read state, de-duplication, and relaunch outcome.
 */
@RunWith(AndroidJUnit4::class)
class QualitySystemNotificationJourneyInstrumentedTest : QualityAppJourneyTestCase() {
    private val notificationManager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    @After
    fun clearJourneyNotifications() {
        notificationManager.cancelAll()
    }

    @Test
    fun duplicateInboundDeliveryPostsOneSystemNotificationAndOpensAccurateReadDetail() = runBlocking {
        val title = "Quality system route 20260828"
        val body = "Exact Android notification route body 20260828."
        val messageId = "quality-system-route-message-20260828"
        val payload = linkedMapOf(
            "entity_type" to "message",
            "entity_id" to messageId,
            "message_id" to messageId,
            "title" to title,
            "body" to body,
            "channel_id" to "quality-system-route",
            "severity" to "normal",
            "sent_at" to "1787918400000",
        )

        configureAndLaunch(fixture = QualityFixture.EMPTY_CLEAN)
        grantAndVerifyNotificationPermission()
        notificationManager.cancelAll()

        // Remove the Activity so the production notification policy must use the system surface.
        scenario?.close()
        scenario = null
        awaitCondition("App activity did not leave the visible lifecycle") { !app.isAppVisible() }

        enqueueAndAwaitSuccess(payload, transportMessageId = "quality-system-route-delivery-1")
        enqueueAndAwaitSuccess(payload, transportMessageId = "quality-system-route-delivery-2")

        val persisted = awaitSingleMessage(title)
        assertEquals(messageId, persisted.messageId)
        assertEquals(body, persisted.bodyPreview)
        assertEquals(false, persisted.isRead)
        assertEquals(1, checkNotNull(app.containerOrNull()).messageRepository.totalCount())

        val matchingNotifications = awaitMatchingNotifications(title, expectedCount = 1)
        assertEquals(body, matchingNotifications.single().notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString())

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.openNotification()
        assertTrue(
            "System notification title was not exposed by the notification shade",
            device.wait(Until.hasObject(By.text(title)), 8_000),
        )
        assertNotNull(
            "System notification body was not exposed by the notification shade",
            device.wait(Until.findObject(By.text(body)), 4_000),
        )
        val systemNotification = device.findObject(By.text(title))
        assertNotNull("Exact system notification row was not found", systemNotification)
        systemNotification.click()

        assertTrue(
            "Notification PendingIntent did not foreground PushGo",
            device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), 10_000),
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("sheet.message.detail"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sheet.message.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.message.detail.title")
            .assertTextContains(title)
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains(body)

        awaitCondition("Opening the real detail did not mark the canonical message read") {
            checkNotNull(app.containerOrNull()).messageRepository.unreadCount() == 0
        }
        awaitMatchingNotifications(title, expectedCount = 0)

        // Recreate the Activity without an intent shortcut: the same canonical row must remain,
        // remain read, and be reachable from the normal product list.
        scenario = launchMainActivity()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("screen.messages.list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).assertIsDisplayed()
        val afterRelaunch = awaitSingleMessage(title)
        assertEquals(persisted.id, afterRelaunch.id)
        assertTrue(afterRelaunch.isRead)
        assertEquals(1, checkNotNull(app.containerOrNull()).messageRepository.totalCount())
    }

    private fun grantAndVerifyNotificationPermission() {
        NotificationPermissionTestSupport.grantAndVerify(app)
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

    private suspend fun awaitSingleMessage(title: String): MessageListItem {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var matches = emptyList<MessageListItem>()
        do {
            matches = checkNotNull(app.containerOrNull()).messageRepository
                .searchMessagesSnapshot(title, unreadOnly = false, limit = 10)
                .filter { it.title == title }
            if (matches.size == 1) return matches.single()
            delay(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Expected one canonical message titled '$title', found ${matches.size}")
    }

    private suspend fun awaitMatchingNotifications(
        title: String,
        expectedCount: Int,
    ): List<android.service.notification.StatusBarNotification> {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        var matches = emptyList<android.service.notification.StatusBarNotification>()
        do {
            matches = notificationManager.activeNotifications.filter { status ->
                status.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
            }
            if (matches.size == expectedCount) return matches
            delay(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(
            "Expected $expectedCount active notification(s) titled '$title', found ${matches.size}",
        )
    }

    private suspend fun awaitCondition(reason: String, predicate: suspend () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        do {
            if (predicate()) return
            delay(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(reason)
    }
}
