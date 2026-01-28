package io.ethan.pushgo.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
private val LightPrimary = Color(0xFF0B57D0)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD3E3FD)
private val LightOnPrimaryContainer = Color(0xFF041E49)
private val LightSecondary = Color(0xFF00639B)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFC2E8FC)
private val LightOnSecondaryContainer = Color(0xFF001D35)
private val LightBackground = Color(0xFFFFFFFF)
private val LightSurface = Color(0xFFFFFFFF)
private val LightOnSurface = Color(0xFF1E1E1E)
private val LightSurfaceVariant = Color(0xFFF0F4F9)
private val LightOnSurfaceVariant = Color(0xFF444746)
private val LightOutline = Color(0xFF747775)

val LightNavigationBar = Color(0xFFF0F4F9)
private val DarkPrimary = Color(0xFFA8C7FA)
private val DarkOnPrimary = Color(0xFF002F65)
private val DarkPrimaryContainer = Color(0xFF00458E)
private val DarkOnPrimaryContainer = Color(0xFFD6E3FF)
private val DarkSecondary = Color(0xFF7FCFFF)
private val DarkOnSecondary = Color(0xFF00344F)
private val DarkSecondaryContainer = Color(0xFF004B72)
private val DarkOnSecondaryContainer = Color(0xFFC2E8FC)
private val DarkBackground = Color(0xFF0B0F13)
private val DarkSurface = Color(0xFF0B0F13)
private val DarkOnSurface = Color(0xFFE3E3E3)
private val DarkSurfaceVariant = Color(0xFF1E2329)
private val DarkOnSurfaceVariant = Color(0xFFC4C7C5)
private val DarkOutline = Color(0xFF8E918F)

val DarkNavigationBar = Color(0xFF0B0F13)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    background = LightBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline
)

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    background = DarkBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline
)
