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
    fun parseMessage_sanitizesBodyUrlAndImagesBeforePersistence() {
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
    fun parseThing_sanitizesProfileJsonNestedUrlFields() {
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

        assertEquals("[bad](#)", profile["description"])
        assertEquals("[ok](https://safe.example/x)", profile["message"])
        assertFalse(profile.containsKey("primary_image"))
        val images = (profile["images"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        assertEquals(listOf("https://cdn.example.com/a.png"), images)
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
}
