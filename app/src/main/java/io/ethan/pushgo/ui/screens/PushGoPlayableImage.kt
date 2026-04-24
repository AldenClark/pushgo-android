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
import androidx.compose.runtime.produceState
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
import io.ethan.pushgo.data.ImageAssetMetadataStore
import io.ethan.pushgo.ui.theme.PushGoThemeExtras
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PushGoPlayableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    clipToBounds: Boolean = true,
    shouldPlayAnimated: Boolean = false,
    knownAnimated: Boolean? = null,
    showPlayOverlayWhenIdle: Boolean = true,
    enableCrossfade: Boolean = true,
    onPlayClick: (() -> Unit)? = null,
    onPlaybackFinished: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onImageLoadStateChanged: ((PushGoImageLoadState) -> Unit)? = null,
) {
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    val metadataAnimatedHint = rememberAnimatedHint(model)
    var detectedAnimated by remember(model) { mutableStateOf<Boolean?>(null) }
    var imageLoadState by remember(model) {
        mutableStateOf(if (model == null) PushGoImageLoadState.Empty else PushGoImageLoadState.Loading)
    }
    var hidePlayOverlayUntilPlaybackEnds by remember(model) { mutableStateOf(false) }
    val playbackFinishedState by rememberUpdatedState(onPlaybackFinished)
    val resolvedAnimatedHint = knownAnimated ?: metadataAnimatedHint ?: detectedAnimated
    val shouldAnimate = shouldPlayAnimated && (resolvedAnimatedHint != false)
    LaunchedEffect(shouldAnimate) {
        if (!shouldAnimate) {
            hidePlayOverlayUntilPlaybackEnds = false
        }
    }
    LaunchedEffect(imageLoadState) {
        onImageLoadStateChanged?.invoke(imageLoadState)
    }
    val request = remember(context, model, shouldAnimate, enableCrossfade) {
        val builder = when (model) {
            is ImageRequest -> model.newBuilder(context)
            else -> ImageRequest.Builder(context).data(model)
        }
            .crossfade(enableCrossfade)
        builder.repeatCount(0)
        if (shouldAnimate) {
            builder.onAnimationEnd {
                playbackFinishedState?.invoke()
            }
        }
        builder.build()
    }

    val imageClickModifier = if (onClick != null && imageLoadState.isLoaded) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

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
            onLoading = {
                imageLoadState = PushGoImageLoadState.Loading
            },
            onSuccess = { success ->
                imageLoadState = PushGoImageLoadState.Loaded
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
            onError = {
                imageLoadState = PushGoImageLoadState.Error
            },
        )
        if (!imageLoadState.isLoaded) {
            PushGoImagePlaceholder(
                modifier = Modifier.fillMaxSize(),
                isError = imageLoadState == PushGoImageLoadState.Error,
            )
        }

        if (
            resolvedAnimatedHint == true &&
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

@Composable
private fun rememberAnimatedHint(model: Any?): Boolean? {
    val context = LocalContext.current
    val identity = remember(model) { pushGoImageModelIdentity(model) }
    val metadataHint by produceState<Boolean?>(initialValue = null, identity) {
        if (identity.isBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            ImageAssetMetadataStore.get(context.applicationContext).findByUrl(identity)?.isAnimated
        }
    }
    return metadataHint ?: if (pushGoIsAnimatedModel(model)) true else null
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
