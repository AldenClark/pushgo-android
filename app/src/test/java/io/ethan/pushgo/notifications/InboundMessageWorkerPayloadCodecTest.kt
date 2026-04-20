package io.ethan.pushgo.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboundMessageWorkerPayloadCodecTest {

    @Test
    fun payloadCodec_encodeDecode_roundTripsMessageData() {
        val payload = mapOf(
            "message_id" to "msg-1",
            "channel_id" to "ch-1",
            "title" to "hello",
            "body" to "world",
        )

        val encoded = InboundMessagePayloadCodec.encode(payload)
        val decoded = InboundMessagePayloadCodec.decode(encoded)

        assertEquals(payload, decoded)
    }

    @Test
    fun payloadCodec_decode_returnsNullForInvalidJson() {
        assertNull(InboundMessagePayloadCodec.decode("{oops"))
    }

    @Test
    fun buildUniqueWorkName_prefersTransportMessageIdWhenAvailable() {
        val payload = InboundMessagePayloadCodec.encode(
            mapOf(
                "channel_id" to "ch-1",
                "message_id" to "msg-100",
            )
        )

        val workName = InboundMessageWorker.buildUniqueWorkName(
            transportMessageId = "transport-1",
            payload = payload,
        )

        assertEquals("inbound:transport-1:ch-1:msg-100", workName)
    }

    @Test
    fun buildUniqueWorkName_fallsBackToMessageIdWhenTransportIdMissing() {
        val payload = InboundMessagePayloadCodec.encode(
            mapOf(
                "channel_id" to "ch-2",
                "message_id" to "msg-200",
            )
        )

        val workName = InboundMessageWorker.buildUniqueWorkName(
            transportMessageId = null,
            payload = payload,
        )

        assertEquals("inbound:ch-2:msg-200", workName)
    }

    @Test
    fun buildUniqueWorkName_usesDefaultWhenIdsUnavailable() {
        val payload = InboundMessagePayloadCodec.encode(
            mapOf("title" to "no ids")
        )

        val workName = InboundMessageWorker.buildUniqueWorkName(
            transportMessageId = " ",
            payload = payload,
        )

        assertEquals("inbound:no-channel-id:no-message-id", workName)
    }
}
