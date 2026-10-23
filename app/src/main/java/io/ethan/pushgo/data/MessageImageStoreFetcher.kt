package io.ethan.pushgo.data

import android.graphics.ImageDecoder
import coil3.asImage
import coil3.Extras
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.fetch.ImageFetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import coil3.request.Options
import java.util.Locale
import okio.ForwardingSource
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream
import okio.Path.Companion.toOkioPath

/** All HTTP images enter the same bounded store. A rejected resource never falls through to Coil HTTP. */
class MessageImageStoreFetcher(
    private val model: MessageImageStore.ImageRequestModel,
    private val options: Options,
    private val imageStore: MessageImageStore,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val acquired = imageStore.acquire(model)
            ?: throw IllegalStateException("Image resource unavailable")
        options.extras[ImageResourceObservation.Key]?.let {
            it.animated = acquired.isAnimated && acquired.animationAllowed
            it.stale = acquired.stale
        }
        if (acquired.isAnimated && !acquired.animationAllowed) {
            return acquired.use { image ->
                withContext(ControlledImageDecoding.dispatcher) {
                    val source = image.file?.let(ImageDecoder::createSource)
                        ?: ImageDecoder.createSource(ByteBuffer.wrap(requireNotNull(image.bytes)))
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val factor = minOf(1.0, ControlledImageDecoding.DETAIL_SIDE.toDouble() / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize(maxOf(1, (info.size.width * factor).toInt()), maxOf(1, (info.size.height * factor).toInt()))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = if (image.file != null) DataSource.DISK else DataSource.NETWORK)
                }
            }
        }
        val source = try {
            acquired.file?.let {
                ImageSource(file = it.toOkioPath(), fileSystem = options.fileSystem, closeable = acquired)
            } ?: acquired.bytes?.let {
                // no-store bytes remain request-owned; no temporary file or Coil disk entry.
                val leasedSource = object : ForwardingSource(ByteArrayInputStream(it).source()) {
                    override fun close() {
                        try { super.close() } finally { acquired.close() }
                    }
                }.buffer()
                ImageSource(source = leasedSource, fileSystem = options.fileSystem)
            } ?: throw IllegalStateException("Image resource unavailable")
        } catch (error: Throwable) {
            acquired.close()
            throw error
        }
        return SourceFetchResult(
            source = source,
            mimeType = acquired.mimeType,
            dataSource = if (acquired.file != null) DataSource.DISK else DataSource.NETWORK,
        )
    }

    class Factory(private val imageStore: () -> MessageImageStore) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme?.lowercase(Locale.US) !in setOf("http", "https")) return null
            return MessageImageStoreFetcher(MessageImageStore.ImageRequestModel(data.toString()), options, imageStore())
        }
    }

    class OwnedFactory(private val imageStore: () -> MessageImageStore) : Fetcher.Factory<MessageImageStore.ImageRequestModel> {
        override fun create(data: MessageImageStore.ImageRequestModel, options: Options, imageLoader: ImageLoader): Fetcher =
            MessageImageStoreFetcher(data, options, imageStore())
    }
}

internal object ControlledImageDecoding {
    val dispatcher = Dispatchers.IO.limitedParallelism(2)
    const val DETAIL_SIDE = 2048
}

/** Request-owned format result, including responses that cannot be stored on disk. */
internal class ImageResourceObservation {
    @Volatile var animated: Boolean? = null
    @Volatile var stale: Boolean = false
    companion object { val Key = Extras.Key<ImageResourceObservation?>(null) }
}
