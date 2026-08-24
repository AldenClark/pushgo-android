package io.ethan.pushgo.ui

import android.os.SystemClock
import io.ethan.pushgo.data.InMemoryPendingLocalDeletionRepository
import io.ethan.pushgo.data.PendingLocalDeletionExecutor
import io.ethan.pushgo.data.PendingLocalDeletionKind
import io.ethan.pushgo.data.PendingLocalDeletionOperation
import io.ethan.pushgo.data.PendingLocalDeletionRecord
import io.ethan.pushgo.data.PendingLocalDeletionRepository
import io.ethan.pushgo.data.PendingLocalDeletionState
import io.ethan.pushgo.data.PermanentPendingLocalDeletionException
import io.ethan.pushgo.util.SilentSink
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface PendingLocalDeletionDrainScheduler {
    fun scheduleImmediate()
    fun scheduleAt(epochMillis: Long)

    object None : PendingLocalDeletionDrainScheduler {
        override fun scheduleImmediate() = Unit
        override fun scheduleAt(epochMillis: Long) = Unit
    }
}

class PendingLocalDeletionCoordinator(
    private val appScope: CoroutineScope,
    private val repository: PendingLocalDeletionRepository = InMemoryPendingLocalDeletionRepository(),
    private val operationExecutor: PendingLocalDeletionExecutor? = null,
    private val drainScheduler: PendingLocalDeletionDrainScheduler = PendingLocalDeletionDrainScheduler.None,
    private val countdownMillis: Long = DEFAULT_COUNTDOWN_MILLIS,
    private val wallClockEpochMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val completionDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    data class Scope(
        val messageIds: Set<String> = emptySet(),
        val eventIds: Set<String> = emptySet(),
        val thingIds: Set<String> = emptySet(),
        val channelIds: Set<String> = emptySet(),
    ) {
        fun suppressesMessage(id: String, channelId: String?): Boolean {
            val normalizedId = id.trim()
            return (normalizedId.isNotEmpty() && normalizedId in messageIds) || suppressesChannel(channelId)
        }

        fun suppressesMessageId(id: String): Boolean {
            val normalizedId = id.trim()
            return normalizedId.isNotEmpty() && normalizedId in messageIds
        }

        fun suppressesEvent(id: String, channelId: String?): Boolean {
            val normalizedId = id.trim()
            return (normalizedId.isNotEmpty() && normalizedId in eventIds) || suppressesChannel(channelId)
        }

        fun suppressesThing(id: String, channelId: String?): Boolean {
            val normalizedId = id.trim()
            return (normalizedId.isNotEmpty() && normalizedId in thingIds) || suppressesChannel(channelId)
        }

        fun suppressesChannel(channelId: String?): Boolean {
            val normalizedChannelId = channelId?.trim().orEmpty()
            return normalizedChannelId.isNotEmpty() && normalizedChannelId in channelIds
        }
    }

    data class PendingDeletion(
        val id: Long,
        val summary: String,
        val scope: Scope,
        val deadlineElapsedRealtimeMillis: Long,
        val frozenRemainingMillis: Long = 0L,
        val isCountdownActive: Boolean = true,
    ) {
        fun remainingMillis(nowElapsedRealtimeMillis: Long): Long = if (isCountdownActive) {
            (deadlineElapsedRealtimeMillis - nowElapsedRealtimeMillis).coerceAtLeast(0L)
        } else {
            frozenRemainingMillis.coerceAtLeast(0L)
        }
    }

    private data class RuntimeCallbacks(
        val compatibilityCommit: (suspend () -> Unit)?,
        val completion: (Result<Unit>) -> Unit,
    )

    private val stateMutex = Mutex()
    private val drainMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val initialization = CompletableDeferred<Unit>()
    private val runtimeCallbacks = mutableMapOf<Long, RuntimeCallbacks>()
    private val terminalIds = linkedSetOf<Long>()
    private val _pendingDeletion = MutableStateFlow<PendingDeletion?>(null)
    private val _effectiveScope = MutableStateFlow(Scope())
    private var countdownJob: Job? = null
    private var interactionActive = true
    private var latestInteractionGeneration = Long.MIN_VALUE

    val pendingDeletion: StateFlow<PendingDeletion?> = _pendingDeletion.asStateFlow()
    val effectiveScope: StateFlow<Scope> = _effectiveScope.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            try {
                repository.recoverInterruptedClaims(wallClockEpochMillis())
                initialization.complete(Unit)
            } catch (error: Throwable) {
                initialization.completeExceptionally(error)
                throw error
            }
            repository.observeActive().collect(::publishRecords)
        }
    }

    suspend fun schedule(
        summary: String,
        operation: PendingLocalDeletionOperation,
        compatibilityCommit: (suspend () -> Unit)? = null,
        onCompletion: (Result<Unit>) -> Unit = {},
    ) {
        start()
        initialization.await()
        val record = repository.enqueue(
            summary = summary.trim(),
            operation = operation,
            requestedAtEpochMillis = wallClockEpochMillis(),
            undoWindowMillis = countdownMillis,
        )
        stateMutex.withLock {
            runtimeCallbacks[record.id] = RuntimeCallbacks(compatibilityCommit, onCompletion)
        }
        // WorkManager is best-effort here; its periodic safety net closes the process-death
        // window between this committed Room insert and the exact one-time registration.
        drainScheduler.scheduleAt(record.eligibleAtEpochMillis())
        publishRecords(repository.loadActive())
    }

    /** Compatibility adapter. The executable callback is never serialized. */
    suspend fun schedule(
        summary: String,
        scope: Scope,
        onCommit: suspend () -> Unit,
        onCompletion: (Result<Unit>) -> Unit = {},
    ) {
        schedule(
            summary = summary,
            operation = operationFromScope(scope),
            compatibilityCommit = onCommit,
            onCompletion = onCompletion,
        )
    }

    /** Backgrounding drains immediately instead of indefinitely freezing an undo window. */
    suspend fun setInteractionActive(active: Boolean, generation: Long? = null) {
        start()
        initialization.await()
        stateMutex.withLock {
            if (generation != null) {
                if (generation <= latestInteractionGeneration) return@withLock
                latestInteractionGeneration = generation
            }
            interactionActive = active
        }
        if (active) {
            publishRecords(repository.loadActive())
        } else {
            drainScheduler.scheduleImmediate()
            drainRecoverable(force = true)
        }
    }

    suspend fun undoCurrent() {
        start()
        initialization.await()
        val id = _pendingDeletion.value?.id ?: return
        if (repository.cancelPending(id)) {
            stateMutex.withLock {
                runtimeCallbacks.remove(id)
                markTerminalLocked(id)
            }
            publishRecords(repository.loadActive())
        }
    }

    suspend fun commitCurrentIfNeeded() {
        drainRecoverable(force = true)
    }

    /** Startup/worker entry point. Returns true while recoverable work remains. */
    suspend fun drainRecoverable(force: Boolean = false): Boolean {
        start()
        initialization.await()
        return drainMutex.withLock {
            // A previous drain can be interrupted after the external/local effect has completed
            // but before its claim is finalized. Recovery must not depend on process restart.
            withContext(NonCancellable) {
                repository.recoverInterruptedClaims(wallClockEpochMillis())
            }
            while (true) {
                val rows = repository.loadActive()
                if (rows.isEmpty()) {
                    publishRecords(emptyList())
                    return@withLock false
                }
                val now = wallClockEpochMillis()
                var claim: PendingLocalDeletionRecord? = null
                for (record in rows) {
                    if (record.state != PendingLocalDeletionState.PENDING) continue
                    if (record.nextAttemptAtEpochMillis > now) continue
                    if (!force && record.attemptCount == 0 && record.undoDeadlineEpochMillis > now) continue
                    claim = repository.claim(record.id, now, force)
                    if (claim != null) break
                }
                if (claim == null) {
                    val currentRows = repository.loadActive()
                    publishRecords(currentRows)
                    scheduleNextDrain(currentRows, now)
                    return@withLock currentRows.isNotEmpty()
                }
                publishRecords(repository.loadActive())
                val result = executeClaim(claim)
                if (result.isSuccess) {
                    val finalizationError = withContext(NonCancellable) {
                        runCatching {
                            check(repository.completeClaimed(claim.id)) {
                                "Claimed deletion ${claim.id} was not finalized"
                            }
                        }.exceptionOrNull()
                    }
                    if (finalizationError != null) {
                        val retryScheduled = withContext(NonCancellable) {
                            recoverFinalizationFailure(claim, finalizationError)
                        }
                        if (retryScheduled) {
                            if (finalizationError is CancellationException) throw finalizationError
                            continue
                        }
                        continue
                    }
                    stateMutex.withLock { markTerminalLocked(claim.id) }
                    completeRuntimeCallback(claim.id, result, removeCompatibilityCommit = true)
                } else {
                    val error = result.exceptionOrNull() ?: IllegalStateException("Deletion failed")
                    if (error is PermanentPendingLocalDeletionException) {
                        withContext(NonCancellable) {
                            repository.failClaimed(
                                id = claim.id,
                                nowEpochMillis = wallClockEpochMillis(),
                                lastError = error.toPersistedError(),
                            )
                        }
                        stateMutex.withLock { markTerminalLocked(claim.id) }
                        completeRuntimeCallback(claim.id, result, removeCompatibilityCommit = true)
                        publishRecords(repository.loadActive())
                        continue
                    }
                    val failedAt = wallClockEpochMillis()
                    val retryAt = failedAt + retryDelayMillis(claim.attemptCount)
                    withContext(NonCancellable) {
                        repository.retryClaimed(
                            id = claim.id,
                            nowEpochMillis = failedAt,
                            nextAttemptAtEpochMillis = retryAt,
                            lastError = error.toPersistedError(),
                        )
                    }
                    completeRuntimeCallback(claim.id, result, removeCompatibilityCommit = false)
                    drainScheduler.scheduleAt(retryAt)
                    publishRecords(repository.loadActive())
                    if (error is CancellationException) throw error
                    // A backed-off row must not head-of-line block other due operations.
                    continue
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    }

    private suspend fun recoverFinalizationFailure(
        claim: PendingLocalDeletionRecord,
        error: Throwable,
    ): Boolean {
        val failedAt = wallClockEpochMillis()
        val retryAt = failedAt + retryDelayMillis(claim.attemptCount)
        val recovered = withContext(NonCancellable) {
            runCatching {
                repository.retryClaimed(
                    id = claim.id,
                    nowEpochMillis = failedAt,
                    nextAttemptAtEpochMillis = retryAt,
                    lastError = "Finalization failed after deletion effect: ${error.toPersistedError()}",
                )
            }.getOrDefault(false) || runCatching {
                repository.recoverInterruptedClaims(failedAt) > 0
            }.getOrDefault(false)
        }
        val rows = repository.loadActive()
        if (rows.none { it.id == claim.id }) {
            stateMutex.withLock { markTerminalLocked(claim.id) }
            completeRuntimeCallback(
                id = claim.id,
                result = Result.success(Unit),
                removeCompatibilityCommit = true,
            )
            publishRecords(rows)
            return false
        }
        completeRuntimeCallback(
            id = claim.id,
            result = Result.failure(error),
            removeCompatibilityCommit = false,
        )
        publishRecords(rows)
        if (recovered) {
            val active = rows.firstOrNull { it.id == claim.id }
            val dueAt = active?.let {
                maxOf(it.eligibleAtEpochMillis(), failedAt)
            } ?: failedAt
            drainScheduler.scheduleAt(dueAt)
        } else {
            // If recovery itself could not reach storage, let WorkManager invoke a fresh drain;
            // its entry recovery above will reclaim any row still left COMMITTING.
            drainScheduler.scheduleImmediate()
        }
        return true
    }

    private suspend fun executeClaim(record: PendingLocalDeletionRecord): Result<Unit> = runCatching {
        val callbacks = stateMutex.withLock { runtimeCallbacks[record.id] }
        if (operationExecutor != null && record.operation.kind != PendingLocalDeletionKind.RUNTIME_COMPATIBILITY) {
            operationExecutor.execute(record.operation)
        } else {
            val compatibilityCommit = callbacks?.compatibilityCommit
                ?: error("No durable executor is registered for ${record.operation.kind}")
            compatibilityCommit()
        }
    }

    private suspend fun completeRuntimeCallback(
        id: Long,
        result: Result<Unit>,
        removeCompatibilityCommit: Boolean,
    ) {
        val callback = stateMutex.withLock {
            val current = runtimeCallbacks[id] ?: return@withLock null
            if (removeCompatibilityCommit) {
                runtimeCallbacks.remove(id)
            } else {
                runtimeCallbacks[id] = current.copy(completion = {})
            }
            current.completion
        } ?: return
        runCatching {
            withContext(completionDispatcher) { callback(result) }
        }.onFailure { error ->
            SilentSink.w(TAG, "completion callback failed for pending local deletion id=$id", error)
        }
    }

    private suspend fun publishRecords(records: List<PendingLocalDeletionRecord>) {
        stateMutex.withLock {
            val visibleRecords = records.filterNot { it.id in terminalIds }
            countdownJob?.cancel()
            countdownJob = null
            val nowEpoch = wallClockEpochMillis()
            val nowElapsed = elapsedRealtimeMillis()
            _effectiveScope.value = visibleRecords.fold(Scope()) { accumulated, record ->
                accumulated + record.operation.toScope()
            }
            val undoable = visibleRecords.firstOrNull {
                it.isUndoable && it.undoDeadlineEpochMillis > nowEpoch
            }
            _pendingDeletion.value = undoable?.let { record ->
                val remaining = (record.undoDeadlineEpochMillis - nowEpoch).coerceAtLeast(0L)
                PendingDeletion(
                    id = record.id,
                    summary = record.summary,
                    scope = record.operation.toScope(),
                    deadlineElapsedRealtimeMillis = nowElapsed + remaining,
                    frozenRemainingMillis = remaining,
                    isCountdownActive = interactionActive,
                )
            }
            if (interactionActive) {
                val dueAt = visibleRecords
                    .asSequence()
                    .filter { it.state == PendingLocalDeletionState.PENDING }
                    .map { it.eligibleAtEpochMillis() }
                    .minOrNull()
                if (dueAt != null) {
                    countdownJob = appScope.launch {
                        delay((dueAt - nowEpoch).coerceAtLeast(0L))
                        val currentJob = currentCoroutineContext()[Job]
                        stateMutex.withLock {
                            if (countdownJob === currentJob) countdownJob = null
                        }
                        drainRecoverable(force = false)
                    }
                    drainScheduler.scheduleAt(dueAt)
                }
            }
        }
    }

    private fun scheduleNextDrain(records: List<PendingLocalDeletionRecord>, nowEpochMillis: Long) {
        val next = records
            .asSequence()
            .filter { it.state == PendingLocalDeletionState.PENDING }
            .map { it.eligibleAtEpochMillis() }
            .minOrNull()
            ?: return
        drainScheduler.scheduleAt(maxOf(next, nowEpochMillis))
    }

    private fun operationFromScope(scope: Scope): PendingLocalDeletionOperation {
        val populatedKinds = listOf(
            scope.messageIds.isNotEmpty(),
            scope.eventIds.isNotEmpty(),
            scope.thingIds.isNotEmpty(),
            scope.channelIds.isNotEmpty(),
        ).count { it }
        if (populatedKinds != 1) {
            return PendingLocalDeletionOperation.runtimeCompatibility(
                scope.messageIds + scope.eventIds + scope.thingIds + scope.channelIds
            )
        }
        return when {
            scope.messageIds.isNotEmpty() -> PendingLocalDeletionOperation.messages(scope.messageIds)
            scope.eventIds.isNotEmpty() -> PendingLocalDeletionOperation.events(scope.eventIds)
            scope.thingIds.isNotEmpty() -> PendingLocalDeletionOperation.things(scope.thingIds)
            else -> PendingLocalDeletionOperation.runtimeCompatibility(scope.channelIds)
        }
    }

    private fun retryDelayMillis(attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, 10)
        return (RETRY_BASE_MILLIS * (1L shl exponent)).coerceAtMost(RETRY_MAX_MILLIS)
    }

    private fun markTerminalLocked(id: Long) {
        terminalIds += id
        while (terminalIds.size > MAX_RECENT_TERMINAL_IDS) {
            val oldest = terminalIds.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private fun Throwable.toPersistedError(): String = buildString {
        append(this@toPersistedError::class.java.simpleName)
        message?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append(": ")
            append(it)
        }
    }

    private operator fun Scope.plus(other: Scope) = Scope(
        messageIds = messageIds + other.messageIds,
        eventIds = eventIds + other.eventIds,
        thingIds = thingIds + other.thingIds,
        channelIds = channelIds + other.channelIds,
    )

    private fun PendingLocalDeletionOperation.toScope(): Scope = when (kind) {
        PendingLocalDeletionKind.MESSAGES -> Scope(messageIds = targetIds)
        PendingLocalDeletionKind.EVENTS -> Scope(eventIds = targetIds)
        PendingLocalDeletionKind.THINGS -> Scope(thingIds = targetIds)
        PendingLocalDeletionKind.CHANNEL -> Scope(channelIds = targetIds)
        PendingLocalDeletionKind.RUNTIME_COMPATIBILITY -> Scope()
    }

    companion object {
        const val DEFAULT_COUNTDOWN_MILLIS: Long = 5_000L
        private const val RETRY_BASE_MILLIS = 1_000L
        private const val RETRY_MAX_MILLIS = 15L * 60L * 1_000L
        private const val MAX_RECENT_TERMINAL_IDS = 256
        private const val TAG = "PendingLocalDeletion"
    }
}
