package io.ethan.pushgo.ui

import io.ethan.pushgo.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PendingLocalDeletionCoordinatorTest {
    @Test fun cancelHasNoExecutorOrPersistenceEffectsAndStaleTokenCannotConfirm() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        var executions = 0
        val coordinator = coordinator(repository) { executions++ }
        val action = async { coordinator.confirmAndDelete("original", PendingLocalDeletionOperation.messages(setOf("m1"))) }
        yield()
        val request = coordinator.confirmation.value!!
        assertTrue(repository.loadActive().isEmpty())
        assertEquals(0, executions)
        coordinator.confirm(request.id + 1)
        assertEquals(request, coordinator.confirmation.value)
        coordinator.cancel(request.id)
        coordinator.confirm(request.id)
        assertFalse(action.await())
        assertEquals(0, executions)
        assertTrue(repository.loadActive().isEmpty())
    }

    @Test fun confirmSnapshotsExactIdsRejectsDuplicateAndDirectlyExecutesWithoutRow() = runBlocking {
        val repository = InMemoryPendingLocalDeletionRepository()
        val targets = linkedSetOf("m1")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = mutableListOf<PendingLocalDeletionOperation>()
        val coordinator = coordinator(repository) {
            operations += it
            entered.complete(Unit)
            release.await()
        }
        val action = async { coordinator.confirmAndDelete("target", PendingLocalDeletionOperation.messages(targets)) }
        yield()
        targets += "control"
        val request = coordinator.confirmation.value!!
        assertFalse(coordinator.confirmAndDelete("different", PendingLocalDeletionOperation.events(setOf("e2"))))
        coordinator.confirm(request.id)
        coordinator.confirm(request.id)
        entered.await()
        assertFalse(coordinator.confirmAndDelete("target", PendingLocalDeletionOperation.messages(setOf("m1"))))
        assertEquals(setOf("m1"), operations.single().targetIds)
        assertTrue(repository.loadActive().isEmpty())
        release.complete(Unit)
        assertTrue(action.await())
        assertEquals(1, operations.size)
    }

    @Test fun failureIsReportedAndRequiresFreshConfirmationForRetry() = runBlocking {
        var fail = true
        val repository = InMemoryPendingLocalDeletionRepository()
        val coordinator = coordinator(repository) { if (fail) error("transaction failed") }
        var receipt: Result<Unit>? = null
        val first = async { coordinator.confirmAndDelete("target", PendingLocalDeletionOperation.things(setOf("t1"))) { receipt = it } }
        yield()
        coordinator.confirm(coordinator.confirmation.value!!.id)
        assertFalse(first.await())
        assertEquals("transaction failed", receipt!!.exceptionOrNull()!!.message)
        assertTrue(repository.loadActive().isEmpty())
        fail = false
        val second = async { coordinator.confirmAndDelete("target", PendingLocalDeletionOperation.things(setOf("t1"))) }
        yield()
        assertNotNull(coordinator.confirmation.value)
        coordinator.confirm(coordinator.confirmation.value!!.id)
        assertTrue(second.await())
    }

    @Test fun abandonedForegroundRequestCannotDeleteAfterCallerCloses() = runBlocking {
        var executions = 0
        val coordinator = coordinator(InMemoryPendingLocalDeletionRepository()) { executions++ }
        val action = async { coordinator.confirmAndDelete("target", PendingLocalDeletionOperation.events(setOf("e1"))) }
        yield()
        val oldId = coordinator.confirmation.value!!.id
        action.cancelAndJoin()
        assertNull(coordinator.confirmation.value)
        coordinator.confirm(oldId)
        assertEquals(0, executions)
    }

    private fun CoroutineScope.coordinator(repository: PendingLocalDeletionRepository, execute: suspend (PendingLocalDeletionOperation) -> Unit) =
        PendingLocalDeletionCoordinator(this, repository, PendingLocalDeletionExecutor(execute), completionDispatcher = Dispatchers.Unconfined)
}
