package io.ethan.pushgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.model.MessageListItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MessageSearchViewModel(
    private val repository: MessageRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val unreadOnly = MutableStateFlow(false)
    private val locallySuppressedMessageIds = MutableStateFlow<Set<String>>(emptySet())

    val queryState: StateFlow<String> = query

    val results: Flow<PagingData<MessageListItem>> = combine(
        query.debounce(200),
        unreadOnly,
        locallySuppressedMessageIds,
    ) { rawQuery, currentUnreadOnly, suppressedIds ->
        Triple(rawQuery, currentUnreadOnly, suppressedIds)
    }
        .flatMapLatest { (rawQuery, currentUnreadOnly, suppressedIds) ->
            repository.searchMessages(rawQuery, currentUnreadOnly, excludedIds = suppressedIds)
        }
        .cachedIn(viewModelScope)

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
