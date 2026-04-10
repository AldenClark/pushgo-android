package io.ethan.pushgo.ui.theme

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun pushGoSegmentedButtonColors(): SegmentedButtonColors {
    val uiColors = PushGoThemeExtras.colors
    return SegmentedButtonDefaults.colors(
        activeContainerColor = uiColors.selectionFill,
        activeContentColor = uiColors.accentPrimary,
        activeBorderColor = uiColors.accentPrimary,
        inactiveContainerColor = uiColors.surfaceBase,
        inactiveContentColor = uiColors.textSecondary,
        inactiveBorderColor = uiColors.dividerStrong,
        disabledActiveContainerColor = uiColors.dividerSubtle,
        disabledActiveContentColor = uiColors.textSecondary,
        disabledInactiveContainerColor = uiColors.surfaceBase,
        disabledInactiveContentColor = uiColors.textSecondary,
        disabledActiveBorderColor = uiColors.dividerStrong,
        disabledInactiveBorderColor = uiColors.dividerStrong,
    )
}

@Composable
fun pushGoPrimaryButtonElevation() = ButtonDefaults.buttonElevation(
    defaultElevation = 4.dp,
    pressedElevation = 2.dp,
)
