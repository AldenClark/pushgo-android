package io.ethan.pushgo.notifications

import android.content.Context
import io.ethan.pushgo.data.MessageRepository

class MessageStateCoordinator(
    context: Context,
    private val repository: MessageRepository,
) {
    private val appContext = context.applicationContext

    suspend fun markRead(messageId: String) {
        val normalized = messageId.trim()
        if (normalized.isEmpty()) return
        repository.markRead(normalized)
        NotificationHelper.cancelMessageNotification(appContext, normalized)
        refreshUnreadCount()
    }

    suspend fun markAllRead() {
        repository.markAllRead()
        refreshUnreadCount()
    }

    suspend fun deleteMessage(messageId: String) {
        val normalized = messageId.trim()
        if (normalized.isEmpty()) return
        repository.deleteById(normalized)
        NotificationHelper.cancelMessageNotification(appContext, normalized)
        refreshUnreadCount()
    }

    suspend fun deleteMessagesByChannel(channel: String): Int {
        val trimmed = channel.trim()
        if (trimmed.isEmpty()) return 0
        val ids = repository.getIdsByChannelRead(trimmed, null)
        val deleted = repository.deleteByChannel(trimmed)
        cancelNotifications(ids)
        refreshUnreadCount()
        return deleted
    }

    suspend fun deleteMessagesByChannelRead(channel: String?, readState: Boolean?): Int {
        val normalizedChannel = channel?.trim()
        val ids = repository.getIdsByChannelRead(normalizedChannel, readState)
        val deleted = repository.deleteByChannelRead(normalizedChannel, readState)
        cancelNotifications(ids)
        refreshUnreadCount()
        return deleted
    }

    suspend fun deleteMessagesBefore(readState: Boolean?, cutoff: Long): Int {
        val ids = repository.getIdsBefore(readState, cutoff)
        if (ids.isEmpty()) return 0
        repository.deleteBefore(readState, cutoff)
        cancelNotifications(ids)
        refreshUnreadCount()
        return ids.size
    }

    suspend fun deleteAllReadMessages(): Int {
        val ids = repository.getIdsByChannelRead(null, true)
        repository.deleteAllRead()
        cancelNotifications(ids)
        refreshUnreadCount()
        return ids.size
    }

    suspend fun deleteAllMessages(): Int {
        val ids = repository.getIdsByChannelRead(null, null)
        repository.deleteAll()
        cancelNotifications(ids)
        refreshUnreadCount()
        return ids.size
    }

    suspend fun deleteOldestReadMessages(limit: Int, excludedChannels: List<String>): Int {
        if (limit <= 0) return 0
        val sanitizedChannels = excludedChannels.map { it.trim() }.filter { it.isNotEmpty() }
        val ids = repository.getOldestReadIds(limit, sanitizedChannels)
        if (ids.isEmpty()) return 0
        val deleted = repository.deleteOldestReadMessages(limit, sanitizedChannels)
        if (deleted > 0) {
            cancelNotifications(ids)
            refreshUnreadCount()
        }
        return deleted
    }

    private fun cancelNotifications(messageIds: List<String>) {
        NotificationHelper.cancelMessageNotifications(appContext, messageIds)
    }

    private suspend fun refreshUnreadCount() {
        val unreadCount = repository.unreadCount()
        NotificationHelper.updateActiveNotificationNumbers(appContext, unreadCount)
    }
}
