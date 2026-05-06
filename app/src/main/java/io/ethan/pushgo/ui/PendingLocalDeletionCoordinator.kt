package io.ethan.pushgo.ui

import android.os.SystemClock
import io.ethan.pushgo.util.SilentSink
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PendingLocalDeletionCoordinator(
    private val appScope: CoroutineScope,
    private val countdownMillis: Long = DEFAULT_COUNTDOWN_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() },
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
    )

    private data class Entry(
        val pendingDeletion: PendingDeletion,
        val onCommit: suspend () -> Unit,
        val onCompletion: (Result<Unit>) -> Unit,
    )

    private val mutex = Mutex()
    private val nextId = AtomicLong(1L)
    private val _pendingDeletion = MutableStateFlow<PendingDeletion?>(null)

    val pendingDeletion: StateFlow<PendingDeletion?> = _pendingDeletion.asStateFlow()

    private var activeEntry: Entry? = null
    private var countdownJob: Job? = null

    suspend fun schedule(
        summary: String,
        scope: Scope,
        onCommit: suspend () -> Unit,
        onCompletion: (Result<Unit>) -> Unit = {},
    ) {
        commitCurrentIfNeeded()

        val pendingDeletion = PendingDeletion(
            id = nextId.getAndIncrement(),
            summary = summary.trim(),
            scope = scope,
            deadlineElapsedRealtimeMillis = elapsedRealtimeMillis() + countdownMillis,
        )
        val entry = Entry(
            pendingDeletion = pendingDeletion,
            onCommit = onCommit,
            onCompletion = onCompletion,
        )

        mutex.withLock {
            countdownJob?.cancel()
            activeEntry = entry
            _pendingDeletion.value = pendingDeletion
            countdownJob = appScope.launch {
                val remainingMillis = (pendingDeletion.deadlineElapsedRealtimeMillis - elapsedRealtimeMillis()).coerceAtLeast(0L)
                delay(remainingMillis)
                commitIfCurrent(pendingDeletion.id)
            }
        }
    }

    suspend fun undoCurrent() {
        mutex.withLock {
            discardActiveEntryLocked(cancelCountdown = true)
        }
    }

    suspend fun commitCurrentIfNeeded() {
        val entry = mutex.withLock {
            discardActiveEntryLocked(cancelCountdown = true)
        }
        if (entry != null) {
            commit(entry)
        }
    }

    private suspend fun commitIfCurrent(pendingDeletionId: Long) {
        val entry = mutex.withLock {
            val currentEntry = activeEntry
            if (currentEntry?.pendingDeletion?.id != pendingDeletionId) {
                null
            } else {
                discardActiveEntryLocked(cancelCountdown = false)
            }
        }
        if (entry != null) {
            commit(entry)
        }
    }

    private fun discardActiveEntryLocked(cancelCountdown: Boolean): Entry? {
        if (cancelCountdown) {
            countdownJob?.cancel()
        }
        countdownJob = null
        val entry = activeEntry
        activeEntry = null
        _pendingDeletion.value = null
        return entry
    }

    private suspend fun commit(entry: Entry) {
        val result = try {
            entry.onCommit()
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        }
        runCatching {
            entry.onCompletion(result)
        }.onFailure { error ->
            SilentSink.w(
                "PendingLocalDeletion",
                "completion callback failed for pending local deletion id=${entry.pendingDeletion.id}",
                error,
            )
        }
    }

    companion object {
        const val DEFAULT_COUNTDOWN_MILLIS: Long = 5_000L
    }
}
