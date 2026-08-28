package io.ethan.pushgo.testing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry

/** Synchronous, verified preparation for tests whose product path requires system notifications. */
object NotificationPermissionTestSupport {
    private const val PRECONDITION_PREFIX = "QUALITY_PRECONDITION"

    fun grantAndVerify(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(25)
            }
            check(
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            ) {
                "$PRECONDITION_PREFIX POST_NOTIFICATIONS grant did not become observable"
            }
        }
        check(NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            "$PRECONDITION_PREFIX app notifications are disabled in the controlled environment"
        }
    }
}
