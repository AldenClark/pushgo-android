package io.ethan.pushgo.ui.markdown

import android.graphics.ImageDecoder
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

    private companion object {
        private const val TWO_FRAME_GIF_BASE64 =
            "R0lGODlhAgACAPAAAP8AAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAAAAAACwAAAAAAgACAAACAoRRACH5BAAKAAAALAAAAAACAAIAgAAA/wAAAAIChFEAOw=="
    }
}
