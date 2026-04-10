package io.ethan.pushgo.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.ethan.pushgo.ui.theme.PushGoThemeExtras

@Composable
internal fun PushGoAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    val uiColors = PushGoThemeExtras.colors
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        containerColor = uiColors.surfaceRaised,
        iconContentColor = uiColors.accentPrimary,
        titleContentColor = uiColors.textPrimary,
        textContentColor = uiColors.textSecondary,
    )
}

@Composable
internal fun PushGoDestructiveTextButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val uiColors = PushGoThemeExtras.colors
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = uiColors.stateDanger.foreground,
            disabledContentColor = uiColors.textSecondary,
        ),
    ) {
        Text(text)
    }
}
