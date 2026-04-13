package io.ethan.pushgo.notifications

import android.content.Context
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.InboundDeliveryLedgerRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.model.PushMessage

object ProviderIngressCoordinator {
    private const val TAG = "ProviderIngressCoordinator"

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
        val persisted = pullAndPersist(
            context = context,
            channelRepository = channelRepository,
            messageRepository = messageRepository,
            entityRepository = entityRepository,
            inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
            settingsRepository = settingsRepository,
            deliveryId = deliveryId,
            beforeMessageNotify = beforeMessageNotify,
        )
        return persisted
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
            if (parsed == null) {
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "provider pull payload rejected deliveryId=${item.deliveryId}",
                )
                continue
            }
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
                inboundDeliveryId(parsed)?.let { inboundDeliveryLedgerRepository.markAcked(listOf(it)) }
            }
        }
        return persisted
    }

    suspend fun ackDirectDeliveryIfNeeded(
        context: Context,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        inbound: InboundPersistenceRequest,
        outcome: InboundPersistenceOutcome,
    ) {
        if (!outcome.shouldAck) return
        val deliveryId = inboundDeliveryId(inbound) ?: return
        // Direct ACK is best effort: do not block ingress, do not retry.
        inboundDeliveryLedgerRepository.markAcked(listOf(deliveryId))
        InboundDeliveryAckWorker.enqueue(context, deliveryId)
    }

    fun inboundDeliveryId(inbound: InboundPersistenceRequest): String? {
        val raw = when (inbound) {
            is InboundPersistenceRequest.Message -> inbound.message.deliveryId
            is InboundPersistenceRequest.Entity -> inbound.record.deliveryId
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}
