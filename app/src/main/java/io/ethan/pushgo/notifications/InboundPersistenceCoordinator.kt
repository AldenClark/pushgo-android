package io.ethan.pushgo.notifications

import android.content.Context
import android.util.Log
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.InboundDeliveryLedgerRepository
import io.ethan.pushgo.data.IncomingEntityRecord
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.model.PushMessage

sealed interface InboundPersistenceRequest {
    data class Message(
        val message: PushMessage,
        val level: String?,
        val imageUrl: String?,
        val shouldNotify: Boolean,
    ) : InboundPersistenceRequest

    data class Entity(
        val record: IncomingEntityRecord,
        val level: String?,
        val notificationTitle: String,
        val notificationBody: String,
        val shouldNotify: Boolean,
        val hasExplicitTitle: Boolean = true,
    ) : InboundPersistenceRequest
}

enum class InboundPersistenceStatus {
    PERSISTED,
    DUPLICATE,
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
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit = { _, _ -> },
    ): InboundPersistenceOutcome {
        return when (inbound) {
            is InboundPersistenceRequest.Message -> persistMessage(
                context = context,
                messageRepository = messageRepository,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                settingsRepository = settingsRepository,
                inbound = inbound,
                beforeMessageNotify = beforeMessageNotify,
            )

            is InboundPersistenceRequest.Entity -> persistEntity(
                context = context,
                entityRepository = entityRepository,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                settingsRepository = settingsRepository,
                inbound = inbound,
            )
        }
    }

    private suspend fun persistMessage(
        context: Context,
        messageRepository: MessageRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        inbound: InboundPersistenceRequest.Message,
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit,
    ): InboundPersistenceOutcome {
        val inserted = runCatching { messageRepository.insertIncoming(inbound.message) }
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
        if (!inserted) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.DUPLICATE,
                notified = false,
                shouldAck = inboundDeliveryLedgerRepository.shouldAck(inbound.message.deliveryId),
            )
        }

        settingsRepository.reenablePageForEntity("message")
        if (!inbound.shouldNotify) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.PERSISTED,
                notified = false,
                shouldAck = inboundDeliveryLedgerRepository.shouldAck(inbound.message.deliveryId),
            )
        }

        beforeMessageNotify(inbound.message, inbound.imageUrl)
        val unreadCount = runCatching { messageRepository.unreadCount() }
            .onFailure { error -> io.ethan.pushgo.util.SilentSink.w(TAG, "message unread count unavailable", error) }
            .getOrNull()
        NotificationHelper.showMessageNotification(
            context = context,
            message = inbound.message,
            level = inbound.level,
            unreadCount = unreadCount,
        )
        return InboundPersistenceOutcome(
            status = InboundPersistenceStatus.PERSISTED,
            notified = true,
            shouldAck = inboundDeliveryLedgerRepository.shouldAck(inbound.message.deliveryId),
        )
    }

    private suspend fun persistEntity(
        context: Context,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        inbound: InboundPersistenceRequest.Entity,
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
        val inserted = runCatching { entityRepository.insertIncoming(resolvedInbound.record) }
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
        settingsRepository.reenablePageForEntity(resolvedInbound.record.entityType)
        if (!inserted) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.DUPLICATE,
                notified = false,
                shouldAck = inboundDeliveryLedgerRepository.shouldAck(resolvedInbound.record.deliveryId),
            )
        }
        if (!resolvedInbound.shouldNotify) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.PERSISTED,
                notified = false,
                shouldAck = inboundDeliveryLedgerRepository.shouldAck(resolvedInbound.record.deliveryId),
            )
        }

        NotificationHelper.showEntityNotification(
            context = context,
            entityType = resolvedInbound.record.entityType,
            entityId = resolvedInbound.record.entityId,
            groupChannel = resolvedInbound.record.channel,
            title = resolvedInbound.notificationTitle,
            body = resolvedInbound.notificationBody,
            level = resolvedInbound.level,
        )
        return InboundPersistenceOutcome(
            status = InboundPersistenceStatus.PERSISTED,
            notified = true,
            shouldAck = inboundDeliveryLedgerRepository.shouldAck(resolvedInbound.record.deliveryId),
        )
    }
}
