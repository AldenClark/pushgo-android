package io.ethan.pushgo.testing

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.ethan.pushgo.data.ProviderAckContract
import io.ethan.pushgo.data.ProviderAckDestination
import io.ethan.pushgo.data.ProviderAckIdentity
import io.ethan.pushgo.data.inboundDeliveryScope
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.data.model.MessageListItem
import io.ethan.pushgo.notifications.AlertPlaybackService
import io.ethan.pushgo.notifications.InboundMessagePayloadCodec
import io.ethan.pushgo.notifications.InboundMessageWorker
import io.ethan.pushgo.notifications.NotificationIngressParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        app.stopService(Intent(app, AlertPlaybackService::class.java))
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
            "severity" to "critical",
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
        val alertNotification = awaitNotificationByTitle(
            app.getString(io.ethan.pushgo.R.string.alert_playback_service_notification_title),
            expected = true,
        )
        assertEquals(
            app.getString(io.ethan.pushgo.R.string.alert_playback_service_notification_text),
            alertNotification?.notification?.extras
                ?.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString(),
        )
        awaitAlertPlaybackService(expected = true)
        awaitAlarmPlayback(expected = true)

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
        composeRule.onNode(
            hasTestTag("banner.message.severity.critical") and
                hasAnyDescendant(
                    hasText("Critical message, please handle it as soon as possible."),
                ),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        awaitCondition("Opening the real detail did not mark the canonical message read") {
            checkNotNull(app.containerOrNull()).messageRepository.unreadCount() == 0
        }
        awaitMatchingNotifications(title, expectedCount = 0)
        awaitNotificationByTitle(
            app.getString(io.ethan.pushgo.R.string.alert_playback_service_notification_title),
            expected = false,
        )
        awaitAlertPlaybackService(expected = false)
        awaitAlarmPlayback(expected = false)

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

        assertEncryptedProviderAuthenticationBoundaries()
    }

    /**
     * One App-owned session covers all three competing notification-detail owners. The purpose is
     * not to prove that a row exists: a user must be able to tap the real system notification and
     * land on the exact persisted Event/Thing/Message, including cold and warm launch, without a
     * stale detail owner surviving the next notification.
     * This remains in the system-notification lane because a Compose-only launch-intent shortcut
     * cannot expose NotificationManager, shade selection, PendingIntent, or onNewIntent defects.
     */
    @Test
    fun entityInboundNotificationsOpenExactColdEventAndWarmThingDetails() = runBlocking {
        val eventId = "quality-system-event-route"
        val eventTitle = "Quality system cooling event"
        val eventSummary = "Exact event projection selected from Android's system notification."
        val thingId = "quality-system-thing-route"
        val thingTitle = "Quality system reactor"
        val thingSummary = "Exact thing projection selected from Android's system notification."
        val messageId = "quality-system-message-after-thing"
        val messageTitle = "Quality system routed message"
        val messageBody = "Exact message selected after replacing the warm Thing detail."

        configureAndLaunch(fixture = QualityFixture.EMPTY_CLEAN)
        grantAndVerifyNotificationPermission()
        notificationManager.cancelAll()

        scenario?.close()
        scenario = null
        awaitCondition("App activity did not leave the visible lifecycle") { !app.isAppVisible() }

        enqueueAndAwaitSuccess(
            payload = linkedMapOf(
                "entity_type" to "event",
                "entity_id" to eventId,
                "event_id" to eventId,
                "delivery_id" to "quality-system-event-delivery",
                "op_id" to "quality-system-event-op",
                "event_state" to "ONGOING",
                "event_time" to "1787920200000",
                "title" to eventTitle,
                "body" to eventSummary,
                "description" to eventSummary,
                "message" to "Inspect the exact system-routed event.",
                "severity" to "high",
                "channel_id" to "quality-system-entity-route",
                "sent_at" to "1787920200000",
            ),
            transportMessageId = "quality-system-event-transport",
        )
        awaitCondition("The production worker did not persist the exact Event projection") {
            checkNotNull(app.containerOrNull()).entityRepository
                .getEventProjectionDetail(eventId)
                ?.head
                ?.let { it.title == eventTitle && it.body == eventSummary } == true
        }
        awaitMatchingNotifications(eventTitle, expectedCount = 1)
            .single()
            .notification
            .extras
            .getCharSequence(Notification.EXTRA_TEXT)
            .let { assertEquals(eventSummary, it?.toString()) }

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        openExactSystemNotification(device, eventTitle, eventSummary)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("sheet.event.detail"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sheet.event.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText(eventTitle)))
        composeRule.onNodeWithTag("field.event.detail.summary")
            .assertTextEquals(eventSummary)

        // Keep the Event detail open while backgrounding. The Thing notification must replace it
        // through MainActivity.onNewIntent rather than merely returning to a list or stale sheet.
        device.pressHome()
        awaitCondition("PushGo did not leave the foreground before the warm route") {
            !app.isAppVisible()
        }
        enqueueAndAwaitSuccess(
            payload = linkedMapOf(
                "entity_type" to "thing",
                "entity_id" to thingId,
                "thing_id" to thingId,
                "delivery_id" to "quality-system-thing-delivery",
                "op_id" to "quality-system-thing-op",
                "observed_at" to "1787920260000",
                "title" to thingTitle,
                "body" to thingSummary,
                "description" to thingSummary,
                "state" to "active",
                "severity" to "normal",
                "channel_id" to "quality-system-entity-route",
                "sent_at" to "1787920260000",
            ),
            transportMessageId = "quality-system-thing-transport",
        )
        awaitCondition("The production worker did not persist the exact Thing projection") {
            checkNotNull(app.containerOrNull()).entityRepository
                .getThingProjectionDetail(thingId)
                ?.head
                ?.let { it.title == thingTitle && it.body == thingSummary } == true
        }
        awaitMatchingNotifications(thingTitle, expectedCount = 1)
            .single()
            .notification
            .extras
            .getCharSequence(Notification.EXTRA_TEXT)
            .let { assertEquals(thingSummary, it?.toString()) }

        openExactSystemNotification(device, thingTitle, thingSummary)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("sheet.thing.detail"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sheet.thing.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText(thingTitle)))
            .assert(hasAnyDescendant(hasText(thingSummary)))
        composeRule.onNodeWithTag("sheet.event.detail").assertDoesNotExist()

        // Complete the ownership chain with a normal Message notification. This is one positive
        // journey, not a pairwise transition matrix: it proves the common entry point cannot leave
        // the warm Thing sheet as the visible owner when the exact Message is requested.
        device.pressHome()
        awaitCondition("PushGo did not leave the foreground before the warm Message route") {
            !app.isAppVisible()
        }
        enqueueAndAwaitSuccess(
            payload = linkedMapOf(
                "entity_type" to "message",
                "entity_id" to messageId,
                "message_id" to messageId,
                "title" to messageTitle,
                "body" to messageBody,
                "channel_id" to "quality-system-entity-route",
                "severity" to "normal",
                "sent_at" to "1787920320000",
            ),
            transportMessageId = "quality-system-message-after-thing-transport",
        )
        val routedMessage = awaitSingleMessage(messageTitle)
        assertEquals(messageId, routedMessage.messageId)
        assertEquals(messageBody, routedMessage.bodyPreview)
        awaitMatchingNotifications(messageTitle, expectedCount = 1)
            .single()
            .notification
            .extras
            .getCharSequence(Notification.EXTRA_TEXT)
            .let { assertEquals(messageBody, it?.toString()) }

        openExactSystemNotification(device, messageTitle, messageBody)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("sheet.message.detail"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("field.message.detail.title")
            .assertTextContains(messageTitle)
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains(messageBody)
        composeRule.onNodeWithTag("sheet.thing.detail").assertDoesNotExist()

        // A normal launch without notification extras must retain both canonical projections and
        // make them reachable from ordinary product navigation.
        scenario = launchMainActivity()
        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("event.row.$eventId", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText(eventTitle)))
            .performClick()
        composeRule.onNodeWithTag("field.event.detail.summary").assertTextEquals(eventSummary)
        device.pressBack()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("sheet.event.detail"))
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("thing.row.$thingId", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText(thingTitle)))
            .performClick()
        composeRule.onNodeWithTag("sheet.thing.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText(thingSummary)))
        Unit
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

    /**
     * Extends the positive system-notification session through the provider ACK boundary. A
     * parser-only test cannot catch a later layer restoring unauthenticated companion fields,
     * notifying them, or treating an authentication failure as successfully consumed.
     */
    private suspend fun assertEncryptedProviderAuthenticationBoundaries() {
        val container = checkNotNull(app.containerOrNull())
        val authenticatedKey = ByteArray(16) { index -> (index + 1).toByte() }
        val wrongKey = ByteArray(16) { index -> (index + 33).toByte() }
        val providerDestination = ProviderAckDestination(
            baseUrl = "https://quality.invalid",
            deviceKey = "quality-auth-device",
        )
        val providerIdentity = checkNotNull(
            ProviderAckIdentity.create(
                destination = providerDestination,
                contract = ProviderAckContract.LEGACY_SINGLE,
                source = "provider_direct",
            ),
        )

        scenario?.close()
        scenario = null
        awaitCondition("App activity did not leave before encrypted provider ingress") {
            !app.isAppVisible()
        }
        notificationManager.cancelAll()

        val wrongKeyId = "quality-authentication-wrong-key"
        val wrongKeyDeliveryId = "quality-authentication-wrong-key-delivery"
        val wrongKeyMarker = "wrong-key-companion-must-never-escape"
        container.settingsRepository.setNotificationKeyBytes(wrongKey)
        enqueueAndAwaitSuccess(
            payload = encryptedProviderPayload(
                messageId = wrongKeyId,
                deliveryId = wrongKeyDeliveryId,
                ciphertext = encryptForIngress(
                    JSONObject()
                        .put("title", "Wrong-key trusted title")
                        .put("body", "Wrong-key trusted body")
                        .put("url", "https://trusted.example/wrong-key")
                        .put("images", JSONArray().put("https://trusted.example/wrong-key.png"))
                        .put("metadata", JSONObject().put("quality-authenticated", "wrong-key"))
                        .toString(),
                    authenticatedKey,
                ),
                companionMarker = wrongKeyMarker,
            ),
            transportMessageId = "quality-authentication-wrong-key-transport",
        )
        assertQuarantinedProviderMessage(
            messageId = wrongKeyId,
            deliveryId = wrongKeyDeliveryId,
            companionMarker = wrongKeyMarker,
            providerIdentity = providerIdentity,
        )

        val corruptId = "quality-authentication-bit-corrupt"
        val corruptDeliveryId = "quality-authentication-bit-corrupt-delivery"
        val corruptMarker = "bit-corrupt-companion-must-never-escape"
        container.settingsRepository.setNotificationKeyBytes(authenticatedKey)
        val validCiphertext = encryptForIngress(
            JSONObject()
                .put("title", "Bit-corrupt trusted title")
                .put("body", "Bit-corrupt trusted body")
                .put("url", "https://trusted.example/bit-corrupt")
                .put("images", JSONArray().put("https://trusted.example/bit-corrupt.png"))
                .put("metadata", JSONObject().put("quality-authenticated", "bit-corrupt"))
                .toString(),
            authenticatedKey,
        )
        enqueueAndAwaitSuccess(
            payload = encryptedProviderPayload(
                messageId = corruptId,
                deliveryId = corruptDeliveryId,
                ciphertext = corruptCiphertext(validCiphertext),
                companionMarker = corruptMarker,
            ),
            transportMessageId = "quality-authentication-bit-corrupt-transport",
        )
        assertQuarantinedProviderMessage(
            messageId = corruptId,
            deliveryId = corruptDeliveryId,
            companionMarker = corruptMarker,
            providerIdentity = providerIdentity,
        )

        assertEquals(3, container.messageRepository.totalCount())
    }

    private fun encryptedProviderPayload(
        messageId: String,
        deliveryId: String,
        ciphertext: String,
        companionMarker: String,
    ): Map<String, String> = linkedMapOf(
        "entity_type" to "message",
        "entity_id" to messageId,
        "message_id" to messageId,
        "delivery_id" to deliveryId,
        "base_url" to "https://quality.invalid",
        "provider_device_key" to "quality-auth-device",
        "title" to "$companionMarker title",
        "body" to "$companionMarker body",
        "url" to "https://attacker.example/$companionMarker/open",
        "images" to JSONArray()
            .put("https://attacker.example/$companionMarker/image.png")
            .toString(),
        "metadata" to JSONObject()
            .put("source", "$companionMarker metadata")
            .toString(),
        "ciphertext" to ciphertext,
    )

    private suspend fun assertQuarantinedProviderMessage(
        messageId: String,
        deliveryId: String,
        companionMarker: String,
        providerIdentity: ProviderAckIdentity,
    ) {
        val container = checkNotNull(app.containerOrNull())
        val quarantined = checkNotNull(container.messageRepository.getByMessageId(messageId))
        assertEquals(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE, quarantined.title)
        assertEquals(NotificationIngressParser.AUTHENTICATION_FAILED_BODY, quarantined.body)
        assertNull(quarantined.url)
        assertTrue(quarantined.imageUrls.isEmpty())
        assertTrue(quarantined.metadata.isEmpty())
        assertEquals(DecryptionState.DECRYPT_FAILED, quarantined.decryptionState)
        assertFalse(quarantined.rawPayloadJson.contains(companionMarker))
        assertNoNotificationContains(companionMarker)
        awaitMatchingNotifications(
            NotificationIngressParser.AUTHENTICATION_FAILED_TITLE,
            expectedCount = 0,
        )
        assertTrue(
            "Authentication-failed provider delivery posted a system notification: $deliveryId",
            notificationManager.activeNotifications.isEmpty(),
        )
        assertFalse(
            "Authentication-failed provider delivery became ACK-eligible: $deliveryId",
            container.inboundDeliveryLedgerRepository.shouldAck(deliveryId, providerIdentity),
        )
        assertNull(
            "Authentication-failed provider delivery created a scoped ledger record: $deliveryId",
            container.inboundDeliveryLedgerRepository.deliveryAckState(
                deliveryId,
                providerIdentity.inboundDeliveryScope(),
            ),
        )
        assertNull(
            "Authentication-failed provider delivery polluted the unscoped ledger: $deliveryId",
            container.inboundDeliveryLedgerRepository.deliveryAckState(deliveryId, null),
        )
        assertFalse(
            "Authentication-failed provider delivery entered the durable ACK outbox: $deliveryId",
            container.inboundDeliveryLedgerRepository.loadPendingAckIds(limit = 100)
                .contains(deliveryId),
        )
    }

    private fun assertNoNotificationContains(marker: String) {
        val exposed = notificationManager.activeNotifications.any { status ->
            val extras = status.notification.extras
            listOf(
                extras.getCharSequence(Notification.EXTRA_TITLE),
                extras.getCharSequence(Notification.EXTRA_TEXT),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            ).any { value -> value?.toString()?.contains(marker) == true }
        }
        assertFalse("System notification exposed unauthenticated companion data: $marker", exposed)
    }

    private fun encryptForIngress(plaintext: String, keyBytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = byteArrayOf(1, 3, 5, 7, 9, 11, 13, 15, 2, 4, 6, 8)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val cipherAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipherAndTag + iv)
    }

    private fun corruptCiphertext(ciphertext: String): String {
        val bytes = Base64.getDecoder().decode(ciphertext)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        return Base64.getEncoder().encodeToString(bytes)
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

    private suspend fun awaitNotificationByTitle(
        title: String,
        expected: Boolean,
    ): android.service.notification.StatusBarNotification? {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        do {
            val match = notificationManager.activeNotifications.firstOrNull { status ->
                status.notification.extras
                    .getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString() == title
            }
            if ((match != null) == expected) return match
            delay(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Notification '$title' presence did not become $expected")
    }

    private fun openExactSystemNotification(
        device: UiDevice,
        title: String,
        body: String,
    ) {
        device.openNotification()
        assertTrue(
            "System notification title was not exposed by the notification shade: $title",
            device.wait(Until.hasObject(By.text(title)), 8_000),
        )
        assertNotNull(
            "System notification body was not exposed by the notification shade: $body",
            device.wait(Until.findObject(By.text(body)), 4_000),
        )
        val notification = device.findObject(By.text(title))
        assertNotNull("Exact system notification row was not found: $title", notification)
        notification.click()
        assertTrue(
            "Notification PendingIntent did not foreground PushGo for $title",
            device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), 10_000),
        )
    }

    private suspend fun awaitAlertPlaybackService(expected: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        do {
            val registered = shell("dumpsys activity services ${app.packageName}")
                .contains("AlertPlaybackService")
            if (registered == expected) return
            delay(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("AlertPlaybackService registration did not become $expected")
    }

    private suspend fun awaitAlarmPlayback(expected: Boolean) {
        val appUid = android.os.Process.myUid()
        val deadline = SystemClock.elapsedRealtime() + 8_000
        do {
            val playing = shell("dumpsys audio")
                .lineSequence()
                .any { line ->
                    line.contains("AudioPlaybackConfiguration") &&
                        line.contains("u/pid:$appUid/") &&
                        line.contains("state:started") &&
                        line.contains("usage=USAGE_ALARM")
                }
            if (playing == expected) return
            delay(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("System USAGE_ALARM playback for PushGo did not become $expected")
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        }
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
