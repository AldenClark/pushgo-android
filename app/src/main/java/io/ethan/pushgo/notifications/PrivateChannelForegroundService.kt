package io.ethan.pushgo.notifications

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.R
import io.ethan.pushgo.automation.PushGoAutomation
import kotlinx.coroutines.runBlocking

class PrivateChannelForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val container = appContainer()
        if (container == null || !shouldRunService(container) || !startForegroundSafely()) {
            stopSelf()
            return
        }
        container.privateChannelClient.setKeepaliveServiceActive(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = appContainer() ?: return START_NOT_STICKY
        if (!shouldRunService(container)) {
            stopSelf()
            return START_NOT_STICKY
        }
        container.privateChannelClient.setKeepaliveServiceActive(true)
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        appContainer()?.privateChannelClient?.setKeepaliveServiceActive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundSafely(): Boolean {
        val notification = buildNotification()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                val notAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && error is ForegroundServiceStartNotAllowedException
                if (!notAllowed) {
                    io.ethan.pushgo.util.SilentSink.w(
                        TAG,
                        "startForeground failed: ${error.message}",
                        error,
                    )
                }
                false
            },
        )
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(getString(R.string.private_channel_service_notification_title))
            .setContentText(currentStatusSummary())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun currentStatusSummary(): String {
        val client = appContainer()?.privateChannelClient
        if (client == null) {
            return getString(R.string.private_transport_status_disconnected)
        }
        return client.summarizeTransportStatus(privateModeEnabled = true)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.private_channel_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.private_channel_service_channel_description)
            setShowBadge(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun appContainer() = (application as? PushGoApp)?.containerOrNull()

    private fun shouldRunService(container: io.ethan.pushgo.data.AppContainer): Boolean {
        return !PushGoAutomation.isSessionConfigured()
            && !runBlocking { container.settingsRepository.getUseFcmChannel() }
    }

    companion object {
        private const val TAG = "PrivateChannelFGService"
        private const val CHANNEL_ID = "pushgo_private_channel_service"
        const val NOTIFICATION_ID = 20_501
        const val ACTION_REFRESH_STATUS = "io.ethan.pushgo.private_channel.REFRESH_STATUS"
    }
}
