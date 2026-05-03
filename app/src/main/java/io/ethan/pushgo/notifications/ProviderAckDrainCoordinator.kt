package io.ethan.pushgo.notifications

internal data class ProviderAckDrainResult(
    val attemptedIds: List<String>,
    val ackedIds: List<String>,
    val failedIds: List<String>,
) {
    val hasFailures: Boolean
        get() = failedIds.isNotEmpty()
}

internal object ProviderAckDrainCoordinator {
    suspend fun drainPendingAcks(
        loadPendingAckIds: suspend (limit: Int) -> List<String>,
        ackMessage: suspend (deliveryId: String) -> Boolean,
        markAcked: suspend (deliveryIds: Collection<String>) -> Unit,
        limit: Int = 200,
    ): ProviderAckDrainResult {
        val pendingIds = normalizePendingAckDeliveryIds(
            loadPendingAckIds(limit.coerceIn(1, 500))
        )
        if (pendingIds.isEmpty()) {
            return ProviderAckDrainResult(
                attemptedIds = emptyList(),
                ackedIds = emptyList(),
                failedIds = emptyList(),
            )
        }

        val ackedIds = ArrayList<String>(pendingIds.size)
        val failedIds = ArrayList<String>()
        pendingIds.forEach { deliveryId ->
            runCatching {
                ackMessage(deliveryId)
            }.onSuccess {
                ackedIds += deliveryId
            }.onFailure {
                failedIds += deliveryId
            }
        }

        if (ackedIds.isNotEmpty()) {
            markAcked(ackedIds)
        }

        return ProviderAckDrainResult(
            attemptedIds = pendingIds,
            ackedIds = ackedIds,
            failedIds = failedIds,
        )
    }
}
