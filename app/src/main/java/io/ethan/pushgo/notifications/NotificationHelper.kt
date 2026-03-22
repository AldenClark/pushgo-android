package io.ethan.pushgo.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppConstants
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessagePreviewExtractor

object NotificationHelper {
    private const val LEGACY_SUMMARY_NOTIFICATION_ID = 10_001
    private const val MESSAGE_NOTIFICATION_GROUP_PREFIX = "io.ethan.pushgo.notifications.messages."
    private val managedLevels = listOf("critical", "high", "normal", "low")

    const val EXTRA_MESSAGE_ID = "extra_message_id"
    const val EXTRA_ENTITY_TYPE = "extra_entity_type"
    const val EXTRA_ENTITY_ID = "extra_entity_id"

    fun ensureChannel(
        context: Context,
        level: String?,
    ): String {
        val profile = resolveLevelProfile(level)
        val ringtone = RingtoneCatalog.ringtoneForLevel(level)
        val ringtoneId = ringtone?.id ?: "none"
        val priorityTag = profile.channelTag
        val channelId =
            "${AppConstants.notificationChannelBaseId}_v${AppConstants.notificationChannelSchemaVersion}_${ringtoneId}_${priorityTag}"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(channelId)
        if (existing != null) return channelId
        val channel = NotificationChannel(
            channelId,
            channelNameForProfile(context, profile),
            profile.channelImportance,
        ).apply {
            description = channelDescriptionForProfile(context, profile)
            setShowBadge(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            enableLights(profile.isHighPriority)
            enableVibration(profile.shouldVibrate)
            vibrationPattern = profile.vibrationPattern
            lightColor = if (profile.isCritical) Color.RED else Color.WHITE
            if (ringtone == null) {
                setSound(null, null)
            } else {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val soundUri = ringtone.rawResId?.let { id ->
                    "android.resource://${context.packageName}/$id".toUri()
                } ?: Settings.System.DEFAULT_NOTIFICATION_URI
                setSound(soundUri, attributes)
            }
        }
        manager.createNotificationChannel(channel)
        return channelId
    }

    fun cleanupObsoleteChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeChannelIds = managedLevels.mapTo(linkedSetOf<String>()) { level ->
            buildChannelId(level)
        }
        manager.notificationChannels
            .asSequence()
            .map { it.id }
            .filter { it.startsWith(AppConstants.notificationChannelBaseId) }
            .filterNot { it in activeChannelIds }
            .forEach(manager::deleteNotificationChannel)
    }

    fun ensureManagedChannels(context: Context) {
        managedLevels.forEach { level ->
            ensureChannel(context, level)
        }
    }

    @SuppressLint("MissingPermission")
    fun showMessageNotification(
        context: Context,
        message: PushMessage,
        level: String?,
        unreadCount: Int?,
    ) {
        if (!canPostNotifications(context)) return
        val channelId = ensureChannel(context, level)
        val profile = resolveLevelProfile(level)
        val notificationId = message.id.hashCode()
        val groupKey = groupKeyForChannel(message.channel)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_MESSAGE_ID, message.id)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val bodyPreview = MessagePreviewExtractor.notificationPreview(message.body)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(message.title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(bodyPreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyPreview))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(profile.compatPriority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setLights(profile.lightColor, 1_500, 1_000)

        if (profile.shouldVibrate) {
            builder.setVibrate(profile.vibrationPattern)
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            updateMessageGroupSummary(context, groupKey, channelId, unreadCount)
        }
    }

    @SuppressLint("MissingPermission")
    fun showEntityNotification(
        context: Context,
        entityType: String,
        entityId: String,
        groupChannel: String?,
        title: String,
        body: String,
        level: String?,
    ) {
        if (!canPostNotifications(context)) return
        if (entityType != "event" && entityType != "thing") return
        val channelId = ensureChannel(context, level)
        val groupKey = groupKeyForChannel(groupChannel)
        val requestCode = "$entityType:$entityId".hashCode()
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_ENTITY_TYPE, entityType)
            putExtra(EXTRA_ENTITY_ID, entityId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val profile = resolveLevelProfile(level)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(profile.compatPriority)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setLights(profile.lightColor, 1_500, 1_000)

        if (profile.shouldVibrate) {
            builder.setVibrate(profile.vibrationPattern)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(requestCode, builder.build())
            updateMessageGroupSummary(context, groupKey, channelId, null)
        }
    }

