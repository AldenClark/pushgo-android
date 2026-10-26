package io.ethan.pushgo.ui

import io.ethan.pushgo.data.*
import io.ethan.pushgo.data.db.PendingLocalDeletionEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DurablePendingLocalDeletionTest {
    private val messageId = "186df7cc-ae66-4e2a-8230-7e0e1dd799e1"
    private val channel = PendingLocalDeletionOperation.channel("channel", "https://original.example", 42L, false)

    @Test fun oldEnqueueBeforeUndoNeverBecomesConsentEvenAfterDeadlineOrReopen() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        val old = repository.enqueue("original message", PendingLocalDeletionOperation.messages(setOf(messageId)), 100L, 120_000L)
        var applied = 0
        for (now in listOf(200L, 120_101L, 900_000L)) {
            assertNull(repository.claim(old.id, now, force = true))
            assertFalse(coordinator(repository, { now }) { applied++ }.drainRecoverable())
            assertFalse(repository.retryFailed(old.id, now))
            assertEquals(old, repository.loadUnconfirmed().single())
        }
        assertEquals(PendingLocalDeletionState.NEEDS_CONFIRMATION, old.state)
        assertEquals(0, applied)
    }

    @Test fun unknownEntityIncarnationCanOnlyCancelAnUnstartedRequest() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        val old = repository.enqueue("old Thing", PendingLocalDeletionOperation.things(setOf("same-business-id")), 100L, 5000L)
        var applied = 0
        val owner = coordinator(repository, { 200L }) { applied++ }
        assertFalse(owner.reconfirmLegacy(old.id))
        assertEquals(PendingLocalDeletionState.QUARANTINED, repository.loadFailures().single().state)
        assertTrue(owner.cancelLegacy(old.id))
        assertFalse(owner.cancelLegacy(old.id))
        assertEquals(0, applied)
        assertTrue(repository.loadUnconfirmed().isEmpty())
    }

    @Test fun cancelAndDuplicateConfirmationKeepOneOriginalLegacyDecision() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        val old = repository.enqueue("old message", PendingLocalDeletionOperation.messages(setOf(messageId)), 100L, 5000L)
        var applied = 0
        val executor = object : PendingLocalDeletionExecutor {
            override suspend fun execute(operation: PendingLocalDeletionOperation) { error("Legacy must never enter force drain") }
            override suspend fun executeReconfirmed(record: PendingLocalDeletionRecord, identity: OrdinaryDeletionConfirmation.Identity?) {
                assertEquals(old.id, record.id)
                assertTrue(repository.cancelUnconfirmed(record.id))
                applied++
            }
        }
        val owner = PendingLocalDeletionCoordinator(this, repository, executor, completionDispatcher = Dispatchers.Unconfined)
        val cancelled = async { owner.reconfirmLegacy(old.id) }
        yield()
        owner.cancel(owner.confirmation.value!!.id)
        assertFalse(cancelled.await())
        assertEquals(old, repository.loadUnconfirmed().single())
        val confirmed = async { owner.reconfirmLegacy(old.id) }
        yield()
        val token = owner.confirmation.value!!.id
        owner.confirm(token)
        owner.confirm(token)
        assertTrue(confirmed.await())
        assertFalse(owner.reconfirmLegacy(old.id))
        assertEquals(1, applied)
    }

    @Test fun currentConfirmedChannelProducerKeepsCapturedContextAcrossRetryAndReopen() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        var now = 200L
        var attempts = 0
        val execute: suspend (PendingLocalDeletionOperation) -> Unit = {
            assertEquals(channel, it)
            attempts++
            if (attempts == 1) error("transport unavailable")
        }
        val owner = coordinator(repository, { now }, execute)
        owner.deleteConfirmedChannel("confirmed by current channel dialog", channel)
        val accepted = repository.loadActive().single()
        assertTrue(accepted.confirmationProven)
        now = accepted.nextAttemptAtEpochMillis
        assertFalse(coordinator(repository, { now }, execute).drainRecoverable())
        assertEquals(2, attempts)
        assertTrue(repository.loadActive().isEmpty())
    }

    @Test fun interruptedCurrentChannelClaimRecoversButOldChannelAttemptIsIsolated() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        // Explicit fixture: current channel dialog already confirmed this captured context.
        val confirmed = repository.enqueue("current channel", channel, 100L, 0L, confirmationProven = true)
        assertNotNull(repository.claim(confirmed.id, 200L, true))
        assertEquals(1, repository.recoverInterruptedClaims(300L))
        val old = entity().copy(operationKind = "CHANNEL", targetIdsJson = "[\"channel\"]", state = "COMMITTING", attemptCount = 1).toRecord()
        assertEquals(PendingLocalDeletionState.QUARANTINED, old.state)
        assertFalse(old.canCancelUnconfirmed)
        var attempts = 0
        assertFalse(coordinator(repository, { 400L }) { attempts++ }.drainRecoverable())
        assertEquals(1, attempts)
    }

    @Test fun corruptPayloadUnknownStateAndKindStayVisibleAndCannotCancelOrClaim() {
        for (bad in listOf(entity().copy(targetIdsJson = "not-json"), entity().copy(operationKind = "FUTURE_KIND"),
            entity().copy(state = "FUTURE_STATE"), entity().copy(targetIdsJson = "[]"),
            entity().copy(state = "FAILED", attemptCount = 1), entity().copy(state = "PENDING", attemptCount = -1))) {
            val original = bad.copy()
            assertEquals(PendingLocalDeletionState.QUARANTINED, bad.toRecord().state)
            assertFalse(bad.isCancelableLegacy())
            assertEquals(original, bad)
        }
        val corruptConfirmed = entity().copy(confirmationProven = true, targetIdsJson = "not-json")
        assertEquals(PendingLocalDeletionState.QUARANTINED, corruptConfirmed.toRecord().state)
        assertFalse(corruptConfirmed.toRecord().canCancelUnconfirmed)
    }

    @Test fun validUnstartedMessageAndChannelPreserveAllOldFieldsForReview() {
        val old = entity()
        val row = old.toRecord()
        assertEquals(PendingLocalDeletionState.NEEDS_CONFIRMATION, row.state)
        assertEquals(old.requestedAtEpochMillis, row.requestedAtEpochMillis)
        assertEquals(old.undoDeadlineEpochMillis, row.undoDeadlineEpochMillis)
        assertEquals(old.lastError, row.lastError)
        assertEquals(old.state, row.legacyState)
        assertTrue(row.canCancelUnconfirmed)
    }

    @Test fun aQuarantinedPastDueRecordCannotCreateAnImmediateRecoveryLoop() = runBlocking {
        val delegate = InMemoryPendingLocalDeletionRepository()
        val corrupt = entity().copy(confirmationProven = true, targetIdsJson = "corrupt").toRecord()
        val repository = object : PendingLocalDeletionRepository by delegate {
            override suspend fun loadActive() = listOf(corrupt)
        }
        var scheduled = 0
        var executed = 0
        val scheduler = object : PendingLocalDeletionDrainScheduler {
            override fun scheduleImmediate() { scheduled++ }
            override fun scheduleAt(epochMillis: Long) { scheduled++ }
        }
        val owner = PendingLocalDeletionCoordinator(this, repository, PendingLocalDeletionExecutor { executed++ }, scheduler,
            wallClockEpochMillis = { 90_000L }, completionDispatcher = Dispatchers.Unconfined)
        repeat(3) { assertFalse(owner.drainRecoverable()) }
        assertEquals(0, scheduled)
        assertEquals(0, executed)
    }

    @Test fun legacyChannelReviewUsesEffectiveFcmCapabilityAndCapturedContext() {
        for (available in listOf(false, true)) {
            val expected = channel.copy(expectedUseProvider = available)
            validateLegacyChannelContext(expected, "https://original.example", 42L, false,
                providerPreference = true, fcmAvailable = available)
            assertTrue(runCatching { validateLegacyChannelContext(expected, "https://changed.example", 42L, false, true, available) }.exceptionOrNull() is OrdinaryDeletionTargetChangedException)
            assertTrue(runCatching { validateLegacyChannelContext(expected, "https://original.example", 43L, false, true, available) }.exceptionOrNull() is OrdinaryDeletionTargetChangedException)
            assertTrue(runCatching { validateLegacyChannelContext(expected, "https://original.example", 42L, true, true, available) }.exceptionOrNull() is OrdinaryDeletionTargetChangedException)
            assertTrue(runCatching { validateLegacyChannelContext(expected.copy(expectedUseProvider = !available), "https://original.example", 42L, false, true, available) }.exceptionOrNull() is OrdinaryDeletionTargetChangedException)
        }
    }

    @Test fun concurrentDrainsCannotExecuteTheSameCurrentlyConfirmedChannelTwice() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val owner = coordinator(repository, { 200L }) {
            assertEquals(channel, it)
            attempts++
            entered.complete(Unit)
            release.await()
        }
        val confirmedProducer = async { owner.deleteConfirmedChannel("current dialog confirmed", channel) }
        entered.await()
        assertTrue(repository.loadActive().single().confirmationProven)
        val concurrentWorker = async { owner.drainRecoverable() }
        yield()
        assertEquals(1, attempts)
        release.complete(Unit)
        confirmedProducer.await()
        assertFalse(concurrentWorker.await())
        assertEquals(1, attempts)
        assertTrue(repository.loadActive().isEmpty())
    }

    @Test fun finalizationWriteFailureRetainsConfirmedChannelResponsibilityAcrossReopen() = runBlocking {
        val delegate = InMemoryPendingLocalDeletionRepository()
        var failFinalization = true
        val repository = object : PendingLocalDeletionRepository by delegate {
            override suspend fun completeClaimed(id: Long): Boolean {
                if (failFinalization) { failFinalization = false; error("finalization storage failure") }
                return delegate.completeClaimed(id)
            }
        }
        var now = 200L
        val applied = mutableSetOf<PendingLocalDeletionOperation>()
        var attempts = 0
        val apply: suspend (PendingLocalDeletionOperation) -> Unit = { assertEquals(channel, it); applied += it; attempts++ }
        coordinator(repository, { now }, apply).deleteConfirmedChannel("current dialog confirmed", channel)
        val retained = repository.loadActive().single()
        assertTrue(retained.confirmationProven)
        assertEquals(channel, retained.operation)
        assertEquals("finalization storage failure", retained.lastError)
        now = retained.nextAttemptAtEpochMillis
        assertFalse(coordinator(repository, { now }, apply).drainRecoverable())
        assertEquals(2, attempts)
        assertEquals(setOf(channel), applied)
        assertTrue(repository.loadActive().isEmpty())
        assertTrue(repository.loadFailures().isEmpty())
    }

    private fun entity() = PendingLocalDeletionEntity(
        id = 1, summary = "original", operationKind = "MESSAGES", targetIdsJson = "[\"$messageId\"]",
        expectedGatewayUrl = "https://original.example", expectedUpdatedAt = 42L, expectedUseProvider = false,
        requestedAtEpochMillis = 100L, undoDeadlineEpochMillis = 5100L, state = "PENDING", attemptCount = 0,
        nextAttemptAtEpochMillis = 100L, updatedAtEpochMillis = 100L, lastError = "original receipt",
    )

    private fun CoroutineScope.coordinator(repository: PendingLocalDeletionRepository, clock: () -> Long, execute: suspend (PendingLocalDeletionOperation) -> Unit) =
        PendingLocalDeletionCoordinator(this, repository, PendingLocalDeletionExecutor(execute), wallClockEpochMillis = clock, completionDispatcher = Dispatchers.Unconfined)
}
