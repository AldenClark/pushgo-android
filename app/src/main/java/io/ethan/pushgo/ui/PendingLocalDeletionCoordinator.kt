package io.ethan.pushgo.ui

import android.os.SystemClock
import io.ethan.pushgo.util.SilentSink
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PendingLocalDeletionCoordinator(
    private val appScope: CoroutineScope,
    private val countdownMillis: Long = DEFAULT_COUNTDOWN_MILLIS,
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
            if (normalizedId.isNotEmpty() && normalizedId in messageIds) {
                return true
            }
            return suppressesChannel(channelId)
        }

        fun suppressesMessageId(id: String): Boolean {
            val normalizedId = id.trim()
            return normalizedId.isNotEmpty() && normalizedId in messageIds
        }

        fun suppressesEvent(id: String, channelId: String?): Boolean {
            val normalizedId = id.trim()
            if (normalizedId.isNotEmpty() && normalizedId in eventIds) {
                return true
            }
            return suppressesChannel(channelId)
        }

        fun suppressesThing(id: String, channelId: String?): Boolean {
            val normalizedId = id.trim()
            if (normalizedId.isNotEmpty() && normalizedId in thingIds) {
                return true
            }
            return suppressesChannel(channelId)
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
        fun remainingMillis(nowElapsedRealtimeMillis: Long): Long {
            return if (isCountdownActive) {
                (deadlineElapsedRealtimeMillis - nowElapsedRealtimeMillis).coerceAtLeast(0L)
            } else {
                frozenRemainingMillis.coerceAtLeast(0L)
            }
        }
    }

    private data class Entry(
        val id: Long,
        val summary: String,
        val scope: Scope,
        val onCommit: suspend () -> Unit,
        val onCompletion: (Result<Unit>) -> Unit,
        var remainingMillis: Long,
        var deadlineElapsedRealtimeMillis: Long? = null,
    )

    private val mutex = Mutex()
    private val nextId = AtomicLong(1L)
    private val _pendingDeletion = MutableStateFlow<PendingDeletion?>(null)
    private val _effectiveScope = MutableStateFlow(Scope())

    val pendingDeletion: StateFlow<PendingDeletion?> = _pendingDeletion.asStateFlow()
    val effectiveScope: StateFlow<Scope> = _effectiveScope.asStateFlow()

    private val queuedEntries = mutableListOf<Entry>()
    private val committingScopes = mutableMapOf<Long, Scope>()
    private var countdownJob: Job? = null
    private var interactionActive = true
    private var latestInteractionGeneration = Long.MIN_VALUE

    suspend fun schedule(
        summary: String,
        scope: Scope,
        onCommit: suspend () -> Unit,
        onCompletion: (Result<Unit>) -> Unit = {},
    ) {
        mutex.withLock {
            queuedEntries += Entry(
                id = nextId.getAndIncrement(),
                summary = summary.trim(),
                scope = scope,
                onCommit = onCommit,
                onCompletion = onCompletion,
                remainingMillis = countdownMillis.coerceAtLeast(0L),
            )
            if (queuedEntries.size == 1) {
                activateCurrentEntryLocked()
            } else {
                publishEffectiveScopeLocked()
            }
        }
    }

    suspend fun setInteractionActive(active: Boolean, generation: Long? = null) {
        mutex.withLock {
            if (generation != null) {
                if (generation <= latestInteractionGeneration) return@withLock
                latestInteractionGeneration = generation
            }
            if (interactionActive == active) return@withLock
            interactionActive = active
            if (active) {
                activateCurrentEntryLocked()
            } else {
                pauseCurrentEntryLocked()
            }
        }
    }

    suspend fun undoCurrent() {
        mutex.withLock {
            if (queuedEntries.isEmpty()) return@withLock
            countdownJob?.cancel()
            countdownJob = null
            queuedEntries.removeAt(0)
            activateCurrentEntryLocked()
        }
    }

    suspend fun commitCurrentIfNeeded() {
        val entry = mutex.withLock {
            val expectedId = queuedEntries.firstOrNull()?.id ?: return@withLock null
            claimCurrentEntryForCommitLocked(expectedId, cancelCountdownJob = true)
        }
        if (entry != null) {
            commit(entry)
        }
    }

    private suspend fun commitIfCurrent(pendingDeletionId: Long) {
        val entry = mutex.withLock {
            claimCurrentEntryForCommitLocked(pendingDeletionId, cancelCountdownJob = false)
        }
        if (entry != null) {
            commit(entry)
        }
    }

    private fun activateCurrentEntryLocked() {
        countdownJob?.cancel()
        countdownJob = null
        val entry = queuedEntries.firstOrNull()
        if (entry == null) {
            _pendingDeletion.value = null
            publishEffectiveScopeLocked()
            return
        }

        val now = elapsedRealtimeMillis()
        entry.deadlineElapsedRealtimeMillis = if (interactionActive) {
            now + entry.remainingMillis
        } else {
            null
        }
        publishPendingDeletionLocked(entry, now)
        publishEffectiveScopeLocked()
        if (!interactionActive) return

        val expectedId = entry.id
        val delayMillis = entry.remainingMillis
        countdownJob = appScope.launch {
            delay(delayMillis)
            commitIfCurrent(expectedId)
        }
    }

    private fun pauseCurrentEntryLocked() {
        countdownJob?.cancel()
        countdownJob = null
        val entry = queuedEntries.firstOrNull() ?: return
        val now = elapsedRealtimeMillis()
        entry.deadlineElapsedRealtimeMillis?.let { deadline ->
            entry.remainingMillis = (deadline - now).coerceAtLeast(0L)
        }
        entry.deadlineElapsedRealtimeMillis = null
        publishPendingDeletionLocked(entry, now)
    }

    private fun publishPendingDeletionLocked(entry: Entry, now: Long) {
        _pendingDeletion.value = PendingDeletion(
            id = entry.id,
            summary = entry.summary,
            scope = entry.scope,
            deadlineElapsedRealtimeMillis = entry.deadlineElapsedRealtimeMillis ?: now + entry.remainingMillis,
            frozenRemainingMillis = entry.remainingMillis,
            isCountdownActive = interactionActive,
        )
    }

    private fun claimCurrentEntryForCommitLocked(
        expectedId: Long,
        cancelCountdownJob: Boolean,
    ): Entry? {
        val entry = queuedEntries.firstOrNull()?.takeIf { it.id == expectedId } ?: return null
        if (cancelCountdownJob) {
            countdownJob?.cancel()
        }
        countdownJob = null
        queuedEntries.removeAt(0)
        committingScopes[entry.id] = entry.scope
        activateCurrentEntryLocked()
        return entry
    }

    private fun publishEffectiveScopeLocked() {
        val scopes = buildList {
            addAll(queuedEntries.map { it.scope })
            addAll(committingScopes.values)
        }
        _effectiveScope.value = Scope(
            messageIds = scopes.flatMapTo(mutableSetOf()) { it.messageIds },
            eventIds = scopes.flatMapTo(mutableSetOf()) { it.eventIds },
            thingIds = scopes.flatMapTo(mutableSetOf()) { it.thingIds },
            channelIds = scopes.flatMapTo(mutableSetOf()) { it.channelIds },
        )
    }

    private suspend fun commit(entry: Entry) {
        val result = try {
            entry.onCommit()
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        }
        mutex.withLock {
            committingScopes.remove(entry.id)
            publishEffectiveScopeLocked()
        }
        runCatching {
            withContext(completionDispatcher) {
                entry.onCompletion(result)
            }
        }.onFailure { error ->
            SilentSink.w(
                "PendingLocalDeletion",
                "completion callback failed for pending local deletion id=${entry.id}",
                error,
            )
        }
    }

    companion object {
        const val DEFAULT_COUNTDOWN_MILLIS: Long = 5_000L
    }
}
