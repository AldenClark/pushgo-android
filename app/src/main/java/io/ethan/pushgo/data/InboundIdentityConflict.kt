package io.ethan.pushgo.data

/** A successfully read canonical identity which cannot be adopted by this ingress. */
enum class InboundIdentityConflictReason {
    FOREIGN_GATEWAY,
    DIFFERENT_CONTENT,
    UNVERIFIABLE_HISTORY,
}

/** Thrown inside canonical acceptance so every tentative ingress claim rolls back. */
internal class InboundIdentityConflictException(val reason: InboundIdentityConflictReason) :
    IllegalStateException("Inbound canonical identity retained: ${reason.name}")

/** Existing literal delivery facts can prove an otherwise irreversible stored scope hash. */
internal data class InboundOriginFact(
    val scope: InboundDeliveryScope,
    val channelId: String?,
    val entityType: String?,
    val entityId: String?,
    val opId: String?,
    val appliedAt: Long,
)

/** Constant client RAM; the legacy delivery-only lookup may scan existing ledger rows. */
internal fun io.ethan.pushgo.data.db.PushGoDatabase.provenInboundGateway(
    deliveryId: String,
    originHash: String? = null,
    scopedSourceId: String? = null,
    accepts: (InboundOriginFact) -> Boolean = { true },
): String? = openHelper.readableDatabase.query(
    "SELECT gateway_url,device_key,channel_id,entity_type,entity_id,op_id,applied_at FROM inbound_delivery_ledger WHERE delivery_id=?",
    arrayOf(deliveryId),
).use { cursor ->
    while (cursor.moveToNext()) {
        val candidate = InboundDeliveryScope.create(cursor.getString(0), cursor.getString(1)) ?: continue
        val provesOrigin = originHash != null && MessageImageStore.originScope(candidate) == originHash
        val provesSource = scopedSourceId != null && candidate.scopedDeliveryStorageKey(deliveryId) == scopedSourceId
        if (!provesOrigin && !provesSource) continue
        if (accepts(InboundOriginFact(candidate, cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getLong(6)))) {
            return@use candidate.gatewayUrl
        }
    }
    null
}

/** Head/source identity is durable business metadata, not the disposable URL cache. */
internal fun io.ethan.pushgo.data.db.PushGoDatabase.requireInboundHeadGateway(
    scope: InboundDeliveryScope?,
    sourceId: String,
    deliveryId: String?,
) {
    if (scope == null) return
    val rawDeliveryId = deliveryId?.trim()?.takeIf(String::isNotEmpty)
    if (rawDeliveryId != null && scope.scopedDeliveryStorageKey(rawDeliveryId) == sourceId) return
    val previousGateway = authenticatedSourceGateway(sourceId)
        ?: rawDeliveryId?.let { provenInboundGateway(it, scopedSourceId = sourceId) }
        ?: throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
    if (previousGateway != scope.gatewayUrl) throw InboundIdentityConflictException(InboundIdentityConflictReason.FOREIGN_GATEWAY)
}

/** New durable scope columns remain interpretable after delivery facts expire, including no-delivery local rows. */
internal fun io.ethan.pushgo.data.db.PushGoDatabase.authenticatedSourceGateway(
    sourceId: String,
): String? = openHelper.readableDatabase.query(
    "SELECT authenticated_gateway_url,authenticated_device_key,origin_scope FROM message_resource_owners WHERE owner_id=? AND source_table IN ('event_change_logs','thing_change_logs','thing_sub_events')",
    arrayOf(sourceId),
).use { cursor ->
    var gateway: String? = null
    while (cursor.moveToNext()) {
        val scope = InboundDeliveryScope.create(cursor.getString(0), cursor.getString(1)) ?: return@use null
        if (MessageImageStore.originScope(scope) != cursor.getString(2)) return@use null
        if (gateway != null && gateway != scope.gatewayUrl) return@use null
        gateway = scope.gatewayUrl
    }
    gateway
}