    @SuppressLint("MissingPermission")
    fun updateActiveNotificationNumbers(context: Context, unreadCount: Int) {
        if (!canPostNotifications(context)) return
        val managerCompat = NotificationManagerCompat.from(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val targetCount = unreadCount.coerceAtLeast(0)
        val activeSummaries = manager.activeNotifications.filter { status ->
            status.notification.isGroupSummary() && status.notification.group?.startsWith(MESSAGE_NOTIFICATION_GROUP_PREFIX) == true
        }
        val activeChildren = manager.activeNotifications.filter { status ->
            status.id != PrivateChannelForegroundService.NOTIFICATION_ID &&
                !status.notification.isGroupSummary() &&
                status.notification.group?.startsWith(MESSAGE_NOTIFICATION_GROUP_PREFIX) == true
        }
        if (activeChildren.isEmpty()) {
            activeSummaries.forEach { summary ->
                managerCompat.cancel(summary.tag, summary.id)
            }
            return
        }
        activeChildren
            .groupBy { it.notification.group.orEmpty() }
            .forEach { (groupKey, children) ->
                updateMessageGroupSummary(
                    context = context,
                    groupKey = groupKey,
                    channelId = children.first().notification.channelId,
                    targetUnreadCount = targetCount,
                )
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

    @SuppressLint("MissingPermission")
    private fun updateMessageGroupSummary(
        context: Context,
        groupKey: String,
        channelId: String?,
        targetUnreadCount: Int?,
    ) {
        val resolvedChannelId = channelId?.takeIf { it.isNotBlank() } ?: return
        val managerCompat = NotificationManagerCompat.from(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeChildren = manager.activeNotifications.filter { status ->
            status.id != PrivateChannelForegroundService.NOTIFICATION_ID &&
                !status.notification.isGroupSummary() &&
                status.notification.group == groupKey
        }
        if (activeChildren.isEmpty()) {
            managerCompat.cancel(summaryNotificationId(groupKey))
            return
        }
        val unreadCount = targetUnreadCount?.coerceAtLeast(0) ?: activeChildren.size
        val displayCount = unreadCount.coerceAtLeast(1)
        val summaryText = context.resources.getQuantityString(
            R.plurals.message_notifications_summary,
            displayCount,
            displayCount,
        )
        val summaryNotification = NotificationCompat.Builder(context, resolvedChannelId)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(summaryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setNumber(displayCount)
            .build()
        managerCompat.notify(summaryNotificationId(groupKey), summaryNotification)
    }

    private fun groupKeyForChannel(channel: String?): String {
        val normalizedChannel = channel?.trim()?.ifEmpty { null } ?: "ungrouped"
        return MESSAGE_NOTIFICATION_GROUP_PREFIX + normalizedChannel
    }

    private fun summaryNotificationId(groupKey: String): Int {
        return LEGACY_SUMMARY_NOTIFICATION_ID xor groupKey.hashCode()
    }

    private fun Notification.isGroupSummary(): Boolean {
        return flags and Notification.FLAG_GROUP_SUMMARY != 0
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

    private data class LevelProfile(
        val channelTag: String,
        val isCritical: Boolean,
        val isHighPriority: Boolean,
        val channelImportance: Int,
        val compatPriority: Int,
        val shouldVibrate: Boolean,
        val vibrationPattern: LongArray?,
        val lightColor: Int,
    )

    private fun resolveLevelProfile(level: String?): LevelProfile {
        val normalized = level?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "critical" -> LevelProfile(
                channelTag = "critical",
                isCritical = true,
                isHighPriority = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                compatPriority = NotificationCompat.PRIORITY_MAX,
                shouldVibrate = true,
                vibrationPattern = longArrayOf(0L, 300L, 180L, 300L, 180L, 420L),
                lightColor = Color.RED,
            )
            "high" -> LevelProfile(
                channelTag = "high",
                isCritical = false,
                isHighPriority = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                compatPriority = NotificationCompat.PRIORITY_HIGH,
                shouldVibrate = true,
                vibrationPattern = longArrayOf(0L, 240L, 160L, 240L),
                lightColor = Color.WHITE,
            )
            "normal" -> LevelProfile(
                channelTag = "normal",
                isCritical = false,
                isHighPriority = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                compatPriority = NotificationCompat.PRIORITY_HIGH,
                shouldVibrate = true,
                vibrationPattern = longArrayOf(0L, 120L, 80L, 120L),
                lightColor = Color.WHITE,
            )
            "low" -> LevelProfile(
                channelTag = "low",
                isCritical = false,
                isHighPriority = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                compatPriority = NotificationCompat.PRIORITY_HIGH,
                shouldVibrate = false,
                vibrationPattern = null,
                lightColor = Color.WHITE,
            )
            else -> LevelProfile(
                channelTag = "normal",
                isCritical = false,
                isHighPriority = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                compatPriority = NotificationCompat.PRIORITY_HIGH,
                shouldVibrate = true,
                vibrationPattern = longArrayOf(0L, 120L, 80L, 120L),
                lightColor = Color.WHITE,
            )
        }
    }

    private fun buildChannelId(level: String?): String {
        val profile = resolveLevelProfile(level)
        val ringtoneId = RingtoneCatalog.ringtoneForLevel(level)?.id ?: "none"
        return "${AppConstants.notificationChannelBaseId}_v${AppConstants.notificationChannelSchemaVersion}_${ringtoneId}_${profile.channelTag}"
    }

    private fun channelNameForProfile(context: Context, profile: LevelProfile): String {
        val suffixResId = when {
            profile.isCritical -> R.string.notification_channel_suffix_critical
            profile.channelTag == "high" -> R.string.notification_channel_suffix_high
            profile.channelTag == "low" -> R.string.notification_channel_suffix_low
            else -> R.string.notification_channel_suffix_normal
        }
        return context.getString(
            R.string.notification_channel_name_with_level,
            context.getString(suffixResId),
        )
    }

    private fun channelDescriptionForProfile(context: Context, profile: LevelProfile): String {
        val descriptionResId = when {
            profile.isCritical -> R.string.notification_channel_description_critical
            profile.channelTag == "high" -> R.string.notification_channel_description_high
            profile.channelTag == "low" -> R.string.notification_channel_description_low
            else -> R.string.notification_channel_description_normal
        }
        return context.getString(descriptionResId)
    }
}
