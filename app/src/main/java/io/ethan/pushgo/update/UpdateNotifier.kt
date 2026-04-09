package io.ethan.pushgo.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.R

object UpdateNotifier {
    const val EXTRA_OPEN_SETTINGS = "extra_open_settings"
    private const val UPDATE_CHANNEL_ID = "pushgo_updates_v1"
    private const val UPDATE_AVAILABLE_NOTIFICATION_ID = 62_001
    private const val UPDATE_INSTALL_RESULT_NOTIFICATION_ID = 62_002
    private const val UPDATE_INSTALL_RECOVERY_REQUEST_CODE = 62_003

    @SuppressLint("MissingPermission")
    fun showUpdateAvailable(context: Context, candidate: UpdateCandidate) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val title = context.getString(R.string.label_update_available_title, candidate.versionName)
        val body = candidate.notes?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.label_update_available_body)
        val contentIntent = PendingIntent.getActivity(
            context,
            UPDATE_AVAILABLE_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_SETTINGS, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(UPDATE_AVAILABLE_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun showInstallSucceeded(context: Context, versionName: String?) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val title = context.getString(R.string.label_update_install_success_title)
        val body = context.getString(
            R.string.label_update_install_success_body,
            versionName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.label_update_unknown_version),
        )
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(UPDATE_INSTALL_RESULT_NOTIFICATION_ID, notification)
        NotificationManagerCompat.from(context).cancel(UPDATE_AVAILABLE_NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission")
    fun showInstallFailed(context: Context, detail: String?) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val title = context.getString(R.string.label_update_install_failed_title)
        val body = context.getString(
            R.string.label_update_install_failed_body,
            detail?.takeIf { it.isNotBlank() } ?: context.getString(R.string.label_unknown_error),
        )
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        NotificationManagerCompat.from(context).notify(UPDATE_INSTALL_RESULT_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun showInstallBlockedRecovery(context: Context, detail: String?) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val body = context.getString(
            R.string.label_update_install_blocked_body,
            detail?.takeIf { it.isNotBlank() } ?: context.getString(R.string.label_unknown_error),
        )
        val recoveryPendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_INSTALL_RECOVERY_REQUEST_CODE,
            selectRecoverySettingsIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(context.getString(R.string.label_update_install_blocked_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(recoveryPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_manage,
                context.getString(R.string.label_update_install_blocked_action),
                recoveryPendingIntent,
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        NotificationManagerCompat.from(context).notify(UPDATE_INSTALL_RESULT_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun showInstallActionRequired(context: Context, actionIntent: Intent?) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        val pendingIntent = actionIntent?.let {
            PendingIntent.getActivity(
                context,
                UPDATE_INSTALL_RESULT_NOTIFICATION_ID,
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pushgo)
            .setContentTitle(context.getString(R.string.label_update_install_action_required_title))
            .setContentText(context.getString(R.string.label_update_install_action_required_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.label_update_install_action_required_body))
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify(UPDATE_INSTALL_RESULT_NOTIFICATION_ID, notification)
    }

    fun cancelAvailableNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(UPDATE_AVAILABLE_NOTIFICATION_ID)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(UPDATE_CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            UPDATE_CHANNEL_ID,
            context.getString(R.string.label_update_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.label_update_notification_channel_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun selectRecoverySettingsIntent(context: Context): Intent {
        val unknownSourcesIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (unknownSourcesIntent.resolveActivity(context.packageManager) != null) {
            return unknownSourcesIntent
        }
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
