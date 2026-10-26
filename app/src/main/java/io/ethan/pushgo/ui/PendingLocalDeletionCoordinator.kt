package io.ethan.pushgo.ui

import io.ethan.pushgo.data.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Scheduling is reserved for accepted legacy rows and durable remote channel removal. */
interface PendingLocalDeletionDrainScheduler {
    fun scheduleImmediate()
    fun scheduleAt(epochMillis: Long)
    object None : PendingLocalDeletionDrainScheduler {
        override fun scheduleImmediate() = Unit
        override fun scheduleAt(epochMillis: Long) = Unit
    }
}

/** A foreground confirmation owns no persisted state. Ordinary local deletes transact directly. */
class PendingLocalDeletionCoordinator(
    private val appScope: CoroutineScope,
    private val repository: PendingLocalDeletionRepository = InMemoryPendingLocalDeletionRepository(),
    private val operationExecutor: PendingLocalDeletionExecutor,
    private val drainScheduler: PendingLocalDeletionDrainScheduler = PendingLocalDeletionDrainScheduler.None,
    private val wallClockEpochMillis: () -> Long = System::currentTimeMillis,
    private val completionDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    data class Confirmation(val id: Long, val summary: String)
    private data class Request(val confirmation: Confirmation, val answer: CompletableDeferred<Boolean>)
    private val requestLock = Any()
    private val sequence = AtomicLong()
    private var request: Request? = null
    private val executing = mutableSetOf<PendingLocalDeletionOperation>()
    private val _confirmation = MutableStateFlow<Confirmation?>(null)
    val confirmation: StateFlow<Confirmation?> = _confirmation.asStateFlow()
    private val _failures = MutableStateFlow<List<PendingLocalDeletionRecord>>(emptyList())
    val failures: StateFlow<List<PendingLocalDeletionRecord>> = _failures.asStateFlow()
    private val drainMutex = Mutex()
    private var started = false
    private var committedCleanupCursor = 0L

    fun start() = synchronized(requestLock) {
        if (started) return@synchronized
        started = true
        appScope.launch { repository.observeFailures().collect { _failures.value = it } }
        appScope.launch { drainRecoverable() }
    }

    /** Capture the production deletion footprint before presenting; revalidate at commit. */
    suspend fun confirmAndDelete(
        summary: String,
        operation: PendingLocalDeletionOperation,
        onCompletion: (Result<Unit>) -> Unit = {},
    ): Boolean {
        require(operation.kind in LOCAL_KINDS && operation.targetIds.size == 1)
        return confirmAndExecute(summary, operation, null, onCompletion)
    }

    suspend fun reconfirmLegacy(id: Long): Boolean {
        val row = repository.findUnconfirmed(id) ?: return false
        if (row.state != PendingLocalDeletionState.NEEDS_CONFIRMATION) return false
        return confirmAndExecute(row.summary, row.operation, row) { it.getOrThrow() }
    }

    suspend fun cancelLegacy(id: Long): Boolean = repository.cancelUnconfirmed(id)

    private suspend fun confirmAndExecute(
        summary: String,
        operation: PendingLocalDeletionOperation,
        legacy: PendingLocalDeletionRecord?,
        onCompletion: (Result<Unit>) -> Unit,
    ): Boolean {
        val snapshot = operation.copy(targetIds = operation.targetIds.toSet())
        val pending = synchronized(requestLock) {
            if (request != null || snapshot in executing) return false
            Request(Confirmation(sequence.incrementAndGet(), summary.trim()), CompletableDeferred()).also {
                request = it
            }
        }
        try {
            val identity = try {
                operationExecutor.captureConfirmation(snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                withContext(completionDispatcher) { onCompletion(Result.failure(error)) }
                return false
            }
            synchronized(requestLock) {
                _confirmation.value = pending.confirmation.copy(
                    summary = if (identity == null) pending.confirmation.summary
                        else (if (legacy != null) identity.recoverySummary else identity.displayTitle)?.trim().orEmpty(),
                )
            }
            if (!pending.answer.await()) return false
            synchronized(requestLock) { executing.add(snapshot) }
            val result = try {
                withContext(NonCancellable) {
                    if (legacy == null) operationExecutor.executeConfirmed(snapshot, identity)
                    else {
                        operationExecutor.executeReconfirmed(legacy, identity)
                        if (snapshot.kind == PendingLocalDeletionKind.CHANNEL) {
                            drainScheduler.scheduleImmediate()
                            drainRecoverable()
                        }
                    }
                }
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            withContext(completionDispatcher) { onCompletion(result) }
            return result.isSuccess
        } finally {
            synchronized(requestLock) {
                executing.remove(snapshot)
                if (request === pending) {
                    request = null
                    _confirmation.value = null
                }
            }
        }
    }

    fun confirm(id: Long) = answer(id, true)
    fun cancel(id: Long) = answer(id, false)
    private fun answer(id: Long, accepted: Boolean) = synchronized(requestLock) {
        val current = request ?: return@synchronized
        if (current.confirmation.id != id || current.answer.isCompleted) return@synchronized
        // Retain the request lock until execution finishes so rapid duplicate clicks cannot
        // open another confirmation in the gap before the confirmed coroutine resumes.
        _confirmation.value = null
        current.answer.complete(accepted)
    }

    /** The channel dialog already confirmed its captured origin/version/mode. */
    suspend fun deleteConfirmedChannel(
        summary: String,
        operation: PendingLocalDeletionOperation,
        onCompletion: (Result<Unit>) -> Unit = {},
    ) {
        require(operation.kind == PendingLocalDeletionKind.CHANNEL)
        synchronized(requestLock) { if (!executing.add(operation)) return }
        try {
            val row = repository.loadActive().firstOrNull { it.operation == operation }
                ?: repository.enqueue(summary, operation, wallClockEpochMillis(), undoWindowMillis = 0L, confirmationProven = true)
            drainScheduler.scheduleImmediate()
            drainRecoverable()
            val active = repository.loadActive().firstOrNull { it.id == row.id }
            val failed = repository.loadFailures().firstOrNull { it.id == row.id }
            val result = when {
                failed != null -> Result.failure<Unit>(IllegalStateException(failed.lastError ?: "Channel removal failed"))
                active != null -> Result.failure<Unit>(IllegalStateException(active.lastError ?: "Channel removal is awaiting recovery"))
                else -> Result.success(Unit)
            }
            withContext(completionDispatcher) { onCompletion(result) }
        } finally {
            synchronized(requestLock) { executing.remove(operation) }
        }
    }

    /** Only explicitly confirmed durable operations can retry without another decision. */
    suspend fun retryFailed(id: Long) {
        if (repository.retryFailed(id, wallClockEpochMillis())) {
            drainScheduler.scheduleImmediate()
            drainRecoverable()
        }
    }

    suspend fun drainRecoverable(): Boolean = drainMutex.withLock {
        withContext(NonCancellable) { repository.recoverInterruptedClaims(wallClockEpochMillis()) }
        while (true) {
            val now = wallClockEpochMillis()
            val rows = repository.loadActive().filter { it.confirmationProven && it.state in setOf(PendingLocalDeletionState.PENDING, PendingLocalDeletionState.COMMITTING) }
            val due = rows.firstOrNull {
                it.state == PendingLocalDeletionState.PENDING && it.nextAttemptAtEpochMillis <= now
            }
            if (due == null) {
                // The bounded query selects matching scope/version tombstone candidates.
                // The executor decodes/checks their exact target before any cleanup; unknown
                // data never enters remote deletion or delays ordinary queue progress.
                val committed = repository.loadCommittedChannelCleanup(committedCleanupCursor, 32)
                committedCleanupCursor = committed.lastOrNull()?.id ?: 0L
                for (legacy in committed) operationExecutor.recoverCommittedCleanup(legacy)
                rows.minOfOrNull { it.nextAttemptAtEpochMillis }?.let(drainScheduler::scheduleAt)
                return@withLock rows.isNotEmpty()
            }
            val claim = repository.claim(due.id, now, force = true) ?: continue
            var error: Throwable? = null
            try {
                operationExecutor.execute(claim.operation)
                withContext(NonCancellable) {
                    check(repository.completeClaimed(claim.id)) { "Deletion finalization failed" }
                }
            } catch (caught: Throwable) {
                error = caught
            }
            if (error != null) {
                withContext(NonCancellable) {
                    val failedAt = wallClockEpochMillis()
                    val terminal = error is PermanentPendingLocalDeletionException ||
                        claim.operation.kind != PendingLocalDeletionKind.CHANNEL
                    if (terminal) {
                        check(repository.failClaimed(claim.id, failedAt, error.message ?: error.javaClass.simpleName))
                    } else {
                        val retryAt = failedAt + RETRY_DELAY_MILLIS
                        check(repository.retryClaimed(claim.id, failedAt, retryAt, error.message ?: error.javaClass.simpleName))
                        drainScheduler.scheduleAt(retryAt)
                    }
                }
                if (error is CancellationException) throw error
            }
        }
        @Suppress("UNREACHABLE_CODE") false
    }

    companion object {
        private val LOCAL_KINDS = setOf(PendingLocalDeletionKind.MESSAGES, PendingLocalDeletionKind.EVENTS, PendingLocalDeletionKind.THINGS)
        const val RETRY_DELAY_MILLIS = 5_000L
    }
}
