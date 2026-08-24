package io.ethan.pushgo.notifications

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.InboundDeliveryScope
import io.ethan.pushgo.data.InboundDeliveryLedgerRepository
import io.ethan.pushgo.data.IncomingEntityRecord
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.ProviderAckIdentity
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.inboundDeliveryScope
import io.ethan.pushgo.data.model.MessageSeverity
import io.ethan.pushgo.data.model.PushMessage
import java.util.concurrent.TimeUnit

internal data class MessagePostProcessHandoff(
    val uniqueWorkName: String,
    val messageId: String,
    val imageUrl: String?,
)

internal fun messagePostProcessHandoff(
    messageId: String,
    imageUrl: String?,
): MessagePostProcessHandoff = MessagePostProcessHandoff(
    uniqueWorkName = "message-post-process:$messageId",
    messageId = messageId,
    imageUrl = imageUrl,
)

internal fun enqueueMessagePostProcess(
    context: Context,
    messageId: String,
    imageUrl: String?,
) {
    val handoff = messagePostProcessHandoff(messageId, imageUrl)
    val input = workDataOf(
        MessagePostProcessWorker.KEY_MESSAGE_ID to handoff.messageId,
        MessagePostProcessWorker.KEY_IMAGE_URL to handoff.imageUrl,
    )
    val request = OneTimeWorkRequestBuilder<MessagePostProcessWorker>()
        .setInputData(input)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        handoff.uniqueWorkName,
        ExistingWorkPolicy.KEEP,
        request,
    )
}

sealed interface InboundPersistenceRequest {
    data class Message(
        val message: PushMessage,
        val level: String?,
        val imageUrl: String?,
        val shouldNotify: Boolean,
        val providerAckIdentity: ProviderAckIdentity? = null,
    ) : InboundPersistenceRequest

    data class Entity(
        val record: IncomingEntityRecord,
        val level: String?,
        val notificationTitle: String,
        val notificationBody: String,
        val shouldNotify: Boolean,
        val hasExplicitTitle: Boolean = true,
        val providerAckIdentity: ProviderAckIdentity? = null,
    ) : InboundPersistenceRequest
}

enum class InboundPersistenceStatus {
    PERSISTED_MAIN,
    PERSISTED_PENDING,
    DUPLICATE,
    REJECTED,
    FAILED,
}

data class InboundPersistenceOutcome(
    val status: InboundPersistenceStatus,
    val notified: Boolean,
    val shouldAck: Boolean,
)

object InboundPersistenceCoordinator {
    private const val TAG = "InboundPersistence"

    suspend fun persistAndNotify(
        context: Context,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        inbound: InboundPersistenceRequest,
        deliveryScope: InboundDeliveryScope? = null,
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit = { _, _ -> },
    ): InboundPersistenceOutcome {
        return when (inbound) {
            is InboundPersistenceRequest.Message -> persistMessage(
                context = context,
                messageRepository = messageRepository,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                settingsRepository = settingsRepository,
                inbound = inbound,
                deliveryScope = deliveryScope ?: inbound.providerAckIdentity.inboundDeliveryScope(),
                beforeMessageNotify = beforeMessageNotify,
            )

            is InboundPersistenceRequest.Entity -> persistEntity(
                context = context,
                messageRepository = messageRepository,
                entityRepository = entityRepository,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                settingsRepository = settingsRepository,
                inbound = inbound,
                deliveryScope = deliveryScope ?: inbound.providerAckIdentity.inboundDeliveryScope(),
            )
        }
    }

