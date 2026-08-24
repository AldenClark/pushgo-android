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
    fun buildUniqueWorkName_usesDeliveryIdWhenMessageIdsUnavailable() {
        val payload = InboundMessagePayloadCodec.encode(
            mapOf(
                "delivery_id" to "delivery-unique",
                "title" to "no message ids",
            )
        )

        val workName = InboundMessageWorker.buildUniqueWorkName(
            transportMessageId = " ",
            payload = payload,
        )

        assertEquals(
            "inbound:delivery:${InboundMessageWorker.stableWorkKey("delivery-unique")}",
            workName,
        )
    }

    @Test
    fun buildUniqueWorkName_distinguishesConcurrentProviderWakeupsByDeliveryId() {
        val first = InboundMessageWorker.buildUniqueWorkName(
            transportMessageId = null,
            payload = InboundMessagePayloadCodec.encode(
                mapOf("provider_wakeup" to "1", "delivery_id" to "delivery-1")
            ),
        )
        val second = InboundMessageWorker.buildUniqueWorkName(
            transportMessageId = null,
            payload = InboundMessagePayloadCodec.encode(
                mapOf("provider_wakeup" to "1", "delivery_id" to "delivery-2")
            ),
        )

        org.junit.Assert.assertNotEquals(first, second)
    }

    @Test
    fun buildUniqueWorkName_scopesSameDeliveryIdByGatewayAndDevice() {
        fun workName(baseUrl: String, providerDevice: String): String {
            return InboundMessageWorker.buildUniqueWorkName(
                transportMessageId = null,
                payload = InboundMessagePayloadCodec.encode(
                    mapOf(
                        "delivery_id" to "shared-delivery",
                        "base_url" to baseUrl,
                        "provider_device_key" to providerDevice,
                    )
                ),
            )
        }

        org.junit.Assert.assertNotEquals(
            workName("https://gateway-a.example", "device-a"),
            workName("https://gateway-b.example", "device-b"),
        )
    }

    @Test
    fun buildUniqueWorkName_payloadDigestIsOrderIndependentAndContentSensitive() {
        val first = InboundMessageWorker.buildUniqueWorkName(
            null,
            InboundMessagePayloadCodec.encode(linkedMapOf("title" to "same", "body" to "one")),
        )
        val reordered = InboundMessageWorker.buildUniqueWorkName(
            null,
            InboundMessagePayloadCodec.encode(linkedMapOf("body" to "one", "title" to "same")),
        )
        val distinct = InboundMessageWorker.buildUniqueWorkName(
            null,
            InboundMessagePayloadCodec.encode(linkedMapOf("body" to "two", "title" to "same")),
        )

        assertEquals(first, reordered)
        org.junit.Assert.assertNotEquals(first, distinct)
    }
}
