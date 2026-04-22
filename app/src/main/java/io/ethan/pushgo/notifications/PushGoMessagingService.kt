package io.ethan.pushgo.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.ethan.pushgo.PushGoApp

class PushGoMessagingService : FirebaseMessagingService() {
    companion object {
        private const val TAG = "PushGoMessagingService"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        runCatching {
            InboundMessageWorker.enqueue(
                context = applicationContext,
                messageData = message.data,
                transportMessageId = message.messageId,
            )
        }.onFailure { error ->
            io.ethan.pushgo.util.SilentSink.e(TAG, "onMessageReceived failed", error)
        }
    }

    override fun onNewToken(token: String) {
        val app = application as PushGoApp
        app.handlePushTokenUpdate(token)
    }
}
