package io.ethan.pushgo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MessageDetailViewModel(
    private val repository: MessageRepository,
    private val stateCoordinator: MessageStateCoordinator,
    private val messageId: String,
) : ViewModel() {
    private val _message = MutableStateFlow<PushMessage?>(null)
    val message: StateFlow<PushMessage?> = _message

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            val loaded = repository.getById(messageId)
            if (loaded != null) {
                stateCoordinator.markRead(messageId)
                _message.value = if (loaded.isRead) loaded else loaded.copy(isRead = true)
            } else {
                _message.value = null
            }
            _isLoading.value = false
        }
    }

    fun delete(): Job {
        return viewModelScope.launch {
            stateCoordinator.deleteMessage(messageId)
        }
    }

    fun markRead(): Job? {
        val current = _message.value ?: return null
        if (current.isRead) return null
        return viewModelScope.launch {
            stateCoordinator.markRead(messageId)
            _message.value = current.copy(isRead = true)
        }
    }
}
