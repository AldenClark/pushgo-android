package io.ethan.pushgo.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class PushGoStateColors(
    val foreground: Color,
    val background: Color,
)

@Immutable
data class PushGoExtendedColors(
    val surfaceBase: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val selectionFill: Color,
    val sheetContainer: Color,
    val fieldContainer: Color,
    val dividerSubtle: Color,
    val dividerStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accentPrimary: Color,
    val accentOnPrimary: Color,
    val iconMuted: Color,
    val placeholderText: Color,
    val selectedRowFill: Color,
    val navigationBarBackground: Color,
    val overlayScrim: Color,
    val overlayForeground: Color,
    val codeBackground: Color,
    val quoteStroke: Color,
    val tableBorder: Color,
    val tableHeaderBackground: Color,
    val tableOddRowBackground: Color,
    val tableEvenRowBackground: Color,
    val shimmerBase: Color,
    val shimmerHighlight: Color,
    val stateInfo: PushGoStateColors,
    val stateNeutral: PushGoStateColors,
    val stateSuccess: PushGoStateColors,
    val stateWarning: PushGoStateColors,
    val stateDanger: PushGoStateColors,
)

internal val LightExtendedColors = PushGoExtendedColors(
    surfaceBase = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF0F4F9),
    surfaceSunken = Color(0xFFE7ECF3),
    selectionFill = Color(0xFFD3E3FD),
    sheetContainer = Color(0xFFF0F4F9),
    fieldContainer = Color(0xFFF0F4F9),
    dividerSubtle = Color(0xFFD7DDE5),
    dividerStrong = Color(0xFFB8C0CC),
    textPrimary = Color(0xFF1E1E1E),
    textSecondary = Color(0xFF444746),
    accentPrimary = Color(0xFF0B57D0),
    accentOnPrimary = Color(0xFFFFFFFF),
    iconMuted = Color(0xFF6B7280),
    placeholderText = Color(0xFF6B7280),
    selectedRowFill = Color(0xFFDCE7F8),
    navigationBarBackground = Color(0xFFF0F4F9),
    overlayScrim = Color(0xF0000000),
    overlayForeground = Color(0xFFFFFFFF),
    codeBackground = Color(0xFFE7ECF3),
    quoteStroke = Color(0xFFB8C0CC),
    tableBorder = Color(0xFFB8C0CC),
    tableHeaderBackground = Color(0xFFF0F4F9),
    tableOddRowBackground = Color(0xFFF6F8FB),
    tableEvenRowBackground = Color(0xFFFFFFFF),
    shimmerBase = Color(0xFFB8C0CC).copy(alpha = 0.22f),
    shimmerHighlight = Color(0xFFB8C0CC).copy(alpha = 0.46f),
    stateInfo = PushGoStateColors(
        foreground = Color(0xFF0B57D0),
        background = Color(0xFFD3E3FD),
    ),
    stateNeutral = PushGoStateColors(
        foreground = Color(0xFF6B7280),
        background = Color(0xFFEEF1F5),
    ),
    stateSuccess = PushGoStateColors(
        foreground = Color(0xFF15803D),
        background = Color(0xFFE2F5E8),
    ),
    stateWarning = PushGoStateColors(
        foreground = Color(0xFFD97706),
        background = Color(0xFFFFF4D8),
    ),
    stateDanger = PushGoStateColors(
        foreground = Color(0xFFB91C1C),
        background = Color(0xFFFEE2E2),
    ),
)

