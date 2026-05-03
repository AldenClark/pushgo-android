package io.ethan.pushgo.notifications

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAckDrainCoordinatorTest {
    @Test
    fun drainPendingAcks_marksOnlyGatewayConfirmedIds() = runBlocking {
        val ackedByGateway = mutableListOf<String>()
        val markedAcked = mutableListOf<String>()

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAckIds = { listOf(" delivery-1 ", "delivery-2", "delivery-1", "") },
            ackMessage = { deliveryId ->
                ackedByGateway += deliveryId
                true
            },
            markAcked = { deliveryIds ->
                markedAcked += deliveryIds
            },
        )

        assertEquals(listOf("delivery-1", "delivery-2"), result.attemptedIds)
        assertEquals(listOf("delivery-1", "delivery-2"), ackedByGateway)
        assertEquals(listOf("delivery-1", "delivery-2"), markedAcked)
        assertTrue(result.failedIds.isEmpty())
    }

    @Test
    fun drainPendingAcks_retainsFailedIdsForRetry() = runBlocking {
        val markedAcked = mutableListOf<String>()

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAckIds = { listOf("delivery-ok", "delivery-fail") },
            ackMessage = { deliveryId ->
                if (deliveryId == "delivery-fail") {
                    error("network unavailable")
                }
                true
            },
            markAcked = { deliveryIds ->
                markedAcked += deliveryIds
            },
        )

        assertEquals(listOf("delivery-ok", "delivery-fail"), result.attemptedIds)
        assertEquals(listOf("delivery-ok"), result.ackedIds)
        assertEquals(listOf("delivery-fail"), result.failedIds)
        assertEquals(listOf("delivery-ok"), markedAcked)
    }

    @Test
    fun drainPendingAcks_treatsSuccessfulFalseResponseAsTerminalAck() = runBlocking {
        val markedAcked = mutableListOf<String>()

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAckIds = { listOf("already-removed") },
            ackMessage = { false },
            markAcked = { deliveryIds ->
                markedAcked += deliveryIds
            },
        )

        assertEquals(listOf("already-removed"), result.ackedIds)
        assertEquals(listOf("already-removed"), markedAcked)
        assertTrue(result.failedIds.isEmpty())
    }
}
