package io.ethan.pushgo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.theme.PushGoThemeExtras

enum class PushGoImageLoadState {
    Empty,
    Loading,
    Loaded,
    Error,
    ;

    val isLoaded: Boolean
        get() = this == Loaded
}

@Composable
fun PushGoAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    clipToBounds: Boolean = true,
    crossfade: Boolean = true,
    showPlaceholderWhenNotLoaded: Boolean = true,
    onClickWhenLoaded: (() -> Unit)? = null,
    onImageLoadStateChanged: ((PushGoImageLoadState) -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = remember(context, model, crossfade) {
        when (model) {
            null -> null
            is ImageRequest -> model
            else -> ImageRequest.Builder(context)
                .data(model)
                .crossfade(crossfade)
                .build()
        }
    }
    var loadState by remember(request) {
        mutableStateOf(
            if (request == null) PushGoImageLoadState.Empty else PushGoImageLoadState.Loading,
        )
    }
    LaunchedEffect(loadState) {
        onImageLoadStateChanged?.invoke(loadState)
    }

    val clickableModifier = if (onClickWhenLoaded != null && loadState.isLoaded) {
        Modifier.clickable(onClick = onClickWhenLoaded)
    } else {
        Modifier
    }

    Box(modifier = modifier.then(clickableModifier)) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            alignment = alignment,
            clipToBounds = clipToBounds,
            onLoading = {
                loadState = PushGoImageLoadState.Loading
            },
            onSuccess = {
                loadState = PushGoImageLoadState.Loaded
            },
            onError = {
                loadState = PushGoImageLoadState.Error
            },
        )

        if (showPlaceholderWhenNotLoaded && !loadState.isLoaded) {
            PushGoImagePlaceholder(
                modifier = Modifier.fillMaxSize(),
                isError = loadState == PushGoImageLoadState.Error,
            )
        }
    }
}

@Composable
internal fun PushGoImagePlaceholder(
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val uiColors = PushGoThemeExtras.colors
    BoxWithConstraints(
        modifier = modifier.background(uiColors.fieldContainer),
        contentAlignment = Alignment.Center,
    ) {
        val showErrorLabel = isError && maxWidth >= 96.dp && maxHeight >= 72.dp
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (isError) Icons.Outlined.WarningAmber else Icons.Outlined.Image,
                contentDescription = null,
                tint = uiColors.placeholderText,
                modifier = Modifier.size(if (showErrorLabel) 28.dp else 36.dp),
            )
            if (showErrorLabel) {
                Text(
                    text = stringResource(R.string.error_image_load_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = uiColors.placeholderText,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
