package io.ethan.pushgo.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SignedUpdateFeed(
    val payload: UpdateFeedPayload,
    val signature: String? = null,
)

@Serializable
data class UpdateFeedPayload(
    val schemaVersion: Int = 1,
    val generatedAtEpochMs: Long? = null,
    val policy: UpdateFeedPolicy = UpdateFeedPolicy(),
    val entries: List<UpdateFeedEntry> = emptyList(),
)

@Serializable
data class UpdateFeedPolicy(
    val scheduledCheckIntervalSeconds: Long = 21_600,
    val impatientReminderIntervalSeconds: Long = 604_800,
)

@Serializable
data class UpdateFeedEntry(
    val channel: String = "stable",
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val releaseNotesUrl: String? = null,
    val minSdk: Int? = null,
    val allowedAbis: List<String> = emptyList(),
    val critical: Boolean = false,
    val publishedAtEpochMs: Long? = null,
    val minimumAutoUpdateVersionCode: Int? = null,
    val minimumSupportedVersionCode: Int? = null,
    val ignoreSkippedUpgradesBelowVersionCode: Int? = null,
    val rolloutFraction: Double? = null,
    val rolloutIntervalSeconds: Long? = null,
    @SerialName("notes")
    val notes: String? = null,
    @SerialName("notesI18n")
    val notesI18n: Map<String, String> = emptyMap(),
)

enum class UpdateChannel(val wireValue: String) {
    STABLE("stable"),
    BETA("beta");

    companion object {
        fun fromWireValue(value: String?): UpdateChannel {
            return when (value?.trim()?.lowercase()) {
                BETA.wireValue -> BETA
                else -> STABLE
            }
        }
    }
}

data class UpdateCandidate(
    val channel: UpdateChannel,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
    val releaseNotesUrl: String?,
    val critical: Boolean,
    val notes: String?,
    val minimumAutoUpdateVersionCode: Int?,
    val ignoreSkippedUpgradesBelowVersionCode: Int?,
)

data class UpdateEvaluation(
    val candidate: UpdateCandidate?,
    val visibleCandidate: UpdateCandidate?,
    val suppressedBySkip: Boolean,
    val suppressedByCooldown: Boolean,
    val failureMessage: String?,
)

sealed interface UpdateInstallStartResult {
    data object Started : UpdateInstallStartResult
    data object PermissionRequired : UpdateInstallStartResult
    data class Failed(val message: String) : UpdateInstallStartResult
}
