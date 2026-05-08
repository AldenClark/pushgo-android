package io.ethan.pushgo.notifications

import android.content.Context
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.InboundDeliveryLedgerRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.model.PushMessage

object ProviderIngressCoordinator {
    suspend fun pullPersistAndDrainAcks(
        context: Context,
        channelRepository: ChannelSubscriptionRepository,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        deliveryId: String? = null,
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit = { _, _ -> },
    ): Int {
        return try {
            pullAndPersist(
                context = context,
                channelRepository = channelRepository,
                messageRepository = messageRepository,
                entityRepository = entityRepository,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                settingsRepository = settingsRepository,
                deliveryId = deliveryId,
                beforeMessageNotify = beforeMessageNotify,
            )
        } finally {
            enqueueAckDrainIfNeeded(
                context = context,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
            )
        }
    }

    suspend fun pullAndPersist(
        context: Context,
        channelRepository: ChannelSubscriptionRepository,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        deliveryId: String? = null,
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit = { _, _ -> },
    ): Int {
        runCatching {
            repairProviderRouteSnapshotIfNeeded(
                channelRepository = channelRepository,
                settingsRepository = settingsRepository,
            )
        }
        val items = channelRepository.pullMessages(deliveryId)
        if (items.isEmpty()) {
            return 0
        }
        val keyBytes = settingsRepository.getNotificationKeyBytes()
        var persisted = 0
        for (item in items) {
            val parsed = NotificationIngressParser.parse(
                data = item.payload,
                transportMessageId = item.deliveryId,
                keyBytes = keyBytes,
                textLocalizer = NotificationIngressParser.NotificationTextLocalizer.fromContext(context),
            )
            if (parsed == null) continue
            val outcome = InboundPersistenceCoordinator.persistAndNotify(
                context = context,
                messageRepository = messageRepository,
                entityRepository = entityRepository,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                settingsRepository = settingsRepository,
                inbound = parsed,
                beforeMessageNotify = beforeMessageNotify,
            )
            if (outcome.status != InboundPersistenceStatus.FAILED) {
                persisted += 1
            }
            if (outcome.shouldAck) {
                // /messages/pull already consumes the delivery on gateway side; this only
                // clears any stale local direct-ack backlog for the same delivery.
                inboundDeliveryId(parsed)?.let {
                    inboundDeliveryLedgerRepository.markAcked(listOf(it))
                }
            }
        }
        return persisted
    }

    private suspend fun repairProviderRouteSnapshotIfNeeded(
        channelRepository: ChannelSubscriptionRepository,
        settingsRepository: SettingsRepository,
    ) {
        if (!settingsRepository.getUseFcmChannel()) {
            return
        }
        val token = settingsRepository.getFcmToken()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        channelRepository.syncProviderDeviceToken(token)
        channelRepository.syncSubscriptionsIfNeeded(token)
    }

    suspend fun ackDirectDeliveryIfNeeded(
        context: Context,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        inbound: InboundPersistenceRequest,
        outcome: InboundPersistenceOutcome,
    ) {
        if (!outcome.shouldAck) return
        val deliveryId = inboundDeliveryId(inbound) ?: return
        inboundDeliveryLedgerRepository.enqueueAcks(
            deliveryIds = listOf(deliveryId),
            source = "provider_direct",
        )
        InboundDeliveryAckWorker.enqueue(context, deliveryId)
    }

    private suspend fun enqueueAckDrainIfNeeded(
        context: Context,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
    ) {
        val hasPendingAcks = runCatching {
            inboundDeliveryLedgerRepository.loadPendingAckIds(limit = 1).isNotEmpty()
        }.getOrDefault(false)
        if (hasPendingAcks) {
            InboundDeliveryAckWorker.enqueueDrain(context)
        }
    }

    fun inboundDeliveryId(inbound: InboundPersistenceRequest): String? {
        val raw = when (inbound) {
            is InboundPersistenceRequest.Message -> inbound.message.deliveryId
            is InboundPersistenceRequest.Entity -> inbound.record.deliveryId
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}
