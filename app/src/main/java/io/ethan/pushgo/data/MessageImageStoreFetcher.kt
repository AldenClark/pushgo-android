package io.ethan.pushgo.data

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.ethan.pushgo.util.UrlValidators
import java.io.File
import java.util.Locale
import okio.Path.Companion.toOkioPath

class MessageImageStoreFetcher(
    private val remoteUrl: String,
    private val options: Options,
    private val imageStore: MessageImageStore,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val normalized = UrlValidators.normalizeHttpsUrl(remoteUrl)
            ?: throw IllegalArgumentException("Unsupported image URL: $remoteUrl")
        val originalPath = imageStore.ensureOriginalCached(normalized)
            ?.let(::File)
            ?: throw IllegalStateException("PushGo image store failed to cache: $normalized")
        return SourceFetchResult(
            source = ImageSource(
                file = originalPath.toOkioPath(),
                fileSystem = options.fileSystem,
            ),
            mimeType = inferMimeType(normalized),
            dataSource = DataSource.DISK,
        )
    }

    private fun inferMimeType(url: String): String? {
        val sanitized = url.substringBefore('#').substringBefore('?')
        val extension = sanitized.substringAfterLast('.', "").lowercase(Locale.US)
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "apng" -> "image/apng"
            "bmp" -> "image/bmp"
            else -> null
        }
    }

    class Factory(
        private val imageStore: MessageImageStore,
    ) : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val scheme = data.scheme?.lowercase(Locale.US)
            if (scheme != "http" && scheme != "https") {
                return null
            }
            return MessageImageStoreFetcher(
                remoteUrl = data.toString(),
                options = options,
                imageStore = imageStore,
            )
        }
    }
}
