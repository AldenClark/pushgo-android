package io.ethan.pushgo.util

import io.ethan.pushgo.data.ChannelSubscriptionException
import io.ethan.pushgo.data.GatewayErrorCategory
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class LocalNetworkPermissionTest {
    @Test fun gatewayRequestEnforcesPermissionBeforeOpeningConnectionAndPreservesSpecificError() = kotlinx.coroutines.runBlocking {
        var checkedEndpoint = ""
        val service = io.ethan.pushgo.data.ChannelSubscriptionService(requireNetworkAccess = { endpoint ->
            checkedEndpoint = endpoint
            LocalNetworkPermission.requireDestinationAccess(false) { listOf(InetAddress.getByName("10.0.2.2")) }
        })
        try {
            service.channelExists("https://public-name.example", null, "channel")
            fail("Gateway must not attempt HTTP before local permission is granted")
        } catch (error: ChannelSubscriptionException) {
            assertEquals(LocalNetworkPermission.ERROR_CODE, error.code)
            assertEquals("https://public-name.example/channel/exists?channel_id=channel", checkedEndpoint)
        }
    }

    @Test fun onlyTarget37OnSystem37RequiresPermission() {
        assertFalse(LocalNetworkPermission.isRequired(28, 37))
        assertFalse(LocalNetworkPermission.isRequired(36, 37))
        assertFalse(LocalNetworkPermission.isRequired(37, 36))
        assertTrue(LocalNetworkPermission.isRequired(37, 37))
    }

    @Test fun publicDestinationsRemainAvailableWithoutPermission() {
        LocalNetworkPermission.requireDestinationAccess(false) {
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("2606:4700:4700::1111"))
        }
    }

    @Test fun resolvedDomainAndMixedAnswersRequirePermissionBeforeConnection() {
        val addresses = listOf("127.0.0.1", "10.0.2.2", "192.168.1.3", "172.16.0.1", "169.254.1.1", "::1", "fe80::1", "fd00::1")
        addresses.forEach { address ->
            try {
                LocalNetworkPermission.requireDestinationAccess(false) {
                    listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName(address))
                }
                fail("local resolved destination was allowed: $address")
            } catch (error: ChannelSubscriptionException) {
                assertEquals(LocalNetworkPermission.ERROR_CODE, error.code)
                assertEquals(GatewayErrorCategory.PERMISSION, error.category)
                assertFalse(error.retryable)
            }
        }
    }

    @Test fun grantedPermissionDoesNotResolveOrBlockAnyDestination() {
        LocalNetworkPermission.requireDestinationAccess(true) { error("unnecessary DNS") }
    }
}