    private suspend fun persistMessage(
        context: Context,
        messageRepository: MessageRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        inbound: InboundPersistenceRequest.Message,
        deliveryScope: InboundDeliveryScope?,
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit,
    ): InboundPersistenceOutcome {
        val inserted = runCatching {
            messageRepository.insertIncoming(
                message = inbound.message,
                providerAckIdentity = inbound.providerAckIdentity,
                deliveryScope = deliveryScope,
            )
        }
            .onFailure { error ->
                io.ethan.pushgo.util.SilentSink.e(TAG, "message persist failed", error)
                PushGoAutomation.recordRuntimeError(
                    source = "inbound.persist.message",
                    error = error,
                    category = "storage",
                )
            }
            .getOrNull()
        if (inserted == null) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.FAILED,
                notified = false,
                shouldAck = false,
            )
        }
        settingsRepository.reenablePageForEntity("message")
        if (!inserted) {
            val pending = messageRepository.wouldPersistAsPending(inbound.message)
            val shouldAck = inboundDeliveryLedgerRepository.shouldAckDelivery(
                deliveryId = inbound.message.deliveryId,
                scope = deliveryScope,
            )
            if (shouldAck && !pending) {
                val stableMessageId = inbound.message.messageId?.trim()?.takeIf { it.isNotEmpty() }
                val canonicalMessage = resolveCanonicalMessageForReplay(inbound.message) { messageId ->
                    messageRepository.getByMessageId(messageId)
                }
                if (canonicalMessage == null) {
                    io.ethan.pushgo.util.SilentSink.w(
                        TAG,
                        "duplicate message replay skipped: canonical row missing messageId=$stableMessageId",
                    )
                    return InboundPersistenceOutcome(
                        status = InboundPersistenceStatus.DUPLICATE,
                        notified = false,
                        shouldAck = shouldAck,
                    )
                }
                // Resolve media from the canonical payload too; a replay payload may differ.
                beforeMessageNotify(canonicalMessage, null)
                if (
                    inbound.shouldNotify &&
                    !NotificationHelper.showMessageReplayNotificationSilently(
                        context = context,
                        message = canonicalMessage,
                        level = canonicalNotificationLevel(canonicalMessage, inbound.level),
                    )
                ) {
                    return InboundPersistenceOutcome(
                        status = InboundPersistenceStatus.FAILED,
                        notified = false,
                        shouldAck = false,
                    )
                }
            }
            return InboundPersistenceOutcome(
                status = if (pending) {
                    InboundPersistenceStatus.PERSISTED_PENDING
                } else {
                    InboundPersistenceStatus.DUPLICATE
                },
                notified = false,
                shouldAck = shouldAck,
            )
        }

        beforeMessageNotify(inbound.message, inbound.imageUrl)
        if (!inbound.shouldNotify) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.PERSISTED_MAIN,
                notified = false,
                shouldAck = inboundDeliveryLedgerRepository.shouldAckDelivery(
                    deliveryId = inbound.message.deliveryId,
                    scope = deliveryScope,
                ),
            )
        }

        if (!NotificationHelper.showMessageNotification(
                context = context,
                message = inbound.message,
                level = inbound.level,
            )
        ) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.FAILED,
                notified = false,
                shouldAck = false,
            )
        }
        return InboundPersistenceOutcome(
            status = InboundPersistenceStatus.PERSISTED_MAIN,
            notified = true,
            shouldAck = inboundDeliveryLedgerRepository.shouldAckDelivery(
                deliveryId = inbound.message.deliveryId,
                scope = deliveryScope,
            ),
        )
    }

    private suspend fun persistEntity(
        context: Context,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        inbound: InboundPersistenceRequest.Entity,
        deliveryScope: InboundDeliveryScope?,
    ): InboundPersistenceOutcome {
        val eventFallbackTitle = if (
            inbound.record.entityType == "event" &&
                !inbound.hasExplicitTitle
        ) {
            val fallbackEventId = inbound.record.eventId?.trim()?.takeIf { it.isNotEmpty() }
                ?: inbound.record.entityId
            runCatching { entityRepository.resolveStoredEventTitle(fallbackEventId) }
                .onFailure { error ->
                    io.ethan.pushgo.util.SilentSink.w(
                        TAG,
                        "event title fallback lookup failed eventId=$fallbackEventId",
                        error,
                    )
                }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val resolvedTitle = eventFallbackTitle ?: inbound.notificationTitle
        val resolvedInbound = if (resolvedTitle == inbound.notificationTitle) {
            inbound
        } else {
            inbound.copy(
                record = inbound.record.copy(title = resolvedTitle),
                notificationTitle = resolvedTitle,
            )
        }
        val inserted = runCatching {
            entityRepository.insertIncoming(
                resolvedInbound.record,
                resolvedInbound.providerAckIdentity,
                deliveryScope,
            )
        }
            .onFailure { error ->
                io.ethan.pushgo.util.SilentSink.e(
                    TAG,
                    "entity persist failed type=${resolvedInbound.record.entityType} id=${resolvedInbound.record.entityId}",
                    error,
                )
                PushGoAutomation.recordRuntimeError(
                    source = "inbound.persist.${resolvedInbound.record.entityType}",
                    error = error,
                    category = "storage",
                )
            }
            .getOrNull()
        if (inserted == null) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.FAILED,
                notified = false,
                shouldAck = false,
            )
        }
        val displayInbound = resolveEntityNotificationDisplayAfterPersist(
            entityRepository = entityRepository,
            inbound = resolvedInbound,
        )
        settingsRepository.reenablePageForEntity(displayInbound.record.entityType)
        if (!inserted) {
            val pending = entityRepository.wouldPersistAsPending(displayInbound.record)
            val shouldAck = inboundDeliveryLedgerRepository.shouldAckDelivery(
                deliveryId = displayInbound.record.deliveryId,
                scope = deliveryScope,
            )
            if (shouldAck && !pending && displayInbound.shouldNotify) {
                if (!showEntityReplayNotificationSilently(context, displayInbound)) {
                    return InboundPersistenceOutcome(
                        status = InboundPersistenceStatus.FAILED,
                        notified = false,
                        shouldAck = false,
                    )
                }
            }
            if (shouldAck && !pending && displayInbound.record.entityType == "thing") {
                replayPendingThingChildren(
                    messageRepository = messageRepository,
                    entityRepository = entityRepository,
                    inbound = displayInbound,
                )
            }
            return InboundPersistenceOutcome(
                status = if (pending) {
                    InboundPersistenceStatus.PERSISTED_PENDING
                } else {
                    InboundPersistenceStatus.DUPLICATE
                },
                notified = false,
                shouldAck = shouldAck,
            )
        }
        if (!displayInbound.shouldNotify) {
            if (displayInbound.record.entityType == "thing") {
                replayPendingThingChildren(messageRepository, entityRepository, displayInbound)
            }
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.PERSISTED_MAIN,
                notified = false,
                shouldAck = inboundDeliveryLedgerRepository.shouldAckDelivery(
                    deliveryId = displayInbound.record.deliveryId,
                    scope = deliveryScope,
                ),
            )
        }

        if (!showEntityNotification(context, displayInbound)) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.FAILED,
                notified = false,
                shouldAck = false,
            )
        }
        if (displayInbound.record.entityType == "thing") {
            replayPendingThingChildren(messageRepository, entityRepository, displayInbound)
        }
        return InboundPersistenceOutcome(
            status = InboundPersistenceStatus.PERSISTED_MAIN,
            notified = true,
            shouldAck = inboundDeliveryLedgerRepository.shouldAckDelivery(
                deliveryId = displayInbound.record.deliveryId,
                scope = deliveryScope,
            ),
        )
    }

    private fun showEntityNotification(
        context: Context,
        inbound: InboundPersistenceRequest.Entity,
    ): Boolean = NotificationHelper.showEntityNotification(
        context = context,
        entityType = inbound.record.entityType,
        entityId = inbound.record.entityId,
        groupChannel = inbound.record.channel,
        eventId = inbound.record.eventId,
        thingId = inbound.record.thingId,
        title = inbound.notificationTitle,
        body = inbound.notificationBody,
        level = inbound.level,
    )

    private fun showEntityReplayNotificationSilently(
        context: Context,
        inbound: InboundPersistenceRequest.Entity,
    ): Boolean = NotificationHelper.showEntityReplayNotificationSilently(
        context = context,
        entityType = inbound.record.entityType,
        entityId = inbound.record.entityId,
        groupChannel = inbound.record.channel,
        eventId = inbound.record.eventId,
        thingId = inbound.record.thingId,
        title = inbound.notificationTitle,
        body = inbound.notificationBody,
        level = inbound.level,
    )

    private suspend fun replayPendingThingChildren(
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inbound: InboundPersistenceRequest.Entity,
    ) {
        val thingId = inbound.record.thingId?.trim()?.takeIf { it.isNotEmpty() }
            ?: inbound.record.entityId
        messageRepository.replayPendingForThing(thingId)
        entityRepository.replayPendingForThing(thingId)
    }

    private suspend fun resolveEntityNotificationDisplayAfterPersist(
        entityRepository: EntityRepository,
        inbound: InboundPersistenceRequest.Entity,
    ): InboundPersistenceRequest.Entity {
        if (inbound.record.entityType != "thing") {
            return inbound
        }
        val thingId = inbound.record.thingId?.trim()?.takeIf { it.isNotEmpty() }
            ?: inbound.record.entityId
        val storedTitle = runCatching { entityRepository.resolveStoredThingTitle(thingId) }
            .onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "thing title snapshot lookup failed thingId=$thingId",
                    error,
                )
            }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return inbound
        if (storedTitle == inbound.notificationTitle) {
            return inbound
        }
        return inbound.copy(
            record = inbound.record.copy(title = storedTitle),
            notificationTitle = storedTitle,
        )
    }
}

private fun canonicalNotificationLevel(message: PushMessage, fallback: String?): String? {
    return when (message.severity) {
        MessageSeverity.LOW -> "low"
        MessageSeverity.MEDIUM -> "normal"
        MessageSeverity.HIGH -> "high"
        MessageSeverity.CRITICAL -> "critical"
        null -> fallback
    }
}

internal suspend fun resolveCanonicalMessageForReplay(
    replay: PushMessage,
    loadByMessageId: suspend (String) -> PushMessage?,
): PushMessage? {
    val stableMessageId = replay.messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return loadByMessageId(stableMessageId)
}
