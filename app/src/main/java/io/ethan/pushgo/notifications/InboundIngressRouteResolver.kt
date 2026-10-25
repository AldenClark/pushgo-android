package io.ethan.pushgo.notifications

internal sealed interface InboundIngressRoute {
    data class ProviderWakeupPull(
        val deliveryId: String,
        val advertisedGatewayUrl: String? = null,
        val advertisedDeviceKey: String? = null,
        val strictConfiguredTarget: Boolean = true,
    ) : InboundIngressRoute

    // Kept for internal hooks compatibility; the production resolver never
    // authorizes an inline payload to choose persistence or ACK identity.
    data object Direct : InboundIngressRoute

    data class Invalid(
        val reason: String,
    ) : InboundIngressRoute

    data class Drop(
        val reason: String,
    ) : InboundIngressRoute
}

internal object InboundIngressRouteResolver {
    fun resolve(messageData: Map<String, String>): InboundIngressRoute {
        val deliveryId = messageData["delivery_id"]?.trim().orEmpty()
        if (deliveryId.isEmpty()) {
            // Default message payloads need not advertise entity_type. None may
            // fall back to unauthenticated inline persistence after a bad wakeup.
            return InboundIngressRoute.Invalid("provider delivery_id missing")
        }
        val gateway = messageData["gateway_url"]?.trim()?.takeIf(String::isNotEmpty)
        val base = messageData["base_url"]?.trim()?.takeIf(String::isNotEmpty)
        return InboundIngressRoute.ProviderWakeupPull(
            deliveryId = deliveryId,
            advertisedGatewayUrl = gateway ?: base,
            advertisedDeviceKey = messageData["provider_device_key"]?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}
