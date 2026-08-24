package io.ethan.pushgo.data

import io.ethan.pushgo.util.UrlValidators
import java.security.MessageDigest

/** Stable origin identity for durable inbound-delivery deduplication. */
@ConsistentCopyVisibility
data class InboundDeliveryScope private constructor(
    val gatewayUrl: String,
    val deviceKey: String,
) {
    companion object {
        fun create(gatewayUrl: String?, deviceKey: String?): InboundDeliveryScope? {
            val normalizedGatewayUrl = UrlValidators.normalizeGatewayBaseUrl(gatewayUrl) ?: return null
            val normalizedDeviceKey = deviceKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return InboundDeliveryScope(
                gatewayUrl = normalizedGatewayUrl,
                deviceKey = normalizedDeviceKey,
            )
        }
    }
}

internal fun ProviderAckIdentity?.inboundDeliveryScope(): InboundDeliveryScope? {
    val identity = this ?: return null
    return InboundDeliveryScope.create(identity.gatewayUrl, identity.deviceKey)
}

internal fun InboundDeliveryScope?.scopedDeliveryStorageKey(deliveryId: String): String {
    val normalizedDeliveryId = deliveryId.trim()
    val scope = this ?: return normalizedDeliveryId
    val scopedValue = "${scope.gatewayUrl}\u0000${scope.deviceKey}\u0000$normalizedDeliveryId"
    val digest = MessageDigest.getInstance("SHA-256").digest(scopedValue.toByteArray(Charsets.UTF_8))
    return buildString(17 + digest.size * 2) {
        // Retain the existing prefix and hash format for provider-ingress compatibility.
        append("provider-scoped:")
        digest.forEach { byte ->
            append(((byte.toInt() ushr 4) and 0x0f).toString(16))
            append((byte.toInt() and 0x0f).toString(16))
        }
    }
}
