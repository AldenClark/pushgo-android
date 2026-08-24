package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.INBOUND_DELIVERY_ACK_STATE_ACKED
import io.ethan.pushgo.data.INBOUND_DELIVERY_ACK_STATE_PENDING
import io.ethan.pushgo.data.InboundDeliveryScope
import io.ethan.pushgo.data.ProviderAckContract
import io.ethan.pushgo.data.ProviderAckDestination
import io.ethan.pushgo.data.ProviderAckIdentity
import io.ethan.pushgo.data.inboundDeliveryScope
import io.ethan.pushgo.data.scopedDeliveryStorageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateChannelDeliveryReliabilityTest {
    @Test
    fun outerStreamDeliveryIdAlwaysOverridesConflictingPayloadId() {
        val payload = mapOf(
            "delivery_id" to "inner-conflict",
            "title" to "kept",
        )

        val authoritative = authoritativePrivatePayload(payload, "outer-authoritative")

        assertEquals("outer-authoritative", authoritative["delivery_id"])
        assertEquals("kept", authoritative["title"])
        assertEquals("inner-conflict", payload["delivery_id"])
    }

    @Test
    fun sameDeliveryIdOnDifferentGatewaysHasDifferentDurableScope() {
        val gatewayA = InboundDeliveryScope.create("https://gateway-a.example/", "device-1")
        val gatewayB = InboundDeliveryScope.create("https://gateway-b.example", "device-1")

        assertNotEquals(gatewayA, gatewayB)
        assertNotEquals(
            gatewayA.scopedDeliveryStorageKey("shared-delivery"),
            gatewayB.scopedDeliveryStorageKey("shared-delivery"),
        )
        assertEquals(
            InboundDeliveryScope.create("https://gateway-a.example", "device-1"),
            gatewayA,
        )
        assertNull(InboundDeliveryScope.create("https://gateway-a.example", ""))
    }

    @Test
    fun providerScopeKeepsItsExistingStableStorageKey() {
        val identity = ProviderAckIdentity.create(
            destination = ProviderAckDestination(
                baseUrl = "https://gateway-a.example/",
                deviceKey = "device-1",
            ),
            contract = ProviderAckContract.V2_BATCH,
            source = "test",
        )

        assertEquals(
            identity.scopedDeliveryStorageKey("delivery-1"),
            identity.inboundDeliveryScope().scopedDeliveryStorageKey("delivery-1"),
        )
    }

    @Test
    fun ackedReplayContinuesToResolveAckOk() {
        assertEquals(
            PRIVATE_STREAM_ACK_STATUS_OK,
            PrivateStreamAckPolicy.statusForDelivery(
                handledResult = Result.success(false),
                ackStateBefore = INBOUND_DELIVERY_ACK_STATE_ACKED,
                ackStateAfter = INBOUND_DELIVERY_ACK_STATE_ACKED,
            ),
        )
        assertEquals(
            PRIVATE_STREAM_ACK_STATUS_OK,
            PrivateStreamAckPolicy.statusForDelivery(
                handledResult = Result.success(false),
                ackStateBefore = INBOUND_DELIVERY_ACK_STATE_PENDING,
                ackStateAfter = INBOUND_DELIVERY_ACK_STATE_PENDING,
            ),
        )
        assertEquals(
            PRIVATE_STREAM_ACK_STATUS_IGNORE,
            PrivateStreamAckPolicy.statusForDelivery(
                handledResult = Result.success(false),
                ackStateBefore = null,
                ackStateAfter = INBOUND_DELIVERY_ACK_STATE_PENDING,
            ),
        )
    }

    @Test
    fun firstPrivatePersistenceResolvesAckOkInTheSameDeliveryAttempt() {
        assertEquals(
            PRIVATE_STREAM_ACK_STATUS_OK,
            PrivateStreamAckPolicy.statusForDelivery(
                handledResult = Result.success(true),
                ackStateBefore = null,
                ackStateAfter = INBOUND_DELIVERY_ACK_STATE_PENDING,
            ),
        )
    }

    @Test
    fun privateMessageUsesDurableImagePostProcessHandoffIdentity() {
        val handoff = messagePostProcessHandoff(
            messageId = "message-1",
            imageUrl = "https://cdn.example/image.png",
        )

        assertEquals("message-post-process:message-1", handoff.uniqueWorkName)
        assertEquals("message-1", handoff.messageId)
        assertEquals("https://cdn.example/image.png", handoff.imageUrl)
    }
}
