package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.ProviderAckContract
import io.ethan.pushgo.util.JsonCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NotificationIngressParserTest {

    @Test
    fun parseMessage_sanitizesOpenUrlAndImagesBeforePersistence() {
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "m-1",
            "entity_id" to "m-1",
            "title" to "hello",
            "body" to "[x](javascript:alert(1)) and [ok](https://safe.example/p)",
            "url" to "javascript:alert(1)",
            "images" to "[\"https://cdn.example.com/a.png\",\"http://localhost/b.png\",\"data:image/png;base64,AAA\"]",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = "fcm-1",
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        message ?: return

        assertEquals("[x](#) and [ok](https://safe.example/p)", message.body)
        assertNull(message.url)
        assertEquals(listOf("https://cdn.example.com/a.png"), message.imageUrls)

        val raw = JsonCompat.parseObject(message.rawPayloadJson) ?: emptyMap()
        assertFalse(raw.containsKey("url"))
        val rawImages = raw["images"]?.toString().orEmpty()
        assertTrue(rawImages.contains("https://cdn.example.com/a.png"))
        assertFalse(rawImages.contains("localhost"))
        assertFalse(rawImages.contains("data:image"))
    }

    @Test
    fun parseThing_keepsCanonicalThingFieldsUntouchedByIngressFilter() {
        val payload = mapOf(
            "entity_type" to "thing",
            "thing_id" to "thing-1",
            "entity_id" to "thing-1",
            "title" to "Object",
            "body" to "updated",
            "description" to "[bad](javascript:alert(1))",
            "message" to "[ok](https://safe.example/x)",
            "primary_image" to "http://127.0.0.1/a.png",
            "images" to "[\"https://cdn.example.com/a.png\",\"http://localhost/b.png\"]",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val entity = parsed as? InboundPersistenceRequest.Entity
        assertNotNull(entity)
        entity ?: return
        val raw = JsonCompat.parseObject(entity.record.rawPayloadJson) ?: emptyMap()
        assertEquals("[bad](javascript:alert(1))", raw["description"])
        assertEquals("[ok](https://safe.example/x)", raw["message"])
        assertEquals("http://127.0.0.1/a.png", raw["primary_image"])
        val images = JsonCompat.parseArray(raw["images"]?.toString())?.mapNotNull { it?.toString() } ?: emptyList()
        assertEquals(
            listOf("https://cdn.example.com/a.png"),
            images,
        )
    }

    @Test
    fun parseMessage_keepsCiphertextFlowAndMarksNotConfiguredWithoutKey() {
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "m-2",
            "entity_id" to "m-2",
            "ciphertext" to "QUJDREVGR0hJSg==",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        message ?: return

        assertEquals(io.ethan.pushgo.data.model.DecryptionState.NOT_CONFIGURED, message.decryptionState)
        val raw = JsonCompat.parseObject(message.rawPayloadJson) ?: emptyMap()
        assertEquals("notConfigured", raw["decryption_state"])
        assertEquals("QUJDREVGR0hJSg==", raw["ciphertext"])
    }

    @Test
    fun parseMessage_keepsMarkdownRichBodyPersistable() {
        val richBody = "[https://sway.cloud.microsoft/lNjlqkdUA7wtAxfV](https://sway.cloud.microsoft/lNjlqkdUA7wtAxfV)\n\n无论可以玩玩。有上千个，\n\n\n\n[原文链接](https://www.v2ex.com/t/1200790)"
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "m-rich-1",
            "entity_id" to "m-rich-1",
            "title" to "sample",
            "body" to richBody,
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = "fcm-rich-1",
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        message ?: return

        assertEquals(richBody, message.body)
        val raw = JsonCompat.parseObject(message.rawPayloadJson) ?: emptyMap()
        assertEquals(richBody, raw["body"])
    }

    @Test
    fun parseMessage_resolvesLegacyLevelAliasWhenSeverityMissing() {
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "m-level-alias-1",
            "entity_id" to "m-level-alias-1",
            "title" to "hello",
            "body" to "world",
            "level" to "medium",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        message ?: return
        val raw = JsonCompat.parseObject(message.rawPayloadJson) ?: emptyMap()
        assertEquals("normal", raw["severity"])
    }

    @Test
    fun parseMessage_resolvesNumericPriorityAliasWhenSeverityMissing() {
        val payload = mapOf(
            "entity_type" to "message",
            "message_id" to "m-priority-alias-1",
            "entity_id" to "m-priority-alias-1",
            "title" to "hello",
            "body" to "world",
            "priority" to "5",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        message ?: return
        val raw = JsonCompat.parseObject(message.rawPayloadJson) ?: emptyMap()
        assertEquals("critical", raw["severity"])
    }

    @Test
    fun parseEntity_normalizesMillisecondTimestamps() {
        val payload = mapOf(
            "entity_type" to "event",
            "event_id" to "evt-1",
            "entity_id" to "evt-1",
            "title" to "alarm",
            "body" to "opened",
            "event_time" to "1710000000123",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_100),
        )
        val entity = parsed as? InboundPersistenceRequest.Entity
        assertNotNull(entity)
        entity ?: return
        assertEquals(1_710_000_000_123L, entity.record.eventTimeEpoch)
    }

    @Test
    fun parseEntity_usesDisplayFallbackWithoutBackfillingPatchPayloadText() {
        val payload = mapOf(
            "entity_type" to "event",
            "event_id" to "evt-fallback-1",
            "entity_id" to "evt-fallback-1",
            "metadata" to "{\"stage\":\"patched\"}",
        )

        val parsed = NotificationIngressParser.parse(
            data = payload,
            transportMessageId = null,
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        )
        val entity = parsed as? InboundPersistenceRequest.Entity
        assertNotNull(entity)
        entity ?: return

        assertEquals("Event evt-fallback-1", entity.record.title)
        assertEquals("Updated", entity.record.body)
        assertTrue(entity.shouldNotify)
        val raw = JsonCompat.parseObject(entity.record.rawPayloadJson) ?: emptyMap()
        assertEquals("", raw["title"])
        assertEquals("", raw["body"])
        assertEquals("{\"stage\":\"patched\"}", raw["metadata"])
    }

    @Test
    fun parseEventUpdateAndClose_notifyWithoutHighSeverity() {
        for (state in listOf("updated", "closed")) {
            val parsed = NotificationIngressParser.parse(
                data = mapOf(
                    "entity_type" to "event",
                    "event_id" to "evt-$state",
                    "entity_id" to "evt-$state",
                    "status" to state,
                    "event_state" to state,
                ),
                transportMessageId = null,
                keyBytes = null,
                now = Instant.ofEpochSecond(1_710_000_000),
            )
            val entity = parsed as? InboundPersistenceRequest.Entity
            assertNotNull(entity)
            entity ?: continue

            assertTrue(entity.shouldNotify)
        }
    }

    @Test
    fun parseThingUpdateArchiveDelete_notifyAndUseOperationBody() {
        val cases = listOf(
            Triple("/thing/update", mapOf("attrs" to "{\"temperature\":\"24\"}"), "Attribute update || temperature: 24"),
            Triple("/thing/archive", mapOf("attrs" to "{\"temperature\":\"24\"}"), "Archived"),
            Triple("/thing/delete", emptyMap<String, String>(), "Deleted"),
        )

        cases.forEachIndexed { index, (endpoint, extra, expectedBody) ->
            val parsed = NotificationIngressParser.parse(
                data = mapOf(
                    "entity_type" to "thing",
                    "thing_id" to "thing-op-$index",
                    "entity_id" to "thing-op-$index",
                    "endpoint" to endpoint,
                    "severity" to "normal",
                ) + extra,
                transportMessageId = null,
                keyBytes = null,
                now = Instant.ofEpochSecond(1_710_000_000),
            )
            val entity = parsed as? InboundPersistenceRequest.Entity
            assertNotNull(entity)
            entity ?: return@forEachIndexed

            assertTrue(entity.shouldNotify)
            assertEquals(expectedBody, entity.notificationBody)
        }
    }

    @Test
    fun providerWakeupPullDeliveryId_requiresWakeupMarkers() {
        assertEquals(
            "delivery-1",
            NotificationIngressParser.providerWakeupPullDeliveryId(
                mapOf(
                    "delivery_id" to "delivery-1",
                    "provider_wakeup" to "1",
                    "provider_mode" to "wakeup",
                )
            ),
        )
        assertNull(
            NotificationIngressParser.providerWakeupPullDeliveryId(
                mapOf("delivery_id" to "delivery-2")
            )
        )
        assertNull(
            NotificationIngressParser.providerWakeupPullDeliveryId(
                mapOf(
                    "delivery_id" to "delivery-3",
                    "provider_wakeup" to "1",
                    "provider_mode" to "direct",
                )
            )
        )
    }

    @Test
    fun parseDirectPayloadCarriesImmutableProviderAckIdentity() {
        val parsed = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "event",
                "event_id" to "event-provider-source",
                "entity_id" to "event-provider-source",
                "delivery_id" to "delivery-provider-source",
                "base_url" to "HTTPS://Gateway-A.Example/GatewayA/",
                "provider_device_key" to " device-a ",
            ),
            transportMessageId = "transport-provider-source",
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as InboundPersistenceRequest.Entity

        val identity = requireNotNull(parsed.providerAckIdentity)
        assertEquals("https://gateway-a.example/GatewayA", identity.gatewayUrl)
        assertEquals("device-a", identity.deviceKey)
        assertEquals(ProviderAckContract.LEGACY_SINGLE, identity.contract)
        assertEquals("provider_direct", identity.source)
    }

    @Test
    fun parseDirectPayloadWithoutCompleteProviderSourceDoesNotInventAckIdentity() {
        val parsed = NotificationIngressParser.parse(
            data = mapOf(
                "entity_type" to "event",
                "event_id" to "event-provider-source-missing",
                "entity_id" to "event-provider-source-missing",
                "delivery_id" to "delivery-provider-source-missing",
                "base_url" to "https://gateway-current.example",
            ),
            transportMessageId = "transport-provider-source-missing",
            keyBytes = null,
            now = Instant.ofEpochSecond(1_710_000_000),
        ) as InboundPersistenceRequest.Entity

        assertNull(parsed.providerAckIdentity)
    }
}
