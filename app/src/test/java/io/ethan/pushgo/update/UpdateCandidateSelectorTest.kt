package io.ethan.pushgo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCandidateSelectorTest {

    @Test
    fun stableOnly_ignoresBetaEntries() {
        val payload = payloadOf(
            entry(versionCode = 1020099, versionName = "v1.2.0", channel = "stable"),
            entry(versionCode = 1020101, versionName = "v1.2.1-beta.1", channel = "beta"),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = false,
            nowEpochMs = 2_000L,
            runtime = runtime(),
        )

        assertNotNull(selected)
        assertEquals(UpdateChannel.STABLE, selected?.channel)
        assertEquals(1020099, selected?.versionCode)
    }

    @Test
    fun betaEnabled_selectsHighestVersionAcrossStableAndBeta() {
        val payload = payloadOf(
            entry(versionCode = 1020099, versionName = "v1.2.0", channel = "stable"),
            entry(versionCode = 1020101, versionName = "v1.2.1-beta.1", channel = "beta"),
            entry(versionCode = 1020100, versionName = "v1.2.0.1", channel = "stable"),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = true,
            nowEpochMs = 2_000L,
            runtime = runtime(),
        )

        assertNotNull(selected)
        assertEquals(UpdateChannel.BETA, selected?.channel)
        assertEquals(1020101, selected?.versionCode)
    }

    @Test
    fun sameVersion_prefersStableOverBeta() {
        val payload = payloadOf(
            entry(versionCode = 1020100, versionName = "v1.2.0.1-beta", channel = "beta"),
            entry(versionCode = 1020100, versionName = "v1.2.0.1", channel = "stable"),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = true,
            nowEpochMs = 2_000L,
            runtime = runtime(),
        )

        assertNotNull(selected)
        assertEquals(UpdateChannel.STABLE, selected?.channel)
    }

    @Test
    fun rejectsEntriesAtOrBelowCurrentVersion() {
        val payload = payloadOf(
            entry(versionCode = 1020001, versionName = "v1.2.0-beta.1", channel = "stable"),
            entry(versionCode = 1019999, versionName = "v1.1.9", channel = "stable"),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = true,
            nowEpochMs = 2_000L,
            runtime = runtime(),
        )

        assertNull(selected)
    }

    @Test
    fun rejectsEntriesWithBlankRequiredFields() {
        val payload = payloadOf(
            entry(versionCode = 1020099, versionName = "", channel = "stable"),
            entry(versionCode = 1020100, versionName = "v1.2.0.1", channel = "stable", apkUrl = ""),
            entry(versionCode = 1020101, versionName = "v1.2.0.2", channel = "stable", apkSha256 = ""),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = true,
            nowEpochMs = 2_000L,
            runtime = runtime(),
        )

        assertNull(selected)
    }

    @Test
    fun filtersByMinimumSdk() {
        val payload = payloadOf(
            entry(versionCode = 1020099, versionName = "v1.2.0", channel = "stable", minSdk = 33),
            entry(versionCode = 1020100, versionName = "v1.2.0.1", channel = "stable", minSdk = 31),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = false,
            nowEpochMs = 2_000L,
            runtime = runtime(sdkInt = 31),
        )

        assertNotNull(selected)
        assertEquals(1020100, selected?.versionCode)
    }

    @Test
    fun filtersByAbiCaseInsensitively() {
        val payload = payloadOf(
            entry(versionCode = 1020099, versionName = "v1.2.0", channel = "stable", allowedAbis = listOf("x86_64")),
            entry(versionCode = 1020100, versionName = "v1.2.0.1", channel = "stable", allowedAbis = listOf(" ARM64-V8A ")),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = false,
            nowEpochMs = 2_000L,
            runtime = runtime(supportedAbis = setOf("arm64-v8a")),
        )

        assertNotNull(selected)
        assertEquals(1020100, selected?.versionCode)
    }

    @Test
    fun filtersByMinimumSupportedVersionCode() {
        val payload = payloadOf(
            entry(
                versionCode = 1020099,
                versionName = "v1.2.0",
                channel = "stable",
                minimumSupportedVersionCode = 1020002,
            ),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = false,
            nowEpochMs = 2_000L,
            runtime = runtime(),
        )

        assertNull(selected)
    }

    @Test
    fun rolloutFractionZero_blocksCandidate() {
        val payload = payloadOf(
            entry(versionCode = 1020099, versionName = "v1.2.0", channel = "stable", rolloutFraction = 0.0),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = false,
            nowEpochMs = 2_000L,
            runtime = runtime(deviceBucketFraction = 0.0),
        )

        assertNull(selected)
    }

    @Test
    fun rolloutWithoutInterval_usesTargetFractionDirectly() {
        val payload = payloadOf(
            entry(versionCode = 1020099, versionName = "v1.2.0", channel = "stable", rolloutFraction = 0.40),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = false,
            nowEpochMs = 2_000L,
            runtime = runtime(deviceBucketFraction = 0.35),
        )
        val rejected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = false,
            nowEpochMs = 2_000L,
            runtime = runtime(deviceBucketFraction = 0.45),
        )

        assertNotNull(selected)
        assertNull(rejected)
    }

    @Test
    fun rolloutWithInterval_appliesProgressiveFraction() {
        val payload = payloadOf(
            entry(
                versionCode = 1020099,
                versionName = "v1.2.0",
                channel = "stable",
                rolloutFraction = 0.8,
                rolloutIntervalSeconds = 100,
                publishedAtEpochMs = 1_000L,
            ),
        )

        val selectedAtHalfProgress = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = false,
            nowEpochMs = 51_000L,
            runtime = runtime(deviceBucketFraction = 0.35),
        )
        val rejectedAtHalfProgress = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = false,
            nowEpochMs = 51_000L,
            runtime = runtime(deviceBucketFraction = 0.50),
        )

        assertNotNull(selectedAtHalfProgress)
        assertNull(rejectedAtHalfProgress)
    }

    @Test
    fun unknownChannel_isTreatedAsStable() {
        val payload = payloadOf(
            entry(versionCode = 1020099, versionName = "v1.2.0", channel = "experimental"),
        )

        val selected = UpdateCandidateSelector.selectBestCandidate(
            payload = payload,
            currentVersionCode = 1020001,
            betaEnabled = false,
            nowEpochMs = 2_000L,
            runtime = runtime(),
        )

        assertNotNull(selected)
        assertEquals(UpdateChannel.STABLE, selected?.channel)
    }

    private fun payloadOf(vararg entries: UpdateFeedEntry): UpdateFeedPayload {
        return UpdateFeedPayload(entries = entries.toList())
    }

    private fun runtime(
        sdkInt: Int = 31,
        supportedAbis: Set<String> = setOf("arm64-v8a"),
        deviceBucketFraction: Double = 0.25,
    ): UpdateRuntimeContext {
        return UpdateRuntimeContext(
            sdkInt = sdkInt,
            supportedAbis = supportedAbis,
            deviceBucketFraction = deviceBucketFraction,
        )
    }

    private fun entry(
        versionCode: Int,
        versionName: String,
        channel: String,
        apkUrl: String = "https://example.com/pushgo-$versionCode.apk",
        apkSha256: String = "a".repeat(64),
        minSdk: Int? = null,
        allowedAbis: List<String> = emptyList(),
        minimumSupportedVersionCode: Int? = null,
        rolloutFraction: Double? = null,
        rolloutIntervalSeconds: Long? = null,
        publishedAtEpochMs: Long? = null,
    ): UpdateFeedEntry {
        return UpdateFeedEntry(
            channel = channel,
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            apkSha256 = apkSha256,
            minSdk = minSdk,
            allowedAbis = allowedAbis,
            minimumSupportedVersionCode = minimumSupportedVersionCode,
            rolloutFraction = rolloutFraction,
            rolloutIntervalSeconds = rolloutIntervalSeconds,
            publishedAtEpochMs = publishedAtEpochMs,
        )
    }
}
