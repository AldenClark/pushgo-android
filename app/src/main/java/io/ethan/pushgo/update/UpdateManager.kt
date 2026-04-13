package io.ethan.pushgo.update

import android.content.Context
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.util.SilentSink
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateManager(
    context: Context,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "UpdateManager"
    }
    private val appContext = context.applicationContext
    private val feedClient = UpdateFeedClient(appContext)
    private val policyEngine = UpdatePolicyEngine(appContext)
    private val installer = UpdateInstaller(appContext)

    suspend fun evaluate(manual: Boolean): UpdateEvaluation = withContext(Dispatchers.IO) {
        val nowEpochMs = System.currentTimeMillis()
        return@withContext runCatching {
            val feed = feedClient.fetchFeed()
            settingsRepository.setUpdateLastCheckAt(nowEpochMs)
            settingsRepository.setCachedUpdatePolicyIntervals(
                scheduledCheckIntervalSeconds = feed.policy.scheduledCheckIntervalSeconds,
                impatientReminderIntervalSeconds = feed.policy.impatientReminderIntervalSeconds,
            )

            val betaEnabled = settingsRepository.getUpdateBetaChannelEnabled()
            val candidate = policyEngine.selectBestCandidate(
                payload = feed,
                currentVersionCode = BuildConfig.VERSION_CODE,
                betaEnabled = betaEnabled,
                nowEpochMs = nowEpochMs,
            )
            if (candidate == null) {
                SilentSink.i(TAG, "evaluate: no candidate currentVersionCode=${BuildConfig.VERSION_CODE}")
                return@runCatching UpdateEvaluation(
                    candidate = null,
                    visibleCandidate = null,
                    suppressedBySkip = false,
                    suppressedByCooldown = false,
                    failureMessage = null,
                )
            }

            maybeClearSkippedGate(candidate)
            val suppressBySkip = shouldSuppressBySkip(candidate, manual)
            val suppressByCooldown = shouldSuppressByCooldown(candidate, nowEpochMs, manual)
            val suppressByAutoVersion = shouldSuppressByMinimumAutoVersion(candidate, manual)

            val visible = if (!suppressBySkip && !suppressByCooldown && !suppressByAutoVersion) {
                candidate
            } else if (manual) {
                candidate
            } else {
                null
            }
            UpdateEvaluation(
                candidate = candidate,
                visibleCandidate = visible,
                suppressedBySkip = suppressBySkip,
                suppressedByCooldown = suppressByCooldown || suppressByAutoVersion,
                failureMessage = null,
            )
        }.getOrElse { error ->
            SilentSink.w(TAG, "evaluate failed: ${error.message}", error)
            UpdateEvaluation(
                candidate = null,
                visibleCandidate = null,
                suppressedBySkip = false,
                suppressedByCooldown = false,
                failureMessage = error.message ?: "Unable to check updates",
            )
        }
    }

    suspend fun install(candidate: UpdateCandidate): UpdateInstallStartResult {
        SilentSink.i(
            TAG,
            "install requested version=${candidate.versionName}(${candidate.versionCode}) package=${candidate.packageKey}",
        )
        return installer.install(candidate)
    }

    suspend fun install(
        candidate: UpdateCandidate,
        onProgress: ((UpdateInstallProgressStage) -> Unit)?,
    ): UpdateInstallStartResult {
        SilentSink.i(
            TAG,
            "install requested version=${candidate.versionName}(${candidate.versionCode}) package=${candidate.packageKey}",
        )
        return installer.install(candidate, onProgress)
    }

    suspend fun skipVersion(versionCode: Int) {
        SilentSink.i(TAG, "skipVersion versionCode=$versionCode")
        settingsRepository.setUpdateSkippedVersionCode(versionCode)
        settingsRepository.clearUpdatePromptCooldown()
        UpdateNotifier.cancelAvailableNotification(appContext)
    }

    suspend fun clearSkippedVersion() {
        settingsRepository.setUpdateSkippedVersionCode(null)
    }

    suspend fun recordPromptDismissed(versionCode: Int): Instant {
        SilentSink.i(TAG, "recordPromptDismissed versionCode=$versionCode")
        val currentDismissCount = if (settingsRepository.getUpdateLastPromptedVersionCode() == versionCode) {
            settingsRepository.getUpdatePromptDismissCount()
        } else {
            0
        }
        val nextDismissCount = (currentDismissCount + 1).coerceAtMost(99)
        val cooldownMillis = UpdatePromptPolicy.nextDismissCooldownMillis(currentDismissCount)
        val nextAllowedAt = System.currentTimeMillis() + cooldownMillis
        settingsRepository.recordUpdatePromptDisplayed(
            versionCode = versionCode,
            nextAllowedPromptAtMillis = nextAllowedAt,
            dismissCount = nextDismissCount,
        )
        return Instant.ofEpochMilli(nextAllowedAt)
    }

    suspend fun recordBackgroundReminderShown(versionCode: Int) {
        SilentSink.i(TAG, "recordBackgroundReminderShown versionCode=$versionCode")
        val now = System.currentTimeMillis()
        val next = now + settingsRepository.getCachedUpdateImpatientReminderIntervalSeconds() * 1000L
        settingsRepository.recordUpdateReminderShown(
            versionCode = versionCode,
            nextAllowedPromptAtMillis = next,
        )
    }

    suspend fun resetPromptCooldown() {
        settingsRepository.clearUpdatePromptCooldown()
    }

    private suspend fun shouldSuppressBySkip(candidate: UpdateCandidate, manual: Boolean): Boolean {
        val skippedVersion = settingsRepository.getUpdateSkippedVersionCode()
        return UpdatePromptPolicy.shouldSuppressBySkip(
            manual = manual,
            critical = candidate.critical,
            skippedVersion = skippedVersion,
            candidateVersionCode = candidate.versionCode,
        )
    }

    private suspend fun shouldSuppressByCooldown(
        candidate: UpdateCandidate,
        nowEpochMs: Long,
        manual: Boolean,
    ): Boolean {
        val lastPromptedVersion = settingsRepository.getUpdateLastPromptedVersionCode()
        val cooldownUntilEpochMs = settingsRepository.getUpdatePromptCooldownUntil()?.toEpochMilli()
        return UpdatePromptPolicy.shouldSuppressByCooldown(
            manual = manual,
            critical = candidate.critical,
            nowEpochMs = nowEpochMs,
            candidateVersionCode = candidate.versionCode,
            lastPromptedVersionCode = lastPromptedVersion,
            cooldownUntilEpochMs = cooldownUntilEpochMs,
        )
    }

    private fun shouldSuppressByMinimumAutoVersion(candidate: UpdateCandidate, manual: Boolean): Boolean {
        return UpdatePromptPolicy.shouldSuppressByMinimumAutoVersion(
            manual = manual,
            critical = candidate.critical,
            currentVersionCode = BuildConfig.VERSION_CODE,
            minimumAutoUpdateVersionCode = candidate.minimumAutoUpdateVersionCode,
        )
    }

    private suspend fun maybeClearSkippedGate(candidate: UpdateCandidate) {
        val skippedVersion = settingsRepository.getUpdateSkippedVersionCode() ?: return
        val ignoreBelow = candidate.ignoreSkippedUpgradesBelowVersionCode ?: return
        if (UpdatePromptPolicy.shouldClearSkippedVersion(skippedVersion, ignoreBelow)) {
            settingsRepository.setUpdateSkippedVersionCode(null)
        }
    }
}

