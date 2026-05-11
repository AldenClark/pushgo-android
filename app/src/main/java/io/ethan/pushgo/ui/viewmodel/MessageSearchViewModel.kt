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

    val queryState: StateFlow<String> = query
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    val results: StateFlow<List<PushMessage>> = combine(
        query.debounce(200),
        unreadOnly,
    ) { rawQuery, currentUnreadOnly ->
        rawQuery to currentUnreadOnly
    }
        .flatMapLatest { (rawQuery, currentUnreadOnly) ->
            repository.searchMessages(rawQuery, currentUnreadOnly)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun updateQuery(value: String) {
        query.value = value
    }

    fun setUnreadOnlyFilter(enabled: Boolean) {
        if (unreadOnly.value == enabled) return
        unreadOnly.value = enabled
    }
}
