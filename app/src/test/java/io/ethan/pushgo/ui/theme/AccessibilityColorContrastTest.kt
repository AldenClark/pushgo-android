package io.ethan.pushgo.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityColorContrastTest {
    @Test
    fun keyForegroundPairs_meetContrastTargets() {
        assertContrastAtLeast(LightExtendedColors.textPrimary, LightExtendedColors.surfaceBase, 7.0)
        assertContrastAtLeast(LightExtendedColors.textSecondary, LightExtendedColors.surfaceBase, 4.5)
        assertContrastAtLeast(LightExtendedColors.iconMuted, LightExtendedColors.surfaceBase, 4.5)
        assertContrastAtLeast(LightExtendedColors.stateInfo.foreground, LightExtendedColors.stateInfo.background, 4.5)
        assertContrastAtLeast(LightExtendedColors.stateSuccess.foreground, LightExtendedColors.stateSuccess.background, 4.5)
        assertContrastAtLeast(LightExtendedColors.stateWarning.foreground, LightExtendedColors.stateWarning.background, 4.5)
        assertContrastAtLeast(LightExtendedColors.stateDanger.foreground, LightExtendedColors.stateDanger.background, 4.5)

        assertContrastAtLeast(DarkExtendedColors.textPrimary, DarkExtendedColors.surfaceBase, 7.0)
        assertContrastAtLeast(DarkExtendedColors.textSecondary, DarkExtendedColors.surfaceBase, 4.5)
        assertContrastAtLeast(DarkExtendedColors.iconMuted, DarkExtendedColors.surfaceBase, 4.5)
        assertContrastAtLeast(DarkExtendedColors.stateInfo.foreground, DarkExtendedColors.stateInfo.background, 4.5)
        assertContrastAtLeast(DarkExtendedColors.stateSuccess.foreground, DarkExtendedColors.stateSuccess.background, 4.5)
        assertContrastAtLeast(DarkExtendedColors.stateWarning.foreground, DarkExtendedColors.stateWarning.background, 4.5)
        assertContrastAtLeast(DarkExtendedColors.stateDanger.foreground, DarkExtendedColors.stateDanger.background, 4.5)
    }

    private fun assertContrastAtLeast(foreground: Color, background: Color, minimum: Double) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("Expected contrast >= $minimum but was $ratio", ratio >= minimum)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val light = maxOf(relativeLuminance(foreground), relativeLuminance(background))
        val dark = minOf(relativeLuminance(foreground), relativeLuminance(background))
        return ((light + 0.05f) / (dark + 0.05f)).toDouble()
    }

    private fun relativeLuminance(color: Color): Float {
        fun channel(value: Float): Float {
            return if (value <= 0.03928f) {
                value / 12.92f
            } else {
                Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
        }

        return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
    }
}
