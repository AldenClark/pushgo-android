package io.ethan.pushgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MessageSearchViewModel(
    private val repository: MessageRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val unreadOnly = MutableStateFlow(false)
    private val locallySuppressedMessageIds = MutableStateFlow<Set<String>>(emptySet())

    val queryState: StateFlow<String> = query
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    val results: StateFlow<List<PushMessage>> = combine(
        query.debounce(200),
        unreadOnly,
        locallySuppressedMessageIds,
    ) { rawQuery, currentUnreadOnly, suppressedIds ->
        Triple(rawQuery, currentUnreadOnly, suppressedIds)
    }
        .flatMapLatest { (rawQuery, currentUnreadOnly, suppressedIds) ->
            repository.searchMessages(rawQuery, currentUnreadOnly, excludedIds = suppressedIds)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun updateQuery(value: String) {
        query.value = value
    }

    fun setUnreadOnlyFilter(enabled: Boolean) {
        if (unreadOnly.value == enabled) return
        unreadOnly.value = enabled
    }

    fun setLocallySuppressedMessageIds(messageIds: Set<String>) {
        val normalized = messageIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        if (locallySuppressedMessageIds.value == normalized) return
        locallySuppressedMessageIds.value = normalized
    }
}
