package io.ethan.pushgo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.ui.viewmodel.MessageListViewModel
import io.ethan.pushgo.ui.viewmodel.MessageSearchViewModel
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel

class PushGoViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(MessageListViewModel::class.java) -> {
                MessageListViewModel(
                    repository = container.messageRepository,
                    stateCoordinator = container.messageStateCoordinator,
                ) as T
            }
            modelClass.isAssignableFrom(MessageSearchViewModel::class.java) -> {
                MessageSearchViewModel(container.messageRepository) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    channelRepository = container.channelRepository,
                    messageRepository = container.messageRepository,
                    messageStateCoordinator = container.messageStateCoordinator,
                    privateChannelClient = container.privateChannelClient,
                ) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
