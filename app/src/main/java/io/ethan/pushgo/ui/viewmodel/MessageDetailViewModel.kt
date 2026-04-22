package io.ethan.pushgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.MessageStateCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

        private data class CachedLoadResult(
            val message: PushMessage?,
            val source: String,
            val error: Throwable?,
        )

        private val detailCache = LinkedHashMap<String, CacheEntry>(DETAIL_CACHE_CAPACITY, 0.75f, true)
        private val inFlightLoads = mutableMapOf<String, CompletableDeferred<Result<PushMessage?>>>()
        private val cacheMutex = Mutex()

        private suspend fun getCachedOrLoad(
            id: String,
            loader: suspend () -> PushMessage?,
        ): CachedLoadResult {
            val now = SystemClock.elapsedRealtime()
            var shouldLoad = false
            val deferred: CompletableDeferred<Result<PushMessage?>>
            cacheMutex.withLock {
                val cached = detailCache[id]
                if (cached != null && now - cached.cachedAtMs <= DETAIL_CACHE_TTL_MS) {
                    return CachedLoadResult(
                        message = cached.message,
                        source = "cache",
                        error = null,
                    )
                }

                val existingLoad = inFlightLoads[id]
                if (existingLoad != null) {
                    deferred = existingLoad
                } else {
                    shouldLoad = true
                    val created = CompletableDeferred<Result<PushMessage?>>()
                    inFlightLoads[id] = created
                    deferred = created
                }
            }

            if (shouldLoad) {
                val loadedResult = runCatching { loader() }
                cacheMutex.withLock {
                    loadedResult.getOrNull()?.let { loaded ->
                        detailCache[id] = CacheEntry(
                            message = loaded,
                            cachedAtMs = SystemClock.elapsedRealtime(),
                        )
                        trimCacheLocked()
                    }
                    inFlightLoads.remove(id)
                }
                deferred.complete(loadedResult)
                return CachedLoadResult(
                    message = loadedResult.getOrNull(),
                    source = "storage",
                    error = loadedResult.exceptionOrNull(),
                )
            }

            val loadedResult = deferred.await()
            return CachedLoadResult(
                message = loadedResult.getOrNull(),
                source = "in_flight",
                error = loadedResult.exceptionOrNull(),
            )
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

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = null
            val startedAtMs = SystemClock.elapsedRealtime()
            val loadResult = getCachedOrLoad(messageId) {
                repository.getById(messageId)
            }
            val loadedAtMs = SystemClock.elapsedRealtime()
            val loaded = loadResult.message
            if (loaded != null) {
                stateCoordinator.markRead(messageId)
                val resolved = if (loaded.isRead) loaded else loaded.copy(isRead = true)
                _message.value = resolved
                storeCachedMessage(messageId, resolved)
            } else {
                _message.value = null
                removeCachedMessage(messageId)
                _loadError.value = loadResult.error?.message?.takeIf { it.isNotBlank() }
            }
            _isLoading.value = false
            val finishedAtMs = SystemClock.elapsedRealtime()
            io.ethan.pushgo.util.SilentSink.i(
                PERF_TAG,
                "android_detail_load message_id=$messageId source=${loadResult.source} db_ms=${loadedAtMs - startedAtMs} total_ms=${finishedAtMs - startedAtMs}"
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
