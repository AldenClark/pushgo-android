package io.ethan.pushgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.data.model.MessageFilter
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.data.model.ReadFilter
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

    val channelCounts = repository.observeChannelCounts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setReadFilter(readFilter: ReadFilter) {
        filter.value = filter.value.copy(readFilter = readFilter)
    }

    fun setWithUrlOnly(withUrlOnly: Boolean) {
        filter.value = filter.value.copy(withUrlOnly = withUrlOnly)
    }

    fun setChannel(channel: String?) {
        filter.value = filter.value.copy(channel = channel)
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

    fun cleanupReadMessagesForCurrentFilter(): Job {
        val channel = filter.value.channel
        return viewModelScope.launch {
            stateCoordinator.deleteMessagesByChannelRead(channel, true)
        }
    }
}
