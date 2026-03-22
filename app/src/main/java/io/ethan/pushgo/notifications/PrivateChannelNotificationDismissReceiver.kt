package io.ethan.pushgo.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.ethan.pushgo.PushGoApp

class PrivateChannelNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PrivateChannelForegroundService.ACTION_NOTIFICATION_DISMISSED) {
            return
        }
        val app = context.applicationContext as? PushGoApp
        app?.containerOrNull()?.privateChannelClient?.onKeepaliveNotificationDismissed("notification dismissed")
        context.stopService(Intent(context, PrivateChannelForegroundService::class.java))
    }
}
