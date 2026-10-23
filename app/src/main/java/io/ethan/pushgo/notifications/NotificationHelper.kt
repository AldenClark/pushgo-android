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
import android.net.Uri
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppConstants
import io.ethan.pushgo.data.PendingLocalDeletionKind
import io.ethan.pushgo.data.PendingLocalDeletionOperation
import io.ethan.pushgo.data.db.DeletionNotificationCleanupEntity
import io.ethan.pushgo.data.db.MessageEntity
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessagePreviewExtractor
import io.ethan.pushgo.testing.InstrumentationRuntime
import java.util.UUID

object NotificationHelper {
    private const val TAG = "NotificationHelper"
    private const val LEGACY_SUMMARY_NOTIFICATION_ID = 10_001
    private const val NOTIFICATION_GROUP_PREFIX = "io.ethan.pushgo.notifications.groups."
    private const val NOTIFICATION_METADATA_EVENT_ID = "io.ethan.pushgo.notification.event_id"
    private const val NOTIFICATION_METADATA_THING_ID = "io.ethan.pushgo.notification.thing_id"
    private const val NOTIFICATION_METADATA_OWN_KIND = "io.ethan.pushgo.notification.own_kind"
    private const val NOTIFICATION_METADATA_OWN_ID = "io.ethan.pushgo.notification.own_id"
    private const val NOTIFICATION_METADATA_GENERATION = "io.ethan.pushgo.notification.generation"
    private val notificationInstanceLock = Any()
    private const val CHILD_INSTANCE_TAG_PREFIX = "io.ethan.pushgo.notification.instance."
    private data class NotificationInstance(
        val tag: String?, val id: Int, val postTime: Long,
        val ownKind: String?, val ownId: String?, val eventId: String?, val thingId: String?,
        val group: String?, val generation: String?, val summary: Boolean = false, val channelId: String? = null,
    )
    // Only submissions not yet observed in the OS snapshot. No Notification or deletion jobs.
    private val submitted = linkedMapOf<Pair<String, String>, NotificationInstance>()
    private var notificationObservationHiddenForTest = false

    fun installNotificationObservationHiddenForTest(hidden: Boolean) = synchronized(notificationInstanceLock) {
        check(InstrumentationRuntime.isUnderInstrumentationTest())
        notificationObservationHiddenForTest = hidden
    }

    private fun activeNotifications(context: Context) =
        if (notificationObservationHiddenForTest) emptyArray<android.service.notification.StatusBarNotification>()
        else (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications

    private fun notificationInstances(context: Context): List<NotificationInstance> {
        val active = activeNotifications(context).map { status ->
            val extras = status.notification.extras
            NotificationInstance(status.tag, status.id, status.postTime,
                extras.getString(NOTIFICATION_METADATA_OWN_KIND), extras.getString(NOTIFICATION_METADATA_OWN_ID),
                extras.getString(NOTIFICATION_METADATA_EVENT_ID), extras.getString(NOTIFICATION_METADATA_THING_ID),
                status.notification.group, extras.getString(NOTIFICATION_METADATA_GENERATION),
                status.notification.isGroupSummary(), status.notification.channelId)
        }
        val activeKeys = active.mapTo(hashSetOf()) { it.tag to it.id }
        submitted.entries.removeAll { (it.value.tag to it.value.id) in activeKeys }
        return (submitted.values + active).associateBy { it.tag to it.id }.values.toList()
    }

    @SuppressLint("MissingPermission")
    private fun postChild(context: Context, id: Int, notification: Notification) {
        val extras = notification.extras
        val kind = checkNotNull(extras.getString(NOTIFICATION_METADATA_OWN_KIND))
        val ownId = checkNotNull(extras.getString(NOTIFICATION_METADATA_OWN_ID))
        cancelMatching(context) { it.ownKind == kind && it.ownId == ownId }
        val generation = checkNotNull(extras.getString(NOTIFICATION_METADATA_GENERATION))
        val tag = CHILD_INSTANCE_TAG_PREFIX + generation
        val instance = NotificationInstance(tag, id, 0L, kind, ownId,
            extras.getString(NOTIFICATION_METADATA_EVENT_ID), extras.getString(NOTIFICATION_METADATA_THING_ID),
            notification.group, generation, channelId = notification.channelId)
        submitted[kind to ownId] = instance
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
        } catch (error: Throwable) {
            submitted.remove(kind to ownId)
            throw error
        }
    }

