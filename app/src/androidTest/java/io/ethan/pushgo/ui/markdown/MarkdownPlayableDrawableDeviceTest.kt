package io.ethan.pushgo.ui.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import coil3.ImageLoader
import coil3.DrawableImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import io.ethan.pushgo.ui.screens.ResourceImageDecodeBudget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownPlayableDrawableDeviceTest {
    @Test
    fun animatedGifWrapsAndStartsOnApi28() {
        assumeTrue("AnimatedImageDrawable is available on API 28+", Build.VERSION.SDK_INT >= 28)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val gifFile = File(context.cacheDir, "pushgo-animated-smoke.gif")
        gifFile.writeBytes(Base64.decode(TWO_FRAME_GIF_BASE64, Base64.DEFAULT))

        val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(gifFile))
        assertTrue(decoded is AnimatedImageDrawable)

        val playable = MarkdownPlayableDrawable.wrapIfAnimated(context.resources, decoded)
        assertTrue(playable is MarkdownPlayableDrawable)
        playable.setBounds(0, 0, 64, 64)
        assertTrue((playable as MarkdownPlayableDrawable).isOverlayVisible)

        instrumentation.runOnMainSync {
            assertTrue(playable.playOnce())
            assertFalse(playable.isOverlayVisible)
            playable.stopAndReset()
        }
    }


    @Test
    fun staticListDecodePreservesOriginalAnimatedBytes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = Base64.decode(TWO_FRAME_GIF_BASE64, Base64.DEFAULT)
        val file = File(context.cacheDir, "resource-static-list.gif").apply { writeBytes(bytes) }
        val loader = ImageLoader.Builder(context).build()
        try {
            val result = loader.execute(ImageRequest.Builder(context)
                .data(file).size(128).decoderFactory(ResourceImageDecodeBudget.staticDecoder)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
            val image = (result as coil3.request.SuccessResult).image
            assertFalse((image as? DrawableImage)?.drawable is android.graphics.drawable.Animatable)
            assertTrue(file.readBytes().contentEquals(bytes))
            assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) is AnimatedImageDrawable)
        } finally { loader.shutdown(); file.delete() }
    }

    @Test
    fun twoViewsDoNotSharePlaybackAndDetachedDrawableStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "resource-playback-owner.gif").apply {
            writeBytes(Base64.decode(TWO_FRAME_GIF_BASE64, Base64.DEFAULT))
        }
        val first = MarkdownPlayableDrawable.wrapIfAnimated(context.resources, ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))) as MarkdownPlayableDrawable
        val second = MarkdownPlayableDrawable.wrapIfAnimated(context.resources, ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))) as MarkdownPlayableDrawable
        instrumentation.runOnMainSync {
            first.setBounds(0, 0, 64, 64)
            second.setBounds(0, 0, 64, 64)
            assertTrue(first.playOnce())
            assertFalse(first.isOverlayVisible)
            assertTrue(second.isOverlayVisible)
            first.setVisible(false, false)
            assertTrue(first.isOverlayVisible)
            first.release()
            second.release()
            assertNull(first.callback)
            assertNull(second.callback)
        }
        file.delete()
    }

    @Test
    fun animatedGifActuallyRendersDifferentFramesAndReleaseStopsCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "resource-real-frames.gif").apply {
            writeBytes(Base64.decode(TWO_FRAME_GIF_BASE64, Base64.DEFAULT))
        }
        val playable = MarkdownPlayableDrawable.wrapIfAnimated(context.resources, ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))) as MarkdownPlayableDrawable
        val handler = Handler(Looper.getMainLooper())
        val frames = mutableSetOf<Int>()
        val changed = CountDownLatch(1)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                who.draw(canvas)
                frames += bitmap.getPixel(0, 0)
                if (frames.size >= 2) changed.countDown()
            }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { handler.postAtTime(what, `when`) }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { handler.removeCallbacks(what) }
        }
        instrumentation.runOnMainSync {
            playable.setBounds(0, 0, 64, 64)
            playable.callback = callback
            playable.draw(canvas)
            assertTrue(playable.playOnce())
        }
        try {
            assertTrue("Platform animated decoder must render at least two distinct source frames", changed.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { playable.release() }
            assertNull(playable.callback)
            bitmap.recycle()
            file.delete()
        }
    }

    private companion object {
        private const val TWO_FRAME_GIF_BASE64 =
            "R0lGODlhAgACAPAAAP8AAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAAAAAACwAAAAAAgACAAACAoRRACH5BAAKAAAALAAAAAACAAIAgAAA/wAAAAIChFEAOw=="
    }
}
