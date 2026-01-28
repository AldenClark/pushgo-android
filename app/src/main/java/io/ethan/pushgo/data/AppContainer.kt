package io.ethan.pushgo.data

import android.content.Context
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.notifications.MessageStateCoordinator

class AppContainer(context: Context) {
    val appContext = context.applicationContext
    private val database = PushGoDatabase.build(appContext)

    val settingsRepository = SettingsRepository(database.appSettingsDao())
    private val channelStore = ChannelSubscriptionStore(database.channelSubscriptionDao())
    private val performanceCoordinator = PerformanceDegradationCoordinator(
        context = appContext,
        settingsRepository = settingsRepository,
        messageDao = database.messageDao(),
        channelStore = channelStore,
    )
    val messageRepository = MessageRepository(
        dao = database.messageDao(),
        performanceCoordinator = performanceCoordinator,
    )
    val messageStateCoordinator = MessageStateCoordinator(
        context = appContext,
        repository = messageRepository,
    )
    init {
        performanceCoordinator.attachMessageStateCoordinator(messageStateCoordinator)
    }
    val channelRepository = ChannelSubscriptionRepository(
        store = channelStore,
        settingsRepository = settingsRepository,
        messageStateCoordinator = messageStateCoordinator,
    )

    suspend fun handlePushTokenUpdate(deviceToken: String) {
        channelRepository.handleTokenUpdate(deviceToken)
    }
}
