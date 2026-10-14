package io.ethan.pushgo.ui

import io.ethan.pushgo.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DurablePendingLocalDeletionTest {
    @Test fun acceptedLegacyLocalIntentIgnoresOldDeadlineAndSettlesOnceAcrossReopen() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        repository.enqueue("legacy", PendingLocalDeletionOperation.events(setOf("e1")), 100L, 120_000L)
        val applied = mutableListOf<PendingLocalDeletionOperation>()
        val first = coordinator(repository, { 200L }) { applied += it }
        assertFalse(first.drainRecoverable())
        assertTrue(repository.loadActive().isEmpty())
        val reopened = coordinator(repository, { 300L }) { applied += it }
        assertFalse(reopened.drainRecoverable())
        assertEquals(setOf("e1"), applied.single().targetIds)
    }

    @Test fun legacyTemporaryFailureRetainsExactIntentAndExplicitRetrySurvivesReopen() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        val row = repository.enqueue("legacy", PendingLocalDeletionOperation.messages(setOf("m1")), 100L, 120_000L)
        var shouldFail = true
        var attempts = 0
        val execute: suspend (PendingLocalDeletionOperation) -> Unit = { assertEquals(setOf("m1"), it.targetIds); attempts++; if (shouldFail) error("storage unavailable") }
        assertFalse(coordinator(repository, { 200L }, execute).drainRecoverable())
        val failure = repository.loadFailures().single()
        assertEquals(row.operation, failure.operation)
        assertEquals("storage unavailable", failure.lastError)
        // Reopening alone cannot silently loop or discard this failed accepted request.
        val reopened = coordinator(repository, { 300L }, execute)
        assertFalse(reopened.drainRecoverable())
        assertEquals(1, attempts)
        shouldFail = false
        reopened.retryFailed(row.id)
        assertTrue(repository.loadFailures().isEmpty())
        assertTrue(repository.loadActive().isEmpty())
        assertEquals(2, attempts)
    }

    @Test fun interruptedClaimAndFinalizeFailurePreserveOriginalOperationForReplay() = runBlocking {
        val delegate = InMemoryPendingLocalDeletionRepository()
        val row = delegate.enqueue("legacy", PendingLocalDeletionOperation.things(setOf("t1")), 100L, 5000L)
        delegate.claim(row.id, 200L, true)
        var failFinalization = true
        val repository = object : PendingLocalDeletionRepository by delegate {
            override suspend fun completeClaimed(id: Long): Boolean {
                if (failFinalization) { failFinalization = false; error("finalize storage failure") }
                return delegate.completeClaimed(id)
            }
        }
        val effects = mutableSetOf<String>()
        val first = coordinator(repository, { 300L }) { effects += it.targetIds }
        assertFalse(first.drainRecoverable())
        assertEquals(row.operation, repository.loadFailures().single().operation)
        val reopened = coordinator(repository, { 400L }) { effects += it.targetIds }
        reopened.retryFailed(row.id)
        assertEquals(setOf("t1"), effects)
        assertTrue(repository.loadFailures().isEmpty())
    }

    @Test fun remoteChannelTransientRecoveryRetainsOriginalDestinationModeAndVersion() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        val original = PendingLocalDeletionOperation.channel("c1", "https://original.example", 42L, false)
        repository.enqueue("channel", original, 100L, 0L)
        var now = 200L
        var attempts = 0
        val coordinator = coordinator(repository, { now }) {
            assertEquals(original, it)
            attempts++
            if (attempts == 1) error("transport unavailable")
        }
        assertTrue(coordinator.drainRecoverable())
        val retained = repository.loadActive().single()
        assertEquals(original, retained.operation)
        now = retained.nextAttemptAtEpochMillis
        assertFalse(coordinator.drainRecoverable())
        assertEquals(2, attempts)
    }

    @Test fun duplicateRecoveryCallsCannotExecuteSameClaimConcurrently() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        repository.enqueue("legacy", PendingLocalDeletionOperation.messages(setOf("m1")), 100L, 5000L)
        var attempts = 0
        val coordinator = coordinator(repository, { 200L }) { attempts++; yield() }
        awaitAll(async { coordinator.drainRecoverable() }, async { coordinator.drainRecoverable() })
        assertEquals(1, attempts)
    }

    private fun CoroutineScope.coordinator(repository: PendingLocalDeletionRepository, clock: () -> Long, execute: suspend (PendingLocalDeletionOperation) -> Unit) =
        PendingLocalDeletionCoordinator(this, repository, PendingLocalDeletionExecutor(execute), wallClockEpochMillis = clock, completionDispatcher = Dispatchers.Unconfined)
}
