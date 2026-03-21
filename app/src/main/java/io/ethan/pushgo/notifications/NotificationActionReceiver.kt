package io.ethan.pushgo.notifications

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import io.ethan.pushgo.PushGoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        private const val TAG = "NotificationActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra(NotificationHelper.EXTRA_MESSAGE_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as PushGoApp).containerOrNull()
                if (container == null) {
                    io.ethan.pushgo.util.SilentSink.e(TAG, "notification action ignored: local storage unavailable")
                    return@launch
                }
                val repository = container.messageRepository
                val stateCoordinator = container.messageStateCoordinator
                when (intent.action) {
                    NotificationHelper.ACTION_MARK_READ -> stateCoordinator.markRead(messageId)
                    NotificationHelper.ACTION_DELETE -> stateCoordinator.deleteMessage(messageId)
                    NotificationHelper.ACTION_COPY -> copyMessage(context, repository, messageId)
                }
                NotificationManagerCompat.from(context).cancel(notificationId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun copyMessage(
        context: Context,
        repository: io.ethan.pushgo.data.MessageRepository,
        messageId: String,
    ) {
        val message = repository.getById(messageId) ?: return
        val text = buildString {
            append(message.title)
            if (message.body.isNotBlank()) {
                append("\n")
                append(message.body)
            }
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("pushgo", text))
    }

}
