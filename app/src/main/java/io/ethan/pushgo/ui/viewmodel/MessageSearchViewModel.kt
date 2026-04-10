package io.ethan.pushgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.model.MessageListSortMode
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
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val sortMode = MutableStateFlow(settingsRepository.getCachedMessageListSortMode())

    val queryState: StateFlow<String> = query
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    val results: StateFlow<List<PushMessage>> = combine(
        query.debounce(200),
        sortMode,
    ) { rawQuery, currentSortMode ->
        rawQuery to currentSortMode
    }
        .flatMapLatest { (rawQuery, currentSortMode) ->
            repository.searchMessages(rawQuery, currentSortMode)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun updateQuery(value: String) {
        query.value = value
    }

    fun setSortMode(value: MessageListSortMode, persist: Boolean = false) {
        if (sortMode.value == value) return
        sortMode.value = value
        if (persist) {
            settingsRepository.setCachedMessageListSortMode(value)
        }
    }
}
