package io.ethan.pushgo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePromptPolicyTest {

    @Test
    fun skipSuppression_appliesOnlyForBackgroundNonCritical() {
        assertTrue(
            UpdatePromptPolicy.shouldSuppressBySkip(
                manual = false,
                critical = false,
                skippedVersion = 1020101,
                candidateVersionCode = 1020101,
            ),
        )
        assertFalse(
            UpdatePromptPolicy.shouldSuppressBySkip(
                manual = true,
                critical = false,
                skippedVersion = 1020101,
                candidateVersionCode = 1020101,
            ),
        )
        assertFalse(
            UpdatePromptPolicy.shouldSuppressBySkip(
                manual = false,
                critical = true,
                skippedVersion = 1020101,
                candidateVersionCode = 1020101,
            ),
        )
    }

    @Test
    fun cooldownSuppression_requiresSameVersionAndFutureWindow() {
        assertTrue(
            UpdatePromptPolicy.shouldSuppressByCooldown(
                manual = false,
                critical = false,
                nowEpochMs = 1_000L,
                candidateVersionCode = 1020101,
                lastPromptedVersionCode = 1020101,
                cooldownUntilEpochMs = 2_000L,
            ),
        )
        assertFalse(
            UpdatePromptPolicy.shouldSuppressByCooldown(
                manual = false,
                critical = false,
                nowEpochMs = 3_000L,
                candidateVersionCode = 1020101,
                lastPromptedVersionCode = 1020101,
                cooldownUntilEpochMs = 2_000L,
            ),
        )
        assertFalse(
            UpdatePromptPolicy.shouldSuppressByCooldown(
                manual = false,
                critical = false,
                nowEpochMs = 1_000L,
                candidateVersionCode = 1020101,
                lastPromptedVersionCode = 1020100,
                cooldownUntilEpochMs = 2_000L,
            ),
        )
    }

    @Test
    fun cooldownSuppression_isBypassedForManualOrCritical() {
        assertFalse(
            UpdatePromptPolicy.shouldSuppressByCooldown(
                manual = true,
                critical = false,
                nowEpochMs = 1_000L,
                candidateVersionCode = 1020101,
                lastPromptedVersionCode = 1020101,
                cooldownUntilEpochMs = 2_000L,
            ),
        )
        assertFalse(
            UpdatePromptPolicy.shouldSuppressByCooldown(
                manual = false,
                critical = true,
                nowEpochMs = 1_000L,
                candidateVersionCode = 1020101,
                lastPromptedVersionCode = 1020101,
                cooldownUntilEpochMs = 2_000L,
            ),
        )
    }

    @Test
    fun minimumAutoSuppression_appliesOnlyForBackgroundNonCriticalAndLowerCurrentVersion() {
        assertTrue(
            UpdatePromptPolicy.shouldSuppressByMinimumAutoVersion(
                manual = false,
                critical = false,
                currentVersionCode = 1020001,
                minimumAutoUpdateVersionCode = 1020100,
            ),
        )
        assertFalse(
            UpdatePromptPolicy.shouldSuppressByMinimumAutoVersion(
                manual = false,
                critical = false,
                currentVersionCode = 1020100,
                minimumAutoUpdateVersionCode = 1020100,
            ),
        )
        assertFalse(
            UpdatePromptPolicy.shouldSuppressByMinimumAutoVersion(
                manual = true,
                critical = false,
                currentVersionCode = 1020001,
                minimumAutoUpdateVersionCode = 1020100,
            ),
        )
        assertFalse(
            UpdatePromptPolicy.shouldSuppressByMinimumAutoVersion(
                manual = false,
                critical = true,
                currentVersionCode = 1020001,
                minimumAutoUpdateVersionCode = 1020100,
            ),
        )
    }

    @Test
    fun clearSkippedVersion_gateMatchesThresholdRule() {
        assertTrue(
            UpdatePromptPolicy.shouldClearSkippedVersion(
                skippedVersion = 1020001,
                ignoreSkippedUpgradesBelowVersionCode = 1020100,
            ),
        )
        assertFalse(
            UpdatePromptPolicy.shouldClearSkippedVersion(
                skippedVersion = 1020100,
                ignoreSkippedUpgradesBelowVersionCode = 1020100,
            ),
        )
    }

    @Test
    fun dismissCooldownSchedule_matches24h72h7dPolicy() {
        assertEquals(24L * 60 * 60 * 1_000, UpdatePromptPolicy.nextDismissCooldownMillis(0))
        assertEquals(72L * 60 * 60 * 1_000, UpdatePromptPolicy.nextDismissCooldownMillis(1))
        assertEquals(7L * 24 * 60 * 60 * 1_000, UpdatePromptPolicy.nextDismissCooldownMillis(2))
        assertEquals(7L * 24 * 60 * 60 * 1_000, UpdatePromptPolicy.nextDismissCooldownMillis(88))
    }
}
