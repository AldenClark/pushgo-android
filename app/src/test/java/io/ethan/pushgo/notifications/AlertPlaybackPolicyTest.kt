package io.ethan.pushgo.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPlaybackPolicyTest {
    @Test
    fun critical_isContinuous() {
        val spec = AlertPlaybackPolicy.specForLevel("critical")
        assertTrue(spec.isEnabled)
        assertEquals(AlertPlaybackMode.CONTINUOUS, spec.mode)
        assertEquals(null, spec.maxDurationMs)
    }

    @Test
    fun high_isTimed() {
        val high = AlertPlaybackPolicy.specForLevel("high")
        assertEquals(AlertPlaybackMode.TIMED, high.mode)
        assertTrue((high.maxDurationMs ?: 0L) > 0L)
    }

    @Test
    fun normal_and_low_areSilent() {
        val normal = AlertPlaybackPolicy.specForLevel("normal")
        assertFalse(normal.isEnabled)
        assertEquals(AlertPlaybackMode.OFF, normal.mode)

        val spec = AlertPlaybackPolicy.specForLevel("low")
        assertFalse(spec.isEnabled)
        assertEquals(AlertPlaybackMode.OFF, spec.mode)
    }

    @Test
    fun severityRank_prefersCriticalOverHighAndNormal() {
        assertTrue(AlertPlaybackPolicy.severityRank("critical") > AlertPlaybackPolicy.severityRank("high"))
        assertTrue(AlertPlaybackPolicy.severityRank("high") > AlertPlaybackPolicy.severityRank("normal"))
        assertTrue(AlertPlaybackPolicy.severityRank("normal") > AlertPlaybackPolicy.severityRank("low"))
    }
}
