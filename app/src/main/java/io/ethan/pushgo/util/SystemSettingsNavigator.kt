package io.ethan.pushgo.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

fun Context.openAppNotificationSettings() {
    val notificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }
    val appDetailsIntent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )
    startActivityWithFallback(primary = notificationIntent, fallback = appDetailsIntent)
}

fun Context.isAppSubjectToBatteryOptimization(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return false
    }
    val powerManager = getSystemService(PowerManager::class.java) ?: return false
    return !powerManager.isIgnoringBatteryOptimizations(packageName)
}

fun Context.openBatteryOptimizationSettings() {
    val packageUri = Uri.fromParts("package", packageName, null)
    val requestIgnoreIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
    val optimizationListIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    startActivityWithFallback(
        primary = requestIgnoreIntent,
        fallback = optimizationListIntent,
        fallback2 = appDetailsIntent,
    )
}

private fun Context.startActivityWithFallback(
    primary: Intent,
    fallback: Intent? = null,
    fallback2: Intent? = null,
) {
    val launched = runCatching {
        startActivity(primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (launched || fallback == null) {
        return
    }
    val fallbackLaunched = runCatching {
        startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (fallbackLaunched || fallback2 == null) {
        return
    }
    runCatching {
        startActivity(fallback2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
