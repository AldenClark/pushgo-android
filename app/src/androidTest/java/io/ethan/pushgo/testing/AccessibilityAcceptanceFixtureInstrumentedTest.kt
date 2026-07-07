package io.ethan.pushgo.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.IncomingEntityRecord
import io.ethan.pushgo.data.MessageImageStore
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.util.snoozeDozeReminderForOneMonth
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityAcceptanceFixtureInstrumentedTest {
    companion object {
        private const val OPS_ALERTS_CHANNEL_ID = "01HX5ECRN7FY2Y8A4K6M9PQRSV"
        private const val OPS_ASSETS_CHANNEL_ID = "01HX5ECS57Q2X4A6K8M9NPRTVW"
        private const val SECURE_NOTIFY_CHANNEL_ID = "01HX5ECT9K7R2Y4A6M8NPQRSVW"
        private const val ANIMATED_GIF_BASE64 =
            "R0lGODlhAgACAIEAAP8AAAAA////ACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQACgAAACwAAAAAAgACAIEA/wAAAP///wAIBgABCAQQEAA7"
    }

    @Test
    fun prepareAccessibilityAcceptanceFixture_persistsStateForManualValidation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as PushGoApp
        val container = checkNotNull(app.containerOrNull()) { "PushGoApp container unavailable" }
        val gatewayBaseUrl = resolveGatewayBaseUrl()

        container.automationController.resetLocalState()
        container.privateChannelClient.resetForAutomation()
        container.privateChannelClient.setForeground(true)
        container.settingsRepository.setUseFcmChannel(true)
        container.settingsRepository.setFcmToken("accessibility-fixture-token")
        container.settingsRepository.setServerAddress(gatewayBaseUrl)
        container.settingsRepository.setGatewayToken(null)
        container.settingsRepository.setMessagePageEnabled(true)
        container.settingsRepository.setEventPageEnabled(true)
        container.settingsRepository.setThingPageEnabled(true)
        context.snoozeDozeReminderForOneMonth()

        val imageAssets = writeFixtureImages(context)
        val fixtureFile = writeFixtureFile(container, imageAssets)
        val messages = buildSeedMessages(imageAssets)
        container.messageRepository.insertAll(messages)

        val importedEntities = buildSeedEntities().count { entity ->
            container.entityRepository.insertIncoming(entity)
        }
        container.messageRepository.replayPendingForThing("thing-rack-1")
        val importedSubscriptions = buildSeedSubscriptions(gatewayBaseUrl).count { subscription ->
            container.channelStore.upsertSubscription(
                gatewayUrl = subscription.getString("gateway_base_url"),
                channelId = subscription.getString("channel_id"),
                displayName = subscription.getString("display_name"),
                password = subscription.getString("password"),
                lastSyncedAt = subscription.getLong("last_synced_at"),
            )
            true
        }

        container.settingsRepository.setNotificationKeyBytes("0123456789abcdef".toByteArray())
        container.settingsRepository.setKeyEncoding(KeyEncoding.BASE64)

        val snapshot = container.automationController.snapshot()
        println(
            "ACCESSIBILITY_FIXTURE_SNAPSHOT " +
                "gateway_base_url=$gatewayBaseUrl " +
                "messages=${container.messageRepository.totalCount()} " +
                "events=${container.entityRepository.eventCount()} " +
                "things=${container.entityRepository.thingCount()} " +
                "channels=${snapshot.channelCount}"
        )

        assertEquals(4, messages.size)
        assertEquals(4, importedEntities)
        assertEquals(3, importedSubscriptions)
        assertEquals(3, container.messageRepository.totalCount())
        assertEquals(1, container.entityRepository.eventCount())
        assertEquals(1, container.entityRepository.thingCount())
        assertEquals(3, snapshot.channelCount)
        assertTrue(container.settingsRepository.getNotificationKeyBytes()?.isNotEmpty() == true)

        println("ACCESSIBILITY_FIXTURE_READY path=${fixtureFile.absolutePath}")
    }

    private fun writeFixtureFile(container: AppContainer, imageAssets: FixtureImageAssets): File {
        val fixtureDir = File(container.appContext.filesDir, "accessibility-fixtures").apply { mkdirs() }
        val fixtureFile = File(fixtureDir, "accessibility-acceptance-v1.json")
        val gatewayBaseUrl = resolveGatewayBaseUrl()

        val messages = JSONArray().apply {
            buildSeedMessages(imageAssets).forEach { put(messageToFixtureJson(it)) }
        }
        val entityRecords = JSONArray().apply {
            buildSeedEntities().forEach { put(entityToFixtureJson(it)) }
        }
        val channelSubscriptions = JSONArray().apply {
            buildSeedSubscriptions(gatewayBaseUrl).forEach { put(it) }
        }

        val fixture = JSONObject()
            .put("messages", messages)
            .put("entity_records", entityRecords)
            .put("channel_subscriptions", channelSubscriptions)

        fixtureFile.writeText(fixture.toString(2))
        return fixtureFile
    }

    private fun buildSeedMessages(imageAssets: FixtureImageAssets): List<PushMessage> {
        val baseTimeMs = 1_722_000_000_000L
        return listOf(
            seedMessage(
                id = "a11y-message-row-1",
                messageId = "a11y-message-1",
                title = "Critical gateway alert",
                body = buildString {
                    append("# Critical gateway alert\n")
                    append("This message is used for TalkBack detail and image preview validation.\n")
                    append("[Open runbook](https://sandbox.pushgo.dev/runbook)")
                },
                channelId = OPS_ALERTS_CHANNEL_ID,
                receivedAtMs = baseTimeMs + 1_000,
                isRead = false,
                status = MessageStatus.NORMAL,
                severity = "critical",
                metadata = mapOf("state" to "open", "region" to "cn-sh", "owner" to "gateway"),
                imageUrls = listOf(
                    "https://sandbox.pushgo.dev/fixture-static.png",
                    "https://sandbox.pushgo.dev/fixture-animated.gif",
                ),
                url = "https://sandbox.pushgo.dev/messages/a11y-message-1",
                thingId = null,
                eventId = "event-gateway-1",
                eventState = "open",
                localImagePath = imageAssets.staticImagePath,
                localThumbnailPath = imageAssets.staticImagePath,
            ),
            seedMessage(
                id = "a11y-message-row-2",
                messageId = "a11y-message-2",
                title = "Decryption key check",
                body = "Use this message when validating message detail reading order and decryption settings.",
                channelId = SECURE_NOTIFY_CHANNEL_ID,
                receivedAtMs = baseTimeMs + 2_000,
                isRead = true,
                status = MessageStatus.DECRYPTED,
                severity = "medium",
                metadata = mapOf("state" to "ready", "cipher" to "aes-256-gcm"),
                imageUrls = emptyList(),
                url = null,
                thingId = null,
                eventId = null,
                eventState = null,
            ),
            seedMessage(
                id = "a11y-message-row-thing-1",
                messageId = "a11y-message-thing-1",
                title = "Rack maintenance note",
                body = "Thing-linked message used to validate related message access from the thing detail sheet.",
                channelId = OPS_ASSETS_CHANNEL_ID,
                receivedAtMs = baseTimeMs + 2_500,
                isRead = true,
                status = MessageStatus.NORMAL,
                severity = "low",
                metadata = mapOf("state" to "noted", "serial" to "rack-42"),
                imageUrls = emptyList(),
                url = "https://sandbox.pushgo.dev/messages/a11y-message-thing-1",
                thingId = "thing-rack-1",
                eventId = "event-rack-fan-1",
                eventState = "open",
                localImagePath = imageAssets.staticImagePath,
                localThumbnailPath = imageAssets.staticImagePath,
            ),
            seedMessage(
                id = "a11y-message-row-3",
                messageId = "a11y-message-3",
                title = "Unread thing update",
                body = "Thing history row for projection validation.",
                channelId = OPS_ASSETS_CHANNEL_ID,
                receivedAtMs = baseTimeMs + 3_000,
                isRead = false,
                status = MessageStatus.NORMAL,
                severity = "high",
                metadata = mapOf("state" to "investigating", "serial" to "rack-42"),
                imageUrls = emptyList(),
                url = "https://sandbox.pushgo.dev/messages/a11y-message-3",
                thingId = null,
                eventId = null,
                eventState = null,
            ),
        )
    }

    private fun buildSeedEntities(): List<IncomingEntityRecord> {
        val baseTimeMs = 1_722_000_000_000L
        return listOf(
            seedEntity(
                entityType = "event",
                entityId = "event-gateway-1",
                title = "Gateway latency breach",
                body = "Primary event record used for event list and detail validation.",
                channelId = OPS_ALERTS_CHANNEL_ID,
                receivedAtMs = baseTimeMs + 4_000,
                eventId = "event-gateway-1",
                thingId = null,
                eventState = "open",
                severity = "high",
                status = "active",
                message = "p95 latency crossed 500ms",
                metadata = mapOf("region" to "cn-sh", "owner" to "gateway", "runbook" to "gateway-latency"),
                imageUrls = emptyList(),
            ),
            seedEntity(
                entityType = "event",
                entityId = "event-gateway-1",
                title = "Gateway latency breach acknowledged",
                body = "Follow-up event change log used to verify event detail history.",
                channelId = OPS_ALERTS_CHANNEL_ID,
                receivedAtMs = baseTimeMs + 5_000,
                eventId = "event-gateway-1",
                thingId = null,
                eventState = "acknowledged",
                severity = "medium",
                status = "active",
                message = "operator acknowledged and collecting traces",
                metadata = mapOf("region" to "cn-sh", "owner" to "gateway", "runbook" to "gateway-latency"),
                imageUrls = emptyList(),
            ),
            seedEntity(
                entityType = "thing",
                entityId = "thing-rack-1",
                title = "Gateway rack 42",
                body = "Thing head used for thing list, metadata, and image preview validation.",
                channelId = OPS_ASSETS_CHANNEL_ID,
                receivedAtMs = baseTimeMs + 6_000,
                eventId = null,
                thingId = "thing-rack-1",
                eventState = null,
                severity = null,
                status = "healthy",
                message = "all fans nominal",
                metadata = mapOf("serial" to "rack-42", "owner" to "infra", "state" to "healthy"),
                imageUrls = emptyList(),
            ),
            seedEntity(
                entityType = "event",
                entityId = "event-rack-fan-1",
                title = "Rack fan warning",
                body = "Sub-event linked to the seeded thing.",
                channelId = OPS_ASSETS_CHANNEL_ID,
                receivedAtMs = baseTimeMs + 7_000,
                eventId = "event-rack-fan-1",
                thingId = "thing-rack-1",
                eventState = "open",
                severity = "medium",
                status = "warning",
                message = "fan speed below threshold",
                metadata = mapOf("serial" to "rack-42", "component" to "fan-3"),
                imageUrls = emptyList(),
            ),
        )
    }

    private fun writeFixtureImages(context: Context): FixtureImageAssets {
        val imageDir = File(context.cacheDir, "accessibility-fixture-images").apply { mkdirs() }
        val staticImage = File(imageDir, "fixture-static.png")
        Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    val red = 40 + (x * 5).coerceAtMost(160)
                    val green = 96 + (y * 4).coerceAtMost(120)
                    val blue = 180
                    setPixel(x, y, Color.rgb(red, green, blue))
                }
            }
            staticImage.outputStream().use { output ->
                compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            recycle()
        }
        val animatedImage = File(imageDir, "fixture-animated.gif").apply {
            writeBytes(Base64.decode(ANIMATED_GIF_BASE64, Base64.DEFAULT))
        }
        val animatedThumbnail = File(imageDir, "fixture-animated-thumb.gif").apply {
            writeBytes(Base64.decode(ANIMATED_GIF_BASE64, Base64.DEFAULT))
        }
        val expiresAtEpochMillis = System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1000L
        writeCacheMetadata(staticImage, expiresAtEpochMillis)
        writeCacheMetadata(animatedImage, expiresAtEpochMillis)
        writeCacheMetadata(animatedThumbnail, expiresAtEpochMillis)
        return FixtureImageAssets(
            staticImagePath = staticImage.absolutePath,
            animatedImagePath = animatedImage.absolutePath,
            animatedThumbnailPath = animatedThumbnail.absolutePath,
        )
    }

    private fun writeCacheMetadata(file: File, expiresAtEpochMillis: Long) {
        File(file.parentFile, "${file.name}.meta.json").writeText(
            JSONObject()
                .put("expiresAtEpochMillis", expiresAtEpochMillis)
                .toString(),
        )
    }

    private fun buildSeedSubscriptions(gatewayBaseUrl: String): List<JSONObject> {
        val baseTimeMs = 1_722_000_000_000L
        return listOf(
            JSONObject()
                .put("channel_id", OPS_ALERTS_CHANNEL_ID)
                .put("display_name", "Ops Alerts")
                .put("password", "ops-alerts-pass")
                .put("gateway_base_url", gatewayBaseUrl)
                .put("last_synced_at", baseTimeMs + 8_000),
            JSONObject()
                .put("channel_id", OPS_ASSETS_CHANNEL_ID)
                .put("display_name", "Ops Assets")
                .put("password", "ops-assets-pass")
                .put("gateway_base_url", gatewayBaseUrl)
                .put("last_synced_at", baseTimeMs + 8_100),
            JSONObject()
                .put("channel_id", SECURE_NOTIFY_CHANNEL_ID)
                .put("display_name", "Secure Notify")
                .put("password", "secure-notify-pass")
                .put("gateway_base_url", gatewayBaseUrl)
                .put("last_synced_at", baseTimeMs + 8_200),
        )
    }

    private fun resolveGatewayBaseUrl(): String {
        val args = InstrumentationRegistry.getArguments()
        return args.getString("pushgo.accessibility.gatewayBaseUrl")
            ?.trim()
            ?.ifEmpty { null }
            ?: "https://sandbox.pushgo.dev"
    }

    private fun seedMessage(
        id: String,
        messageId: String,
        title: String,
        body: String,
        channelId: String,
        receivedAtMs: Long,
        isRead: Boolean,
        status: MessageStatus,
        severity: String,
        metadata: Map<String, String>,
        imageUrls: List<String>,
        url: String?,
        thingId: String?,
        eventId: String?,
        eventState: String?,
        localImagePath: String? = null,
        localThumbnailPath: String? = null,
    ): PushMessage {
        val payload = JSONObject()
            .put("entity_type", "message")
            .put("entity_id", messageId)
            .put("message_id", messageId)
            .put("delivery_id", "delivery-$messageId")
            .put("op_id", "op-$messageId")
            .put("channel_id", channelId)
            .put("title", title)
            .put("body", body)
            .put("severity", severity)
            .put("occurred_at", Instant.ofEpochMilli(receivedAtMs).toString())
            .put("tags", JSONArray(listOf("accessibility", "fixture")).toString())
            .put("metadata", JSONObject(metadata as Map<*, *>).toString())
            .put("images", JSONArray(imageUrls).toString())
        url?.let { payload.put("url", it) }
        thingId?.let { payload.put("thing_id", it) }
        eventId?.let { payload.put("event_id", it) }
        eventState?.let { payload.put("event_state", it) }
        localImagePath?.let { payload.put(MessageImageStore.KEY_IMAGE_LOCAL_PATH, it) }
        localThumbnailPath?.let { payload.put(MessageImageStore.KEY_IMAGE_THUMBNAIL_LOCAL_PATH, it) }

        return PushMessage(
            id = id,
            messageId = messageId,
            title = title,
            body = body,
            channel = channelId,
            url = url,
            isRead = isRead,
            receivedAt = Instant.ofEpochMilli(receivedAtMs),
            rawPayloadJson = payload.toString(),
            status = status,
            decryptionState = null,
            notificationId = "notification-$messageId",
            serverId = "accessibility-fixture",
            bodyPreview = null,
        )
    }

    private fun seedEntity(
        entityType: String,
        entityId: String,
        title: String,
        body: String,
        channelId: String,
        receivedAtMs: Long,
        eventId: String?,
        thingId: String?,
        eventState: String?,
        severity: String?,
        status: String?,
        message: String?,
        metadata: Map<String, String>,
        imageUrls: List<String>,
    ): IncomingEntityRecord {
        val payload = JSONObject()
            .put("entity_type", entityType)
            .put("entity_id", entityId)
            .put("delivery_id", "delivery-$entityId-$receivedAtMs")
            .put("op_id", "op-$entityId-$receivedAtMs")
            .put("channel_id", channelId)
            .put("title", title)
            .put("description", body)
            .put("created_at", receivedAtMs)
            .put("metadata", JSONObject(metadata as Map<*, *>))
            .put("images", JSONArray(imageUrls))
        eventId?.let { payload.put("event_id", it) }
        thingId?.let { payload.put("thing_id", it) }
        eventState?.let {
            payload.put("event_state", it)
            payload.put("state", it)
        }
        severity?.let { payload.put("severity", it) }
        status?.let { payload.put("status", it) }
        message?.let { payload.put("message", it) }

        return IncomingEntityRecord(
            entityType = entityType,
            entityId = entityId,
            channel = channelId,
            title = title,
            body = body,
            rawPayloadJson = payload.toString(),
            receivedAt = Instant.ofEpochMilli(receivedAtMs),
            opId = "op-$entityId-$receivedAtMs",
            deliveryId = "delivery-$entityId-$receivedAtMs",
            serverId = "accessibility-fixture",
            eventId = eventId,
            thingId = thingId,
            eventState = eventState,
            eventTimeEpoch = receivedAtMs,
            observedTimeEpoch = if (entityType == "thing") receivedAtMs else null,
        )
    }

    private fun messageToFixtureJson(message: PushMessage): JSONObject {
        return JSONObject()
            .put("id", message.id)
            .put("message_id", message.messageId)
            .put("title", message.title)
            .put("body", message.body)
            .put("channel_id", message.channel)
            .put("received_at", message.receivedAt.toString())
            .put("is_read", message.isRead)
            .put("status", message.status.name)
            .put("notification_id", message.notificationId)
            .put("server_id", message.serverId)
            .put("url", message.url)
            .put("raw_payload_json", message.rawPayloadJson)
    }

    private fun entityToFixtureJson(entity: IncomingEntityRecord): JSONObject {
        return JSONObject()
            .put("entity_type", entity.entityType)
            .put("entity_id", entity.entityId)
            .put("title", entity.title)
            .put("body", entity.body)
            .put("channel_id", entity.channel)
            .put("received_at", entity.receivedAt.toString())
            .put("delivery_id", entity.deliveryId)
            .put("server_id", entity.serverId)
            .put("event_id", entity.eventId)
            .put("thing_id", entity.thingId)
            .put("event_state", entity.eventState)
            .put("event_time_epoch", entity.eventTimeEpoch)
            .put("observed_time_epoch", entity.observedTimeEpoch)
            .put("raw_payload_json", entity.rawPayloadJson)
    }

    private data class FixtureImageAssets(
        val staticImagePath: String,
        val animatedImagePath: String,
        val animatedThumbnailPath: String,
    )
}
