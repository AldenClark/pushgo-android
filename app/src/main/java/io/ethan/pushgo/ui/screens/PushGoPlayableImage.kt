package io.ethan.pushgo.ui.screens

import android.graphics.drawable.Animatable
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.DrawableImage
import coil3.Image
import coil3.compose.AsyncImage
import coil3.gif.onAnimationEnd
import coil3.gif.repeatCount
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import java.io.File
import java.util.Locale

@Composable
fun PushGoPlayableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    clipToBounds: Boolean = true,
    shouldPlayAnimated: Boolean = false,
    showPlayOverlayWhenIdle: Boolean = true,
    onPlayClick: (() -> Unit)? = null,
    onPlaybackFinished: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    var detectedAnimated by remember(model) { mutableStateOf<Boolean?>(null) }
    var hidePlayOverlayUntilPlaybackEnds by remember(model) { mutableStateOf(false) }
    val playbackFinishedState by rememberUpdatedState(onPlaybackFinished)
    val shouldAnimate = shouldPlayAnimated && (detectedAnimated != false)
    LaunchedEffect(shouldAnimate) {
        if (!shouldAnimate) {
            hidePlayOverlayUntilPlaybackEnds = false
        }
    }
    val request = remember(context, model, shouldAnimate) {
        val builder = when (model) {
            is ImageRequest -> model.newBuilder(context)
            else -> ImageRequest.Builder(context).data(model)
        }
            .crossfade(true)
        builder.repeatCount(0)
        if (shouldAnimate) {
            builder.onAnimationEnd {
                playbackFinishedState?.invoke()
            }
        }
        builder.build()
    }

    val imageClickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Box(
        modifier = modifier.then(imageClickModifier),
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            alignment = alignment,
            clipToBounds = clipToBounds,
            onSuccess = { success ->
                val animatable = success.result.image.isAnimationCapable()
                if (detectedAnimated != animatable) {
                    detectedAnimated = animatable
                }
                if (animatable) {
                    if (shouldAnimate) {
                        success.result.image.startAnimationIfPossible()
                    } else {
                        success.result.image.stopAnimationIfPossible()
                    }
                }
            },
        )

        if (
            detectedAnimated == true &&
            showPlayOverlayWhenIdle &&
            !shouldAnimate &&
            !hidePlayOverlayUntilPlaybackEnds &&
            onPlayClick != null
        ) {
            IconButton(
                onClick = {
                    hidePlayOverlayUntilPlaybackEnds = true
                    onPlayClick()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(uiColors.overlayScrim.copy(alpha = 0.35f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = uiColors.overlayForeground,
                )
            }
        }
    }
}

internal fun pushGoImageModelIdentity(model: Any?): String {
    val raw = when (model) {
        null -> ""
        is String -> model
        is File -> model.absolutePath
        is Uri -> model.toString()
        is ImageRequest -> pushGoImageModelIdentity(model.data)
        else -> model.toString()
    }
    return raw.trim()
}

internal fun pushGoIsAnimatedModel(model: Any?): Boolean {
    val identity = pushGoImageModelIdentity(model)
    if (identity.isEmpty()) {
        return false
    }
    val sanitized = identity.substringBefore('#').substringBefore('?')
    val name = sanitized.substringAfterLast('/')
    val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
    return extension == "gif" || extension == "webp" || extension == "apng"
}

private fun Image.stopAnimationIfPossible() {
    val drawable = (this as? DrawableImage)?.drawable ?: return
    when (drawable) {
        is Animatable -> drawable.stop()
        is androidx.vectordrawable.graphics.drawable.Animatable2Compat -> drawable.stop()
    }
}

private fun Image.startAnimationIfPossible() {
    val drawable = (this as? DrawableImage)?.drawable ?: return
    when (drawable) {
        is Animatable -> drawable.start()
        is androidx.vectordrawable.graphics.drawable.Animatable2Compat -> drawable.start()
    }
}

private fun Image.isAnimationCapable(): Boolean {
    val drawable = (this as? DrawableImage)?.drawable ?: return false
    return when (drawable) {
        is Animatable -> true
        is androidx.vectordrawable.graphics.drawable.Animatable2Compat -> true
        else -> false
    }
}
