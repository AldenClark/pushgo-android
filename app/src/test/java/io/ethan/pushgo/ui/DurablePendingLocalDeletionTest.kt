package io.ethan.pushgo.ui

import io.ethan.pushgo.data.InMemoryPendingLocalDeletionRepository
import io.ethan.pushgo.data.PendingLocalDeletionExecutor
import io.ethan.pushgo.data.PendingLocalDeletionOperation
import io.ethan.pushgo.data.PendingLocalDeletionRepository
import io.ethan.pushgo.data.PendingLocalDeletionState
import io.ethan.pushgo.data.PendingLocalDeletionKind
import io.ethan.pushgo.data.PermanentPendingLocalDeletionException
import io.ethan.pushgo.notifications.privateTransportFailure
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurablePendingLocalDeletionTest {
    @Test
    fun firstClaimPermanentlyConsumesUndoCapability() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        val record = repository.enqueue(
            summary = "event",
            operation = PendingLocalDeletionOperation.events(setOf("event-1")),
            requestedAtEpochMillis = 1_000L,
            undoWindowMillis = 5_000L,
        )
        assertTrue(record.isUndoable)

        val claim = requireNotNull(repository.claim(record.id, 1_000L, force = true))
        assertFalse(claim.isUndoable)
        assertTrue(
            repository.retryClaimed(
                id = record.id,
                nowEpochMillis = 1_100L,
                nextAttemptAtEpochMillis = 10_000L,
                lastError = "transient",
            )
        )

        val retry = repository.loadActive().single()
        assertEquals(PendingLocalDeletionState.PENDING, retry.state)
        assertEquals(1, retry.attemptCount)
        assertFalse(retry.isUndoable)
        assertFalse(repository.cancelPending(record.id))
    }

    @Test
    fun retryPendingRemainsSuppressedButIsNotPresentedAsUndoable() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        val record = repository.enqueue(
            summary = "event",
            operation = PendingLocalDeletionOperation.events(setOf("event-1")),
            requestedAtEpochMillis = 1_000L,
            undoWindowMillis = 5_000L,
        )
        requireNotNull(repository.claim(record.id, 1_000L, force = true))
        assertTrue(repository.retryClaimed(record.id, 1_100L, 10_000L, "offline"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                repository = repository,
                operationExecutor = PendingLocalDeletionExecutor {},
                wallClockEpochMillis = { 2_000L },
                elapsedRealtimeMillis = { 2_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )

            coordinator.setInteractionActive(true)

            assertEquals(setOf("event-1"), coordinator.effectiveScope.value.eventIds)
            assertEquals(null, coordinator.pendingDeletion.value)
            coordinator.undoCurrent()
            assertEquals(record.id, repository.loadActive().single().id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun backedOffHeadDoesNotBlockLaterDueOperationAndSchedulesEarliestRetry() = runBlocking {
        val clock = AtomicLong(1_000L)
        val repository = InMemoryPendingLocalDeletionRepository()
        val backedOff = repository.enqueue(
            summary = "thing",
            operation = PendingLocalDeletionOperation.things(setOf("thing-1")),
            requestedAtEpochMillis = clock.get(),
            undoWindowMillis = 5_000L,
        )
        requireNotNull(repository.claim(backedOff.id, clock.get(), force = true))
        assertTrue(repository.retryClaimed(backedOff.id, 1_100L, 20_000L, "offline"))
        repository.enqueue(
            summary = "message",
            operation = PendingLocalDeletionOperation.messages(setOf("message-2")),
            requestedAtEpochMillis = clock.get(),
            undoWindowMillis = 0L,
        )
        clock.set(6_000L)
        val scheduler = RecordingDrainScheduler()
        val executions = CopyOnWriteArrayList<PendingLocalDeletionKind>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                repository = repository,
                operationExecutor = PendingLocalDeletionExecutor { executions += it.kind },
                drainScheduler = scheduler,
                wallClockEpochMillis = clock::get,
                elapsedRealtimeMillis = clock::get,
                completionDispatcher = Dispatchers.Unconfined,
            )

            assertTrue(coordinator.drainRecoverable(force = false))

            assertEquals(listOf(PendingLocalDeletionKind.MESSAGES), executions)
            assertEquals(backedOff.id, repository.loadActive().single().id)
            assertTrue(20_000L in scheduler.scheduledAt)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun finalizeFalseIsRecoveredByTheSameCoordinator() = runBlocking {
        assertSameCoordinatorRecoversFinalizeFault(FinalizeFault.RETURN_FALSE)
    }

    @Test
    fun finalizeExceptionIsRecoveredByTheSameCoordinator() = runBlocking {
        assertSameCoordinatorRecoversFinalizeFault(FinalizeFault.THROW)
    }

    @Test
    fun finalizeCancellationIsRecoveredByTheSameCoordinator() = runBlocking {
        assertSameCoordinatorRecoversFinalizeFault(FinalizeFault.CANCEL)
    }

    @Test
    fun interruptedClaimIsRecoveredAndExecutedAfterCoordinatorRestart() = runBlocking {
        val clock = AtomicLong(1_000L)
        val repository = InMemoryPendingLocalDeletionRepository()
        val record = repository.enqueue(
            summary = "message",
            operation = PendingLocalDeletionOperation.messages(setOf("m1")),
            requestedAtEpochMillis = clock.get(),
            undoWindowMillis = 5_000L,
        )
        assertNotNull(repository.claim(record.id, clock.get(), force = true))
        assertEquals(PendingLocalDeletionState.COMMITTING, repository.loadActive().single().state)
        clock.set(7_000L)

        val executions = CopyOnWriteArrayList<PendingLocalDeletionOperation>()
        val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val restarted = PendingLocalDeletionCoordinator(
                appScope = restartedScope,
                repository = repository,
                operationExecutor = PendingLocalDeletionExecutor { executions += it },
                countdownMillis = 5_000L,
                wallClockEpochMillis = clock::get,
                elapsedRealtimeMillis = clock::get,
                completionDispatcher = Dispatchers.Unconfined,
            )
            restarted.start()

            repeat(100) {
                if (repository.loadActive().isEmpty()) return@repeat
                delay(10L)
            }

            assertTrue(repository.loadActive().isEmpty())
            assertEquals(listOf(PendingLocalDeletionOperation.messages(setOf("m1"))), executions)
            assertFalse(restarted.drainRecoverable())
        } finally {
            restartedScope.cancel()
        }
    }

    @Test
    fun undoRemovesDurableIntentSoRestartHasNothingToExecute() = runBlocking {
        val clock = AtomicLong(1_000L)
        val repository = InMemoryPendingLocalDeletionRepository()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = PendingLocalDeletionCoordinator(
            appScope = firstScope,
            repository = repository,
            countdownMillis = 5_000L,
            wallClockEpochMillis = clock::get,
            elapsedRealtimeMillis = clock::get,
            completionDispatcher = Dispatchers.Unconfined,
        )
        first.schedule(
            summary = "message",
            operation = PendingLocalDeletionOperation.messages(setOf("m1")),
        )
        first.undoCurrent()
        firstScope.cancel()

        clock.set(10_000L)
        val executions = CopyOnWriteArrayList<PendingLocalDeletionOperation>()
        val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val restarted = PendingLocalDeletionCoordinator(
                appScope = restartedScope,
                repository = repository,
                operationExecutor = PendingLocalDeletionExecutor { executions += it },
                wallClockEpochMillis = clock::get,
                elapsedRealtimeMillis = clock::get,
                completionDispatcher = Dispatchers.Unconfined,
            )
            assertFalse(restarted.drainRecoverable(force = true))
            assertTrue(executions.isEmpty())
            assertTrue(repository.loadActive().isEmpty())
        } finally {
            restartedScope.cancel()
        }
    }

    @Test
    fun failedCommitRetainsAttemptMetadataAndRetriesIdempotently() = runBlocking {
        val clock = AtomicLong(1_000L)
        val repository = InMemoryPendingLocalDeletionRepository()
        repository.enqueue(
            summary = "thing",
            operation = PendingLocalDeletionOperation.things(setOf("t1")),
            requestedAtEpochMillis = clock.get(),
            undoWindowMillis = 5_000L,
        )
        var attempts = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                repository = repository,
                operationExecutor = PendingLocalDeletionExecutor {
                    attempts += 1
                    if (attempts == 1) error("transient")
                },
                wallClockEpochMillis = clock::get,
                elapsedRealtimeMillis = clock::get,
                completionDispatcher = Dispatchers.Unconfined,
            )

            assertTrue(coordinator.drainRecoverable(force = true))
            val retry = repository.loadActive().single()
            assertEquals(PendingLocalDeletionState.PENDING, retry.state)
            assertEquals(1, retry.attemptCount)
            assertTrue(retry.nextAttemptAtEpochMillis > clock.get())
            assertTrue(retry.lastError.orEmpty().contains("transient"))

            clock.set(maxOf(retry.nextAttemptAtEpochMillis, retry.undoDeadlineEpochMillis))
            assertFalse(coordinator.drainRecoverable(force = false))
            assertEquals(2, attempts)
            assertTrue(repository.loadActive().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun backgroundTransitionCommitsBeforeUndoDeadline() = runBlocking {
        val clock = AtomicLong(1_000L)
        val repository = InMemoryPendingLocalDeletionRepository()
        var executed = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                repository = repository,
                operationExecutor = PendingLocalDeletionExecutor { executed = true },
                countdownMillis = 60_000L,
                wallClockEpochMillis = clock::get,
                elapsedRealtimeMillis = clock::get,
                completionDispatcher = Dispatchers.Unconfined,
            )
            coordinator.schedule(
                summary = "event",
                operation = PendingLocalDeletionOperation.events(setOf("e1")),
            )

            coordinator.setInteractionActive(false)

            assertTrue(executed)
            assertTrue(repository.loadActive().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun permanentConflictDoesNotBlockLaterRecoverableOperation() = runBlocking {
        val clock = AtomicLong(1_000L)
        val repository = InMemoryPendingLocalDeletionRepository()
        repository.enqueue(
            summary = "conflicting channel",
            operation = PendingLocalDeletionOperation.channel(
                id = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                expectedGatewayUrl = "https://gateway.example",
                expectedUpdatedAt = 1L,
                expectedUseProvider = true,
            ),
            requestedAtEpochMillis = clock.get(),
            undoWindowMillis = 5_000L,
        )
        repository.enqueue(
            summary = "message",
            operation = PendingLocalDeletionOperation.messages(setOf("m1")),
            requestedAtEpochMillis = clock.get(),
            undoWindowMillis = 5_000L,
        )
        val executed = CopyOnWriteArrayList<PendingLocalDeletionKind>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                repository = repository,
                operationExecutor = PendingLocalDeletionExecutor { operation ->
                    if (operation.kind == PendingLocalDeletionKind.CHANNEL) {
                        throw PermanentPendingLocalDeletionException("newer version")
                    }
                    executed += operation.kind
                },
                wallClockEpochMillis = clock::get,
                elapsedRealtimeMillis = clock::get,
                completionDispatcher = Dispatchers.Unconfined,
            )

            assertFalse(coordinator.drainRecoverable(force = true))

            assertEquals(listOf(PendingLocalDeletionKind.MESSAGES), executed)
            assertTrue(repository.loadActive().isEmpty())
            assertTrue(coordinator.effectiveScope.value.channelIds.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun privateTransportFailureStaysPendingAndSucceedsAfterRecovery() = runBlocking {
        val clock = AtomicLong(1_000L)
        val repository = InMemoryPendingLocalDeletionRepository()
        repository.enqueue(
            summary = "private channel",
            operation = PendingLocalDeletionOperation.channel(
                id = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                expectedGatewayUrl = "https://gateway.example",
                expectedUpdatedAt = 1L,
                expectedUseProvider = false,
            ),
            requestedAtEpochMillis = clock.get(),
            undoWindowMillis = 5_000L,
        )
        var attempts = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                repository = repository,
                operationExecutor = PendingLocalDeletionExecutor {
                    attempts += 1
                    if (attempts == 1) {
                        throw privateTransportFailure(IOException("connection reset"))
                    }
                },
                wallClockEpochMillis = clock::get,
                elapsedRealtimeMillis = clock::get,
                completionDispatcher = Dispatchers.Unconfined,
            )

            assertTrue(coordinator.drainRecoverable(force = true))
            val pending = repository.loadActive().single()
            assertEquals(PendingLocalDeletionState.PENDING, pending.state)
            assertEquals(1, pending.attemptCount)

            clock.set(maxOf(pending.nextAttemptAtEpochMillis, pending.undoDeadlineEpochMillis))
            assertFalse(coordinator.drainRecoverable(force = false))
            assertEquals(2, attempts)
            assertTrue(repository.loadActive().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private suspend fun assertSameCoordinatorRecoversFinalizeFault(fault: FinalizeFault) {
        val clock = AtomicLong(1_000L)
        val delegate = InMemoryPendingLocalDeletionRepository()
        val repository = FaultingFinalizeRepository(delegate, fault)
        repository.enqueue(
            summary = "message",
            operation = PendingLocalDeletionOperation.messages(setOf("m1")),
            requestedAtEpochMillis = clock.get(),
            undoWindowMillis = 5_000L,
        )
        var executions = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                repository = repository,
                operationExecutor = PendingLocalDeletionExecutor { executions += 1 },
                wallClockEpochMillis = clock::get,
                elapsedRealtimeMillis = clock::get,
                completionDispatcher = Dispatchers.Unconfined,
            )

            val firstDrain = runCatching { coordinator.drainRecoverable(force = true) }
            if (fault == FinalizeFault.CANCEL) {
                assertTrue(firstDrain.exceptionOrNull() is CancellationException)
            } else {
                assertTrue(firstDrain.getOrThrow())
            }
            val retry = repository.loadActive().single()
            assertEquals(PendingLocalDeletionState.PENDING, retry.state)
            assertTrue(retry.lastError.orEmpty().contains("Finalization failed"))

            clock.set(maxOf(retry.undoDeadlineEpochMillis, retry.nextAttemptAtEpochMillis))
            assertFalse(coordinator.drainRecoverable(force = false))
            assertEquals(2, executions)
            assertTrue(repository.loadActive().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    private enum class FinalizeFault {
        RETURN_FALSE,
        THROW,
        CANCEL,
    }

    private class RecordingDrainScheduler : PendingLocalDeletionDrainScheduler {
        val scheduledAt = CopyOnWriteArrayList<Long>()

        override fun scheduleImmediate() = Unit

        override fun scheduleAt(epochMillis: Long) {
            scheduledAt += epochMillis
        }
    }

    private class FaultingFinalizeRepository(
        private val delegate: PendingLocalDeletionRepository,
        private val fault: FinalizeFault,
    ) : PendingLocalDeletionRepository by delegate {
        private var shouldFail = true

        override suspend fun completeClaimed(id: Long): Boolean {
            if (!shouldFail) return delegate.completeClaimed(id)
            shouldFail = false
            return when (fault) {
                FinalizeFault.RETURN_FALSE -> false
                FinalizeFault.THROW -> error("finalize storage failure")
                FinalizeFault.CANCEL -> throw CancellationException("finalize cancelled")
            }
        }
    }
}
