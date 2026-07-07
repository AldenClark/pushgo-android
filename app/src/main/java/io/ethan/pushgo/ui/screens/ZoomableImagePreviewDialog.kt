package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.accessibility.pushGoPaneSemantics
import io.ethan.pushgo.ui.theme.PushGoThemeExtras

@Composable
fun ZoomableImagePreviewDialog(
    model: Any?,
    onDismiss: () -> Unit,
    onSaveImage: (() -> Unit)? = null,
    onShareImage: (() -> Unit)? = null,
    autoPlayAnimated: Boolean = true,
) {
    if (model == null) return
    val uiColors = PushGoThemeExtras.colors
    var scale by remember(model) { mutableFloatStateOf(1f) }
    var offset by remember(model) { mutableStateOf(Offset.Zero) }
    var isPlaying by remember(model, autoPlayAnimated) { mutableStateOf(autoPlayAnimated) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(uiColors.overlayScrim)
                .pushGoPaneSemantics(stringResource(R.string.a11y_pane_image_preview))
                .testTag("dialog.image.preview")
        ) {
            PushGoPlayableImage(
                model = model,
                contentDescription = stringResource(R.string.label_image_attachment),
                contentScale = ContentScale.Fit,
                shouldPlayAnimated = isPlaying,
                enableCrossfade = false,
                onPlayClick = { isPlaying = true },
                onPlaybackFinished = { isPlaying = false },
                onClickLabel = stringResource(R.string.a11y_action_open_image_preview),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(model) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            scale = nextScale
                            offset = if (nextScale <= 1f) {
                                Offset.Zero
                            } else {
                                offset + pan
                            }
                        }
                    }
                    .pointerInput(model) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2f
                                }
                            }
                        )
                    }
                    .align(Alignment.Center)
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSaveImage != null) {
                    PushGoCircularActionIconButton(
                        imageVector = Icons.Outlined.Download,
                        accessibilityLabel = stringResource(R.string.a11y_action_save_image),
                        onClick = onSaveImage,
                        containerColor = uiColors.overlayScrim.copy(alpha = 0.35f),
                        contentColor = uiColors.overlayForeground,
                        modifier = Modifier.testTag("action.dialog.image_preview.save"),
                    )
                }
                if (onShareImage != null) {
                    PushGoCircularActionIconButton(
                        imageVector = Icons.Outlined.Share,
                        accessibilityLabel = stringResource(R.string.a11y_action_share_image),
                        onClick = onShareImage,
                        containerColor = uiColors.overlayScrim.copy(alpha = 0.35f),
                        contentColor = uiColors.overlayForeground,
                        modifier = Modifier.testTag("action.dialog.image_preview.share"),
                    )
                }
                PushGoCircularActionIconButton(
                    imageVector = Icons.Outlined.Close,
                    accessibilityLabel = stringResource(R.string.a11y_action_close_image_preview),
                    onClick = onDismiss,
                    containerColor = uiColors.overlayScrim.copy(alpha = 0.35f),
                    contentColor = uiColors.overlayForeground,
                    modifier = Modifier.testTag("action.dialog.image_preview.dismiss"),
                )
            }
        }
    }
}
