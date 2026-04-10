package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import io.ethan.pushgo.ui.theme.PushGoThemeExtras

@Composable
internal fun PushGoDividerSubtle(
    modifier: Modifier = Modifier,
    thickness: androidx.compose.ui.unit.Dp = 1.dp,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = thickness,
        color = PushGoThemeExtras.colors.dividerSubtle,
    )
}

@Composable
internal fun PushGoDividerStrong(
    modifier: Modifier = Modifier,
    thickness: androidx.compose.ui.unit.Dp = 1.dp,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = thickness,
        color = PushGoThemeExtras.colors.dividerStrong,
    )
}

@Composable
internal fun PushGoSelectionIndicator(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiColors = PushGoThemeExtras.colors
    Icon(
        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
        contentDescription = null,
        tint = if (selected) uiColors.accentPrimary else uiColors.dividerStrong,
        modifier = modifier
            .size(24.dp)
            .padding(top = 2.dp)
            .clickable(onClick = onClick),
    )
}

@Composable
internal fun PushGoStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
