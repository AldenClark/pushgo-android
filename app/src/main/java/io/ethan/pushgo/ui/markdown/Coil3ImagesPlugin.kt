package io.ethan.pushgo.ui.markdown

import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.widget.TextView
import coil3.Image
import coil3.ImageLoader
import coil3.asDrawable
import coil3.gif.repeatCount
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.target.Target
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.DrawableUtils
import io.noties.markwon.image.ImageSpanFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.commonmark.node.Image as MarkdownImage

class Coil3ImagesPlugin private constructor(
    private val coilStore: CoilStore,
    private val imageLoader: ImageLoader,
    private val resources: Resources,
) : AbstractMarkwonPlugin() {

    interface CoilStore {
        fun load(drawable: AsyncDrawable): ImageRequest
        fun cancel(disposable: Disposable)
    }

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(MarkdownImage::class.java, ImageSpanFactory())
    }

    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.asyncDrawableLoader(CoilAsyncDrawableLoader(coilStore, imageLoader, resources))
    }

    override fun beforeSetText(textView: TextView, markdown: Spanned) {
        AsyncDrawableScheduler.unschedule(textView)
    }

    override fun afterSetText(textView: TextView) {
        AsyncDrawableScheduler.schedule(textView)
    }

    private class CoilAsyncDrawableLoader(
        private val coilStore: CoilStore,
        private val imageLoader: ImageLoader,
        private val resources: Resources,
    ) : AsyncDrawableLoader() {
        private val cache = ConcurrentHashMap<AsyncDrawable, Disposable>(2)

        override fun load(drawable: AsyncDrawable) {
            val loaded = AtomicBoolean(false)
            val request = coilStore
                .load(drawable)
                .newBuilder()
                .repeatCount(0)
                .target(AsyncDrawableTarget(drawable, loaded, resources, cache))
                .build()
            val disposable = imageLoader.enqueue(request)
            if (!loaded.get()) {
                loaded.set(true)
                cache[drawable] = disposable
            }
        }

        override fun cancel(drawable: AsyncDrawable) {
            val disposable = cache.remove(drawable) ?: return
            coilStore.cancel(disposable)
        }

        override fun placeholder(drawable: AsyncDrawable): Drawable? {
            return null
        }
    }

    private class AsyncDrawableTarget(
        private val drawable: AsyncDrawable,
        private val loaded: AtomicBoolean,
        private val resources: Resources,
        private val cache: MutableMap<AsyncDrawable, Disposable>,
    ) : Target {
        override fun onStart(placeholder: Image?) {
            val placeholderDrawable = placeholder?.asDrawable(resources) ?: return
            if (drawable.isAttached) {
                DrawableUtils.applyIntrinsicBoundsIfEmpty(placeholderDrawable)
                drawable.setResult(placeholderDrawable)
            }
        }

        override fun onError(error: Image?) {
            if (cache.remove(drawable) == null) return
            val errorDrawable = error?.asDrawable(resources) ?: return
            if (drawable.isAttached) {
                DrawableUtils.applyIntrinsicBoundsIfEmpty(errorDrawable)
                drawable.setResult(errorDrawable)
            }
        }

        override fun onSuccess(result: Image) {
            if (cache.remove(drawable) != null || !loaded.get()) {
                loaded.set(true)
                if (drawable.isAttached) {
                    val loadedDrawable = result.asDrawable(resources)
                    val displayDrawable = MarkdownPlayableDrawable.wrapIfAnimated(resources, loadedDrawable)
                    if (displayDrawable is MarkdownPlayableDrawable) {
                        MarkdownAnimatedImagePlaybackRegistry.register(displayDrawable)
                    }
                    DrawableUtils.applyIntrinsicBoundsIfEmpty(displayDrawable)
                    drawable.setResult(displayDrawable)
                }
            }
        }
    }

    companion object {
        fun create(context: Context): Coil3ImagesPlugin {
            val appContext = context.applicationContext
            return create(
                coilStore = object : CoilStore {
                    override fun load(drawable: AsyncDrawable): ImageRequest {
                        return ImageRequest.Builder(appContext)
                            .data(drawable.destination)
                            .build()
                    }

                    override fun cancel(disposable: Disposable) {
                        disposable.dispose()
                    }
                },
                imageLoader = coil3.SingletonImageLoader.get(appContext),
                resources = appContext.resources,
            )
        }

        fun create(context: Context, imageLoader: ImageLoader): Coil3ImagesPlugin {
            val appContext = context.applicationContext
            return create(
                coilStore = object : CoilStore {
                    override fun load(drawable: AsyncDrawable): ImageRequest {
                        return ImageRequest.Builder(appContext)
                            .data(drawable.destination)
                            .build()
                    }

                    override fun cancel(disposable: Disposable) {
                        disposable.dispose()
                    }
                },
                imageLoader = imageLoader,
                resources = appContext.resources,
            )
        }

        fun create(coilStore: CoilStore, imageLoader: ImageLoader, resources: Resources): Coil3ImagesPlugin {
            return Coil3ImagesPlugin(coilStore, imageLoader, resources)
        }
    }
}
