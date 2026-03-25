package io.ethan.pushgo.notifications

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object AlertPlaybackController {
    fun startOrUpdate(
        context: Context,
        notificationId: Int,
        level: String?,
        channelId: String,
    ) {
        val spec = AlertPlaybackPolicy.specForLevel(level)
        if (!spec.isEnabled) {
            stopForNotification(context, notificationId)
            return
        }
        val intent = Intent(context, AlertPlaybackService::class.java).apply {
            action = AlertPlaybackService.ACTION_START_OR_UPDATE
            putExtra(AlertPlaybackService.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(AlertPlaybackService.EXTRA_LEVEL, level)
            putExtra(AlertPlaybackService.EXTRA_CHANNEL_ID, channelId)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopForNotification(context: Context, notificationId: Int) {
        val intent = Intent(context, AlertPlaybackService::class.java).apply {
            action = AlertPlaybackService.ACTION_STOP_FOR_NOTIFICATION
            putExtra(AlertPlaybackService.EXTRA_NOTIFICATION_ID, notificationId)
        }
        runCatching { context.startService(intent) }
    }

    fun stopAll(context: Context) {
        val intent = Intent(context, AlertPlaybackService::class.java).apply {
            action = AlertPlaybackService.ACTION_STOP_ALL
        }
        runCatching { context.startService(intent) }
    }
}
