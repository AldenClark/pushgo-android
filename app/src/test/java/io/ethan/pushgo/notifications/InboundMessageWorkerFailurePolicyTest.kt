package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.ChannelSubscriptionException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundMessageWorkerFailurePolicyTest {
    @Test
    fun nonRetryableGatewayFailureIsTerminal() {
        assertFalse(
            shouldRetryInboundFailure(
                ChannelSubscriptionException(
                    message = "authentication failed",
                    retryable = false,
                )
            )
        )
    }

    @Test
    fun retryableGatewayFailureRemainsLive() {
        assertTrue(
            shouldRetryInboundFailure(
                ChannelSubscriptionException(
                    message = "gateway busy",
                    retryable = true,
                )
            )
        )
    }

    @Test
    fun localPersistenceFailureRemainsLive() {
        assertTrue(
            shouldRetryInboundFailure(
                InboundMessageProcessor.InboundRetryableException(
                    "canonical persistence incomplete"
                )
            )
        )
    }

    @Test
    fun wrappedNonRetryableGatewayFailureIsTerminal() {
        assertFalse(
            shouldRetryInboundFailure(
                IllegalStateException(
                    "provider pull failed",
                    ChannelSubscriptionException(
                        message = "device key missing",
                        retryable = false,
                    ),
                )
            )
        )
    }
}
