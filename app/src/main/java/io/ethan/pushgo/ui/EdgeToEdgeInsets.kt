package io.ethan.pushgo.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun rememberBottomGestureInset(): Dp {
    return WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
}

@Composable
fun rememberBottomBarNestedScrollConnection(
    onVisibilityChanged: (Boolean) -> Unit,
    hideThreshold: Dp = 56.dp,
    showThreshold: Dp = 20.dp,
): NestedScrollConnection {
    val density = LocalDensity.current
    val hideThresholdPx = with(density) { hideThreshold.toPx() }
    val showThresholdPx = with(density) { showThreshold.toPx() }

    return remember(onVisibilityChanged, hideThresholdPx, showThresholdPx) {
        object : NestedScrollConnection {
            private var isVisible = true
            private var hideAccumulatorPx = 0f
            private var showAccumulatorPx = 0f

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val deltaY = consumed.y
                if (deltaY == 0f) return Offset.Zero

                if (deltaY < 0f) {
                    showAccumulatorPx = 0f
                    hideAccumulatorPx += -deltaY
                    if (isVisible && hideAccumulatorPx >= hideThresholdPx) {
                        isVisible = false
                        hideAccumulatorPx = 0f
                        onVisibilityChanged(false)
                    }
                } else {
                    hideAccumulatorPx = 0f
                    showAccumulatorPx += deltaY
                    if (!isVisible && showAccumulatorPx >= showThresholdPx) {
                        isVisible = true
                        showAccumulatorPx = 0f
                        onVisibilityChanged(true)
                    }
                }

                return Offset.Zero
            }
        }
    }
}
