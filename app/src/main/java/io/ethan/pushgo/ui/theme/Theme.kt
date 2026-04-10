package io.ethan.pushgo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun PushGoTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val extendedColors = when {
        dynamicColor && useDarkTheme -> DarkExtendedColors
        dynamicColor -> LightExtendedColors
        useDarkTheme -> DarkExtendedColors
        else -> LightExtendedColors
    }
    val colorScheme = if (useDarkTheme) {
        extendedColors.toDarkMaterialColorScheme()
    } else {
        extendedColors.toMaterialColorScheme()
    }

    CompositionLocalProvider(LocalPushGoExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
