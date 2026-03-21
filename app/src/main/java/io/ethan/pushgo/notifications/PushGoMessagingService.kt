package io.ethan.pushgo.notifications

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PushGoMessagingService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "PushGoMessagingService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as PushGoApp
        val container = app.containerOrNull()
        if (container == null) {
            io.ethan.pushgo.util.SilentSink.e(TAG, "onMessageReceived ignored: local storage unavailable")
            return
        }
        if (NotificationIngressParser.isPrivateWakeupPayload(message.data)) {
            container.privateChannelClient.triggerWakeupPull()
            return
        }
        val messageRepository = container.messageRepository
        val entityRepository = container.entityRepository
        val settingsRepository = container.settingsRepository
        serviceScope.launch {
            val parsed = parseMessage(message, settingsRepository)
            if (parsed != null) {
                handleInbound(
                    messageRepository = messageRepository,
                    entityRepository = entityRepository,
                    settingsRepository = settingsRepository,
                    parsed = parsed,
                )
            }
        }
    }

    override fun onNewToken(token: String) {
        val app = application as PushGoApp
        app.handlePushTokenUpdate(token)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private suspend fun handleInbound(
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        settingsRepository: SettingsRepository,
        parsed: InboundPersistenceRequest,
    ) {
        InboundPersistenceCoordinator.persistAndNotify(
            context = applicationContext,
            messageRepository = messageRepository,
            entityRepository = entityRepository,
            inboundDeliveryLedgerRepository = (application as PushGoApp).container.inboundDeliveryLedgerRepository,
            settingsRepository = settingsRepository,
            inbound = parsed,
        ) { message, imageUrl ->
            enqueuePostProcess(message.id, imageUrl)
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
