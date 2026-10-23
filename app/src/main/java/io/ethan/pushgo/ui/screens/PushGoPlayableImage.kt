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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.request.CachePolicy
import coil3.gif.AnimatedImageDecoder
import io.ethan.pushgo.data.MessageImageStore
import io.ethan.pushgo.data.ImageResourceObservation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.DrawableImage
import coil3.Image
import coil3.compose.AsyncImage
import coil3.gif.onAnimationEnd
import coil3.gif.repeatCount
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.ethan.pushgo.R
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
    knownAnimated: Boolean? = null,
    showPlayOverlayWhenIdle: Boolean = true,
    enableCrossfade: Boolean = true,
    onPlayClick: (() -> Unit)? = null,
    onPauseClick: (() -> Unit)? = null,
    onPlaybackFinished: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onImageLoadStateChanged: ((PushGoImageLoadState) -> Unit)? = null,
) {
    val context = LocalContext.current
    var retryVersion by remember(model) { mutableIntStateOf(0) }
    val retryLabel = stringResource(R.string.action_retry)
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var visible by remember(model) { mutableStateOf(true) }
    var decodedImage by remember(model) { mutableStateOf<Image?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val uiColors = PushGoThemeExtras.colors
    var detectedAnimated by remember(model) { mutableStateOf<Boolean?>(null) }
    var imageLoadState by remember(model) {
        mutableStateOf(if (model == null) PushGoImageLoadState.Empty else PushGoImageLoadState.Loading)
    }
    var hidePlayOverlayUntilPlaybackEnds by remember(model) { mutableStateOf(false) }
    val playbackFinishedState by rememberUpdatedState(onPlaybackFinished)
    val resolvedAnimatedHint = detectedAnimated ?: knownAnimated ?: if (pushGoIsAnimatedModel(model)) true else null
    val wantsAnimation = shouldPlayAnimated && foreground && visible
    val shouldAnimate = wantsAnimation && (resolvedAnimatedHint != false)
    val resourceObservation = remember(model, retryVersion) { ImageResourceObservation() }
    LaunchedEffect(shouldAnimate) {
        if (!shouldAnimate) {
            hidePlayOverlayUntilPlaybackEnds = false
        }
    }
    LaunchedEffect(imageLoadState) {
        onImageLoadStateChanged?.invoke(imageLoadState)
    }
    val request = remember(context, model, wantsAnimation, enableCrossfade, retryVersion) {
        val builder = when (model) {
            is ImageRequest -> model.newBuilder(context)
            else -> ImageRequest.Builder(context).data(model)
        }
            .crossfade(enableCrossfade)
        builder
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .decoderCoroutineContext(ResourceImageDecodeBudget.dispatcher)
            .decoderFactory(if (wantsAnimation) AnimatedImageDecoder.Factory() else ResourceImageDecodeBudget.staticDecoder)
            .size(ResourceImageDecodeBudget.DETAIL_SIDE)
            .repeatCount(0)
        builder.extras[ImageResourceObservation.Key] = resourceObservation
        if (wantsAnimation) {
            builder.onAnimationEnd {
                playbackFinishedState?.invoke()
            }
        }
        builder.build()
    }

    DisposableEffect(decodedImage, shouldAnimate) {
        val image = decodedImage
        if (shouldAnimate) image?.startAnimationIfPossible() else image?.stopAnimationIfPossible()
        onDispose { image?.stopAnimationIfPossible() }
    }

    val imageClickModifier = if (onClick != null && imageLoadState.isLoaded) {
        Modifier.clickable(
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier.then(imageClickModifier).onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            val screen = context.resources.displayMetrics
            visible = bounds.bottom > 0 && bounds.right > 0 && bounds.top < screen.heightPixels && bounds.left < screen.widthPixels
        },
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            alignment = alignment,
            clipToBounds = clipToBounds,
            onLoading = {
                decodedImage?.stopAnimationIfPossible()
                decodedImage = null
                imageLoadState = PushGoImageLoadState.Loading
            },
            onSuccess = { success ->
                imageLoadState = if (resourceObservation.stale) PushGoImageLoadState.Stale else PushGoImageLoadState.Loaded
                decodedImage = success.result.image
                val animatable = success.result.image.isAnimationCapable()
                detectedAnimated = if (wantsAnimation) animatable else resourceObservation.animated
                if (animatable) {
                    if (shouldAnimate) {
                        success.result.image.startAnimationIfPossible()
                    } else {
                        success.result.image.stopAnimationIfPossible()
                    }
                }
            },
            onError = {
                decodedImage?.stopAnimationIfPossible()
                decodedImage = null
                imageLoadState = PushGoImageLoadState.Error
            },
        )
        if (!imageLoadState.isLoaded) {
            PushGoImagePlaceholder(
                modifier = Modifier.fillMaxSize().then(
                    if (imageLoadState == PushGoImageLoadState.Error) Modifier.clickable(onClickLabel = retryLabel) { retryVersion++ } else Modifier,
                ),
                isError = imageLoadState == PushGoImageLoadState.Error,
            )
        }

        if (imageLoadState == PushGoImageLoadState.Stale) {
            ResourceImageFallbackBadge(Modifier.align(Alignment.TopEnd)) { retryVersion++ }
        }

        if (
            imageLoadState.isLoaded &&
            resolvedAnimatedHint == true &&
            showPlayOverlayWhenIdle &&
            (shouldAnimate || !hidePlayOverlayUntilPlaybackEnds) &&
            onPlayClick != null
        ) {
            IconButton(
                onClick = {
                    if (shouldAnimate) {
                        hidePlayOverlayUntilPlaybackEnds = false
                        onPauseClick?.invoke()
                    } else {
                        hidePlayOverlayUntilPlaybackEnds = true
                        onPlayClick()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(uiColors.overlayScrim.copy(alpha = 0.35f)),
            ) {
                Icon(
                    imageVector = if (shouldAnimate) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (shouldAnimate) R.string.a11y_action_pause_image else R.string.a11y_action_play_image),
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
        is MessageImageStore.ImageRequestModel -> "${model.ownerScope}:${model.ownerId}:${model.ownerVersion}:${model.ownerSelector}:${model.url}"
        else -> model.toString()
    }
    return raw.trim()
}

internal fun pushGoIsAnimatedModel(model: Any?): Boolean {
    val identity = pushGoImageModelIdentity(model)
    if (identity.isEmpty()) {
        return false
    }
    val rawUrl = (model as? MessageImageStore.ImageRequestModel)?.url ?: identity
    val sanitized = rawUrl.substringBefore('#').substringBefore('?')
    val name = sanitized.substringAfterLast('/')
    val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
    // WebP can be either static or animated; do not infer animation from extension alone.
    // Animated WebP should be determined by decoded drawable capability or persisted metadata.
    return extension == "gif" || extension == "apng"
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
