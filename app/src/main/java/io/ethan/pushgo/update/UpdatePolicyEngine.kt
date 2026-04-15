package io.ethan.pushgo.update

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale
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
                supportedAbis = Build.SUPPORTED_ABIS
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                deviceBucketFraction = deviceBucketFraction(),
                preferredLocales = preferredLocales(),
            ),
        )
    }

    private fun preferredLocales(): List<String> {
        val localeList = context.resources.configuration.locales
        return buildList(localeList.size()) {
            for (index in 0 until localeList.size()) {
                add(localeList[index].toLanguageTag())
            }
        }
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
    val supportedAbis: List<String>,
    val deviceBucketFraction: Double,
    val preferredLocales: List<String> = emptyList(),
)

internal data class ResolvedPackageArtifact(
    val packageKey: String,
    val artifact: UpdatePackageArtifact,
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
            .filter { (entry, _) -> entry.versionName.isNotBlank() }
            .mapNotNull { (entry, channel) ->
                val resolvedArtifact = resolvePackageArtifact(entry, runtime) ?: return@mapNotNull null
                if (!isSdkCompatible(entry, runtime)) return@mapNotNull null
                if (!isSupportedByMinimumVersion(entry, currentVersionCode)) return@mapNotNull null
                if (!isRolledOut(entry, nowEpochMs, runtime)) return@mapNotNull null
                Triple(entry, channel, resolvedArtifact)
            }
            .toList()
            .sortedWith(
                compareBy<Triple<UpdateFeedEntry, UpdateChannel, ResolvedPackageArtifact>> { it.first.versionCode }
                    .thenBy { if (it.second == UpdateChannel.STABLE) 1 else 0 }
            )

        val selected = entries.lastOrNull() ?: return null
        val (entry, channel, resolvedArtifact) = selected
        return UpdateCandidate(
            channel = channel,
            versionCode = entry.versionCode,
            versionName = entry.versionName,
            apkUrl = resolvedArtifact.artifact.apkUrl,
            apkSha256 = resolvedArtifact.artifact.apkSha256,
            packageKey = resolvedArtifact.packageKey,
            releaseNotesUrl = entry.releaseNotesUrl,
            critical = entry.critical,
            notes = resolveNotes(entry, runtime.preferredLocales),
            minimumAutoUpdateVersionCode = entry.minimumAutoUpdateVersionCode,
            ignoreSkippedUpgradesBelowVersionCode = entry.ignoreSkippedUpgradesBelowVersionCode,
        )
    }

    private fun resolvePackageArtifact(
        entry: UpdateFeedEntry,
        runtime: UpdateRuntimeContext,
    ): ResolvedPackageArtifact? {
        val normalizedPackages = normalizePackages(entry.packages)
        if (normalizedPackages.isEmpty()) {
            if (!hasValidLegacyPackage(entry)) return null
            if (!isLegacyAbiCompatible(entry, runtime)) return null
            return ResolvedPackageArtifact(
                packageKey = "legacy",
                artifact = UpdatePackageArtifact(apkUrl = entry.apkUrl, apkSha256 = entry.apkSha256),
            )
        }

        val runtimeAbis = runtime.supportedAbis
        if (runtimeAbis.isEmpty()) {
            val universal = normalizedPackages["universal"] ?: return null
            return universal.takeIf { isValidArtifact(it.artifact) }
        }

        for (abi in runtimeAbis) {
            for (candidateKey in packageKeysForAbi(abi)) {
                val candidate = normalizedPackages[candidateKey] ?: continue
                if (isValidArtifact(candidate.artifact)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun resolveNotes(entry: UpdateFeedEntry, preferredLocales: List<String>): String? {
        val explicit = entry.notes?.trim().takeUnless { it.isNullOrEmpty() }
        val localized = normalizeLocalizedNotes(entry.notesI18n)
        if (localized.isEmpty()) return explicit

        for (rawLocale in preferredLocales) {
            val normalizedTag = normalizeLanguageTag(rawLocale) ?: continue
            for (candidateTag in languageTagCandidates(normalizedTag)) {
                localized[candidateTag]?.let { return it }
            }
        }
        return explicit ?: localized["en"] ?: localized.values.firstOrNull()
    }

    private fun normalizeLocalizedNotes(input: Map<String, String>): Map<String, String> {
        if (input.isEmpty()) return emptyMap()
        val normalized = linkedMapOf<String, String>()
        input.forEach { (rawKey, rawValue) ->
            val value = rawValue.trim()
            if (value.isEmpty()) return@forEach
            val key = normalizeLanguageTag(rawKey) ?: return@forEach
            normalized.putIfAbsent(key, value)
        }
        return normalized
    }

    private fun normalizeLanguageTag(raw: String): String? {
        val trimmed = raw.trim().replace('_', '-')
        if (trimmed.isEmpty()) return null
        val locale = runCatching { Locale.forLanguageTag(trimmed) }.getOrNull() ?: return null
        val language = locale.language.trim().lowercase()
        if (language.isEmpty() || language == "und") return null
        val script = locale.script.trim()
        val region = locale.country.trim()
        val parts = mutableListOf(language)
        if (script.isNotEmpty()) {
            parts += script.lowercase().replaceFirstChar { it.titlecase() }
        }
        if (region.isNotEmpty()) {
            parts += region.uppercase()
        }
        return parts.joinToString("-")
    }

    private fun languageTagCandidates(tag: String): List<String> {
        val parts = tag.split('-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val language = parts.first()
        val region = parts.lastOrNull()?.takeIf { it.length == 2 || it.length == 3 }
        return buildList {
            add(tag)
            if (region != null) {
                add("$language-$region")
            }
            add(language)
        }.distinct()
    }

    private fun isSdkCompatible(entry: UpdateFeedEntry, runtime: UpdateRuntimeContext): Boolean {
        val minSdk = entry.minSdk ?: return true
        return runtime.sdkInt >= minSdk
    }

    private fun hasValidLegacyPackage(entry: UpdateFeedEntry): Boolean {
        return entry.apkUrl.isNotBlank() && entry.apkSha256.isNotBlank()
    }

    private fun isLegacyAbiCompatible(entry: UpdateFeedEntry, runtime: UpdateRuntimeContext): Boolean {
        if (entry.allowedAbis.isEmpty()) return true
        if (runtime.supportedAbis.isEmpty()) return true
        val runtimeAbis = runtime.supportedAbis.toSet()
        return entry.allowedAbis.any { it.trim().lowercase() in runtimeAbis }
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

    private fun normalizePackages(
        packages: Map<String, UpdatePackageArtifact>,
    ): Map<String, ResolvedPackageArtifact> {
        if (packages.isEmpty()) return emptyMap()
        val normalized = linkedMapOf<String, ResolvedPackageArtifact>()
        packages.forEach { (rawKey, artifact) ->
            val key = normalizePackageKey(rawKey) ?: return@forEach
            if (normalized.containsKey(key)) return@forEach
            normalized[key] = ResolvedPackageArtifact(packageKey = key, artifact = artifact)
        }
        return normalized
    }

    private fun normalizePackageKey(raw: String): String? {
        return when (raw.trim().lowercase().replace('_', '-')) {
            "arm64-v8a" -> "arm64-v8a"
            "armeabi-v7a" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "universal", "all", "any" -> "universal"
            else -> null
        }
    }

    private fun packageKeysForAbi(abi: String): List<String> {
        return when (abi.trim().lowercase()) {
            "arm64-v8a", "aarch64" -> listOf("arm64-v8a", "universal")
            "armeabi-v7a", "armeabi", "armv7", "arm-v7a" -> listOf("armeabi-v7a", "universal")
            "x86_64", "x86-64", "x86" -> listOf("x86_64", "universal")
            else -> listOf("universal")
        }
    }

    private fun isValidArtifact(artifact: UpdatePackageArtifact): Boolean {
        return artifact.apkUrl.isNotBlank() && artifact.apkSha256.length == 64
    }
}
