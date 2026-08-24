package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.ProviderAckContract
import io.ethan.pushgo.data.ProviderAckAttemptResult
import io.ethan.pushgo.data.ProviderAckDestination
import io.ethan.pushgo.data.db.InboundDeliveryAckOutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAckDrainCoordinatorTest {
    @Test
    fun drainPendingAcks_marksOnlyGatewayConfirmedRecords() = runBlocking {
        val submitted = mutableListOf<String>()
        val markedAcked = mutableListOf<InboundDeliveryAckOutboxEntity>()
        val records = listOf(record("delivery-1"), record("delivery-2"))

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { records },
            ackMessages = { destination, contract, deliveryIds ->
                assertEquals(ProviderAckDestination(GATEWAY_A, DEVICE_A), destination)
                assertEquals(ProviderAckContract.V2_BATCH, contract)
                submitted += deliveryIds
                ackResult(deliveryIds.size, deliveryIds.size)
            },
            markAcked = { markedAcked += it },
        )

        assertEquals(listOf("delivery-1", "delivery-2"), result.attemptedIds)
        assertEquals(listOf("delivery-1", "delivery-2"), submitted)
        assertEquals(claimed(records), markedAcked)
        assertTrue(result.failedIds.isEmpty())
    }

    @Test
    fun drainPendingAcks_retainsFailedRecordsForRetry() = runBlocking {
        val records = listOf(record("delivery-ok"), record("delivery-fail"))
        val markedAcked = mutableListOf<InboundDeliveryAckOutboxEntity>()

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { records },
            ackMessages = { _, _, _ -> error("network unavailable") },
            markAcked = { markedAcked += it },
        )

        assertEquals(emptyList<String>(), result.ackedIds)
        assertEquals(listOf("delivery-ok", "delivery-fail"), result.failedIds)
        assertEquals(claimed(records), result.uncertainFailures)
        assertTrue(result.definitiveFailures.isEmpty())
        assertTrue(markedAcked.isEmpty())
    }

    @Test
    fun drainPendingAcks_v2FreshZeroRemovedIsAnIdempotentTerminalResponse() = runBlocking {
        val records = listOf(record("already-removed"))
        val markedAcked = mutableListOf<InboundDeliveryAckOutboxEntity>()

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { records },
            ackMessages = { _, _, deliveryIds -> ackResult(deliveryIds.size, 0) },
            markAcked = { markedAcked += it },
        )

        assertEquals(listOf("already-removed"), result.ackedIds)
        assertEquals(claimed(records), markedAcked)
    }

    @Test
    fun drainPendingAcks_v2AmbiguousRetryAllowsValidatedZeroRemoved() = runBlocking {
        val records = listOf(
            record("already-removed", attemptCount = 1, lastAttemptUncertain = true)
        )
        val markedAcked = mutableListOf<InboundDeliveryAckOutboxEntity>()

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { records },
            ackMessages = { _, _, deliveryIds -> ackResult(deliveryIds.size, 0) },
            markAcked = { markedAcked += it },
        )

        assertEquals(listOf("already-removed"), result.ackedIds)
        assertEquals(claimed(records), markedAcked)
    }

    @Test
    fun drainPendingAcks_v2RepeatedDefinitiveZeroIsStillAnIdempotentTerminalResponse() = runBlocking {
        val records = listOf(record("definitive-zero", attemptCount = 7, lastAttemptUncertain = false))

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { records },
            ackMessages = { _, _, deliveryIds -> ackResult(deliveryIds.size, 0) },
            markAcked = { marked -> assertEquals(claimed(records), marked.toList()) },
        )

        assertEquals(claimed(records), result.acked)
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun drainPendingAcks_v2PartialRemovalIsTerminalBecauseDeleteAllLeavesEveryIdAbsent() = runBlocking {
        val records = listOf(record("delivery-1"), record("delivery-2"))

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { records },
            ackMessages = { _, _, deliveryIds -> ackResult(deliveryIds.size, 1) },
            markAcked = {},
        )

        assertEquals(listOf("delivery-1", "delivery-2"), result.ackedIds)
        assertTrue(result.failedIds.isEmpty())
    }

    @Test
    fun drainPendingAcks_neverSubmitsMoreThanGatewayBatchLimit() = runBlocking {
        var requestedLimit = 0
        var submittedCount = 0

        ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { limit ->
                requestedLimit = limit
                (1..limit).map { record("delivery-$it") }
            },
            ackMessages = { _, _, deliveryIds ->
                submittedCount = deliveryIds.size
                ackResult(deliveryIds.size, deliveryIds.size)
            },
            markAcked = {},
            limit = 500,
        )

        assertEquals(201, requestedLimit)
        assertEquals(200, submittedCount)
    }

    @Test
    fun drainPendingAcks_gatewayFailureDoesNotMisrouteOrBlockOtherGateway() = runBlocking {
        val oldRecord = record("same-delivery", gateway = GATEWAY_A, deviceKey = DEVICE_A)
        val newRecord = record("same-delivery", gateway = GATEWAY_B, deviceKey = DEVICE_B)
        val calls = mutableListOf<ProviderAckDestination>()
        val marked = mutableListOf<InboundDeliveryAckOutboxEntity>()

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { listOf(oldRecord, newRecord) },
            ackMessages = { destination, _, _ ->
                calls += destination
                if (destination.baseUrl == GATEWAY_A) error("old gateway offline")
                ackResult(requestedCount = 1, removedCount = 1)
            },
            markAcked = { marked += it },
        )

        assertEquals(
            listOf(
                ProviderAckDestination(GATEWAY_A, DEVICE_A),
                ProviderAckDestination(GATEWAY_B, DEVICE_B),
            ),
            calls,
        )
        assertEquals(claimed(listOf(oldRecord)), result.failed)
        assertEquals(claimed(listOf(newRecord)), result.acked)
        assertEquals(claimed(listOf(newRecord)), marked)
    }

    @Test
    fun drainPendingAcks_preservesLegacySingleContractAsSeparateGroup() = runBlocking {
        val v2 = record("v2", contract = ProviderAckContract.V2_BATCH)
        val legacy = record("legacy", contract = ProviderAckContract.LEGACY_SINGLE)
        val contracts = mutableListOf<ProviderAckContract>()

        ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { listOf(v2, legacy) },
            ackMessages = { _, contract, deliveryIds ->
                contracts += contract
                ackResult(deliveryIds.size, deliveryIds.size)
            },
            markAcked = {},
        )

        assertEquals(listOf(ProviderAckContract.V2_BATCH, ProviderAckContract.LEGACY_SINGLE), contracts)
    }

    @Test
    fun drainPendingAcks_legacyFreshFalseIsRetainedForRetry() = runBlocking {
        val fresh = record(
            deliveryId = "legacy-fresh",
            contract = ProviderAckContract.LEGACY_SINGLE,
        )
        val marked = mutableListOf<InboundDeliveryAckOutboxEntity>()

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { listOf(fresh) },
            ackMessages = { _, _, _ -> ackResult(requestedCount = 1, removedCount = 0) },
            markAcked = { marked += it },
        )

        assertEquals(claimed(listOf(fresh)), result.failed)
        assertTrue(marked.isEmpty())
    }

    @Test
    fun drainPendingAcks_legacyExactRetryMayAcceptAlreadyRemovedAfterUncertainAttempt() = runBlocking {
        val retry = record(
            deliveryId = "legacy-retry",
            contract = ProviderAckContract.LEGACY_SINGLE,
            attemptCount = 1,
            lastAttemptUncertain = true,
        )
        val marked = mutableListOf<InboundDeliveryAckOutboxEntity>()

        val result = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = { listOf(retry) },
            ackMessages = { _, _, _ -> ackResult(requestedCount = 1, removedCount = 0) },
            markAcked = { marked += it },
        )

        assertEquals(claimed(listOf(retry)), result.acked)
        assertEquals(claimed(listOf(retry)), marked)
    }

    private fun record(
        deliveryId: String,
        gateway: String = GATEWAY_A,
        deviceKey: String = DEVICE_A,
        contract: ProviderAckContract = ProviderAckContract.V2_BATCH,
        attemptCount: Int = 0,
        lastAttemptUncertain: Boolean = false,
    ) = InboundDeliveryAckOutboxEntity(
        deliveryId = deliveryId,
        gatewayUrl = gateway,
        deviceKey = deviceKey,
        ackContract = contract.persistedValue,
        source = "test",
        enqueuedAt = 1,
        updatedAt = 1,
        attemptCount = attemptCount,
        lastAttemptUncertain = lastAttemptUncertain,
    )

    private fun ackResult(requestedCount: Int, removedCount: Int) = ProviderAckAttemptResult(
        requestedCount = requestedCount,
        removedCount = removedCount,
    )

    private fun claimed(records: Collection<InboundDeliveryAckOutboxEntity>) =
        records.map { it.copy(attemptCount = it.attemptCount + 1) }

    private companion object {
        const val GATEWAY_A = "https://gateway-a.example"
        const val GATEWAY_B = "https://gateway-b.example"
        const val DEVICE_A = "device-a"
        const val DEVICE_B = "device-b"
    }
}
