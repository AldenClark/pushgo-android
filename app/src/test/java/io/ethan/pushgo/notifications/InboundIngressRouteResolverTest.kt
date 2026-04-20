package io.ethan.pushgo.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundIngressRouteResolverTest {

    @Test
    fun resolve_returnsProviderWakeupPull_whenWakeupMarkersAndDeliveryIdPresent() {
        val route = InboundIngressRouteResolver.resolve(
            mapOf(
                "provider_mode" to "wakeup",
                "provider_wakeup" to "1",
                "delivery_id" to "delivery-1",
            )
        )

        assertEquals(
            InboundIngressRoute.ProviderWakeupPull("delivery-1"),
            route,
        )
    }

    @Test
    fun resolve_returnsDrop_whenWakeupMarkersPresentButDeliveryIdMissing() {
        val route = InboundIngressRouteResolver.resolve(
            mapOf(
                "provider_mode" to "wakeup",
                "provider_wakeup" to "1",
            )
        )

        assertTrue(route is InboundIngressRoute.Drop)
    }

    @Test
    fun resolve_returnsDirect_whenNoWakeupMarkers() {
        val route = InboundIngressRouteResolver.resolve(
            mapOf(
                "entity_type" to "message",
                "message_id" to "m-1",
            )
        )

        assertEquals(InboundIngressRoute.Direct, route)
    }
}