internal object UpdatePromptPolicy {
    fun shouldSuppressBySkip(
        manual: Boolean,
        critical: Boolean,
        skippedVersion: Int?,
        candidateVersionCode: Int,
    ): Boolean {
        if (manual || critical) return false
        return skippedVersion == candidateVersionCode
    }

    fun shouldSuppressByCooldown(
        manual: Boolean,
        critical: Boolean,
        nowEpochMs: Long,
        candidateVersionCode: Int,
        lastPromptedVersionCode: Int?,
        cooldownUntilEpochMs: Long?,
    ): Boolean {
        if (manual || critical) return false
        if (lastPromptedVersionCode != candidateVersionCode) return false
        val cooldownUntil = cooldownUntilEpochMs ?: return false
        return cooldownUntil > nowEpochMs
    }

    fun shouldSuppressByMinimumAutoVersion(
        manual: Boolean,
        critical: Boolean,
        currentVersionCode: Int,
        minimumAutoUpdateVersionCode: Int?,
    ): Boolean {
        if (manual || critical) return false
        val minimum = minimumAutoUpdateVersionCode ?: return false
        return currentVersionCode < minimum
    }

    fun shouldClearSkippedVersion(skippedVersion: Int, ignoreSkippedUpgradesBelowVersionCode: Int): Boolean {
        return skippedVersion < ignoreSkippedUpgradesBelowVersionCode
    }

    fun nextDismissCooldownMillis(currentDismissCount: Int): Long {
        return when (currentDismissCount) {
            0 -> 24L * 60 * 60 * 1_000
            1 -> 72L * 60 * 60 * 1_000
            else -> 7L * 24 * 60 * 60 * 1_000
        }
    }
}
