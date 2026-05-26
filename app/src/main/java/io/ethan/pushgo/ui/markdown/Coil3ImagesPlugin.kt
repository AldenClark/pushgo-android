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
import io.ethan.pushgo.R
import io.ethan.pushgo.data.ImageAssetMetadataStore
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
    private val metadataStore: ImageAssetMetadataStore,
) : AbstractMarkwonPlugin() {

    interface CoilStore {
        fun load(drawable: AsyncDrawable): ImageRequest
        fun cancel(disposable: Disposable)
    }

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(MarkdownImage::class.java, ImageSpanFactory())
    }

    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.asyncDrawableLoader(CoilAsyncDrawableLoader(coilStore, imageLoader, resources, metadataStore))
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
        private val metadataStore: ImageAssetMetadataStore,
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
            val metadata = metadataStore.findByUrl(drawable.destination) ?: return null
            val width = metadata.pixelWidth.coerceAtLeast(1)
            val height = metadata.pixelHeight.coerceAtLeast(1)
            return MetadataPlaceholderDrawable(width, height)
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
            DrawableUtils.applyIntrinsicBoundsIfEmpty(placeholderDrawable)
            drawable.setResult(placeholderDrawable)
        }

        override fun onError(error: Image?) {
            cache.remove(drawable)
            loaded.set(true)
            val errorDrawable = error?.asDrawable(resources) ?: ErrorPlaceholderDrawable(
                resources = resources,
                label = resources.getString(R.string.error_image_load_failed),
            )
            DrawableUtils.applyIntrinsicBoundsIfEmpty(errorDrawable)
            drawable.setResult(errorDrawable)
        }

        override fun onSuccess(result: Image) {
            cache.remove(drawable)
            loaded.set(true)
            val loadedDrawable = result.asDrawable(resources)
            val displayDrawable = MarkdownPlayableDrawable.wrapIfAnimated(resources, loadedDrawable)
            if (displayDrawable is MarkdownPlayableDrawable) {
                MarkdownAnimatedImagePlaybackRegistry.register(displayDrawable)
            }
            DrawableUtils.applyIntrinsicBoundsIfEmpty(displayDrawable)
            drawable.setResult(displayDrawable)
        }
    }

    companion object {
        fun create(context: Context): Coil3ImagesPlugin {
            val appContext = context.applicationContext
            val metadataStore = ImageAssetMetadataStore.get(appContext)
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
                metadataStore = metadataStore,
            )
        }

        fun create(context: Context, imageLoader: ImageLoader): Coil3ImagesPlugin {
            val appContext = context.applicationContext
            val metadataStore = ImageAssetMetadataStore.get(appContext)
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
                metadataStore = metadataStore,
            )
        }

        fun create(
            coilStore: CoilStore,
            imageLoader: ImageLoader,
            resources: Resources,
            metadataStore: ImageAssetMetadataStore,
        ): Coil3ImagesPlugin {
            return Coil3ImagesPlugin(coilStore, imageLoader, resources, metadataStore)
        }
    }
}

private class MetadataPlaceholderDrawable(
    private val intrinsicWidthPx: Int,
    private val intrinsicHeightPx: Int,
) : Drawable() {
    override fun draw(canvas: android.graphics.Canvas) {
        // Keep placeholder transparent while still reserving layout size.
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = intrinsicWidthPx

    override fun getIntrinsicHeight(): Int = intrinsicHeightPx
}

private class ErrorPlaceholderDrawable(
    resources: Resources,
    private val label: String,
) : Drawable() {
    private val density = resources.displayMetrics.density
    private val intrinsicWidthPx = (320f * density).toInt().coerceAtLeast(1)
    private val intrinsicHeightPx = (180f * density).toInt().coerceAtLeast(1)
    private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEFF3F9.toInt()
        style = android.graphics.Paint.Style.FILL
    }
    private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCBD5E1.toInt()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = density
    }
    private val iconPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt()
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = 14f * density
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val rect = android.graphics.RectF(bounds)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, fillPaint)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, strokePaint)

        val centerX = rect.centerX()
        val iconTop = rect.centerY() - 32f * density
        val iconRect = android.graphics.RectF(
            centerX - 20f * density,
            iconTop,
            centerX + 20f * density,
            iconTop + 28f * density,
        )
        canvas.drawRoundRect(iconRect, 4f * density, 4f * density, iconPaint)
        canvas.drawLine(iconRect.left + 6f * density, iconRect.bottom - 7f * density, iconRect.centerX(), iconRect.centerY(), iconPaint)
        canvas.drawLine(iconRect.centerX(), iconRect.centerY(), iconRect.right - 6f * density, iconRect.bottom - 7f * density, iconPaint)
        canvas.drawLine(iconRect.right - 5f * density, iconRect.top + 5f * density, iconRect.right - 14f * density, iconRect.top + 14f * density, iconPaint)
        canvas.drawText(label, centerX, iconRect.bottom + 24f * density, textPaint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = intrinsicWidthPx

    override fun getIntrinsicHeight(): Int = intrinsicHeightPx
}
