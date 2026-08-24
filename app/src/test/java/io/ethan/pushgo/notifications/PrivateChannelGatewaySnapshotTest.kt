package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.GatewayErrorCategory
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateChannelGatewaySnapshotTest {
    @Test
    fun gatewaySwitchDoesNotMatchPersistedDeletionSnapshot() {
        assertFalse(
            gatewayMatchesExpectedSnapshot(
                current = "https://new-gateway.example",
                expected = "https://old-gateway.example",
            )
        )
    }

    @Test
    fun cosmeticTrailingSlashDoesNotCreateFalseGatewayConflict() {
        assertTrue(
            gatewayMatchesExpectedSnapshot(
                current = "https://gateway.example/",
                expected = "https://gateway.example",
            )
        )
    }

    @Test
    fun ioAndTimeoutFailuresAreExplicitlyRetryableNetworkErrors() {
        listOf(
            IOException("connection reset"),
            SocketTimeoutException("read timed out"),
        ).forEach { transportError ->
            val wrapped = privateTransportFailure(transportError)
            assertEquals(GatewayErrorCategory.NETWORK, wrapped.category)
            assertTrue(wrapped.retryable)
        }
    }
}
