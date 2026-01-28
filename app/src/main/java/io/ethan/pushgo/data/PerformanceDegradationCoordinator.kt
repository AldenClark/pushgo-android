package io.ethan.pushgo.data

import android.content.Context
import android.widget.Toast
import io.ethan.pushgo.R
import io.ethan.pushgo.data.db.MessageDao
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.ui.announceForAccessibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PerformanceDegradationCoordinator(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val messageDao: MessageDao,
    private val channelStore: ChannelSubscriptionStore,
    private val monitor: PerformanceMonitor = PerformanceMonitor(),
) {
    private var messageStateCoordinator: MessageStateCoordinator? = null

    fun attachMessageStateCoordinator(coordinator: MessageStateCoordinator) {
        messageStateCoordinator = coordinator
    }

    suspend fun record(operation: PerformanceOperation, durationMs: Long) {
        if (!monitor.record(operation, durationMs)) return
        val enabled = settingsRepository.getAutoCleanupEnabled()
        val message = if (enabled) {
            val excludedChannels = loadExcludedChannels()
            val coordinator = messageStateCoordinator
            if (coordinator != null) {
                coordinator.deleteOldestReadMessages(
                    limit = AppConstants.autoCleanupBatchSize,
                    excludedChannels = excludedChannels,
                )
            } else {
                val limit = AppConstants.autoCleanupBatchSize
                val ids = if (excludedChannels.isEmpty()) {
                    messageDao.getOldestReadIds(limit)
                } else {
                    messageDao.getOldestReadIdsExcludingChannels(
                        limit = limit,
                        excludedChannels = excludedChannels,
                        excludedSize = excludedChannels.size,
                    )
                }
                val deleted = if (excludedChannels.isEmpty()) {
                    messageDao.deleteOldestRead(limit)
                } else {
                    messageDao.deleteOldestReadExcludingChannels(
                        limit = limit,
                        excludedChannels = excludedChannels,
                        excludedSize = excludedChannels.size,
                    )
                }
                if (deleted > 0) {
                    NotificationHelper.cancelMessageNotifications(context, ids)
                    val unreadCount = messageDao.unreadCount()
                    NotificationHelper.updateActiveNotificationNumbers(context, unreadCount)
                }
            }
            context.getString(R.string.auto_cleanup_manual_suggestion)
        } else {
            context.getString(R.string.auto_cleanup_suggestion)
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            announceForAccessibility(context, message)
        }
    }

    private suspend fun loadExcludedChannels(): List<String> {
        val gateway = settingsRepository.getServerAddress()?.trim().orEmpty()
        if (gateway.isEmpty()) return emptyList()
        return channelStore.loadSubscriptions(gatewayUrl = gateway)
            .filter { !it.autoCleanupEnabled }
            .map { it.channelId }
    }
}
