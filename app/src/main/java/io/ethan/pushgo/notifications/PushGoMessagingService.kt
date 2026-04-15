package io.ethan.pushgo.notifications

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PushGoMessagingService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "PushGoMessagingService"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as PushGoApp
        val container = app.containerOrNull()
        if (container == null) {
            io.ethan.pushgo.util.SilentSink.e(TAG, "onMessageReceived ignored: local storage unavailable")
            return
        }
        runCatching {
            runBlocking {
                withContext(Dispatchers.IO) {
                    handleInboundMessage(
                        message = message,
                        container = container,
                    )
                }
            }
        }.onFailure { error ->
            io.ethan.pushgo.util.SilentSink.e(TAG, "onMessageReceived failed", error)
        }
    }

    override fun onNewToken(token: String) {
        val app = application as PushGoApp
        app.handlePushTokenUpdate(token)
    }

    private suspend fun handleInboundMessage(
        message: RemoteMessage,
        container: io.ethan.pushgo.data.AppContainer,
    ) {
        val providerPullDeliveryId =
            NotificationIngressParser.providerWakeupPullDeliveryId(message.data)
        if (providerPullDeliveryId != null) {
            handleProviderWakeupPull(
                channelRepository = container.channelRepository,
                messageRepository = container.messageRepository,
                entityRepository = container.entityRepository,
                inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
                settingsRepository = container.settingsRepository,
                deliveryId = providerPullDeliveryId,
            )
            return
        }
        val parsed = parseMessage(message, container.settingsRepository)
        if (parsed != null) {
            val outcome = handleInbound(
                messageRepository = container.messageRepository,
                entityRepository = container.entityRepository,
                inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
                settingsRepository = container.settingsRepository,
                parsed = parsed,
            )
            ProviderIngressCoordinator.ackDirectDeliveryIfNeeded(
                context = applicationContext,
                inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
                inbound = parsed,
                outcome = outcome,
            )
        }
    }

    private suspend fun handleInbound(
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: io.ethan.pushgo.data.InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        parsed: InboundPersistenceRequest,
    ): InboundPersistenceOutcome {
        return InboundPersistenceCoordinator.persistAndNotify(
            context = applicationContext,
            messageRepository = messageRepository,
            entityRepository = entityRepository,
            inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
            settingsRepository = settingsRepository,
            inbound = parsed,
        ) { message, imageUrl ->
            enqueuePostProcess(message.id, imageUrl)
        }
    }

    private suspend fun handleProviderWakeupPull(
        channelRepository: ChannelSubscriptionRepository,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: io.ethan.pushgo.data.InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        deliveryId: String,
    ) {
        runCatching {
            ProviderIngressCoordinator.pullPersistAndDrainAcks(
                context = applicationContext,
                channelRepository = channelRepository,
                messageRepository = messageRepository,
                entityRepository = entityRepository,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                settingsRepository = settingsRepository,
                deliveryId = deliveryId,
            ) { message, imageUrl ->
                enqueuePostProcess(message.id, imageUrl)
            }
        }
            .onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "provider wakeup pull failed deliveryId=$deliveryId",
                    error,
                )
            }
    }

    private suspend fun parseMessage(
        message: RemoteMessage,
        settingsRepository: SettingsRepository,
    ): InboundPersistenceRequest? {
        val keyBytes = settingsRepository.getNotificationKeyBytes()
        return NotificationIngressParser.parse(
            data = message.data,
            transportMessageId = message.messageId,
            keyBytes = keyBytes,
            textLocalizer = NotificationIngressParser.NotificationTextLocalizer.fromContext(
                applicationContext
            ),
        )
    }

    private fun enqueuePostProcess(
        messageId: String,
        imageUrl: String?,
    ) {
        val input = workDataOf(
            MessagePostProcessWorker.KEY_MESSAGE_ID to messageId,
            MessagePostProcessWorker.KEY_IMAGE_URL to imageUrl,
        )
        val request = OneTimeWorkRequestBuilder<MessagePostProcessWorker>()
            .setInputData(input)
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }
}
