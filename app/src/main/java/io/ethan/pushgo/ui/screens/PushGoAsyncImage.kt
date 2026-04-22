package io.ethan.pushgo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

@Composable
fun PushGoAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    clipToBounds: Boolean = true,
    crossfade: Boolean = true,
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
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        clipToBounds = clipToBounds,
    )
}
