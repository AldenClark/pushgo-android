package io.ethan.pushgo.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppConstants
import io.ethan.pushgo.data.model.PushMessage

object NotificationHelper {
    private const val LEGACY_SUMMARY_NOTIFICATION_ID = 10_001
    const val EXTRA_MESSAGE_ID = "extra_message_id"
    const val ACTION_MARK_READ = "io.ethan.pushgo.action.MARK_READ"
    const val ACTION_DELETE = "io.ethan.pushgo.action.DELETE"
    const val ACTION_COPY = "io.ethan.pushgo.action.COPY"

    fun ensureChannel(
        context: Context,
        ringtoneId: String?,
        ringMode: String?,
        level: String?,
    ): String {
        val ringtone = RingtoneCatalog.findById(ringtoneId)
        val normalizedLevel = level?.trim()?.lowercase()
        val isCritical = normalizedLevel == "critical"
        val useLongSound = ringMode?.trim()?.equals("long", ignoreCase = true) == true
        val longSoundUri = if (useLongSound) {
            LongRingtoneManager.getExistingLongSoundUri(context, ringtone.id)
        } else {
            null
        }
        val soundVariant = if (longSoundUri != null) "long" else "short"
        val channelId = "${AppConstants.notificationChannelBaseId}_${ringtone.id}_${soundVariant}_${if (isCritical) "critical" else "normal"}"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(channelId)
        if (existing != null) return channelId
        val channel = NotificationChannel(
            channelId,
            AppConstants.notificationChannelName,
            if (isCritical) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = AppConstants.notificationChannelDescription
            setShowBadge(true)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val soundUri = longSoundUri ?: ringtone.rawResId?.let { id ->
                "android.resource://${context.packageName}/$id".toUri()
            } ?: Settings.System.DEFAULT_NOTIFICATION_URI
            setSound(soundUri, attributes)
        }
        manager.createNotificationChannel(channel)
        return channelId
    }

    @SuppressLint("MissingPermission")
    fun showMessageNotification(
        context: Context,
        message: PushMessage,
        ringtoneId: String?,
        ringMode: String?,
        level: String?,
        unreadCount: Int?,
    ) {
        if (!canPostNotifications(context)) return
        val channelId = ensureChannel(context, ringtoneId, ringMode, level)
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_MESSAGE_ID, message.id)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            message.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationId = message.id.hashCode()

        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_MESSAGE_ID, message.id)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val deleteIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_DELETE
            putExtra(EXTRA_MESSAGE_ID, message.id)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val copyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_COPY
            putExtra(EXTRA_MESSAGE_ID, message.id)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val markReadPending = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deletePending = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val copyPending = PendingIntent.getBroadcast(
            context,
            notificationId + 3,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val normalizedLevel = level?.trim()?.lowercase()
        val isCritical = normalizedLevel == "critical"
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(message.title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(message.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setContentIntent(contentIntent)
            .setDeleteIntent(markReadPending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(if (isCritical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (isCritical) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .addAction(0, context.getString(R.string.action_mark_read), markReadPending)
            .addAction(0, context.getString(R.string.action_copy), copyPending)
            .addAction(0, context.getString(R.string.action_delete), deletePending)

        unreadCount?.takeIf { it >= 0 }?.let { builder.setNumber(it) }

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
    }

    @SuppressLint("MissingPermission")
    fun updateActiveNotificationNumbers(context: Context, unreadCount: Int) {
        val managerCompat = NotificationManagerCompat.from(context)
        managerCompat.cancel(LEGACY_SUMMARY_NOTIFICATION_ID)
        if (!canPostNotifications(context)) return
        if (unreadCount <= 0) {
            managerCompat.cancelAll()
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val targetCount = unreadCount.coerceAtLeast(0)
        val active = manager.activeNotifications
        if (active.isEmpty()) return
        active.forEach { status ->
            val notification = status.notification
            if (notification.number == targetCount) return@forEach
            notification.number = targetCount
            notification.flags = notification.flags or Notification.FLAG_ONLY_ALERT_ONCE
            managerCompat.notify(status.tag, status.id, notification)
        }
    }

    fun cancelMessageNotification(context: Context, messageId: String) {
        if (messageId.isBlank()) return
        NotificationManagerCompat.from(context).cancel(messageId.hashCode())
    }

    fun cancelMessageNotifications(context: Context, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        messageIds.forEach { id ->
            if (id.isNotBlank()) {
                manager.cancel(id.hashCode())
            }
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
