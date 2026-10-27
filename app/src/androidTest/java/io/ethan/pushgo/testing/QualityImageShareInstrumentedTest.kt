package io.ethan.pushgo.testing

import android.os.ParcelFileDescriptor
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import io.ethan.pushgo.data.MessageImageStore
import io.ethan.pushgo.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class QualityImageShareInstrumentedTest : QualityAppJourneyTestCase() {
    /** Real MainActivity viewer actions, without the unrelated browser portion of the
     * standard-message journey. This exercises owned originals, not a direct export helper. */
    @Test
    fun viewerSavePreservesCompletePngAndAnimatedGifOriginalsInMediaStore() {
        configureAndLaunch(QualityFixture.MESSAGES_STANDARD)
        // Use the same accepted legacy-local source directory as the existing PNG
        // fixture. Production safeLocalFile must not be relaxed for test material.
        val original = checkNotNull(runBlocking { app.container.messageRepository.getById("quality-standard-message") })
        val sources = listOf("static-1024.png", "64-frame-512.gif").map { name ->
            val file = File(app.cacheDir, "message_images/original/viewer-original-${UUID.randomUUID()}-${name}")
                .apply { parentFile?.mkdirs() }
            InstrumentationRegistry.getInstrumentation().context.assets.open("representative-resources/$name").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val id = "quality-original-${file.extension}-${UUID.randomUUID()}"
            val value = original.copy(id = id, messageId = id, title = "Synthetic original ${file.extension}", receivedAt = Instant.now(),
                rawPayloadJson = JSONObject().put("entity_type", "message").put("message_id", id)
                    .put("delivery_id", "delivery-$id").put("op_id", "op-$id")
                    .put(MessageImageStore.KEY_IMAGE_LOCAL_PATH, file.absolutePath).toString())
            runBlocking { app.container.messageRepository.insert(value) }
            id to file
        }
        assertEquals(64, sources.last().second.readBytes().let { bytes -> bytes.indices.count { i ->
            i + 2 < bytes.size && bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xf9.toByte() && bytes[i + 2] == 4.toByte()
        } })
        val results = JSONArray()
        val resourceObservations = JSONArray()
        fun observe(stage: String) {
            resourceObservations.put(JSONObject().put("stage", stage).put("elapsed_ms", android.os.SystemClock.elapsedRealtime())
                .put("cpu_ms", android.os.Process.getElapsedCpuTime()).put("pss_kib", android.os.Debug.getPss())
                .put("native_heap_bytes", android.os.Debug.getNativeHeapAllocatedSize())
                .put("java_used_bytes", Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()))
        }
        for ((id, source) in sources) {
            val before = galleryImages().keys
            val expected = sha256(source.readBytes())
            composeRule.waitUntil(8_000) { composeRule.onAllNodesWithTag("message.row.$id").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("message.row.$id").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("message.image.0").performScrollTo().assertIsDisplayed()
            composeRule.waitUntil(5_000) {
                composeRule.onNodeWithTag("message.image.0").fetchSemanticsNode().config.contains(SemanticsActions.OnClick)
            }
            composeRule.onNodeWithTag("message.image.0").assertHasClickAction().performClick()
            composeRule.onNodeWithTag("dialog.image.preview").assertIsDisplayed()
            if (source.extension == "gif") {
                val play = app.getString(R.string.a11y_action_play_image)
                val pause = app.getString(R.string.a11y_action_pause_image)
                fun control(label: String) = hasContentDescription(label) and hasAnyAncestor(hasTestTag("dialog.image.preview"))
                fun visible(label: String) = composeRule.onAllNodes(control(label)).fetchSemanticsNodes().isNotEmpty()
                var pausedByTest = false
                fun assertPlayDisplayed(stage: String, readyByMillis: Long) {
                    try {
                        // A pause replaces the animated request with its poster. The overlay
                        // is absent while that request loads; keep the original 5s deadline.
                        composeRule.waitUntil((readyByMillis - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1L)) {
                            composeRule.onNode(control(play)).isDisplayed()
                        }
                        composeRule.onNode(control(play)).assertIsDisplayed()
                    } catch (failure: Throwable) {
                        runCatching {
                            for (label in listOf(play, pause)) {
                                val nodes = composeRule.onAllNodes(control(label)).fetchSemanticsNodes()
                                QualityUiFailureDiagnostics.logText("Save07 $stage control",
                                    "label=$label count=${nodes.size} pausedByTest=$pausedByTest " +
                                        nodes.joinToString { "root=${it.boundsInRoot} window=${it.boundsInWindow} placed=${it.layoutInfo.isPlaced}" })
                            }
                            QualityUiFailureDiagnostics.logText("Save07 $stage preview",
                                composeRule.onNodeWithTag("dialog.image.preview", useUnmergedTree = true)
                                    .printToString(maxDepth = 20))
                            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                            QualityUiFailureDiagnostics.logWindowState(
                                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
                                "Save07 $stage", output?.let { File(it, "save07-$stage-window.xml") })
                        }.onFailure {
                            QualityUiFailureDiagnostics.logText("Save07 $stage observation unavailable", it.javaClass.simpleName)
                        }
                        throw failure
                    }
                }
                val playReadyBy = android.os.SystemClock.uptimeMillis() + 5_000L
                composeRule.waitUntil(5_000) {
                    composeRule.onNode(control(play)).isDisplayed() || composeRule.onNode(control(pause)).isDisplayed()
                }
                if (visible(pause)) {
                    pausedByTest = true
                    composeRule.onNode(control(pause)).performClick()
                }
                assertPlayDisplayed("first-play", playReadyBy)
                composeRule.onNode(control(play)).performClick()
                observe("gif64-active-start")
                val bounds = composeRule.onNodeWithTag("dialog.image.preview").fetchSemanticsNode().boundsInWindow
                val colors = linkedSetOf<Int>()
                repeat(15) { second ->
                    if (second > 0 && second % 7 == 0 && visible(play)) {
                        composeRule.onNode(control(play)).performClick() // bounded user-demand replay, not a retry
                    }
                    if (second in setOf(1, 5, 9, 13)) {
                        val screen = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                        try { colors += screen.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()) } finally { screen.recycle() }
                    }
                    if (second in setOf(3, 8, 14)) observe("gif64-active-$second")
                    Thread.sleep(1_000) // fixed observation sampling interval, never a navigation/retry synchronization
                }
                assertTrue("The actual display must show distinct frames", colors.size >= 2)
                val posterReadyBy = android.os.SystemClock.uptimeMillis() + 5_000L
                if (visible(pause)) {
                    pausedByTest = true
                    composeRule.onNode(control(pause)).performClick()
                }
                assertPlayDisplayed("paused-final", posterReadyBy)
                observe("gif64-paused")
            }
            composeRule.onNodeWithTag("action.dialog.image_preview.save").assertIsDisplayed().performClick()
            composeRule.waitUntil(8_000) { galleryImages().keys.count { it !in before } == 1 }
            val saved = galleryImages().filterKeys { it !in before }.values.single()
            val bytes = checkNotNull(app.contentResolver.openInputStream(saved)).use { it.readBytes() }
            assertEquals("viewer Save must copy the complete $source original, not its poster", expected, sha256(bytes))
            assertEquals(if (source.extension == "gif") "image/gif" else "image/png", app.contentResolver.getType(saved))
            results.put(JSONObject().put("format", source.extension).put("bytes", bytes.size).put("sha256", expected)
                .put("media_store_id", ContentUris.parseId(saved)).put("entry", "MainActivity detail -> viewer Save"))
            composeRule.onNodeWithTag("action.dialog.image_preview.dismiss").performClick()
            try {
                // The detail is still a focused Dialog after the viewer closes.
                // Espresso's default picker can retain the unfocused Activity root.
                // Send the same single system Back as the existing share journey.
                val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                composeRule.onNodeWithTag("dialog.image.preview").assertDoesNotExist()
                composeRule.onNodeWithTag("sheet.message.detail").assertIsDisplayed()
                // UiAutomator2.4 reports whether a WINDOW_CONTENT_CHANGED event
                // arrived within1s, not whether Back was injected. Retain that
                // diagnostic while requiring the actual sheet/list outcome below.
                val contentEventSeen = device.pressBack()
                resourceObservations.put(JSONObject().put("stage", "${source.extension}-system-back")
                    .put("window_content_event_seen", contentEventSeen))
                device.waitForIdle()
                composeRule.waitUntil(8_000) {
                    composeRule.onAllNodesWithTag("sheet.message.detail").fetchSemanticsNodes().isEmpty()
                }
                composeRule.onNodeWithTag("message.row.$id").assertIsDisplayed()
            } catch (failure: Throwable) {
                runCatching {
                    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                    val evidence = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                        ?: File(app.cacheDir, "image-export-output").absolutePath).apply { mkdirs() }
                    QualityUiFailureDiagnostics.logWindowState(device, "original export Back failure",
                        File(evidence, "export-back-failure-${UUID.randomUUID()}.xml"))
                }
                throw failure
            }
            composeRule.onNodeWithTag("dialog.image.preview").assertDoesNotExist()
            observe("${source.extension}-exited")
        }
        val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: File(app.cacheDir, "image-export-output").absolutePath).apply { mkdirs() }
        File(output, "main-activity-original-export.json").writeText(JSONObject().put("saved", results)
            .put("resource_observations", resourceObservations).put("gif_frames", 64)
            .put("limits", "Synthetic local originalPNG1024 and64x512GIF, actual MediaStore and finite15s playback; sampledPSS includes observer, not peak, HTTP/TLS, physical storage, long-term leak, energy or comparative speedup evidence").toString(2))
    }

    private fun galleryImages(): Map<Long, Uri> = app.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.IS_PENDING}=0", arrayOf("Pictures/PushGo/"), null,
    )?.use { cursor -> buildMap { while (cursor.moveToNext()) {
        val id = cursor.getLong(0); put(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
    } } }.orEmpty()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun recipientShell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand("run-as io.ethan.pushgo.test $command")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readText() }
    }

    @Test
    fun systemChooserGrantsTheCanonicalImageToASeparateUidRecipient() {
        recipientShell("rm -f files/quality-image-share-result.json")
        configureAndLaunch(QualityFixture.MESSAGES_STANDARD)

        val qualitySessionId = checkNotNull(QualityRuntime.currentSession()).sessionId
        val canonicalImage = File(
            app.filesDir,
            "quality-fixtures/$qualitySessionId/standard-message.png",
        )
        val canonicalDigest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalImage.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        composeRule.onNodeWithTag("message.row.quality-standard-message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("message.image.0")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("dialog.image.preview").assertIsDisplayed()
        composeRule.onNodeWithTag("action.dialog.image_preview.share")
            .assertIsDisplayed()
            .performClick()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "The system chooser did not expose the shared image preview.",
            device.wait(Until.hasObject(By.res("com.android.intentresolver", "image")), 5_000),
        )
        var recipient = device.wait(Until.findObject(By.textContains("PushGo QA")), 3_000)
        for (attempt in 0 until 6) {
            if (recipient != null) break
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 3,
                24,
            )
            recipient = device.wait(Until.findObject(By.textContains("PushGo QA")), 1_500)
        }
        checkNotNull(recipient) { "The isolated recipient was absent from the system share chooser" }
            .click()
        var recipientResult: String? = null
        composeRule.waitUntil(timeoutMillis = 10_000) {
            recipientResult = recipientShell("cat files/quality-image-share-result.json")
                .takeIf { it.trimStart().startsWith("{") }
            recipientResult != null
        }

        val result = JSONObject(checkNotNull(recipientResult))
        assertTrue("Recipient failed to open EXTRA_STREAM: $result", !result.has("error"))
        assertNotEquals(app.applicationInfo.uid, result.getInt("receiver_uid"))
        assertTrue(result.getInt("size") > 0)
        assertEquals(canonicalDigest, result.getString("sha256"))
    }
}
