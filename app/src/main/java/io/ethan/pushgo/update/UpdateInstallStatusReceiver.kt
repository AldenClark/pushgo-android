package io.ethan.pushgo.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.ethan.pushgo.util.SilentSink

class UpdateInstallStatusReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_INSTALL_STATUS = "io.ethan.pushgo.action.UPDATE_INSTALL_STATUS"
        const val EXTRA_VERSION_CODE = "extra_update_version_code"
        const val EXTRA_VERSION_NAME = "extra_update_version_name"
        const val EXTRA_APK_FILE_PATH = "extra_apk_file_path"
        private const val TAG = "UpdateInstallStatus"
        private val recoverableInstallFailureHints = listOf(
            "install_failed_aborted",
            "install_failed_user_restricted",
            "user rejected permissions",
            "blocked session install",
            "not allowed to install",
            "unknown sources",
            "request_install_packages",
        )

        internal fun isRecoverableInstallerBlock(status: Int, statusMessage: String?): Boolean {
            if (status == PackageInstaller.STATUS_FAILURE_BLOCKED) {
                return true
            }
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                return true
            }
            val normalized = statusMessage?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) {
                return false
            }
            return recoverableInstallFailureHints.any { hint -> normalized.contains(hint) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) {
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME)
        SilentSink.i(TAG, "install status=$status version=$versionName")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val pendingAction = readPendingUserActionIntent(intent)
                if (pendingAction != null) {
                    runCatching {
                        context.startActivity(pendingAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { error ->
                        SilentSink.w(TAG, "pending user action start failed: ${error.message}", error)
                        UpdateNotifier.showInstallActionRequired(context, pendingAction)
                    }
                } else {
                    UpdateNotifier.showInstallActionRequired(context, null)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateNotifier.showInstallSucceeded(context, versionName)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "status=$status"
                SilentSink.w(TAG, "install failed status=$status message=$message")
                if (isRecoverableInstallerBlock(status, message)) {
                    val apkPath = intent.getStringExtra(EXTRA_APK_FILE_PATH)
                    UpdateInstallUiEvents.emitInstallBlocked(
                        detail = message,
                        apkPath = apkPath,
                    )
                    val manualInstallIntent = UpdateInstallIntentLauncher.buildManualApkInstallIntent(context, apkPath)
                    val launched = manualInstallIntent?.let { intent ->
                        UpdateInstallIntentLauncher.launchIntent(context, intent)
                    } == true
                    if (launched) {
                        UpdateNotifier.showInstallActionRequired(context, manualInstallIntent)
                    } else {
                        UpdateNotifier.showInstallBlockedRecovery(context, message)
                    }
                } else {
                    UpdateNotifier.showInstallFailed(context, message)
                }
            }
        }
    }

    private fun readPendingUserActionIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }
}
