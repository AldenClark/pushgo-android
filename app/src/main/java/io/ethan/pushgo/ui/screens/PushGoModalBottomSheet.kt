package io.ethan.pushgo.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.ethan.pushgo.ui.theme.PushGoSheetContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushGoModalBottomSheet(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
    sheetState: SheetState? = null,
    minHeightFraction: Float? = null,
    maxHeightFraction: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedSheetState = sheetState ?: rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val resolvedMaxHeightFraction = maxHeightFraction.coerceIn(0.5f, 1f)
    val maxSheetHeight = configuration.screenHeightDp.dp * resolvedMaxHeightFraction
    val minSheetHeight = minHeightFraction
        ?.coerceIn(0f, resolvedMaxHeightFraction)
        ?.let { configuration.screenHeightDp.dp * it }
        ?: Dp.Unspecified
    val sheetContainerColor = PushGoSheetContainerColor()
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
                    .heightIn(min = minSheetHeight, max = maxSheetHeight),
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
