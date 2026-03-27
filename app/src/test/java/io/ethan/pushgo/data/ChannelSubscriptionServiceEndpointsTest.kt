package io.ethan.pushgo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSubscriptionServiceEndpointsTest {

    @Test
    fun deviceRouteEndpoints_matchGatewayContract() {
        assertEquals("/device/register", ChannelSubscriptionService.DEVICE_REGISTER_ENDPOINT)
        assertEquals("/channel/device/delete", ChannelSubscriptionService.DEVICE_CHANNEL_DELETE_ENDPOINT)
    }
}
