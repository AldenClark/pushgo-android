package io.ethan.pushgo.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.util.SilentSink
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateInstaller(private val context: Context) {
    companion object {
        private const val TAG = "UpdateInstaller"
    }

    suspend fun install(candidate: UpdateCandidate): UpdateInstallStartResult = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        if (!packageManager.canRequestPackageInstalls()) {
            SilentSink.i(TAG, "install blocked: unknown-sources permission required")
            return@withContext UpdateInstallStartResult.PermissionRequired
        }

        val apkFile = runCatching { downloadAndVerify(candidate) }.getOrElse { error ->
            return@withContext UpdateInstallStartResult.Failed(
                "Download failed: ${error.message.orEmpty()}".trim()
            )
        }

        val archiveValidation = runCatching { verifyArchiveCompatibility(apkFile) }.getOrElse { error ->
            return@withContext UpdateInstallStartResult.Failed(
                "Archive validation failed: ${error.message.orEmpty()}".trim()
            )
        }
        if (!archiveValidation) {
            return@withContext UpdateInstallStartResult.Failed("Update package is incompatible with this app")
        }

        runCatching {
            startPackageInstallSession(candidate, apkFile)
        }.onFailure { error ->
            SilentSink.w(TAG, "install preparation failed: ${error.message}", error)
            return@withContext UpdateInstallStartResult.Failed(
                "Install preparation failed: ${error.message.orEmpty()}".trim()
            )
        }

        SilentSink.i(TAG, "session committed version=${candidate.versionName}(${candidate.versionCode})")
        UpdateInstallStartResult.Started
    }

    private fun downloadAndVerify(candidate: UpdateCandidate): File {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(updateDir, "update-${candidate.versionCode}.apk")
        if (target.exists() && sha256Hex(target).equals(candidate.apkSha256, ignoreCase = true)) {
            SilentSink.i(TAG, "reuse cached apk ${target.name}")
            return target
        }

        val tempFile = File(updateDir, "update-${candidate.versionCode}.apk.download")
        val connection = (URL(candidate.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/vnd.android.package-archive")
            setRequestProperty("User-Agent", "${context.packageName}/android-update-installer")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status")
            }
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }

        val actual = sha256Hex(tempFile)
        if (!actual.equals(candidate.apkSha256, ignoreCase = true)) {
            tempFile.delete()
            throw IllegalStateException("Checksum mismatch")
        }

        if (target.exists()) {
            target.delete()
        }
        check(tempFile.renameTo(target)) { "Unable to finalize update package" }
        SilentSink.i(TAG, "download complete ${target.name}")
        return target
    }

    @Suppress("DEPRECATION")
    private fun verifyArchiveCompatibility(apkFile: File): Boolean {
        val packageManager = context.packageManager
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } ?: return false

        if (archiveInfo.packageName != context.packageName) {
            return false
        }
        if (archiveInfo.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
            return false
        }
        return hasTrustedSigner(archiveInfo)
    }

    private fun hasTrustedSigner(archiveInfo: PackageInfo): Boolean {
        val signingInfo = archiveInfo.signingInfo ?: return true
        val signers = signingInfo.apkContentsSigners ?: return true
        if (signers.isEmpty()) return true
        return signers.any { signer ->
            val digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
            context.packageManager.hasSigningCertificate(
                context.packageName,
                digest,
                PackageManager.CERT_INPUT_SHA256,
            )
        }
    }

    private fun startPackageInstallSession(candidate: UpdateCandidate, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                FileInputStream(apkFile).use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callbackIntent = Intent(context, UpdateInstallStatusReceiver::class.java).apply {
                    action = UpdateInstallStatusReceiver.ACTION_INSTALL_STATUS
                    putExtra(UpdateInstallStatusReceiver.EXTRA_VERSION_CODE, candidate.versionCode)
                    putExtra(UpdateInstallStatusReceiver.EXTRA_VERSION_NAME, candidate.versionName)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw error
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