    private fun cancelMatching(context: Context, predicate: (NotificationInstance) -> Boolean) {
        val instances = notificationInstances(context)
        val targets = instances.filter(predicate)
        val targetKeys = targets.mapTo(hashSetOf()) { it.tag to it.id }
        val manager = NotificationManagerCompat.from(context)
        targets.forEach { instance ->
            manager.cancel(instance.tag, instance.id)
            submitted.entries.removeAll { it.value.tag == instance.tag && it.value.id == instance.id }
            if (instances.none { it.id == instance.id && (it.tag to it.id) !in targetKeys }) {
                AlertPlaybackController.stopForNotification(context, instance.id)
            }
        }
    }

    private fun cancelLegacyId(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
        if (notificationInstances(context).none { it.id == id && it.tag != null }) {
            AlertPlaybackController.stopForNotification(context, id)
        }
    }

    private fun launchIdentity(kind: String, id: String): Uri = Uri.Builder()
        .scheme("pushgo-notification").authority("identity").appendPath(kind).appendPath(id).build()
    private const val MESSAGE_CHANNEL_GROUP_ID = "io.ethan.pushgo.notification_channels.messages"
    private val managedLevels = listOf("critical", "high", "normal", "low")

    const val EXTRA_MESSAGE_ID = "extra_message_id"
    const val EXTRA_ENTITY_TYPE = "extra_entity_type"
    const val EXTRA_ENTITY_ID = "extra_entity_id"

    internal enum class NotificationPostBehavior(
        val silenceSystemAlert: Boolean,
        val startCustomAlert: Boolean,
        val onlyAlertOnce: Boolean,
    ) {
        NEW_DELIVERY(
            silenceSystemAlert = false,
            startCustomAlert = true,
            onlyAlertOnce = false,
        ),
        SILENT_REPLAY(
            silenceSystemAlert = true,
            startCustomAlert = false,
            onlyAlertOnce = true,
        ),
    }

    internal fun normalizeNotificationPresentationLevel(level: String?): String? {
        return level
    }

