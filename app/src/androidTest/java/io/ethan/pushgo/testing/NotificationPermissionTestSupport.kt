package io.ethan.pushgo.testing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Read-only precondition for tests whose product path requires enabled notifications. */
object NotificationPermissionTestSupport {
    private const val PRECONDITION_PREFIX = "QUALITY_PRECONDITION"

    fun requireEnabled(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            check(
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            ) {
                "$PRECONDITION_PREFIX POST_NOTIFICATIONS must already be granted; tests do not change permissions"
            }
        }
        check(NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            "$PRECONDITION_PREFIX app notifications are disabled in the controlled environment"
        }
    }
}
