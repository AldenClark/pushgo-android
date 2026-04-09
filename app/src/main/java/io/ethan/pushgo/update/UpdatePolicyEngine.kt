package io.ethan.pushgo.update

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import kotlin.math.roundToInt

class UpdatePolicyEngine(private val context: Context) {
    fun selectBestCandidate(
        payload: UpdateFeedPayload,
        currentVersionCode: Int,
        betaEnabled: Boolean,
        nowEpochMs: Long,
    ): UpdateCandidate? {
        return UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = currentVersionCode,
            betaEnabled = betaEnabled,
            nowEpochMs = nowEpochMs,
            runtime = UpdateRuntimeContext(
                sdkInt = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS?.map { it.trim().lowercase() }?.toSet().orEmpty(),
                deviceBucketFraction = deviceBucketFraction(),
            ),
        )
    }

    private fun deviceBucketFraction(): Double {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            ?.ifEmpty { null }
            ?: "unknown-device"
        val seed = "${context.packageName}|$androidId".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(seed)
        val value = (
            ((digest[0].toInt() and 0xFF) shl 24) or
                ((digest[1].toInt() and 0xFF) shl 16) or
                ((digest[2].toInt() and 0xFF) shl 8) or
                (digest[3].toInt() and 0xFF)
            ) ushr 1
        val ratio = value.toDouble() / Int.MAX_VALUE.toDouble()
        return ((ratio * 10_000).roundToInt().coerceIn(0, 10_000)) / 10_000.0
    }
}

internal data class UpdateRuntimeContext(
    val sdkInt: Int,
    val supportedAbis: Set<String>,
    val deviceBucketFraction: Double,
)

internal object UpdateCandidateSelector {
    fun selectBestCandidate(
        payload: UpdateFeedPayload,
        currentVersionCode: Int,
        betaEnabled: Boolean,
        nowEpochMs: Long,
        runtime: UpdateRuntimeContext,
    ): UpdateCandidate? {
        val allowedChannels = if (betaEnabled) {
            setOf(UpdateChannel.STABLE, UpdateChannel.BETA)
        } else {
            setOf(UpdateChannel.STABLE)
        }

        val entries = payload.entries
            .asSequence()
            .map { it to UpdateChannel.fromWireValue(it.channel) }
            .filter { (_, channel) -> channel in allowedChannels }
            .filter { (entry, _) -> entry.versionCode > currentVersionCode }
            .filter { (entry, _) -> entry.versionName.isNotBlank() && entry.apkUrl.isNotBlank() && entry.apkSha256.isNotBlank() }
            .filter { (entry, _) -> isSdkCompatible(entry, runtime) }
            .filter { (entry, _) -> isAbiCompatible(entry, runtime) }
            .filter { (entry, _) -> isSupportedByMinimumVersion(entry, currentVersionCode) }
            .filter { (entry, _) -> isRolledOut(entry, nowEpochMs, runtime) }
            .toList()
            .sortedWith(
                compareBy<Pair<UpdateFeedEntry, UpdateChannel>> { it.first.versionCode }
                    .thenBy { if (it.second == UpdateChannel.STABLE) 1 else 0 }
            )

        val selected = entries.lastOrNull() ?: return null
        val (entry, channel) = selected
        return UpdateCandidate(
            channel = channel,
            versionCode = entry.versionCode,
            versionName = entry.versionName,
            apkUrl = entry.apkUrl,
            apkSha256 = entry.apkSha256,
            releaseNotesUrl = entry.releaseNotesUrl,
            critical = entry.critical,
            notes = entry.notes,
            minimumAutoUpdateVersionCode = entry.minimumAutoUpdateVersionCode,
            ignoreSkippedUpgradesBelowVersionCode = entry.ignoreSkippedUpgradesBelowVersionCode,
        )
    }

    private fun isSdkCompatible(entry: UpdateFeedEntry, runtime: UpdateRuntimeContext): Boolean {
        val minSdk = entry.minSdk ?: return true
        return runtime.sdkInt >= minSdk
    }

    private fun isAbiCompatible(entry: UpdateFeedEntry, runtime: UpdateRuntimeContext): Boolean {
        if (entry.allowedAbis.isEmpty()) return true
        if (runtime.supportedAbis.isEmpty()) return true
        return entry.allowedAbis.any { it.trim().lowercase() in runtime.supportedAbis }
    }

    private fun isSupportedByMinimumVersion(entry: UpdateFeedEntry, currentVersionCode: Int): Boolean {
        val minimum = entry.minimumSupportedVersionCode ?: return true
        return currentVersionCode >= minimum
    }

    private fun isRolledOut(entry: UpdateFeedEntry, nowEpochMs: Long, runtime: UpdateRuntimeContext): Boolean {
        val targetFraction = (entry.rolloutFraction ?: 1.0).coerceIn(0.0, 1.0)
        if (targetFraction <= 0.0) return false
        val bucket = runtime.deviceBucketFraction.coerceIn(0.0, 1.0)
        val intervalSeconds = entry.rolloutIntervalSeconds
        val publishedAt = entry.publishedAtEpochMs
        if (intervalSeconds == null || intervalSeconds <= 0L || publishedAt == null || publishedAt <= 0L) {
            return bucket <= targetFraction
        }
        val elapsedMillis = (nowEpochMs - publishedAt).coerceAtLeast(0L)
        val progression = (elapsedMillis.toDouble() / (intervalSeconds * 1000.0)).coerceIn(0.0, 1.0)
        val dynamicFraction = progression * targetFraction
        return bucket <= dynamicFraction
    }
}
