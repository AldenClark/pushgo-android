package io.ethan.pushgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.data.model.PushMessage
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

class MessageDetailViewModel(
    private val repository: MessageRepository,
    private val stateCoordinator: MessageStateCoordinator,
    private val messageId: String,
) : ViewModel() {
    companion object {
        private const val PERF_TAG = "PushGoPerf"
        private const val DETAIL_CACHE_CAPACITY = 160
        private const val DETAIL_CACHE_TTL_MS = 15_000L

        private data class CacheEntry(
            val message: PushMessage?,
            val cachedAtMs: Long,
        )

        private val detailCache = LinkedHashMap<String, CacheEntry>(DETAIL_CACHE_CAPACITY, 0.75f, true)
        private val inFlightLoads = mutableMapOf<String, CompletableDeferred<PushMessage?>>()
        private val cacheMutex = Mutex()

        private suspend fun getCachedOrLoad(
            id: String,
            loader: suspend () -> PushMessage?,
        ): Pair<PushMessage?, String> {
            val now = SystemClock.elapsedRealtime()
            var shouldLoad = false
            val deferred: CompletableDeferred<PushMessage?>
            cacheMutex.withLock {
                val cached = detailCache[id]
                if (cached != null && now - cached.cachedAtMs <= DETAIL_CACHE_TTL_MS) {
                    return Pair(cached.message, "cache")
                }

                val existingLoad = inFlightLoads[id]
                if (existingLoad != null) {
                    deferred = existingLoad
                } else {
                    shouldLoad = true
                    val created = CompletableDeferred<PushMessage?>()
                    inFlightLoads[id] = created
                    deferred = created
                }
            }

            if (shouldLoad) {
                val loaded = runCatching { loader() }.getOrNull()
                cacheMutex.withLock {
                    detailCache[id] = CacheEntry(message = loaded, cachedAtMs = SystemClock.elapsedRealtime())
                    trimCacheLocked()
                    inFlightLoads.remove(id)
                }
                deferred.complete(loaded)
                return Pair(loaded, "storage")
            }

            return Pair(deferred.await(), "in_flight")
        }

        private fun trimCacheLocked() {
            while (detailCache.size > DETAIL_CACHE_CAPACITY) {
                val eldestKey = detailCache.entries.firstOrNull()?.key ?: break
                detailCache.remove(eldestKey)
            }
        }

        private suspend fun storeCachedMessage(id: String, message: PushMessage?) {
            cacheMutex.withLock {
                detailCache[id] = CacheEntry(message = message, cachedAtMs = SystemClock.elapsedRealtime())
                trimCacheLocked()
            }
        }

        private suspend fun removeCachedMessage(id: String) {
            cacheMutex.withLock {
                detailCache.remove(id)
                inFlightLoads.remove(id)?.cancel()
            }
        }
    }

    private val _message = MutableStateFlow<PushMessage?>(null)
    val message: StateFlow<PushMessage?> = _message

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            val startedAtMs = SystemClock.elapsedRealtime()
            val (loaded, source) = getCachedOrLoad(messageId) {
                repository.getById(messageId)
            }
            val loadedAtMs = SystemClock.elapsedRealtime()
            if (loaded != null) {
                stateCoordinator.markRead(messageId)
                val resolved = if (loaded.isRead) loaded else loaded.copy(isRead = true)
                _message.value = resolved
                storeCachedMessage(messageId, resolved)
            } else {
                _message.value = null
                removeCachedMessage(messageId)
            }
            _isLoading.value = false
            val finishedAtMs = SystemClock.elapsedRealtime()
            io.ethan.pushgo.util.SilentSink.i(
                PERF_TAG,
                "android_detail_load message_id=$messageId source=$source db_ms=${loadedAtMs - startedAtMs} total_ms=${finishedAtMs - startedAtMs}"
            )
        }
    }

    fun delete(): Job {
        return viewModelScope.launch {
            stateCoordinator.deleteMessage(messageId)
            removeCachedMessage(messageId)
        }
    }

    fun markRead(): Job? {
        val current = _message.value ?: return null
        if (current.isRead) return null
        return viewModelScope.launch {
            stateCoordinator.markRead(messageId)
            val updated = current.copy(isRead = true)
            _message.value = updated
            storeCachedMessage(messageId, updated)
        }
    }
}
