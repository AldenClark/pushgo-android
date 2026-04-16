package io.ethan.pushgo.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object UpdateInstallIntentLauncher {
    fun buildManualApkInstallIntent(context: Context, apkPath: String?): Intent? {
        val path = apkPath?.trim().orEmpty()
        if (path.isEmpty()) {
            return null
        }
        val apkFile = File(path)
        if (!apkFile.exists()) {
            return null
        }
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun launchIntent(context: Context, intent: Intent): Boolean {
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openManualApkInstall(context: Context, apkPath: String?): Boolean {
        val intent = buildManualApkInstallIntent(context, apkPath) ?: return false
        return launchIntent(context, intent)
    }
}
