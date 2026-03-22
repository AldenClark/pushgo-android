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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PrivateChannelForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationWatchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val container = appContainer()
        val shouldRun = container != null && shouldRunService(container)
        if (container == null || !shouldRun || !startForegroundSafely()) {
            stopSelf()
            return
        }
        container.privateChannelClient.setKeepaliveServiceActive(true)
        ensureWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = appContainer() ?: return START_NOT_STICKY
        val shouldRun = shouldRunService(container)
        if (!shouldRun) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_NOTIFICATION_DISMISSED) {
            container.privateChannelClient.onKeepaliveNotificationDismissed("notification dismissed")
            stopSelf()
            return START_NOT_STICKY
        }
        container.privateChannelClient.setKeepaliveServiceActive(true)
        updateNotification()
        ensureWatchdog()
        return START_STICKY
    }

    override fun onDestroy() {
        notificationWatchdogJob?.cancel()
        notificationWatchdogJob = null
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
        val deleteIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, PrivateChannelNotificationDismissReceiver::class.java).apply {
                action = ACTION_NOTIFICATION_DISMISSED
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(getString(R.string.private_channel_service_notification_title))
            .setContentText(currentStatusSummary())
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deleteIntent)
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

    private fun ensureWatchdog() {
        if (notificationWatchdogJob?.isActive == true) return
        notificationWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_WATCHDOG_INTERVAL_MS)
                appContainer()?.privateChannelClient?.refreshNetworkStateFromSystem()
                if (!isNotificationPresent()) {
                    runCatching { updateNotification() }
                }
            }
        }
    }

    private fun isNotificationPresent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return manager.activeNotifications.any { record -> record.id == NOTIFICATION_ID }
    }

    private fun shouldRunService(container: io.ethan.pushgo.data.AppContainer): Boolean {
        val app = application as? PushGoApp ?: return false
        return app.shouldRunPrivateChannelForegroundService()
    }

    companion object {
        private const val TAG = "PrivateChannelFGService"
        private const val CHANNEL_ID = "pushgo_private_channel_service"
        private const val NOTIFICATION_WATCHDOG_INTERVAL_MS = 5_000L
        const val NOTIFICATION_ID = 20_501
        const val ACTION_REFRESH_STATUS = "io.ethan.pushgo.private_channel.REFRESH_STATUS"
        const val ACTION_NOTIFICATION_DISMISSED = "io.ethan.pushgo.private_channel.NOTIFICATION_DISMISSED"
    }
}
