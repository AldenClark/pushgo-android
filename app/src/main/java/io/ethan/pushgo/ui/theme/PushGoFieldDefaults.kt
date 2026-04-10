package io.ethan.pushgo.ui.theme

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable

@Composable
fun pushGoOutlinedTextFieldColors(): TextFieldColors {
    val uiColors = PushGoThemeExtras.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = uiColors.accentPrimary,
        unfocusedBorderColor = uiColors.dividerStrong,
        focusedContainerColor = uiColors.fieldContainer,
        unfocusedContainerColor = uiColors.fieldContainer,
    )
}

@Composable
fun pushGoPrimaryButtonColors(): ButtonColors {
    val uiColors = PushGoThemeExtras.colors
    return ButtonDefaults.buttonColors(
        containerColor = uiColors.accentPrimary,
        contentColor = uiColors.accentOnPrimary,
        disabledContainerColor = uiColors.dividerSubtle,
        disabledContentColor = uiColors.textSecondary,
    )
}

@Composable
fun pushGoDangerButtonColors(): ButtonColors {
    val uiColors = PushGoThemeExtras.colors
    return ButtonDefaults.buttonColors(
        containerColor = uiColors.stateDanger.foreground,
        contentColor = uiColors.overlayForeground,
        disabledContainerColor = uiColors.stateDanger.background,
        disabledContentColor = uiColors.textSecondary,
    )
}
