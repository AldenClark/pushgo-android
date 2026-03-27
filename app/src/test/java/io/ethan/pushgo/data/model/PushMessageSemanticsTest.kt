package io.ethan.pushgo.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PushMessageSemanticsTest {

    private fun messageWithPayload(rawPayloadJson: String): PushMessage {
        return PushMessage(
            id = "local-1",
            messageId = "msg-1",
            title = "title",
            body = "body",
            channel = "ch-1",
            url = null,
            isRead = false,
            receivedAt = Instant.ofEpochSecond(1_710_000_000),
            rawPayloadJson = rawPayloadJson,
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = null,
        )
    }

    @Test
    fun messageSeverity_mapsCanonicalAndAliasValues() {
        assertEquals(MessageSeverity.LOW, MessageSeverity.fromRaw("low"))
        assertEquals(MessageSeverity.MEDIUM, MessageSeverity.fromRaw("normal"))
        assertEquals(MessageSeverity.HIGH, MessageSeverity.fromRaw("HIGH"))
        assertEquals(MessageSeverity.CRITICAL, MessageSeverity.fromRaw("critical"))
        assertNull(MessageSeverity.fromRaw("unknown"))
    }

    @Test
    fun pushMessage_parsesOccurredAtMetadataTagsAndImages() {
        val payload = """
            {
              "entity_type":"event",
              "event_id":"evt-1",
              "occurred_at":"1710000000",
              "severity":"high",
              "metadata":"{\"region\":\"cn\",\"retries\":3,\"enabled\":true,\"nested\":{\"ignored\":1}}",
              "tags":"[\"ops\",\"ops\",\"urgent\"]",
              "images":"[\"https://cdn.example.com/a.png\",\"http://localhost/blocked.png\"]"
            }
        """.trimIndent()

        val message = messageWithPayload(payload)

        assertEquals("event", message.entityType)
        assertEquals("evt-1", message.eventId)
        assertEquals(1_710_000_000_000L, message.occurredAtEpoch)
        assertEquals(MessageSeverity.HIGH, message.severity)
        assertEquals(mapOf("region" to "cn", "retries" to "3", "enabled" to "true"), message.metadata)
        assertEquals(listOf("ops", "urgent"), message.tags)
        assertEquals(listOf("https://cdn.example.com/a.png"), message.imageUrls)
    }

    @Test
    fun pushMessage_invalidOccurredAtAndUnknownEntityTypeFallbackToNullOrEmpty() {
        val payload = """
            {
              "entity_type":"other",
              "occurred_at":"not-a-number"
            }
        """.trimIndent()
        val message = messageWithPayload(payload)

        assertEquals("", message.entityType)
        assertNull(message.occurredAtEpoch)
        assertTrue(message.metadata.isEmpty())
        assertTrue(message.tags.isEmpty())
    }
}
