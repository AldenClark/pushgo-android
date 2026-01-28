package io.ethan.pushgo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.ui.viewmodel.MessageDetailViewModel

class MessageDetailViewModelFactory(
    private val repository: MessageRepository,
    private val stateCoordinator: MessageStateCoordinator,
    private val messageId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MessageDetailViewModel::class.java)) {
            return MessageDetailViewModel(repository, stateCoordinator, messageId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
