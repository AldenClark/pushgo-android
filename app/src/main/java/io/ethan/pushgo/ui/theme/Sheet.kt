package io.ethan.pushgo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

@Composable
fun PushGoSheetContainerColor(): Color {
    return lerp(
        start = MaterialTheme.colorScheme.background,
        stop = MaterialTheme.colorScheme.surfaceVariant,
        fraction = 0.2f,
    )
}
