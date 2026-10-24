package io.ethan.pushgo.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Debug
import android.os.SystemClock
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.EventListener
import coil3.DrawableImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.repeatCount
import io.ethan.pushgo.R
import io.ethan.pushgo.data.*
import io.ethan.pushgo.data.db.MessageEntity
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.ui.markdown.MarkdownPlayableDrawable
import io.ethan.pushgo.ui.screens.*
import io.ethan.pushgo.ui.theme.PushGoTheme
import io.noties.markwon.image.AsyncDrawableSpan
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Visible production display components on a real emulator, with the same owned Fetcher,
 * real Room/files and the existing deterministic HTTP fixture. Only the fixture connection
 * maps a synthetic HTTPS identity to loopback HTTP; this does not prove deployment TLS. */
@RunWith(AndroidJUnit4::class)
class ResourceDisplayLifecycleDeviceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: PushGoDatabase
    private lateinit var store: MessageImageStore
    private lateinit var fixture: MessageResourceStoreDeviceTest.Fixture
    private lateinit var loader: ImageLoader
    private lateinit var previousLoader: ImageLoader
    private var now = System.currentTimeMillis() / 1000 * 1000
    private val url = "https://resource-ui.invalid/fixed.png?signature=synthetic"
    private val current = mutableStateOf<PushMessage?>(null)
    private val preview = mutableStateOf<Any?>(null)
    private val listMode = mutableStateOf(true)
    private val observations = mutableListOf<String>()
    private val decoded = java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<android.graphics.drawable.Drawable>>()

    @Before fun prepare() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "visible-resource-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, "db/$name").apply { parentFile?.mkdirs() }
        }
        db = PushGoDatabase.buildForTest(context, "visible-resource.db")
        fixture = MessageResourceStoreDeviceTest.Fixture {
            DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))
        }
        store = MessageImageStore(context, db, clock = { now }, connectionOpener = { identity ->
            URL("http://127.0.0.1:${fixture.port}${identity.file}").openConnection() as HttpURLConnection
        })
        // The production request helpers and Fetcher are unchanged; only the fixture-owned
        // Room/store/connection are injected. Restore the process singleton after this test.
        previousLoader = SingletonImageLoader.get(base)
        loader = ImageLoader.Builder(base).components {
            add(MessageImageStoreFetcher.OwnedFactory { store })
            add(MessageImageStoreFetcher.Factory { store })
            add(AnimatedImageDecoder.Factory())
        }.eventListener(object : EventListener() {
            private fun owner(request: ImageRequest) = (request.data as? MessageImageStore.ImageRequestModel)?.let { "${it.ownerId}:thumbnail=${it.thumbnail}" } ?: "non-owned"
            override fun onStart(request: ImageRequest) {
                android.util.Log.i("ResourceDisplayObservation", "request start ${owner(request)} decoder=${request.decoderFactory?.javaClass?.simpleName} elapsed=${SystemClock.elapsedRealtime()}")
            }
            override fun onCancel(request: ImageRequest) {
                android.util.Log.i("ResourceDisplayObservation", "request cancel ${owner(request)} elapsed=${SystemClock.elapsedRealtime()}")
            }
            override fun onError(request: ImageRequest, result: ErrorResult) {
                android.util.Log.i("ResourceDisplayObservation", "request error ${owner(request)} ${result.throwable.javaClass.simpleName} cause=${result.throwable.cause?.javaClass?.simpleName} elapsed=${SystemClock.elapsedRealtime()}")
            }
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                val drawable = (result.image as? DrawableImage)?.drawable
                drawable?.let { if (decoded.size < 16) decoded += java.lang.ref.WeakReference(it) }
                val animation = (drawable as? coil3.size.ScaleDrawable)?.child ?: drawable
                if (animation is android.graphics.drawable.Animatable2) {
                    val identity = owner(request)
                    animation.registerAnimationCallback(object : android.graphics.drawable.Animatable2.AnimationCallback() {
                        override fun onAnimationStart(drawable: android.graphics.drawable.Drawable?) {
                            android.util.Log.i("ResourceDisplayObservation", "animation start $identity elapsed=${SystemClock.elapsedRealtime()}")
                        }
                        override fun onAnimationEnd(drawable: android.graphics.drawable.Drawable?) {
                            android.util.Log.i("ResourceDisplayObservation", "animation end $identity elapsed=${SystemClock.elapsedRealtime()}")
                        }
                    })
                }
                android.util.Log.i("ResourceDisplayObservation", "decode ${owner(request)} ${drawable?.javaClass?.simpleName ?: result.image.javaClass.simpleName} elapsed=${SystemClock.elapsedRealtime()}")
            }
        }).diskCache(null).build()
        SingletonImageLoader.setUnsafe(loader)
        composeRule.setContent {
            PushGoTheme {
                current.value?.let { message ->
                    if (listMode.value) PushGoAsyncImage(
                        model = store.resolveListImageModels(message, 1).first(), contentDescription = "Synthetic message thumbnail",
                        modifier = Modifier.size(56.dp).testTag("resource.list.image"),
                        onClickWhenLoaded = { listMode.value = false },
                    ) else MessageDetailCoreContent(
                        message = message, timeText = "Synthetic", imageModels = store.resolveDetailImageModels(message),
                        primaryImageAspectRatio = 16f / 9f, channelDisplayName = null,
                        resolvedBodyText = message.body, bottomGestureInset = 0.dp, onDelete = null,
                        onCopyText = {}, onOpenImage = { preview.value = it }, onOpenUrl = {},
                    )
                }
                preview.value?.let { model ->
                    ZoomableImagePreviewDialog(model, onDismiss = { preview.value = null })
                }
            }
        }
    }

    @After fun finish() {
        composeRule.runOnIdle { current.value = null; preview.value = null }
        SingletonImageLoader.setUnsafe(previousLoader)
        loader.shutdown()
        store.close(); db.close(); fixture.close()
        ImageAssetMetadataStore.closeForTest(context)
        // Fixed-size observations of this synthetic run, not a physical performance claim.
        android.util.Log.i("ResourceDisplayObservation", observations.joinToString("\n"))
        root.deleteRecursively()
    }

    @Test
    fun visibleHistoryStaysFixedAndMarkdownRetryBindsOnlyTheCurrentMessage() {
        val red = png(0xffff0000.toInt())
        val green = png(0xff00ff00.toInt())
        val blue = png(0xff0000ff.toInt())
        fixture.response = response(red, "max-age=60", "\"red\"")
        val firstResponse = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        fixture.entered = firstResponse; fixture.release = releaseFirst
        val first = message("visible-first"); saveAndShow(first)
        // Drive Compose's frame clock while observing the network barrier. A blocking
        // latch await here prevents the just-assigned UI state from being composed.
        composeRule.waitUntil(5_000) { firstResponse.count == 0L }
        assertFalse(composeRule.onNodeWithTag("resource.list.image").fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
        releaseFirst.countDown(); fixture.entered = null; fixture.release = null
        awaitImageColor("resource.list.image", 0xffff0000.toInt())
        composeRule.onNodeWithTag("resource.list.image").performClick()
        awaitColor(0xffff0000.toInt())
        awaitMarkdown { !it.isRetryVisible }
        assertEquals(1, fixture.requests.size)
        observe("first")

        val fresh = message("visible-fresh"); saveAndShow(fresh)
        awaitBinding(fresh.id); awaitColor(0xffff0000.toInt())
        awaitMarkdown { !it.isRetryVisible }
        assertEquals(1, fixture.requests.size) // a new owner reuses a fresh representation with zero GET

        now += 61_000
        fixture.response = MessageResourceStoreDeviceTest.Response(ByteArray(0), mapOf("Cache-Control" to "max-age=60"), 304)
        val revalidated = message("visible-304"); saveAndShow(revalidated)
        awaitBinding(revalidated.id); awaitColor(0xffff0000.toInt())
        assertEquals(2, fixture.requests.size)
        assertEquals("\"red\"", fixture.requests.last()["if-none-match"])

        now += 61_000
        fixture.response = response(green, "max-age=60", "\"green\"")
        val replaced = message("visible-200"); saveAndShow(replaced)
        awaitBinding(replaced.id); awaitColor(0xff00ff00.toInt())
        assertEquals(3, fixture.requests.size)
        fixture.response = MessageResourceStoreDeviceTest.Response(ByteArray(0), emptyMap(), 503)
        composeRule.runOnIdle { current.value = first }
        awaitColor(0xffff0000.toInt()); awaitMarkdown { !it.isRetryVisible }
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.image_cached_fallback_retry)).assertDoesNotExist()
        assertEquals(3, fixture.requests.size) // offline history is an original, not a stale warning

        now += 61_000
        val temporary = message("visible-stale"); saveAndShow(temporary)
        awaitColor(0xff00ff00.toInt())
        awaitMarkdown { it.isTemporaryFallback }
        assertEquals(0, bindings(temporary.id))
        observe("temporary")
        val beforeRetry = fixture.requests.size
        fixture.response = response(blue, "max-age=60", "\"blue\"")
        // Execute the actual TextView accessibility action, not a helper-only retry.
        composeRule.runOnIdle {
            val view = checkNotNull(markdownView())
            assertTrue(ViewCompat.performAccessibilityAction(view, R.id.action_markdown_resource_retry, null))
        }
        awaitBinding(temporary.id); awaitMarkdown { !it.isRetryVisible }
        assertEquals(beforeRetry + 1, fixture.requests.size)
        // The other currently displayed fallback now adopts this message's final local
        // binding without another GET. It cannot update first/replaced historical owners.
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.image_cached_fallback_retry)).performClick()
        awaitColor(0xff0000ff.toInt())
        assertEquals(beforeRetry + 1, fixture.requests.size)
        composeRule.runOnIdle { current.value = replaced }
        awaitColor(0xff00ff00.toInt())
        composeRule.runOnIdle { current.value = first }
        awaitColor(0xffff0000.toInt())
        assertEquals(beforeRetry + 1, fixture.requests.size)
        observe("history-reopened")

        fixture.response = MessageResourceStoreDeviceTest.Response(ByteArray(0), emptyMap(), 503)
        val unavailable = message("visible-error", source = "$url&uncached=1")
        saveAndShow(unavailable)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.error_image_load_failed)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, bindings(unavailable.id))
        fixture.response = response(blue, "max-age=60", "\"retry-blue\"")
        val retryLabel = composeRule.activity.getString(R.string.action_retry)
        composeRule.onNode(SemanticsMatcher("actual failed-image Retry action") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == retryLabel
        }).performClick()
        awaitBinding(unavailable.id); awaitColor(0xff0000ff.toInt())
        observe("error-recovered")
    }

    @Test
    fun nativeCoilGifProbeUsesPublishedOriginalAndEncodedDelays() {
        val gif = timedGif()
        fixture.response = MessageResourceStoreDeviceTest.Response(gif, mapOf("Cache-Control" to "max-age=60"))
        val message = message("native-gif-probe")
        save(message)
        val model = store.imageModel(message, url)
        runBlocking { store.acquire(model)!!.use { assertArrayEquals(gif, it.file!!.readBytes()) } }
        // The UI-only diagnostics register animation callbacks on the UI request
        // path. This direct worker-side execute has its own main-thread probe below.
        val nativeLoader = loader.newBuilder().eventListener(EventListener.NONE).build()
        val image = try { runBlocking {
            val result = nativeLoader.execute(ImageRequest.Builder(composeRule.activity)
                .data(model).decoderFactory(AnimatedImageDecoder.Factory())
                .decoderCoroutineContext(ResourceImageDecodeBudget.dispatcher)
                .size(ResourceImageDecodeBudget.DETAIL_SIDE)
                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                .diskCachePolicy(coil3.request.CachePolicy.DISABLED)
                .repeatCount(0).build())
            if (result is ErrorResult) throw AssertionError("Native GIF probe failed before playback", result.throwable)
            (result as SuccessResult).image
        } } finally { nativeLoader.shutdown() }
        val drawable = (image as DrawableImage).drawable
        val native = (drawable as? coil3.size.ScaleDrawable)?.child ?: drawable
        assertTrue(native is android.graphics.drawable.AnimatedImageDrawable)
        assertEquals(0, (native as android.graphics.drawable.AnimatedImageDrawable).repeatCount)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val colors = linkedSetOf<Int>()
        val ended = java.util.concurrent.CountDownLatch(1)
        val startedAt = java.util.concurrent.atomic.AtomicLong()
        val endedAt = java.util.concurrent.atomic.AtomicLong()
        val callback = object : android.graphics.drawable.Drawable.Callback {
            override fun invalidateDrawable(who: android.graphics.drawable.Drawable) {
                who.draw(canvas); colors += bitmap.getPixel(32, 32)
            }
            override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, at: Long) { handler.postAtTime(what, at) }
            override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) { handler.removeCallbacks(what) }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            native.registerAnimationCallback(object : android.graphics.drawable.Animatable2.AnimationCallback() {
                override fun onAnimationStart(drawable: android.graphics.drawable.Drawable?) { startedAt.compareAndSet(0, SystemClock.elapsedRealtime()) }
                override fun onAnimationEnd(drawable: android.graphics.drawable.Drawable?) { endedAt.set(SystemClock.elapsedRealtime()); ended.countDown() }
            })
            drawable.callback = callback
            drawable.setBounds(0, 0, 64, 64)
            drawable.draw(canvas); colors += bitmap.getPixel(32, 32)
            (drawable as android.graphics.drawable.Animatable).start()
        }
        try {
            assertTrue("Platform one-shot must terminate in the bounded observation", ended.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val elapsed = endedAt.get() - startedAt.get()
            android.util.Log.i("ResourceDisplayObservation", "native-probe originalBytes=${gif.size} delaysCs=100,100 repeat=${native.repeatCount} elapsed=$elapsed colors=${colors.map { Integer.toHexString(it) }}")
            assertTrue("Two one-second frames must not terminate in one display frame", elapsed >= 800)
            assertTrue(colors.contains(0xffff0000.toInt()) && colors.contains(0xff0000ff.toInt()))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { (drawable as android.graphics.drawable.Animatable).stop(); drawable.callback = null }
            bitmap.recycle()
        }
    }

    @Test
    fun visibleGifPlayPauseScrollBackgroundAndViewerCloseReleaseTheDisplay() {
        // Blue is an intermediate frame here: Android's one-shot ends as its final
        // frame appears, so a two-frame red/blue source has no blue Pause window.
        val gif = android.util.Base64.decode("R0lGODlhAgACAPAAAP8AAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAZAAAACwAAAAAAgACAAACAoRRACH5BABkAAAALAAAAAACAAIAgAAA/wAAAAIChFEAIfkEAGQAAAAsAAAAAAIAAgAAAgKEUQA7", android.util.Base64.DEFAULT)
        fixture.response = MessageResourceStoreDeviceTest.Response(gif, mapOf("Cache-Control" to "max-age=60"))
        val message = message("visible-gif", body = "# Kept content\n\n" + (1..30).joinToString("\n\n") { "Visible row $it" })
        saveAndShow(message)
        awaitImageColor("resource.list.image", 0xffff0000.toInt())
        composeRule.onNodeWithTag("resource.list.image").performClick()
        awaitColor(0xffff0000.toInt())
        val play = composeRule.activity.getString(R.string.a11y_action_play_image)
        val pause = composeRule.activity.getString(R.string.a11y_action_pause_image)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription(play).fetchSemanticsNodes().isNotEmpty() }
        val imageBounds = composeRule.onNodeWithTag("message.image.0").fetchSemanticsNode().boundsInWindow
        composeRule.onNodeWithContentDescription(play).performClick()
        // captureToImage waits for Compose idle, which can be after one-shot playback
        // has ended and restored its poster. Observe the real display without that
        // synchronization; the user action and blue-frame pixel assertion are unchanged.
        awaitScreenColor(imageBounds, 0xff0000ff.toInt())
        composeRule.onNodeWithContentDescription(pause).performClick()
        awaitColor(0xffff0000.toInt())
        composeRule.onNodeWithContentDescription(play).assertIsDisplayed()
        observe("paused")
        composeRule.onNodeWithContentDescription(play).performClick()
        composeRule.onNodeWithTag("message.detail.scroll").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("message.image.0").performScrollTo()
        awaitColor(0xffff0000.toInt())
        composeRule.onNodeWithContentDescription(play).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(play).performClick()
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        repeat(2) { cycle ->
            // RESUMED is earlier than window focus and the foreground re-decode.
            // The real image correctly disables its action while Loading; wait for
            // that explicit user-ready state, then perform the original single tap.
            composeRule.waitUntil(5_000) {
                composeRule.activity.hasWindowFocus() &&
                    composeRule.onNodeWithTag("message.image.0").isDisplayed() &&
                    composeRule.onNodeWithTag("message.image.0").fetchSemanticsNode().config.contains(SemanticsActions.OnClick)
            }
            composeRule.onNodeWithTag("message.image.0").performClick()
            composeRule.waitUntil(5_000) { composeRule.onNodeWithTag("dialog.image.preview").isDisplayed() }
            composeRule.onNodeWithTag("dialog.image.preview").assertIsDisplayed()
            if (cycle == 0) awaitImageColor("dialog.image.preview", 0xffff0000.toInt())
            observe("viewer-$cycle")
            composeRule.onNodeWithTag("action.dialog.image_preview.dismiss").performClick()
            composeRule.onNodeWithTag("dialog.image.preview").assertDoesNotExist()
            composeRule.runOnIdle { current.value = null }
            composeRule.onNodeWithTag("message.detail.scroll").assertDoesNotExist()
            observe("closed-$cycle")
            composeRule.runOnIdle { current.value = message }
            awaitColor(0xffff0000.toInt())
        }
        assertEquals(1, fixture.requests.size)
        observe("closed")
    }

    private fun message(id: String, source: String = url, body: String = "Same Markdown ![fixed]($source)") = PushMessage(
        id, id, "Visible resource", body, null, null, false, Instant.ofEpochMilli(now),
        JSONObject().put("entity_type", "message").put("images", source).toString(), MessageStatus.NORMAL, null, null, "ui-synthetic",
    )
    private fun saveAndShow(message: PushMessage) {
        save(message)
        composeRule.runOnIdle { current.value = message }
    }
    private fun save(message: PushMessage) {
        runBlocking {
            db.messageDao().insert(MessageEntity.fromModel(message))
            db.recordResourceOrigin(message.id, message.receivedAt.toEpochMilli(), requireNotNull(InboundDeliveryScope.create("https://gateway-ui.invalid", "synthetic-ui-device")))
        }
    }
    private fun timedGif(): ByteArray {
        val gif = android.util.Base64.decode("R0lGODlhAgACAPAAAP8AAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAAAAAACwAAAAAAgACAAACAoRRACH5BAAKAAAALAAAAAACAAIAgAAA/wAAAAIChFEAOw==", android.util.Base64.DEFAULT)
        var frames = 0
        for (i in 0 until gif.size - 7) if (gif[i] == 0x21.toByte() && gif[i + 1] == 0xf9.toByte() && gif[i + 2] == 4.toByte()) { gif[i + 4] = 100; gif[i + 5] = 0; frames++ }
        assertEquals(2, frames)
        return gif
    }
    private fun response(bytes: ByteArray, cache: String, etag: String) = MessageResourceStoreDeviceTest.Response(bytes, mapOf("Cache-Control" to cache, "ETag" to etag))
    private fun png(color: Int) = ByteArrayOutputStream().use { output -> Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).also { it.eraseColor(color); it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle() }; output.toByteArray() }
    private fun bindings(id: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM message_resource_bindings WHERE owner_id=?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
    private fun awaitBinding(id: String) { composeRule.waitUntil(5_000) { bindings(id) > 0 } }
    private fun awaitColor(argb: Int) {
        awaitImageColor("message.image.0", argb)
    }
    private fun awaitImageColor(tag: String, argb: Int) {
        var last = "none"
        try {
            composeRule.waitUntil(5_000) {
                val image = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
                val actual = image[image.width / 2, image.height / 2]
                last = actual.toString()
                val expected = androidx.compose.ui.graphics.Color(argb)
                kotlin.math.abs(actual.red - expected.red) < .08 && kotlin.math.abs(actual.green - expected.green) < .08 && kotlin.math.abs(actual.blue - expected.blue) < .08
            }
        } catch (error: Throwable) {
            android.util.Log.e("ResourceDisplayObservation", "pixel failed tag=$tag owner=${current.value?.id} expected=${Integer.toHexString(argb)} actual=$last")
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            screenshot?.let { try { saveScreenshot(it, "failed-${tag.replace('.', '-')}") } finally { it.recycle() } }
            throw error
        }
    }
    private fun outputDirectory() = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        ?: File(context.externalMediaDirs.first(), "additional_test_output").absolutePath).apply { mkdirs() }
    private fun saveScreenshot(bitmap: Bitmap, label: String) {
        // External-media evidence can outlive a package reinstall and retain its
        // previous UID's ownership. Never overwrite or remove an earlier receipt.
        val file = File(outputDirectory(), "$label-${UUID.randomUUID()}.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        android.util.Log.i("ResourceDisplayObservation", "screenshot=${file.name}")
    }
    private fun awaitScreenColor(bounds: androidx.compose.ui.geometry.Rect, argb: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val began = SystemClock.elapsedRealtime()
        val pixels = linkedSetOf<Int>()
        try {
            composeRule.waitUntil(5_000) {
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val actual = screenshot.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
                    pixels += actual
                    val matches = (actual and 0x00ffffff) == (argb and 0x00ffffff)
                    if (matches) {
                        saveScreenshot(screenshot, "visible-gif-blue-frame")
                    }
                    matches
                } finally { screenshot.recycle() }
            }
        } finally {
            val drawableStates = decoded.mapNotNull { it.get() }.map {
                "${it.javaClass.simpleName}:running=${(it as? android.graphics.drawable.Animatable)?.isRunning}:callback=${it.callback != null}"
            }
            android.util.Log.i("ResourceDisplayObservation", "visible-frame bounds=$bounds elapsed=${SystemClock.elapsedRealtime()-began} pixels=${pixels.map { Integer.toHexString(it) }} drawables=$drawableStates")
        }
    }
    private fun awaitMarkdown(condition: (MarkdownPlayableDrawable) -> Boolean) {
        composeRule.waitUntil(5_000) {
            var ready = false
            composeRule.runOnIdle { ready = markdownImage()?.let(condition) == true }
            ready
        }
    }
    private fun markdownView(): TextView? {
        fun find(view: View): TextView? {
            if (view is TextView && (view.text as? Spanned)?.getSpans(0, view.text.length, AsyncDrawableSpan::class.java)?.isNotEmpty() == true) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(composeRule.activity.window.decorView)
    }
    private fun markdownImage() = (markdownView()?.text as? Spanned)?.let { text ->
        text.getSpans(0, text.length, AsyncDrawableSpan::class.java).firstOrNull()?.drawable?.result as? MarkdownPlayableDrawable
    }
    private fun observe(label: String) {
        val rss = File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim() }
        fun bytes(directory: File) = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        observations += "$label pssKiB=${Debug.getPss()} rss=$rss javaUsed=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} nativeBytes=${Debug.getNativeHeapAllocatedSize()} originalsBytes=${bytes(File(context.filesDir, "message_resources/original"))} derivedBytes=${bytes(File(context.cacheDir, "message_resources/thumbnail"))} requests=${fixture.requests.size}"
    }
}
