package io.ethan.pushgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.data.model.MessageFilter
import io.ethan.pushgo.data.model.MessageFacetOptionCount
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job

@OptIn(ExperimentalCoroutinesApi::class)
class MessageListViewModel(
    private val repository: MessageRepository,
    private val stateCoordinator: MessageStateCoordinator,
) : ViewModel() {
    private val filter = MutableStateFlow(MessageFilter())

    val messages: Flow<PagingData<PushMessage>> = filter
        .flatMapLatest { repository.observeMessages(it) }
        .cachedIn(viewModelScope)

    val filterState: StateFlow<MessageFilter> = filter
        .stateIn(viewModelScope, SharingStarted.Lazily, MessageFilter())

    val facetChannelCounts: StateFlow<List<MessageFacetOptionCount>> = repository.observeFacetChannelCounts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val facetTagCounts: StateFlow<List<MessageFacetOptionCount>> = repository.observeFacetTagCounts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setWithUrlOnly(withUrlOnly: Boolean) {
        filter.value = filter.value.copy(withUrlOnly = withUrlOnly)
    }

    fun toggleChannel(channel: String) {
        val normalized = channel.trim()
        if (normalized.isEmpty()) return
        val current = filter.value.channels
        val next = if (current.contains(normalized)) current - normalized else current + normalized
        filter.value = filter.value.copy(channels = next)
    }

    fun toggleTag(tag: String) {
        val normalized = tag.trim().lowercase().takeIf { it.isNotEmpty() } ?: return
        val current = filter.value.tags
        val next = if (current.contains(normalized)) current - normalized else current + normalized
        filter.value = filter.value.copy(tags = next)
    }

    fun toggleUnreadOnlyFilter() {
        filter.value = filter.value.copy(unreadOnly = !filter.value.unreadOnly)
    }

    fun markRead(messageId: String): Job {
        return viewModelScope.launch {
            stateCoordinator.markRead(messageId)
        }
    }

    fun markAllRead(): Job {
        return viewModelScope.launch {
            stateCoordinator.markAllRead()
        }
    }

    fun deleteMessage(messageId: String): Job {
        return viewModelScope.launch {
            stateCoordinator.deleteMessage(messageId)
        }
    }

    fun cleanupMessagesForCurrentFilter(): Job {
        val selectedChannels = filter.value.channels
        val channel = if (selectedChannels.size == 1) selectedChannels.first() else null
        return viewModelScope.launch {
            stateCoordinator.deleteMessagesByChannelRead(channel, null)
        }
    }
}