    internal fun defaultLockscreenVisibilityForLevel(level: String?): Int {
        return resolveLevelProfile(level).lockscreenVisibility
    }

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
    ): Boolean = showMessageNotification(
        context = context,
        message = message,
        level = level,
        postBehavior = NotificationPostBehavior.NEW_DELIVERY,
    )

    /** Reposts an already-persisted message without replaying system or custom alert audio. */
    @SuppressLint("MissingPermission")
    fun showMessageReplayNotificationSilently(
        context: Context,
        message: PushMessage,
        level: String?,
    ): Boolean = showMessageNotification(
        context = context,
        message = message,
        level = level,
        postBehavior = NotificationPostBehavior.SILENT_REPLAY,
    )

    @SuppressLint("MissingPermission")
    private fun showMessageNotification(
        context: Context,
        message: PushMessage,
        level: String?,
        postBehavior: NotificationPostBehavior,
    ): Boolean {
        if (!canPostNotifications(context)) return true
        if (!shouldPostSystemNotification(context, NotificationKind.MESSAGE, level)) return true
        val channelId = ensureChannel(context, level)
        val profile = resolveLevelProfile(level)
        val route = routeForMessage(message)
        val messageProjection = MessageEntity.fromModel(message)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            data = launchIdentity("message", message.id)
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
            .setOnlyAlertOnce(postBehavior.onlyAlertOnce)
            .setPriority(profile.compatPriority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setVisibility(profile.lockscreenVisibility)
            .setGroup(route.groupKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setLights(profile.lightColor, 1_500, 1_000)
            .setDeleteIntent(alertStopDeleteIntent(context, route.notificationId))
            .addExtras(notificationInstanceMetadata("message", message.id))
            .addExtras(entityNotificationMetadata(messageProjection.eventId, messageProjection.thingId))

        if (postBehavior.silenceSystemAlert) {
            builder.setSilent(true)
        } else if (profile.shouldVibrate) {
            builder.setVibrate(profile.vibrationPattern)
        }
        return runCatching {
            synchronized(notificationInstanceLock) {
                postChild(context, route.notificationId, builder.build())
                if (postBehavior.startCustomAlert) {
                    AlertPlaybackController.startOrUpdate(context, route.notificationId, level, channelId)
                }
                reconcileActiveNotificationGroups(context)
                true
            }
        }.getOrElse { error ->
            io.ethan.pushgo.util.SilentSink.w(TAG, "message notification display failed", error)
            false
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
    ): Boolean = showEntityNotification(
        context = context,
        entityType = entityType,
        entityId = entityId,
        groupChannel = groupChannel,
        eventId = eventId,
        thingId = thingId,
        title = title,
        body = body,
        level = level,
        postBehavior = NotificationPostBehavior.NEW_DELIVERY,
    )

    /** Reposts an already-persisted entity without replaying system or custom alert audio. */
    @SuppressLint("MissingPermission")
    fun showEntityReplayNotificationSilently(
        context: Context,
        entityType: String,
        entityId: String,
        groupChannel: String?,
        eventId: String?,
        thingId: String?,
        title: String,
        body: String,
        level: String?,
    ): Boolean = showEntityNotification(
        context = context,
        entityType = entityType,
        entityId = entityId,
        groupChannel = groupChannel,
        eventId = eventId,
        thingId = thingId,
        title = title,
        body = body,
        level = level,
        postBehavior = NotificationPostBehavior.SILENT_REPLAY,
    )

    @SuppressLint("MissingPermission")
    private fun showEntityNotification(
        context: Context,
        entityType: String,
        entityId: String,
        groupChannel: String?,
        eventId: String?,
        thingId: String?,
        title: String,
        body: String,
        level: String?,
        postBehavior: NotificationPostBehavior,
    ): Boolean {
        if (!canPostNotifications(context)) return true
        val kind = entityKind(entityType) ?: return true
        if (!shouldPostSystemNotification(context, kind, level)) return true
        val channelId = ensureChannel(context, level)
        val route = routeForEntity(
            entityType = entityType,
            entityId = entityId,
            channel = groupChannel,
            eventId = eventId,
            thingId = thingId,
        )
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            data = launchIdentity(entityType, entityId)
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
            .setOnlyAlertOnce(postBehavior.onlyAlertOnce)
            .setPriority(profile.compatPriority)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setVisibility(profile.lockscreenVisibility)
            .setGroup(route.groupKey)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setLights(profile.lightColor, 1_500, 1_000)
            .setDeleteIntent(alertStopDeleteIntent(context, route.notificationId))
            .addExtras(entityNotificationMetadata(eventId, thingId))
            .addExtras(notificationInstanceMetadata(entityType, entityId))

        if (postBehavior.silenceSystemAlert) {
            builder.setSilent(true)
        } else if (profile.shouldVibrate) {
            builder.setVibrate(profile.vibrationPattern)
        }

        return runCatching {
            synchronized(notificationInstanceLock) {
                postChild(context, route.notificationId, builder.build())
                if (postBehavior.startCustomAlert) {
                    AlertPlaybackController.startOrUpdate(context, route.notificationId, level, channelId)
                }
                reconcileActiveNotificationGroups(context)
                true
            }
        }.getOrElse { error ->
            io.ethan.pushgo.util.SilentSink.w(TAG, "entity notification display failed", error)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun reconcileActiveNotificationGroups(context: Context) = reconcileActiveNotificationGroups(context, emptySet())

    private fun reconcileActiveNotificationGroups(
        context: Context,
        excludedKeys: Set<Pair<String?, Int>>,
    ) = synchronized(notificationInstanceLock) {
        if (!canPostNotifications(context)) return@synchronized
        val managerCompat = NotificationManagerCompat.from(context)
        // A submitted child may still be queued in system_server. Canceling its summary
        // would cascade to that child when the cancellation runnable reaches the group.
        val instances = notificationInstances(context).filter { (it.tag to it.id) !in excludedKeys }
        val activeSummaries = instances.filter {
            it.summary && it.group?.startsWith(NOTIFICATION_GROUP_PREFIX) == true
        }
        val childrenByGroup = instances.filter {
            it.id != PrivateChannelForegroundService.NOTIFICATION_ID && !it.summary &&
                it.group?.startsWith(NOTIFICATION_GROUP_PREFIX) == true
        }.groupBy { it.group.orEmpty() }
        activeSummaries.forEach { summary ->
            if (childrenByGroup[summary.group].isNullOrEmpty()) {
                managerCompat.cancel(summary.tag, summary.id)
            }
        }
        val existingSummaryGroups = activeSummaries.mapTo(hashSetOf()) { it.group }
        childrenByGroup.forEach { (groupKey, children) ->
            updateGroupSummary(
                context = context,
                groupKey = groupKey,
                channelId = children.first().channelId,
                childCount = children.size,
                hasExistingSummary = groupKey in existingSummaryGroups,
            )
        }
    }

    internal fun captureDeletionNotifications(
        context: Context,
        operation: PendingLocalDeletionOperation,
        entityKeys: Set<Pair<String, String>> = emptySet(),
        associatedMessageIds: Set<String> = emptySet(),
    ): List<DeletionNotificationCleanupEntity> = synchronized(notificationInstanceLock) {
        notificationInstances(context).asSequence()
            .filter { instance ->
                instance.id != PrivateChannelForegroundService.NOTIFICATION_ID && !instance.summary &&
                    matchesDeletion(instance.ownKind, instance.ownId, instance.eventId, instance.thingId,
                        operation, entityKeys, associatedMessageIds)
            }.map { instance ->
                DeletionNotificationCleanupEntity(tag = instance.tag, notificationId = instance.id,
                    postTime = instance.postTime, generation = instance.generation)
            }.toList()
    }

    /** Own identity is exact; associated IDs intentionally also cover entity deletion consumers. */
    internal fun notificationMatchesDeletionCapture(
        notification: Notification,
        operation: PendingLocalDeletionOperation,
        entityKeys: Set<Pair<String, String>> = emptySet(),
        associatedMessageIds: Set<String> = emptySet(),
    ): Boolean {
        val extras = notification.extras
        return matchesDeletion(extras.getString(NOTIFICATION_METADATA_OWN_KIND),
            extras.getString(NOTIFICATION_METADATA_OWN_ID), extras.getString(NOTIFICATION_METADATA_EVENT_ID),
            extras.getString(NOTIFICATION_METADATA_THING_ID), operation, entityKeys, associatedMessageIds)
    }

    private fun matchesDeletion(
        ownKind: String?, ownId: String?, eventId: String?, thingId: String?,
        operation: PendingLocalDeletionOperation, entityKeys: Set<Pair<String, String>>,
        associatedMessageIds: Set<String>,
    ): Boolean {
        val matchesOwnEntity = ownKind != null && ownId != null && (ownKind to ownId) in entityKeys
        // Never infer legacy message identity from a hash or opaque PendingIntent.
        return when (operation.kind) {
            PendingLocalDeletionKind.MESSAGES -> ownKind == "message" && ownId in operation.targetIds
            PendingLocalDeletionKind.EVENTS -> ownKind != "message" &&
                (matchesOwnEntity || eventId in operation.targetIds)
            PendingLocalDeletionKind.THINGS -> if (ownKind == "message") ownId in associatedMessageIds
                else matchesOwnEntity || thingId in operation.targetIds
            else -> false
        }
    }

    internal fun reconcileDeletionNotifications(
        context: Context,
        receipts: List<DeletionNotificationCleanupEntity>,
    ) = synchronized(notificationInstanceLock) {
        val managerCompat = NotificationManagerCompat.from(context)
        val active = activeNotifications(context)
        val currentByKey = active.associateBy { it.tag to it.id }
        val canceledKeys = linkedSetOf<Pair<String?, Int>>()
        receipts.forEach { receipt ->
            val current = currentByKey[receipt.tag to receipt.notificationId]
            val exactGenerationTag = receipt.generation != null &&
                receipt.tag == CHILD_INSTANCE_TAG_PREFIX + receipt.generation
            val currentGeneration = current?.notification?.extras?.getString(NOTIFICATION_METADATA_GENERATION)
            val matchingLegacy = current != null && current.postTime == receipt.postTime &&
                currentGeneration == receipt.generation
            if (exactGenerationTag || matchingLegacy) {
                val exactSubmitted = submitted.values.any {
                    it.tag == receipt.tag && it.id == receipt.notificationId &&
                        it.generation == receipt.generation
                }
                // Exact UUID keys also cancel submissions still queued in system_server.
                managerCompat.cancel(receipt.tag, receipt.notificationId)
                canceledKeys += receipt.tag to receipt.notificationId
                submitted.entries.removeAll {
                    it.value.tag == receipt.tag && it.value.id == receipt.notificationId
                }
                if ((current != null || exactSubmitted) && active.none {
                        it.id == receipt.notificationId && it.tag != receipt.tag
                    } && submitted.values.none {
                        it.id == receipt.notificationId && it.tag != receipt.tag
                    }) {
                    AlertPlaybackController.stopForNotification(context, receipt.notificationId)
                }
            }
        }
        reconcileActiveNotificationGroups(context, canceledKeys)
    }

    fun cancelMessageNotification(context: Context, messageId: String) =
        cancelMessageNotifications(context, listOf(messageId))

    fun cancelMessageNotifications(context: Context, messageIds: List<String>) = synchronized(notificationInstanceLock) {
        val ids = messageIds.filter(String::isNotBlank).toSet()
        if (ids.isEmpty()) return@synchronized
        cancelMatching(context) { it.ownKind == "message" && it.ownId in ids }
        ids.forEach { cancelLegacyId(context, routeForMessageId(it).notificationId) }
        reconcileActiveNotificationGroups(context)
    }

    fun cancelEntityNotifications(context: Context, entityKeys: Collection<Pair<String, String>>) =
        synchronized(notificationInstanceLock) {
            val keys = entityKeys.map { it.first.trim() to it.second.trim() }
                .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toSet()
            cancelMatching(context) { it.ownKind != null && it.ownId != null && (it.ownKind to it.ownId) in keys }
            keys.forEach { cancelLegacyId(context, "${it.first}:${it.second}".hashCode()) }
            reconcileActiveNotificationGroups(context)
        }

    /** Cancels the event and any thing/event notification whose persisted metadata references it. */
    fun cancelEventNotifications(context: Context, eventIds: Collection<String>) {
        cancelEntityDeletionNotifications(
            context = context,
            eventIds = eventIds,
            thingIds = emptySet(),
        )
    }

    /** Cancels the thing and any associated entity notification carrying its thing metadata. */
    fun cancelThingNotifications(context: Context, thingIds: Collection<String>) {
        cancelEntityDeletionNotifications(
            context = context,
            eventIds = emptySet(),
            thingIds = thingIds,
        )
    }

    private fun cancelEntityDeletionNotifications(
        context: Context, eventIds: Collection<String>, thingIds: Collection<String>,
    ) = synchronized(notificationInstanceLock) {
        val events = eventIds.normalizeNotificationIds()
        val things = thingIds.normalizeNotificationIds()
        if (events.isEmpty() && things.isEmpty()) return@synchronized
        cancelMatching(context) {
            (it.ownKind != "message" && it.eventId in events) || it.thingId in things ||
                (it.ownKind == "event" && it.ownId in events) ||
                (it.ownKind == "thing" && it.ownId in things) ||
                (it.ownKind != "message" && notificationGroupMatchesEntityDeletion(it.group, events, things))
        }
        events.forEach { cancelLegacyId(context, "event:$it".hashCode()) }
        things.forEach { cancelLegacyId(context, "thing:$it".hashCode()) }
        reconcileActiveNotificationGroups(context)
    }

    internal fun notificationGroupMatchesEntityDeletion(
        groupKey: String?,
        eventIds: Set<String>,
        thingIds: Set<String>,
    ): Boolean {
        val group = groupKey?.takeIf { it.startsWith(NOTIFICATION_GROUP_PREFIX) } ?: return false
        val parts = group.removePrefix(NOTIFICATION_GROUP_PREFIX).split('|').toSet()
        return eventIds.any { "event=$it" in parts } || thingIds.any { "thing=$it" in parts }
    }

    private fun notificationMatchesEntityDeletion(
        notification: Notification,
        eventIds: Set<String>,
        thingIds: Set<String>,
    ): Boolean {
        val metadataEventId = notification.extras.getString(NOTIFICATION_METADATA_EVENT_ID)?.trim()
        val metadataThingId = notification.extras.getString(NOTIFICATION_METADATA_THING_ID)?.trim()
        return (metadataEventId != null && metadataEventId in eventIds) ||
            (metadataThingId != null && metadataThingId in thingIds) ||
            notificationGroupMatchesEntityDeletion(notification.group, eventIds, thingIds)
    }

    /**
     * Replays channel notification cleanup after its Room rows have already been deleted.
     * Notification groups persist outside the app process and encode the normalized channel ID,
     * so this does not depend on querying deleted message/entity rows.
     */
    fun cancelChannelNotifications(context: Context, channelId: String) = synchronized(notificationInstanceLock) {
        val normalized = channelId.trim()
        if (normalized.isEmpty()) return@synchronized
        cancelMatching(context) { notificationGroupBelongsToChannel(it.group, normalized) }
        reconcileActiveNotificationGroups(context)
    }

    internal fun notificationGroupBelongsToChannel(groupKey: String?, channelId: String): Boolean {
        val normalizedChannelId = channelId.trim()
        if (normalizedChannelId.isEmpty()) return false
        val group = groupKey?.takeIf { it.startsWith(NOTIFICATION_GROUP_PREFIX) } ?: return false
        return group
            .removePrefix(NOTIFICATION_GROUP_PREFIX)
            .split('|')
            .any { part -> part == "channel=$normalizedChannelId" }
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
        hasExistingSummary: Boolean,
    ) {
        val resolvedChannelId = channelId?.takeIf { it.isNotBlank() } ?: return
        val managerCompat = NotificationManagerCompat.from(context)
        if (childCount == 0) {
            managerCompat.cancel(summaryNotificationId(groupKey))
            return
        }
        // Removing a summary cancels its remaining child on Android. Retain/update an
        // existing summary for one child; a new one is needed only for two or more.
        if (childCount == 1 && !hasExistingSummary) return
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
            .addExtras(notificationInstanceMetadata("summary", groupKey))
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
        normalizedGroupPart("thing", thingId)?.let(parts::add)
        if (parts.size == 1) {
            parts += "scope=global"
        }
        return NOTIFICATION_GROUP_PREFIX + parts.joinToString("|")
    }

    private fun normalizedGroupPart(label: String, value: String?): String? {
        val normalized = value?.trim()?.ifEmpty { null } ?: return null
        return "$label=$normalized"
    }

    private fun entityNotificationMetadata(eventId: String?, thingId: String?): Bundle = Bundle().apply {
        eventId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            putString(NOTIFICATION_METADATA_EVENT_ID, it)
        }
        thingId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            putString(NOTIFICATION_METADATA_THING_ID, it)
        }
    }

    private fun notificationInstanceMetadata(kind: String, id: String): Bundle = Bundle().apply {
        putString(NOTIFICATION_METADATA_OWN_KIND, kind)
        putString(NOTIFICATION_METADATA_OWN_ID, id)
        putString(NOTIFICATION_METADATA_GENERATION, UUID.randomUUID().toString())
    }

    private fun Collection<String>.normalizeNotificationIds(): Set<String> = asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

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
        return !ForegroundNotificationPresentationState.shouldSuppress(notificationEntityType(kind))
    }

    private fun notificationEntityType(kind: NotificationKind): String {
        return when (kind) {
            NotificationKind.MESSAGE -> "message"
            NotificationKind.EVENT -> "event"
            NotificationKind.THING -> "thing"
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
        val normalized = normalizeNotificationPresentationLevel(level)
            ?.trim()
            ?.lowercase()
            .orEmpty()
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
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE,
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
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE,
                allowBubbles = true,
                lightColor = Color.WHITE,
            )
            "normal" -> LevelProfile(
                channelTag = "normal",
                isCritical = false,
                isHighPriority = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                compatPriority = NotificationCompat.PRIORITY_HIGH,
                enableSound = true,
                shouldVibrate = true,
                vibrationPattern = longArrayOf(0L, 80L),
                bypassDnd = false,
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE,
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
                isHighPriority = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
                compatPriority = NotificationCompat.PRIORITY_HIGH,
                enableSound = true,
                shouldVibrate = true,
                vibrationPattern = longArrayOf(0L, 80L),
                bypassDnd = false,
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE,
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
