package io.ethan.pushgo.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.focusable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ethan.pushgo.ui.accessibility.pushGoPaneSemantics
import io.ethan.pushgo.ui.theme.pushGoSheetContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushGoModalBottomSheet(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
    sheetState: SheetState? = null,
    minHeightFraction: Float? = null,
    maxHeightFraction: Float = 1f,
    paneTitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedSheetState = sheetState ?: rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val containerHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val focusRequester = remember { FocusRequester() }
    val resolvedMaxHeightFraction = maxHeightFraction.coerceIn(0.5f, 1f)
    val maxSheetHeight = containerHeight * resolvedMaxHeightFraction
    val minSheetHeight = minHeightFraction
        ?.coerceIn(0f, resolvedMaxHeightFraction)
        ?.let { containerHeight * it }
        ?: Dp.Unspecified
    val sheetContainerColor = pushGoSheetContainerColor()
    val activity = LocalContext.current.findActivity()
    @Suppress("DEPRECATION")
    DisposableEffect(activity, sheetContainerColor) {
        val window = activity?.window
        if (window == null) {
            onDispose {}
        } else {
            val previousNavigationBarColor = window.navigationBarColor
            window.navigationBarColor = sheetContainerColor.toArgb()
            onDispose {
                window.navigationBarColor = previousNavigationBarColor
            }
        }
    }
    LaunchedEffect(paneTitle) {
        if (!paneTitle.isNullOrBlank()) {
            focusRequester.requestFocus()
        }
    }
    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        sheetState = resolvedSheetState,
        containerColor = sheetContainerColor,
        tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets(0) },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minSheetHeight, max = maxSheetHeight)
                    .focusRequester(focusRequester)
                    .focusable()
                    .pushGoPaneSemantics(paneTitle),
                content = content,
            )
        },
    )
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
