package io.ethan.pushgo.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
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
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppConstants
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessagePreviewExtractor

object NotificationHelper {
    private const val LEGACY_SUMMARY_NOTIFICATION_ID = 10_001
    private const val NOTIFICATION_GROUP_PREFIX = "io.ethan.pushgo.notifications.groups."
    private const val MESSAGE_CHANNEL_GROUP_ID = "io.ethan.pushgo.notification_channels.messages"
    private val managedLevels = listOf("critical", "high", "normal", "low")

    const val EXTRA_MESSAGE_ID = "extra_message_id"
    const val EXTRA_ENTITY_TYPE = "extra_entity_type"
    const val EXTRA_ENTITY_ID = "extra_entity_id"

    private enum class NotificationKind {
        MESSAGE,
        EVENT,
        THING,
    }

    private enum class SummaryKind {
        MESSAGE,
        ENTITY,
    }

    private data class NotificationRoute(
        val notificationId: Int,
        val groupKey: String,
    )

    fun ensureChannel(
        context: Context,
        level: String?,
    ): String {
        val profile = resolveLevelProfile(level)
        val ringtone = RingtoneCatalog.ringtoneForLevel(level)
        val ringtoneId = ringtone.id
        val priorityTag = profile.channelTag
        val channelId =
            "${AppConstants.notificationChannelBaseId}_v${AppConstants.notificationChannelSchemaVersion}_${ringtoneId}_${priorityTag}"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureMessageChannelGroup(context, manager)
        val existing = manager.getNotificationChannel(channelId)
        if (existing != null) return channelId
        val channel = NotificationChannel(
            channelId,
            channelNameForProfile(context, profile),
            profile.channelImportance,
        ).apply {
            group = MESSAGE_CHANNEL_GROUP_ID
            description = channelDescriptionForProfile(context, profile)
            setShowBadge(true)
            lockscreenVisibility = profile.lockscreenVisibility
            enableLights(profile.isHighPriority)
            enableVibration(profile.shouldVibrate)
            vibrationPattern = profile.vibrationPattern
            lightColor = if (profile.isCritical) Color.RED else Color.WHITE
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (profile.enableSound) {
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, attributes)
            } else {
                setSound(null, null)
            }
            setBypassDnd(profile.bypassDnd)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(profile.allowBubbles)
            }
        }
        manager.createNotificationChannel(channel)
        return channelId
    }

    private fun ensureMessageChannelGroup(
        context: Context,
        manager: NotificationManager,
    ) {
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(
                MESSAGE_CHANNEL_GROUP_ID,
                context.getString(R.string.notification_channel_group_messages_name),
            ),
        )
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
    ) {
        if (!canPostNotifications(context)) return
        if (!shouldPostSystemNotification(context, NotificationKind.MESSAGE, level)) return
        val channelId = ensureChannel(context, level)
        val profile = resolveLevelProfile(level)
        val route = routeForMessage(message)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_MESSAGE_ID, message.id)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            route.notificationId,
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
            .setVisibility(profile.lockscreenVisibility)
            .setGroup(route.groupKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setLights(profile.lightColor, 1_500, 1_000)
            .setDeleteIntent(alertStopDeleteIntent(context, route.notificationId))

        if (profile.shouldVibrate) {
            builder.setVibrate(profile.vibrationPattern)
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(route.notificationId, builder.build())
            AlertPlaybackController.startOrUpdate(context, route.notificationId, level, channelId)
            reconcileActiveNotificationGroups(context)
        }
    }

    @SuppressLint("MissingPermission")
    fun showEntityNotification(
        context: Context,
        entityType: String,
        entityId: String,
        groupChannel: String?,
        eventId: String?,
        thingId: String?,
        title: String,
        body: String,
        level: String?,
    ) {
        if (!canPostNotifications(context)) return
        val kind = entityKind(entityType) ?: return
        if (!shouldPostSystemNotification(context, kind, level)) return
        val channelId = ensureChannel(context, level)
        val route = routeForEntity(
            entityType = entityType,
            entityId = entityId,
            channel = groupChannel,
            eventId = eventId,
            thingId = thingId,
        )
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_ENTITY_TYPE, entityType)
            putExtra(EXTRA_ENTITY_ID, entityId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            route.notificationId,
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
            .setVisibility(profile.lockscreenVisibility)
            .setGroup(route.groupKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setLights(profile.lightColor, 1_500, 1_000)
            .setDeleteIntent(alertStopDeleteIntent(context, route.notificationId))

        if (profile.shouldVibrate) {
            builder.setVibrate(profile.vibrationPattern)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(route.notificationId, builder.build())
            AlertPlaybackController.startOrUpdate(context, route.notificationId, level, channelId)
            reconcileActiveNotificationGroups(context)
        }
    }

    @SuppressLint("MissingPermission")
    fun reconcileActiveNotificationGroups(context: Context) {
        if (!canPostNotifications(context)) return
        val managerCompat = NotificationManagerCompat.from(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeSummaries = manager.activeNotifications.filter { status ->
            status.notification.isGroupSummary() && status.notification.group?.startsWith(NOTIFICATION_GROUP_PREFIX) == true
        }
        val activeChildren = manager.activeNotifications.filter { status ->
            status.id != PrivateChannelForegroundService.NOTIFICATION_ID &&
                !status.notification.isGroupSummary() &&
                status.notification.group?.startsWith(NOTIFICATION_GROUP_PREFIX) == true
        }

        val childrenByGroup = activeChildren.groupBy { it.notification.group.orEmpty() }
        activeSummaries.forEach { summary ->
            val groupKey = summary.notification.group.orEmpty()
            val childCount = childrenByGroup[groupKey]?.size ?: 0
            if (childCount < 2) {
                managerCompat.cancel(summary.tag, summary.id)
            }
        }
        childrenByGroup.forEach { (groupKey, children) ->
            updateGroupSummary(
                context = context,
                groupKey = groupKey,
                channelId = children.first().notification.channelId,
                childCount = children.size,
            )
        }
    }

    fun cancelMessageNotification(context: Context, messageId: String) {
        if (messageId.isBlank()) return
        val route = routeForMessageId(messageId)
        NotificationManagerCompat.from(context).cancel(route.notificationId)
        AlertPlaybackController.stopForNotification(context, route.notificationId)
        reconcileActiveNotificationGroups(context)
    }

    fun cancelMessageNotifications(context: Context, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        messageIds.forEach { id ->
            if (id.isNotBlank()) {
                val route = routeForMessageId(id)
                manager.cancel(route.notificationId)
                AlertPlaybackController.stopForNotification(context, route.notificationId)
            }
        }
        reconcileActiveNotificationGroups(context)
    }

    fun stopAlertPlaybackForLaunchIntent(
        context: Context,
        intent: Intent?,
    ) {
        val resolvedIntent = intent ?: return
        val messageId = resolvedIntent.getStringExtra(EXTRA_MESSAGE_ID)?.trim().orEmpty()
        if (messageId.isNotEmpty()) {
            AlertPlaybackController.stopForNotification(context, routeForMessageId(messageId).notificationId)
        }
        val entityType = resolvedIntent.getStringExtra(EXTRA_ENTITY_TYPE)?.trim().orEmpty()
        val entityId = resolvedIntent.getStringExtra(EXTRA_ENTITY_ID)?.trim().orEmpty()
        if (entityType.isNotEmpty() && entityId.isNotEmpty()) {
            AlertPlaybackController.stopForNotification(context, "$entityType:$entityId".hashCode())
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateGroupSummary(
        context: Context,
        groupKey: String,
        channelId: String?,
        childCount: Int,
    ) {
        val resolvedChannelId = channelId?.takeIf { it.isNotBlank() } ?: return
        val managerCompat = NotificationManagerCompat.from(context)
        if (childCount < 2) {
            managerCompat.cancel(summaryNotificationId(groupKey))
            return
        }
        val summaryText = summaryTextForGroup(
            context = context,
            groupKey = groupKey,
            count = childCount,
        )
        val summaryContentIntent = PendingIntent.getActivity(
            context,
            summaryNotificationId(groupKey),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summaryNotification = NotificationCompat.Builder(context, resolvedChannelId)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(summaryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setContentIntent(summaryContentIntent)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setNumber(childCount)
            .build()
        managerCompat.notify(summaryNotificationId(groupKey), summaryNotification)
    }

    private fun routeForMessage(message: PushMessage): NotificationRoute {
        return NotificationRoute(
            notificationId = routeForMessageId(message.id).notificationId,
            groupKey = buildGroupKey(
                kind = NotificationKind.MESSAGE,
                channel = message.channel,
                eventId = null,
                thingId = null,
            ),
        )
    }

    private fun routeForMessageId(messageId: String): NotificationRoute {
        return NotificationRoute(
            notificationId = messageId.hashCode(),
            groupKey = "",
        )
    }

    private fun routeForEntity(
        entityType: String,
        entityId: String,
        channel: String?,
        eventId: String?,
        thingId: String?,
    ): NotificationRoute {
        val kind = entityKind(entityType) ?: error("unsupported entity type: $entityType")
        return NotificationRoute(
            notificationId = "$entityType:$entityId".hashCode(),
            groupKey = buildGroupKey(
                kind = kind,
                channel = channel,
                eventId = eventId,
                thingId = thingId,
            ),
        )
    }

    private fun buildGroupKey(
        kind: NotificationKind,
        channel: String?,
        eventId: String?,
        thingId: String?,
    ): String {
        val parts = mutableListOf<String>()
        parts += when (kind) {
            NotificationKind.MESSAGE -> "message"
            NotificationKind.EVENT -> "event"
            NotificationKind.THING -> "thing"
        }
        normalizedGroupPart("channel", channel)?.let(parts::add)
        if (kind != NotificationKind.MESSAGE) {
            normalizedGroupPart("event", eventId)?.let(parts::add)
        }
        if (kind == NotificationKind.THING) {
            normalizedGroupPart("thing", thingId)?.let(parts::add)
        }
        if (parts.size == 1) {
            parts += "scope=global"
        }
        return NOTIFICATION_GROUP_PREFIX + parts.joinToString("|")
    }

    private fun normalizedGroupPart(label: String, value: String?): String? {
        val normalized = value?.trim()?.ifEmpty { null } ?: return null
        return "$label=$normalized"
    }

    private fun summaryTextForGroup(
        context: Context,
        groupKey: String,
        count: Int,
    ): String {
        val summaryKind = when {
            groupKey.startsWith("${NOTIFICATION_GROUP_PREFIX}message|") -> SummaryKind.MESSAGE
            else -> SummaryKind.ENTITY
        }
        val resId = when (summaryKind) {
            SummaryKind.MESSAGE -> R.plurals.message_notification_group_summary
            SummaryKind.ENTITY -> R.plurals.entity_notification_group_summary
        }
        return context.resources.getQuantityString(resId, count, count)
    }

    private fun entityKind(entityType: String): NotificationKind? {
        return when (entityType.trim().lowercase()) {
            "event" -> NotificationKind.EVENT
            "thing" -> NotificationKind.THING
            else -> null
        }
    }

    private fun summaryNotificationId(groupKey: String): Int {
        return LEGACY_SUMMARY_NOTIFICATION_ID xor groupKey.hashCode()
    }

    private fun Notification.isGroupSummary(): Boolean {
        return flags and Notification.FLAG_GROUP_SUMMARY != 0
    }

    private fun alertStopDeleteIntent(
        context: Context,
        notificationId: Int,
    ): PendingIntent {
        val intent = Intent(context, AlertPlaybackService::class.java).apply {
            action = AlertPlaybackService.ACTION_STOP_FOR_NOTIFICATION
            putExtra(AlertPlaybackService.EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getService(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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

    private fun shouldPostSystemNotification(
        context: Context,
        kind: NotificationKind,
        level: String?,
    ): Boolean {
        val appVisible = (context.applicationContext as? PushGoApp)?.isAppVisible() == true
        if (!appVisible) return true
        return when (kind) {
            NotificationKind.MESSAGE -> false
            NotificationKind.EVENT,
            NotificationKind.THING,
            -> level == "critical" || level == "high"
        }
    }

    private data class LevelProfile(
        val channelTag: String,
        val isCritical: Boolean,
        val isHighPriority: Boolean,
        val channelImportance: Int,
        val compatPriority: Int,
        val enableSound: Boolean,
        val shouldVibrate: Boolean,
        val vibrationPattern: LongArray?,
        val bypassDnd: Boolean,
        val lockscreenVisibility: Int,
        val allowBubbles: Boolean,
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
                enableSound = true,
                shouldVibrate = true,
                vibrationPattern = longArrayOf(0L, 300L, 180L, 300L, 180L, 420L),
                bypassDnd = true,
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC,
                allowBubbles = true,
                lightColor = Color.RED,
            )
            "high" -> LevelProfile(
                channelTag = "high",
                isCritical = false,
                isHighPriority = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                compatPriority = NotificationCompat.PRIORITY_HIGH,
                enableSound = true,
                shouldVibrate = true,
                vibrationPattern = longArrayOf(0L, 240L, 160L, 240L),
                bypassDnd = false,
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC,
                allowBubbles = true,
                lightColor = Color.WHITE,
            )
            "normal" -> LevelProfile(
                channelTag = "normal",
                isCritical = false,
                isHighPriority = false,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
                compatPriority = NotificationCompat.PRIORITY_DEFAULT,
                enableSound = true,
                shouldVibrate = true,
                vibrationPattern = longArrayOf(0L, 80L),
                bypassDnd = false,
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC,
                allowBubbles = true,
                lightColor = Color.WHITE,
            )
            "low" -> LevelProfile(
                channelTag = "low",
                isCritical = false,
                isHighPriority = false,
                channelImportance = NotificationManager.IMPORTANCE_LOW,
                compatPriority = NotificationCompat.PRIORITY_LOW,
                enableSound = false,
                shouldVibrate = false,
                vibrationPattern = null,
                bypassDnd = false,
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE,
                allowBubbles = false,
                lightColor = Color.WHITE,
            )
            else -> LevelProfile(
                channelTag = "normal",
                isCritical = false,
                isHighPriority = false,
                channelImportance = NotificationManager.IMPORTANCE_DEFAULT,
                compatPriority = NotificationCompat.PRIORITY_DEFAULT,
                enableSound = true,
                shouldVibrate = true,
                vibrationPattern = longArrayOf(0L, 80L),
                bypassDnd = false,
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC,
                allowBubbles = true,
                lightColor = Color.WHITE,
            )
        }
    }

    private fun buildChannelId(level: String?): String {
        val profile = resolveLevelProfile(level)
        val ringtoneId = RingtoneCatalog.ringtoneForLevel(level).id
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
