package io.ethan.pushgo.notifications

internal sealed interface InboundIngressRoute {
    data class ProviderWakeupPull(
        val deliveryId: String,
    ) : InboundIngressRoute

    data object Direct : InboundIngressRoute

    data class Drop(
        val reason: String,
    ) : InboundIngressRoute
}

internal object InboundIngressRouteResolver {
    fun resolve(messageData: Map<String, String>): InboundIngressRoute {
        val wakeupDeliveryId = NotificationIngressParser.providerWakeupPullDeliveryId(messageData)
        if (wakeupDeliveryId != null) {
            return InboundIngressRoute.ProviderWakeupPull(deliveryId = wakeupDeliveryId)
        }

        val providerWakeup = messageData["provider_wakeup"]?.trim()?.lowercase()
        val providerMode = messageData["provider_mode"]?.trim()?.lowercase()
        val deliveryId = messageData["delivery_id"]?.trim().orEmpty()
        val isWakeupCandidate = providerWakeup == "1" || providerWakeup == "true" || providerMode == "wakeup"
        if (isWakeupCandidate && deliveryId.isEmpty()) {
            return InboundIngressRoute.Drop(
                reason = "provider wakeup marker present but delivery_id missing",
            )
        }

        return InboundIngressRoute.Direct
    }
}
