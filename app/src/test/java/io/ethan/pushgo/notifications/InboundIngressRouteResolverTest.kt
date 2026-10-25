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
    fun resolve_returnsInvalid_whenWakeupMarkersPresentButDeliveryIdMissing() {
        val route = InboundIngressRouteResolver.resolve(
            mapOf(
                "provider_mode" to "wakeup",
                "provider_wakeup" to "1",
            )
        )

        assertTrue(route is InboundIngressRoute.Invalid)
    }

    @Test
    fun resolve_returnsInvalid_whenInlineContentHasNoDeliveryId() {
        val route = InboundIngressRouteResolver.resolve(
            mapOf(
                "entity_type" to "message",
                "message_id" to "m-1",
            )
        )

        assertTrue(route is InboundIngressRoute.Invalid)
    }

    @Test
    fun resolve_fullInlinePayloadAlwaysPullsByDeliveryInsteadOfParsingItsContent() {
        for (type in listOf("message", "event", "thing", "")) {
            val data = mutableMapOf("delivery_id" to "  delivery-full  ", "title" to "Inline title",
                "body" to "Inline body", "ciphertext" to "Untrusted encrypted representation")
            if (type.isNotEmpty()) data["entity_type"] = type
            assertEquals(InboundIngressRoute.ProviderWakeupPull("delivery-full"), InboundIngressRouteResolver.resolve(data))
        }
    }

    @Test
    fun resolve_advertisementsRemainOptionalUntrustedMatchingConstraints() {
        assertEquals(
            InboundIngressRoute.ProviderWakeupPull("delivery-advertised", "https://advertised.invalid", "advertised-device", true),
            InboundIngressRouteResolver.resolve(mapOf("delivery_id" to "delivery-advertised",
                "base_url" to " https://advertised.invalid ", "provider_device_key" to " advertised-device ")),
        )
        assertEquals(InboundIngressRoute.ProviderWakeupPull("delivery-only"),
            InboundIngressRouteResolver.resolve(mapOf("delivery_id" to "delivery-only", "base_url" to " ", "provider_device_key" to " ")))
    }

    @Test
    fun resolve_explicitGatewayAdvertisementUsesTheExistingGatewayFieldName() {
        assertEquals(InboundIngressRoute.ProviderWakeupPull("delivery-gateway", "https://one.invalid"),
            InboundIngressRouteResolver.resolve(mapOf("delivery_id" to "delivery-gateway", "gateway_url" to "https://one.invalid")))
    }

    @Test
    fun resolve_defaultOrUnknownInlinePayloadWithoutDeliveryNeverFallsBackToDirect() {
        for (data in listOf(emptyMap(), mapOf("title" to "default Message"),
            mapOf("ciphertext" to "encrypted"), mapOf("entity_type" to "unknown", "delivery_id" to " "))) {
            assertTrue(InboundIngressRouteResolver.resolve(data) is InboundIngressRoute.Invalid)
        }
    }
}
