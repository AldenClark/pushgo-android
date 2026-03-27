package io.ethan.pushgo.notifications

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
    fun parseThing_keepsProfileJsonUntouchedByIngressFilter() {
        val payload = mapOf(
            "entity_type" to "thing",
            "thing_id" to "thing-1",
            "entity_id" to "thing-1",
            "title" to "Object",
            "body" to "updated",
            "thing_profile_json" to """
                {"title":"Object","description":"[bad](javascript:alert(1))","message":"[ok](https://safe.example/x)","primary_image":"http://127.0.0.1/a.png","images":["https://cdn.example.com/a.png","http://localhost/b.png"]}
            """.trimIndent(),
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
        val profileRaw = raw["thing_profile_json"]?.toString()
        assertNotNull(profileRaw)
        val profile = JsonCompat.parseObject(profileRaw) ?: emptyMap()

        assertEquals("[bad](javascript:alert(1))", profile["description"])
        assertEquals("[ok](https://safe.example/x)", profile["message"])
        assertEquals("http://127.0.0.1/a.png", profile["primary_image"])
        val images = (profile["images"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        assertEquals(
            listOf("https://cdn.example.com/a.png", "http://localhost/b.png"),
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
}
