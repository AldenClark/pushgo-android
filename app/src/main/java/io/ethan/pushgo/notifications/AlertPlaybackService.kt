package io.ethan.pushgo.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlertPlaybackService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timeoutJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentNotificationId: Int? = null
    private var currentLevel: String? = null
    private var foregroundStarted: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureServiceChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OR_UPDATE -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                if (notificationId != 0) {
                    ensureForegroundStarted()
                    val level = intent.getStringExtra(EXTRA_LEVEL)
                    val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
                    startOrUpdatePlayback(notificationId, level, channelId)
                }
            }
            ACTION_STOP_FOR_NOTIFICATION -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                if (notificationId != 0 && notificationId == currentNotificationId) {
                    stopSelf()
                }
            }
            ACTION_STOP_ALL -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        timeoutJob = null
        mediaPlayer?.runCatching {
            stop()
            reset()
            release()
        }
        mediaPlayer = null
        currentNotificationId = null
        currentLevel = null
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    private fun startOrUpdatePlayback(
        notificationId: Int,
        level: String?,
        channelId: String,
    ) {
        val incomingSpec = AlertPlaybackPolicy.specForLevel(level)
        if (!incomingSpec.isEnabled) {
            if (notificationId == currentNotificationId) {
                stopSelf()
            }
            return
        }

        val incomingRank = AlertPlaybackPolicy.severityRank(level)
        val currentRank = AlertPlaybackPolicy.severityRank(currentLevel)
        if (currentNotificationId != null && incomingRank < currentRank) {
            return
        }

        val soundUri = resolveSoundUri(channelId) ?: return
        currentNotificationId = notificationId
        currentLevel = level

        timeoutJob?.cancel()
        timeoutJob = null

        val player = mediaPlayer ?: MediaPlayer().also { mediaPlayer = it }
        runCatching {
            player.reset()
            player.setDataSource(this, soundUri)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.isLooping = true
            player.prepare()
            player.start()
        }.onFailure {
            stopSelf()
            return
        }

        val durationMs = incomingSpec.maxDurationMs
        if (incomingSpec.mode == AlertPlaybackMode.TIMED && durationMs != null && durationMs > 0L) {
            timeoutJob = serviceScope.launch {
                delay(durationMs)
                stopSelf()
            }
        }
    }

    private fun resolveSoundUri(channelId: String): Uri? {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelSound = manager.getNotificationChannel(channelId)?.sound
        if (channelSound != null) {
            return channelSound
        }
        return Settings.System.DEFAULT_NOTIFICATION_URI
    }

    private fun ensureServiceChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            getString(R.string.alert_playback_service_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.alert_playback_service_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildServiceNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(getString(R.string.alert_playback_service_notification_title))
            .setContentText(getString(R.string.alert_playback_service_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setGroup(ALERT_PLAYBACK_NOTIFICATION_GROUP_KEY)
            .setLocalOnly(true)
            .setSound(null)
            .build()
    }

    private fun ensureForegroundStarted() {
        if (foregroundStarted) {
            return
        }
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification())
        foregroundStarted = true
    }

    companion object {
        const val ACTION_START_OR_UPDATE = "io.ethan.pushgo.notifications.ALERT_PLAYBACK_START_OR_UPDATE"
        const val ACTION_STOP_FOR_NOTIFICATION = "io.ethan.pushgo.notifications.ALERT_PLAYBACK_STOP_FOR_NOTIFICATION"
        const val ACTION_STOP_ALL = "io.ethan.pushgo.notifications.ALERT_PLAYBACK_STOP_ALL"

        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_LEVEL = "extra_level"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"

        private const val SERVICE_CHANNEL_ID = "pushgo_alert_playback"
        private const val SERVICE_NOTIFICATION_ID = 84_201
        private const val ALERT_PLAYBACK_NOTIFICATION_GROUP_KEY = "io.ethan.pushgo.alert_playback_service"
    }
}
