package io.ethan.pushgo.testing

import io.ethan.pushgo.notifications.InboundPersistenceRequest
import io.ethan.pushgo.notifications.NotificationIngressParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RuntimeFixtureGeneratorTest {
    @Test
    fun generator_isStableForFixedSeed() {
        val first = RuntimeFixtureGenerator(seed = 42L).generateDataset(10)
        val second = RuntimeFixtureGenerator(seed = 42L).generateDataset(10)

        assertEquals(
            first.records.map { it.kind to it.payload },
            second.records.map { it.kind to it.payload },
        )
    }

    @Test
    fun generator_outputsSupportedSizesWithExpectedCounts() {
        val generator = RuntimeFixtureGenerator(seed = 7L)

        RuntimeFixtureSizes.supported.forEach { size ->
            val counts = generator.generateRecords(size)
                .groupingBy { it.kind }
                .eachCount()
            assertEquals("size=$size", size, counts.values.sum())
            if (size >= 10) {
                assertTrue("size=$size messages", counts.getValue(RuntimeFixtureKind.MESSAGE) > 0)
                assertTrue("size=$size events", counts.getValue(RuntimeFixtureKind.EVENT) > 0)
                assertTrue("size=$size things", counts.getValue(RuntimeFixtureKind.THING) > 0)
                assertTrue("size=$size tasks", counts.getValue(RuntimeFixtureKind.TASK_MESSAGE) > 0)
            }
        }
    }

    @Test
    fun boundaryPayloads_includeInvalidAndExtremeCases() {
        val payloads = RuntimeFixtureGenerator(seed = 9L).boundaryPayloads()

        assertTrue(payloads.any { it.payload["title"].orEmpty().isEmpty() })
        assertTrue(payloads.any { it.payload["body"].orEmpty().contains("# Runtime Markdown") })
        assertTrue(payloads.any { it.payload["body"].orEmpty().contains("مرحبا") })
        assertTrue(payloads.any { it.kind == RuntimeFixtureKind.INVALID && it.payload["message_id"] == null })
        assertTrue(payloads.any { it.payload.containsKey("unknown_future_field") })
    }

    @Test
    fun fcmAndPrivatePayloads_parseToSameCanonicalMessageSemantics() {
        val record = RuntimeFixtureGenerator(seed = 11L)
            .generateRecords(10)
            .first { it.kind == RuntimeFixtureKind.MESSAGE }
        val fcm = parseMessage(record.fcmPayload())
        val private = parseMessage(record.privatePayload())

        assertEquals(record.canonicalId, fcm.messageId)
        assertEquals(fcm.messageId, private.messageId)
        assertEquals(fcm.title, private.title)
        assertEquals(fcm.body, private.body)
        assertEquals(fcm.channel, private.channel)
        assertEquals(fcm.deliveryId, private.deliveryId)
        assertEquals(fcm.receivedAt, private.receivedAt)
    }

    @Test
    fun entityPayloads_parseAsEventAndThingRequests() {
        val generator = RuntimeFixtureGenerator(seed = 12L)
        val records = generator.generateRecords(10).toList()
        val event = records.first { it.kind == RuntimeFixtureKind.EVENT }
        val thing = records.first { it.kind == RuntimeFixtureKind.THING }

        val parsedEvent = NotificationIngressParser.parse(
            data = event.fcmPayload().data,
            transportMessageId = event.fcmPayload().transportMessageId,
            keyBytes = null,
            now = FIXED_NOW,
        )
        val parsedThing = NotificationIngressParser.parse(
            data = thing.privatePayload().data,
            transportMessageId = thing.privatePayload().transportMessageId,
            keyBytes = null,
            now = FIXED_NOW,
        )

        assertTrue(parsedEvent is InboundPersistenceRequest.Entity)
        assertTrue(parsedThing is InboundPersistenceRequest.Entity)
        parsedEvent as InboundPersistenceRequest.Entity
        parsedThing as InboundPersistenceRequest.Entity
        assertEquals("event", parsedEvent.record.entityType)
        assertEquals(event.canonicalId, parsedEvent.record.eventId)
        assertEquals("thing", parsedThing.record.entityType)
        assertEquals(thing.canonicalId, parsedThing.record.thingId)
    }

    @Test
    fun duplicateIds_areCanonicalizedByFirstArrivalWithoutOverwrite() {
        val generator = RuntimeFixtureGenerator(seed = 13L)
        val records = generator.duplicateAndOutOfOrderPayloads()
        val newerFirst = records.filter { it.canonicalId == "duplicate-message-1" }

        assertEquals(2, newerFirst.size)
        val canonical = generator.canonicalizeFirstArrival(newerFirst)

        assertEquals(1, canonical.size)
        assertEquals("newer duplicate", canonical.single().payload["title"])
        assertEquals(RuntimeFixtureGenerator.BASE_TIME_MS + 10_000, canonical.single().sentAtEpochMillis)
    }

    @Test
    fun outOfOrderData_sortsNewestFirstWithStableTieBreak() {
        val generator = RuntimeFixtureGenerator(seed = 14L)
        val sorted = generator.sortNewestFirst(generator.duplicateAndOutOfOrderPayloads())

        assertEquals("future-message", sorted.first().canonicalId)
        assertEquals("ancient-message", sorted.last().canonicalId)
        assertTrue(sorted.zipWithNext().all { (left, right) ->
            left.sentAtEpochMillis > right.sentAtEpochMillis ||
                (left.sentAtEpochMillis == right.sentAtEpochMillis && left.canonicalId >= right.canonicalId)
        })
    }

    @Test
    fun channelSwitchScenario_tracksExpectedActiveChannelAndFailureRollback() {
        val scenario = RuntimeFixtureGenerator(seed = 15L).generateChannelSwitchScenario()

        assertEquals(RuntimeChannelEventType.INITIAL_DEFAULT_FCM, scenario.events.first().type)
        assertEquals(RuntimeActiveChannel.FCM, scenario.events.first().activeAfter)
        assertTrue(scenario.events.any {
            it.type == RuntimeChannelEventType.SWITCH_SUCCEEDED &&
                it.activeBefore == RuntimeActiveChannel.FCM &&
                it.activeAfter == RuntimeActiveChannel.PRIVATE
        })
        assertTrue(scenario.events.any {
            it.type == RuntimeChannelEventType.SWITCH_SUCCEEDED &&
                it.activeBefore == RuntimeActiveChannel.PRIVATE &&
                it.activeAfter == RuntimeActiveChannel.FCM
        })
        val failed = scenario.events.last { it.type == RuntimeChannelEventType.SWITCH_FAILED }
        assertEquals(RuntimeActiveChannel.FCM, failed.activeBefore)
        assertEquals(RuntimeActiveChannel.FCM, failed.activeAfter)
        assertEquals(RuntimeActiveChannel.FCM, scenario.finalActiveChannel)
    }

    @Test
    fun simultaneousFcmAndPrivateDelivery_keepsOneCanonicalAcceptedMessage() {
        val scenario = RuntimeFixtureGenerator(seed = 16L).generateChannelSwitchScenario()
        val dual = scenario.events.filter { it.messageId == "dual-delivery-1" }

        assertEquals(2, dual.size)
        assertEquals(1, dual.count { it.accepted == true })
        assertEquals(setOf("dual-delivery-1"), dual.filter { it.accepted == true }.mapNotNull { it.messageId }.toSet())
        assertTrue(scenario.acceptedCanonicalMessageIds().contains("dual-delivery-1"))
    }

    @Test
    fun channelScenario_expressesAckResumeSessionAndTokenCases() {
        val scenario = RuntimeFixtureGenerator(seed = 17L).generateChannelSwitchScenario()

        assertTrue(scenario.events.any { it.type == RuntimeChannelEventType.FCM_TOKEN_MISSING })
        assertTrue(scenario.events.any { it.type == RuntimeChannelEventType.FCM_TOKEN_REFRESHED && !it.token.isNullOrBlank() })
        assertTrue(scenario.events.any { it.type == RuntimeChannelEventType.FCM_TOKEN_INVALIDATED })
        assertTrue(scenario.events.any { it.type == RuntimeChannelEventType.PRIVATE_DISCONNECTED && !it.sessionId.isNullOrBlank() })
        assertTrue(scenario.events.any {
            it.type == RuntimeChannelEventType.PRIVATE_SESSION_RESUMED &&
                !it.sessionId.isNullOrBlank() &&
                !it.resumeToken.isNullOrBlank()
        })
        assertTrue(scenario.events.any {
            it.type == RuntimeChannelEventType.PRIVATE_SESSION_RESUME_FAILED &&
                !it.sessionId.isNullOrBlank()
        })
        assertTrue(scenario.events.any { it.type == RuntimeChannelEventType.ACK_FAILED && !it.ackId.isNullOrBlank() })
        assertTrue(scenario.events.any { it.type == RuntimeChannelEventType.ACK_RETRIED && !it.ackId.isNullOrBlank() })
        assertTrue(scenario.events.any { it.type == RuntimeChannelEventType.ACK_SUCCEEDED && !it.ackId.isNullOrBlank() })
    }

    @Test
    fun invalidPayloadsDoNotParseIntoInboundRequests() {
        val invalids = RuntimeFixtureGenerator(seed = 18L)
            .boundaryPayloads()
            .filter { it.kind == RuntimeFixtureKind.INVALID }

        assertFalse(invalids.isEmpty())
        invalids.forEach { record ->
            val parsed = NotificationIngressParser.parse(
                data = record.payload,
                transportMessageId = null,
                keyBytes = null,
                now = FIXED_NOW,
            )
            assertEquals("invalid=${record.canonicalId}", null, parsed)
        }
    }

    private fun parseMessage(payload: RuntimeInboundPayload): io.ethan.pushgo.data.model.PushMessage {
        val parsed = NotificationIngressParser.parse(
            data = payload.data,
            transportMessageId = payload.transportMessageId,
            keyBytes = null,
            now = FIXED_NOW,
        )
        val message = (parsed as? InboundPersistenceRequest.Message)?.message
        assertNotNull(message)
        return message!!
    }

    companion object {
        private val FIXED_NOW: Instant = Instant.parse("2026-01-02T00:00:00Z")
    }
}