internal val DarkExtendedColors = PushGoExtendedColors(
    surfaceBase = Color(0xFF0B0F13),
    surfaceRaised = Color(0xFF1E2329),
    surfaceSunken = Color(0xFF171B20),
    selectionFill = Color(0xFF233F68),
    sheetContainer = Color(0xFF1E2329),
    fieldContainer = Color(0xFF1E2329),
    dividerSubtle = Color(0xFF2C333A),
    dividerStrong = Color(0xFF48515B),
    textPrimary = Color(0xFFE3E3E3),
    textSecondary = Color(0xFFC4C7C5),
    accentPrimary = Color(0xFFA8C7FA),
    accentOnPrimary = Color(0xFF002F65),
    iconMuted = Color(0xFF9CA3AF),
    placeholderText = Color(0xFF9CA3AF),
    selectedRowFill = Color(0xFF233F68),
    navigationBarBackground = Color(0xFF0B0F13),
    overlayScrim = Color(0xF0000000),
    overlayForeground = Color(0xFFFFFFFF),
    codeBackground = Color(0xFF171B20),
    quoteStroke = Color(0xFF697483),
    tableBorder = Color(0xFF697483),
    tableHeaderBackground = Color(0xFF1E2329),
    tableOddRowBackground = Color(0xFF171B20),
    tableEvenRowBackground = Color(0xFF0B0F13),
    shimmerBase = Color(0xFF48515B).copy(alpha = 0.28f),
    shimmerHighlight = Color(0xFF697483).copy(alpha = 0.52f),
    stateInfo = PushGoStateColors(
        foreground = Color(0xFFA8C7FA),
        background = Color(0xFF12304D),
    ),
    stateNeutral = PushGoStateColors(
        foreground = Color(0xFF9CA3AF),
        background = Color(0xFF232830),
    ),
    stateSuccess = PushGoStateColors(
        foreground = Color(0xFF67D98C),
        background = Color(0xFF103522),
    ),
    stateWarning = PushGoStateColors(
        foreground = Color(0xFFF6C56F),
        background = Color(0xFF3A2B07),
    ),
    stateDanger = PushGoStateColors(
        foreground = Color(0xFFFCA5A5),
        background = Color(0xFF4A1719),
    ),
)

internal val LocalPushGoExtendedColors = staticCompositionLocalOf<PushGoExtendedColors> {
    error("PushGoExtendedColors are not provided")
}

object PushGoThemeExtras {
    val colors: PushGoExtendedColors
        @Composable
        get() = LocalPushGoExtendedColors.current
}

internal fun PushGoExtendedColors.toMaterialColorScheme(): ColorScheme = lightColorScheme(
    primary = accentPrimary,
    onPrimary = accentOnPrimary,
    primaryContainer = selectionFill,
    onPrimaryContainer = textPrimary,
    secondary = accentPrimary,
    onSecondary = accentOnPrimary,
    secondaryContainer = selectionFill,
    onSecondaryContainer = textPrimary,
    background = surfaceBase,
    onBackground = textPrimary,
    surface = surfaceBase,
    onSurface = textPrimary,
    surfaceVariant = surfaceRaised,
    onSurfaceVariant = textSecondary,
    outline = dividerStrong,
    outlineVariant = dividerSubtle,
    error = stateDanger.foreground,
    onError = overlayForeground,
    errorContainer = stateDanger.background,
    onErrorContainer = stateDanger.foreground,
)

internal fun PushGoExtendedColors.toDarkMaterialColorScheme(): ColorScheme = darkColorScheme(
    primary = accentPrimary,
    onPrimary = accentOnPrimary,
    primaryContainer = selectionFill,
    onPrimaryContainer = overlayForeground,
    secondary = accentPrimary,
    onSecondary = accentOnPrimary,
    secondaryContainer = selectionFill,
    onSecondaryContainer = overlayForeground,
    background = surfaceBase,
    onBackground = textPrimary,
    surface = surfaceBase,
    onSurface = textPrimary,
    surfaceVariant = surfaceRaised,
    onSurfaceVariant = textSecondary,
    outline = dividerStrong,
    outlineVariant = dividerSubtle,
    error = stateDanger.foreground,
    onError = accentOnPrimary,
    errorContainer = stateDanger.background,
    onErrorContainer = stateDanger.foreground,
)
