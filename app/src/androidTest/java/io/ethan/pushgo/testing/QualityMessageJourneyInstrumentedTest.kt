package io.ethan.pushgo.testing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.ethan.pushgo.R
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.ui.accessibility.hasContentDescriptionContaining
import io.ethan.pushgo.ui.markdown.MarkdownRenderedSpanClassesKey
import io.ethan.pushgo.ui.markdown.MarkdownRenderedTextKey
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class QualityMessageJourneyInstrumentedTest : QualityAppJourneyTestCase() {

    @Test
    fun markdownFixtureRendersMajorStructuresInTheRealDetail() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_MARKDOWN)

        composeRule.onNodeWithText("Quality Markdown Structure")
            .assertIsDisplayed()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("field.message.detail.body"))
                .fetchSemanticsNodes().firstOrNull()
                ?.config
                ?.let { config -> runCatching { config[MarkdownRenderedTextKey] }.getOrNull() }
                ?.contains("Quality Markdown Heading") == true
        }

        val body = composeRule.onNodeWithTag("field.message.detail.body")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .config
        val renderedText = body[MarkdownRenderedTextKey]
        val renderedSpans = body[MarkdownRenderedSpanClassesKey]
        val accessibleText = body[SemanticsProperties.Text].joinToString(separator = "\n") { it.text }
        assertTrue(renderedText.contains("Quality Markdown Heading"))
        assertTrue(renderedText.contains("Completed deployment check"))
        assertTrue(renderedText.contains("Production quote remains visible"))
        assertTrue(accessibleText.contains("Gateway"))
        assertTrue(accessibleText.contains("Healthy"))
        assertTrue(renderedText.contains("pushgo status"))
        assertTrue(renderedText.contains("Open quality guide"))
        assertTrue(renderedText.contains("{\"environment\":\"quality\"}"))
        assertTrue(renderedText.contains("Unicode completion sentinel 终点 終點 Ω مرحبا 👩🏽‍💻"))
        assertFalse(renderedText.contains("# Quality Markdown Heading"))
        listOf(
            "HeadingSpan",
            "TaskListSpan",
            "BlockQuoteSpan",
            "TableRowSpan",
            "TableSpan",
            "CodeSpan",
            "CodeBlockSpan",
            "LinkSpan",
        ).forEach { expectedSpan ->
            assertTrue(
                "Production Markwon output lacks $expectedSpan; actual spans=$renderedSpans",
                renderedSpans.contains(expectedSpan),
            )
        }

        val scroll = composeRule.onNodeWithTag("message.detail.scroll").assertIsDisplayed()
        val initialRange = runCatching {
            scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        }.getOrNull()
        assertTrue(
            "The representative long body must remain user-scrollable instead of being clipped",
            initialRange != null && initialRange.maxValue() > 0f,
        )
        checkNotNull(initialRange)
        for (attempt in 0 until 12) {
            val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() >= range.maxValue() - 1f) break
            scroll.performTouchInput { swipeUp() }
        }
        val finalRange = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(
            "A user must be able to scroll through the exact long body to its Unicode tail",
            finalRange.value() >= finalRange.maxValue() - 1f,
        )
        pressBack()
        composeRule.onNodeWithTag("screen.messages.list").assertIsDisplayed()
        composeRule.onNodeWithText("Quality Markdown Structure").assertIsDisplayed()
    }

    @Test
    fun emptyFixtureShowsTheFunctionalEmptyStateInAnAppOwnedDatabase() {
        configureAndLaunch(fixture = QualityFixture.EMPTY_CLEAN)

        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(
                hasTestTag("state.messages.empty")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.empty").assertIsDisplayed()
        val databaseName = checkNotNull(
            checkNotNull(app.containerOrNull()).database.openHelper.databaseName
        )
        assertNotEquals("pushgo.db", databaseName)
        assertTrue(databaseName.startsWith("pushgo-quality-"))
    }

    @Test
    fun fatalStoreInitializationStopsReadWriteAndRecoversAfterRelaunch() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(failLocalStoreInitialization = true),
            awaitRuntimeReady = false,
        )

        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("state.storage.unavailable"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.storage.unavailable").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.label_local_storage_unavailable))
            .assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.label_local_storage_unavailable_safety))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("field.storage.failure_reason")
            .assertTextContains("Quality-injected local persistent storage initialization failure.")
        composeRule.onNodeWithTag("screen.messages.list").assertDoesNotExist()
        composeRule.onNodeWithTag("state.messages.empty").assertDoesNotExist()
        composeRule.onNodeWithTag("nav.item.messages").assertDoesNotExist()
        composeRule.onNodeWithTag("action.storage.exit").assertIsDisplayed().assertHasClickAction()

        relaunchCurrentQualitySessionWithFaults()

        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("P2 Split Seed Message"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed()
        composeRule.onNodeWithTag("state.storage.unavailable").assertDoesNotExist()
    }

    @Test
    fun standardFixtureShowsAccurateContentAndSurvivesActivityRelaunch() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(messageSearchDelayMs = 2_000),
            messageRefreshScenario = QualityMessageRefreshScenario.NEW_MESSAGE,
        )

        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Seeded from fixture.seed_messages for UI validation.")
        composeRule.onNode(
            hasText(app.getString(R.string.message_severity_high).uppercase()) and
                hasAnyAncestor(hasTestTag("sheet.message.detail")),
        )
            .assertIsDisplayed()
        val expectedMessageUrl = "https://pushgo.dev/quality-message"
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val savedClip = clipboard.primaryClip
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("quality", "pushgo-quality-copy-sentinel"))
            composeRule.onNodeWithTag("action.message.copy_url")
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 3_000) {
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(app)
                    ?.toString() == expectedMessageUrl
            }
            assertEquals(
                expectedMessageUrl,
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString(),
            )
        } finally {
            if (savedClip != null) {
                clipboard.setPrimaryClip(savedClip)
            } else {
                clipboard.clearPrimaryClip()
            }
        }
        val originalGalleryImageIds = pushGoGalleryImages().mapTo(mutableSetOf()) { it.id }
        val shareDirectory = File(app.cacheDir, "shared-images")
        val originalSharedImagePaths = shareDirectory.listFiles()
            ?.mapTo(mutableSetOf()) { it.absolutePath }
            .orEmpty()
        val qualitySessionId = checkNotNull(QualityRuntime.currentSession()).sessionId
        val canonicalImage = File(
            app.filesDir,
            "quality-fixtures/$qualitySessionId/standard-message.png",
        )
        val canonicalImageSha256 = canonicalImage.inputStream().use(::sha256)
        try {
            composeRule.onNodeWithTag("message.image.0")
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
            composeRule.onNodeWithTag("dialog.image.preview").assertIsDisplayed()

            composeRule.onNodeWithTag("action.dialog.image_preview.save")
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 8_000) {
                pushGoGalleryImages().count { it.id !in originalGalleryImageIds } == 1
            }
            val savedImage = pushGoGalleryImages().single { it.id !in originalGalleryImageIds }
            val decodedImage = app.contentResolver.openInputStream(savedImage.uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
            assertTrue(
                "The production save action did not create a decodable system gallery image.",
                decodedImage != null && decodedImage.width > 0 && decodedImage.height > 0,
            )
            decodedImage?.recycle()
            val savedImageSha256 = checkNotNull(
                app.contentResolver.openInputStream(savedImage.uri),
            ).use(::sha256)
            assertEquals(
                "The production save action did not preserve the canonical image payload.",
                canonicalImageSha256,
                savedImageSha256,
            )

            composeRule.onNodeWithTag("action.dialog.image_preview.share")
                .assertIsDisplayed()
                .assertHasClickAction()
                .performClick()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue(
                "The production share action did not leave PushGo for a system-owned surface.",
                device.wait(Until.gone(By.pkg(app.packageName).depth(0)), 10_000),
            )
            val resolverPackage = device.currentPackageName
            val resolverInfo = resolverPackage
                ?.takeIf { it != app.packageName }
                ?.let { app.packageManager.getApplicationInfo(it, 0) }
            assertTrue(
                "The production share action did not foreground Android's system resolver; " +
                    "actualPackage=$resolverPackage",
                resolverInfo != null &&
                    (resolverInfo.flags and (
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                        )) != 0,
            )
            val sharedImages = shareDirectory.listFiles()
                ?.filter { it.absolutePath !in originalSharedImagePaths }
                .orEmpty()
            assertTrue(
                "The production share action did not create exactly one isolated payload file; " +
                    "actual=${sharedImages.map { it.name }}",
                sharedImages.size == 1,
            )
            val decodedSharedImage = BitmapFactory.decodeFile(sharedImages.single().absolutePath)
            assertTrue(
                "The payload handed to Android's system resolver was not a decodable image.",
                decodedSharedImage != null &&
                    decodedSharedImage.width > 0 &&
                    decodedSharedImage.height > 0,
            )
            decodedSharedImage?.recycle()
            assertEquals(
                "The payload handed to Android's system resolver did not preserve the canonical image.",
                canonicalImageSha256,
                sharedImages.single().inputStream().use(::sha256),
            )
            device.pressBack()
            assertTrue(
                "PushGo did not return to the existing detail after dismissing the system resolver.",
                device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), 8_000),
            )
            composeRule.onNodeWithTag("dialog.image.preview").assertIsDisplayed()
            composeRule.onNodeWithTag("action.dialog.image_preview.dismiss")
                .assertIsDisplayed()
                .performClick()
            composeRule.onNodeWithTag("dialog.image.preview").assertDoesNotExist()
            composeRule.onNodeWithTag("field.message.detail.body")
                .assertTextContains("Seeded from fixture.seed_messages for UI validation.")
        } finally {
            pushGoGalleryImages()
                .filter { it.id !in originalGalleryImageIds }
                .forEach { app.contentResolver.delete(it.uri, null, null) }
            shareDirectory.listFiles()
                ?.filter { it.absolutePath !in originalSharedImagePaths }
                ?.forEach(File::delete)
        }

        pressBack()
        composeRule.onNodeWithTag("screen.messages.list").assertIsDisplayed()
        waitForCanonicalUnreadCount(0)
        assertUnreadNavigationBadge(null)
        composeRule.onNodeWithTag("screen.messages.list").performTouchInput { swipeDown() }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("P2 Refresh Result")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("P2 Split Seed Message").assertExists()
        val refreshedBeforeOpen = runBlocking {
            checkNotNull(app.containerOrNull()).messageRepository
                .getByMessageId("quality-refresh-result")
        }
        assertEquals("P2 Refresh Result", refreshedBeforeOpen?.title)
        assertEquals(
            "Persisted through the provider refresh ingress path.",
            refreshedBeforeOpen?.body,
        )
        assertEquals(false, refreshedBeforeOpen?.isRead)
        waitForCanonicalUnreadCount(1)
        assertUnreadNavigationBadge("1")
        composeRule.onNodeWithText("P2 Refresh Result").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals("Persisted through the provider refresh ingress path.")
        waitForCanonicalUnreadCount(0)
        assertEquals(
            true,
            runBlocking {
                checkNotNull(app.containerOrNull()).messageRepository
                    .getByMessageId("quality-refresh-result")
                    ?.isRead
            },
        )
        assertUnreadNavigationBadge(null)

        scenario?.close()
        scenario = launchMainActivity()

        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(
                hasText("P2 Split Seed Message")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("P2 Split Seed Message").assertExists()
        composeRule.onNodeWithText("P2 Refresh Result").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals("Persisted through the provider refresh ingress path.")
        waitForCanonicalUnreadCount(0)
        assertUnreadNavigationBadge(null)
        pressBack()

        composeRule.onNodeWithTag("field.message.search")
            .assertIsDisplayed()
            .performTextInput("not-present-in-any-message")
        composeRule.onNodeWithTag("state.messages.search.loading").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("state.messages.search.empty"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("P2 Split Seed Message").assertDoesNotExist()
        composeRule.onNodeWithTag("field.message.search").performTextClearance()
        composeRule.onNodeWithTag("field.message.search").performTextInput("P2 Split")
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("P2 Split Seed Message"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.search.empty").assertDoesNotExist()
        composeRule.onNodeWithText("P2 Split Seed Message").performClick()
        composeRule.onNodeWithTag("sheet.message.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Seeded from fixture.seed_messages for UI validation.")
    }

    @Test
    fun historyCleanupRemovesOnlyOldMessagesAndPersistsAcrossRelaunch() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_CLEANUP)

        composeRule.onNodeWithText("Quality Old Cleanup Target").assertIsDisplayed()
        composeRule.onNodeWithText("Quality Recent Cleanup Control").assertIsDisplayed()
        assertUnreadNavigationBadge("2")

        composeRule.onNodeWithTag("action.messages.filter").performClick()
        composeRule.onNodeWithTag("action.messages.history_cleanup")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("sheet.messages.history_cleanup.range").assertIsDisplayed()
        composeRule.onNodeWithTag("option.messages.history_cleanup.30_days")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("dialog.messages.history_cleanup.status").assertIsDisplayed()
        composeRule.onNodeWithTag("action.messages.history_cleanup.confirm")
            .assertIsDisplayed()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText(app.getString(R.string.history_cleanup_complete)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            app.resources.getQuantityString(R.plurals.history_cleanup_success, 1, 1),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("action.messages.history_cleanup.done")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("Quality Old Cleanup Target").assertDoesNotExist()
        composeRule.onNodeWithText("Quality Recent Cleanup Control").assertIsDisplayed()
        assertUnreadNavigationBadge("1")

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithText("Quality Old Cleanup Target").assertDoesNotExist()
        composeRule.onNodeWithText("Quality Recent Cleanup Control").assertIsDisplayed()
        assertUnreadNavigationBadge("1")
    }

    private data class GalleryImage(
        val id: Long,
        val uri: Uri,
    )

    private fun pushGoGalleryImages(): List<GalleryImage> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection =
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.IS_PENDING} = 0"
        val selectionArgs = arrayOf("Pictures/PushGo/")
        return app.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    add(GalleryImage(id, ContentUris.withAppendedId(collection, id)))
                }
            }
        }.orEmpty()
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    @Test
    fun workflowFixtureLoadsSecondPageAndPersistsReadActions() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_WORKFLOW,
            faults = QualityFaults(
                messagePageLoadDelayMs = 5_000,
                failMessagePageLoadOnce = true,
            ),
        )

        composeRule.onNodeWithText("Quality workflow 133").assertIsDisplayed()
        waitForCanonicalUnreadCount(100)
        val messagesNavigation = composeRule.onNodeWithTag("nav.item.messages")
        val messagesNavigationLabel = composeRule.onNodeWithTag(
            "nav.item.messages.label",
            useUnmergedTree = true,
        )
        val unreadBadge = composeRule.onNodeWithTag(
            "nav.item.messages.unread_badge",
            useUnmergedTree = true,
        )
        messagesNavigation.assertIsDisplayed().assertHasClickAction()
        messagesNavigationLabel
            .assertTextEquals(app.getString(R.string.tab_messages))
            .assertIsDisplayed()
        unreadBadge.assertTextEquals("99+").assertIsDisplayed()
        val messagesLabelBounds = messagesNavigationLabel.fetchSemanticsNode().boundsInRoot
        val unreadBadgeBounds = unreadBadge.fetchSemanticsNode().boundsInRoot
        assertTrue("The Messages label must retain visible geometry beside a capped badge", messagesLabelBounds.width > 0f)
        assertTrue("The capped unread badge must retain visible geometry", unreadBadgeBounds.width > 0f)
        assertTrue(
            "The capped unread badge must not obscure the Messages label",
            !messagesLabelBounds.overlaps(unreadBadgeBounds),
        )
        composeRule.onNodeWithTag("nav.item.events").performClick()
        composeRule.onNodeWithTag("screen.events.list").assertIsDisplayed()
        composeRule.onNodeWithTag("nav.item.messages").performClick()
        composeRule.onNodeWithTag("screen.messages.list").assertIsDisplayed()
        composeRule.onNodeWithText("Quality workflow 133").assertIsDisplayed()
        composeRule.onNodeWithTag("action.messages.mark_all_read").assertIsDisplayed()
        val messageList = composeRule.onNodeWithTag("messages.list.scroll")
        messageList.performScrollToIndex(50)
        composeRule.onNodeWithTag("state.messages.page.loading").assertIsDisplayed()
        composeRule.onNodeWithText("Quality workflow 84").assertExists()
        composeRule.onNodeWithText("Quality workflow 83").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("state.messages.page.failed"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.page.failed").assertIsDisplayed()
        composeRule.onNode(
            hasTestTag("message.row.quality-workflow-84") and
                hasContentDescriptionContaining("Quality workflow 84"),
        )
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithTag("action.messages.page.retry")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithText("Quality workflow 84").assertExists()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("Quality workflow 83")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.page.failed").assertDoesNotExist()
        messageList.performScrollToNode(hasText("Quality workflow 80"))
        composeRule.onNodeWithText("Quality workflow 80").assertExists()
        composeRule.onAllNodesWithText("Quality workflow 80").assertCountEquals(1)

        messageList.performScrollToNode(hasText("Quality workflow 83"))
        val firstPageTail = hasTestTag("message.row.quality-workflow-84") and
            hasContentDescriptionContaining("Quality workflow 84")
        val secondPageHead = hasTestTag("message.row.quality-workflow-83") and
            hasContentDescriptionContaining("Quality workflow 83")
        composeRule.onAllNodes(firstPageTail).assertCountEquals(1)
        composeRule.onAllNodes(secondPageHead).assertCountEquals(1)
        assertTrue(
            "The first/second-page boundary must preserve newest-first order",
            composeRule.onNode(firstPageTail).fetchSemanticsNode().boundsInRoot.top <
                composeRule.onNode(secondPageHead).fetchSemanticsNode().boundsInRoot.top,
        )

        messageList.performScrollToIndex(100)
        composeRule.onNodeWithText("Quality workflow 34").assertExists()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("Quality workflow 33"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        messageList.performScrollToNode(hasText("Quality workflow 33"))
        val secondPageTail = hasTestTag("message.row.quality-workflow-34") and
            hasContentDescriptionContaining("Quality workflow 34")
        val thirdPageHead = hasTestTag("message.row.quality-workflow-33") and
            hasContentDescriptionContaining("Quality workflow 33")
        composeRule.onAllNodes(secondPageTail).assertCountEquals(1)
        composeRule.onAllNodes(thirdPageHead).assertCountEquals(1)
        assertTrue(
            "The second/third-page boundary must preserve newest-first order",
            composeRule.onNode(secondPageTail).fetchSemanticsNode().boundsInRoot.top <
                composeRule.onNode(thirdPageHead).fetchSemanticsNode().boundsInRoot.top,
        )

        messageList.performScrollToNode(hasText("Quality workflow 0"))
        val oldestCanonicalRow = hasTestTag("message.row.quality-workflow-0") and
            hasContentDescriptionContaining("Quality workflow 0")
        composeRule.onAllNodes(oldestCanonicalRow).assertCountEquals(1)
        composeRule.onNode(oldestCanonicalRow).assertIsDisplayed()

        revealMessagesNavigation()
        composeRule.onNodeWithTag("nav.item.messages").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Quality workflow 119")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Quality workflow 119").assertIsDisplayed()
        composeRule.onNodeWithText("Quality workflow 133").assertDoesNotExist()

        revealMessagesNavigation()
        composeRule.onNodeWithTag("nav.item.messages").performTouchInput { doubleClick() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Quality workflow 133")).fetchSemanticsNodes().isNotEmpty()
        }
        val cancellationDeadline = SystemClock.elapsedRealtime() + 450L
        composeRule.waitUntil(timeoutMillis = 1_500) {
            SystemClock.elapsedRealtime() >= cancellationDeadline &&
                composeRule.onAllNodes(hasText("Quality workflow 133")).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodes(hasText("Quality workflow 119")).fetchSemanticsNodes().isEmpty()
        }

        revealMessagesNavigation()
        composeRule.onNodeWithTag("nav.item.messages").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Quality workflow 119")).fetchSemanticsNodes().isNotEmpty()
        }
        val workflowRow = composeRule.onNode(hasContentDescriptionContaining("Quality workflow 119"))
        val unreadLabel = app.getString(R.string.a11y_state_unread)
        val readLabel = app.getString(R.string.a11y_state_read)
        workflowRow.assert(hasStateDescription(unreadLabel)).performClick()
        composeRule.onNodeWithTag("sheet.message.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Cross-page deterministic workflow row 119.")
        pressBack()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("sheet.message.detail"))
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNode(hasContentDescriptionContaining("Quality workflow 119"))
            .assert(hasStateDescription(readLabel))
        waitForCanonicalUnreadCount(99)
        revealMessagesNavigation()
        assertUnreadNavigationBadge("99")

        messageList.performScrollToIndex(0)
        composeRule.onNodeWithTag("action.messages.mark_all_read").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("action.messages.mark_all_read"))
                .fetchSemanticsNodes().isEmpty()
        }
        assertUnreadNavigationBadge(null)
        scenario?.close()
        scenario = launchMainActivity()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("screen.messages.list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("action.messages.mark_all_read").assertDoesNotExist()
        assertUnreadNavigationBadge(null)

        composeRule.onNodeWithTag("action.messages.filter").performClick()
        composeRule.onNodeWithTag("filter.unread_only").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("state.messages.empty"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.empty").assertIsDisplayed()
        composeRule.onNodeWithTag("action.messages.filter").performClick()
        composeRule.onNodeWithTag("filter.unread_only").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("Quality workflow 133"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Quality workflow 133").assertIsDisplayed()
        composeRule.onNodeWithTag("state.messages.empty").assertDoesNotExist()
    }

    @Test
    fun channelTagCombinedUngroupedFiltersAndScopedReadPersist() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_FILTERS)

        waitForMessageSet(
            present = setOf(
                "Quality filter alpha even",
                "Quality filter alpha odd",
                "Quality filter beta odd",
                "Quality filter beta even",
                "Quality filter ungrouped orphan",
            ),
        )
        assertUnreadNavigationBadge("4")

        openMessageFilters()
        composeRule.onNodeWithTag("filter.channel.filter-alpha").performClick()
        pressBack()

        openMessageFilters()
        revealFilterOption("filter.tag.even")
        composeRule.onNodeWithTag("filter.tag.even").performClick()
        pressBack()
        waitForMessageSet(
            present = setOf("Quality filter alpha even"),
            absent = setOf(
                "Quality filter alpha odd",
                "Quality filter beta odd",
                "Quality filter beta even",
                "Quality filter ungrouped orphan",
            ),
        )
        openMessageFilters()
        revealFilterOption("filter.channel.filter-alpha")
        composeRule.onNodeWithTag("filter.channel.filter-alpha").performClick()
        revealFilterOption("filter.tag.even")
        composeRule.onNodeWithTag("filter.tag.even").performClick()
        revealFilterOption("filter.channel.ungrouped")
        composeRule.onNodeWithTag("filter.channel.ungrouped").performClick()
        pressBack()
        waitForMessageSet(
            present = setOf("Quality filter ungrouped orphan"),
            absent = setOf(
                "Quality filter alpha even",
                "Quality filter alpha odd",
                "Quality filter beta odd",
                "Quality filter beta even",
            ),
        )

        composeRule.onNodeWithTag("action.messages.mark_all_read")
            .assertIsDisplayed()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("action.messages.mark_all_read"))
                .fetchSemanticsNodes().isEmpty()
        }
        waitForCanonicalUnreadCount(3)
        assertUnreadNavigationBadge("3")

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("Quality filter ungrouped orphan"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertUnreadNavigationBadge("3")
        composeRule.onNode(hasContentDescriptionContaining("Quality filter ungrouped orphan"))
            .assert(hasStateDescription(app.getString(R.string.a11y_state_read)))

        openMessageFilters()
        revealFilterOption("filter.channel.ungrouped")
        composeRule.onNodeWithTag("filter.channel.ungrouped").performClick()
        pressBack()
        waitForMessageSet(
            present = setOf("Quality filter ungrouped orphan"),
            absent = setOf(
                "Quality filter alpha even",
                "Quality filter alpha odd",
                "Quality filter beta odd",
                "Quality filter beta even",
            ),
        )
        composeRule.onNodeWithTag("action.messages.mark_all_read").assertDoesNotExist()
    }

    private fun openMessageFilters() {
        composeRule.onNodeWithTag("action.messages.filter").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("filter.surface").assertIsDisplayed()
        composeRule.onNodeWithTag("filter.unread_only").assertIsDisplayed()
    }

    private fun revealFilterOption(testTag: String) {
        // DropdownMenu owns the actual verticalScroll node below its popup
        // surface and clips off-screen children from the semantics tree. Find
        // that real scroll owner through a visible production descendant.
        val scrollOwner = composeRule.onNode(
            hasScrollAction() and hasAnyDescendant(hasTestTag("filter.unread_only")),
            useUnmergedTree = true,
        )
        repeat(4) {
            if (runCatching {
                    composeRule.onNodeWithTag(testTag).assertIsDisplayed()
                }.isSuccess
            ) {
                return
            }
            scrollOwner.performTouchInput {
                if (testTag.startsWith("filter.tag.")) swipeUp() else swipeDown()
            }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag(testTag).assertIsDisplayed()
    }

    private fun waitForMessageSet(
        present: Set<String>,
        absent: Set<String> = emptySet(),
    ) {
        composeRule.waitUntil(timeoutMillis = 8_000) {
            present.all { title ->
                composeRule.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty()
            } && absent.all { title ->
                composeRule.onAllNodes(hasText(title)).fetchSemanticsNodes().isEmpty()
            }
        }
        present.forEach { title -> composeRule.onNodeWithText(title).assertIsDisplayed() }
        absent.forEach { title -> composeRule.onNodeWithText(title).assertDoesNotExist() }
    }

    private fun assertUnreadNavigationBadge(expectedText: String?) {
        val matcher = hasTestTag("nav.item.messages.unread_badge")
        val matched = runCatching { composeRule.waitUntil(timeoutMillis = 8_000) {
            val nodes = composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
            if (expectedText == null) {
                nodes.isEmpty()
            } else {
                nodes.size == 1 && runCatching {
                    composeRule.onNode(matcher, useUnmergedTree = true).assertTextEquals(expectedText)
                }.isSuccess
            }
        } }.isSuccess
        if (!matched && expectedText != null) {
            composeRule.onNode(matcher, useUnmergedTree = true).assertTextEquals(expectedText)
        }
        if (expectedText == null) {
            composeRule.onNode(matcher, useUnmergedTree = true).assertDoesNotExist()
        } else {
            composeRule.onNode(matcher, useUnmergedTree = true)
                .assertTextEquals(expectedText)
                .assertIsDisplayed()
        }
    }

    private fun waitForCanonicalUnreadCount(expectedCount: Int) {
        val repository = checkNotNull(app.containerOrNull()).messageRepository
        composeRule.waitUntil(timeoutMillis = 8_000) {
            runBlocking { repository.unreadCount() == expectedCount }
        }
    }

    private fun revealMessagesNavigation() {
        val navigation = hasTestTag("nav.item.messages")
        repeat(14) {
            if (composeRule.onAllNodes(navigation).fetchSemanticsNodes().isNotEmpty()) {
                return
            }
            composeRule.onNodeWithTag("screen.messages.list").performTouchInput { swipeDown() }
            composeRule.waitForIdle()
        }
        composeRule.onNode(navigation).assertIsDisplayed()
    }

    @Test
    fun searchReturnsOnlyTheTargetAndOpensItsRealDetail() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(failMessageSearchOnce = true),
        )

        composeRule.onNodeWithTag("field.message.search")
            .assertIsDisplayed()
            .performTextInput("P2 Split")
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("state.messages.search.failed"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.search.failed").assertIsDisplayed()
        composeRule.onNodeWithTag("state.messages.search.empty").assertDoesNotExist()
        composeRule.onNodeWithText("P2 Split Seed Message").assertDoesNotExist()
        composeRule.onNodeWithTag("action.messages.search.retry")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("P2 Split Seed Message"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.search.failed").assertDoesNotExist()
        composeRule.onNodeWithTag("state.messages.search.empty").assertDoesNotExist()
        composeRule.onAllNodesWithText("P2 Split Seed Message").assertCountEquals(1)
        composeRule.onNodeWithText("P2 Split Seed Message").performClick()
        composeRule.onNodeWithTag("sheet.message.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals("Seeded from fixture.seed_messages for UI validation.")
    }

    @Test
    fun deleteUndoRestoresTheSameObjectAcrossActivityRelaunch() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_STANDARD)

        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("action.message.delete").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("state.pending_deletion"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("message.row.quality-standard-message").assertDoesNotExist()
        composeRule.onNodeWithTag("action.pending_deletion.undo").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("P2 Split Seed Message"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("P2 Split Seed Message"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("P2 Split Seed Message").assertExists()
    }

    @Test
    fun deleteWithoutUndoPermanentlyRemovesOnlyTargetAcrossStorageRecreation() {
        configureAndLaunch(fixture = QualityFixture.CHANNELS_STANDARD)

        composeRule.onNodeWithText("Quality Delete History Message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("sheet.message.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Deterministic history owned by 01H00000000000000000000002.")
        composeRule.onNodeWithTag("action.message.delete").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("state.pending_deletion"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("message.row.quality-channel-delete-message")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("action.pending_deletion.undo").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasTestTag("state.pending_deletion"))
                .fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithText("Quality Delete History Message").assertDoesNotExist()
        composeRule.onNodeWithText("Quality Keep History Message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Deterministic history owned by 01H00000000000000000000001.")
        pressBack()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("sheet.message.detail"))
                .fetchSemanticsNodes().isEmpty()
        }

        scenario?.close()
        app.releaseStorageForInstrumentationTest()
        scenario = launchMainActivity()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("screen.messages.list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("Quality Keep History Message"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("message.row.quality-channel-delete-message")
            .assertDoesNotExist()
        composeRule.onNodeWithText("Quality Delete History Message").assertDoesNotExist()
        composeRule.onNodeWithText("Quality Keep History Message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Deterministic history owned by 01H00000000000000000000001.")

        scenario?.close()
        scenario = launchMainActivity {
            putExtra(NotificationHelper.EXTRA_MESSAGE_ID, "quality-channel-delete-message")
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("screen.messages.list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("feedback.message.target_unavailable")
            .assertIsDisplayed()
            .assertTextContains("The requested item was not found or has expired.")
        composeRule.onNodeWithTag("sheet.message.detail").assertDoesNotExist()
        composeRule.onNodeWithText("Quality Delete History Message").assertDoesNotExist()
        composeRule.onNodeWithText("Quality Keep History Message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Deterministic history owned by 01H00000000000000000000001.")
    }

    @Test
    fun slowLoadBecomesVisibleBeforeTheRealEmptyResult() {
        configureAndLaunch(
            fixture = QualityFixture.EMPTY_CLEAN,
            faults = QualityFaults(messageLoadDelayMs = 8_000),
        )

        composeRule.waitUntil(timeoutMillis = 4_000) {
            composeRule.onAllNodes(
                hasTestTag("state.messages.loading.slow")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.loading.slow").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(
                hasTestTag("state.messages.empty")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.empty").assertIsDisplayed()
    }

    @Test
    fun slowRefreshKeepsAccurateContentVisibleUntilCompletion() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(
                messageRefreshPresentationDelayMs = 2_500,
            ),
            messageRefreshScenario = QualityMessageRefreshScenario.NEW_MESSAGE,
        )

        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed()
        assertUnreadNavigationBadge("1")
        composeRule.onNodeWithTag("screen.messages.list").performTouchInput { swipeDown() }
        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 2_500) {
            composeRule.onAllNodes(hasTestTag("state.messages.refresh.slow"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.refresh.slow").assertIsDisplayed()
        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 6_000) {
            composeRule.onAllNodes(hasTestTag("state.messages.refresh.slow"))
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("state.messages.refresh.slow").assertDoesNotExist()
        composeRule.onNodeWithTag("message.row.quality-refresh-result")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Persisted through the provider refresh ingress path.")
    }

    @Test
    fun refreshFailureKeepsSnapshotAndRetryRecoversPersistedResult() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            messageRefreshScenario = QualityMessageRefreshScenario.FAIL_ONCE_THEN_NEW_MESSAGE,
        )

        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed()
        composeRule.onNodeWithTag("screen.messages.list").performTouchInput { swipeDown() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("state.messages.refresh.failed"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed()
        composeRule.onNodeWithTag("action.messages.refresh.retry").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("P2 Refresh Result")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.refresh.failed").assertDoesNotExist()
        composeRule.onNodeWithText("P2 Refresh Result").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Persisted through the provider refresh ingress path.")
    }

    @Test
    fun failedLoadShowsUsableRetryAndRecoversToTheRealEmptyResult() {
        configureAndLaunch(
            fixture = QualityFixture.EMPTY_CLEAN,
            faults = QualityFaults(failMessageLoad = true),
        )

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(
                hasTestTag("state.messages.load_failed")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.load_failed").assertIsDisplayed()
        composeRule.onNodeWithTag("action.messages.retry").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(
                hasTestTag("state.messages.empty")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("state.messages.empty").assertIsDisplayed()
    }

    @Test
    fun primaryNavigationUsesRealControlsAndReachesEveryProductScreen() {
        configureAndLaunch(fixture = QualityFixture.EMPTY_CLEAN)

        composeRule.onNodeWithTag("screen.messages.list").assertIsDisplayed()
        composeRule.onNodeWithTag("state.messages.empty").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.message_list_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.message_list_empty_hint)).assertIsDisplayed()
        val databaseName = checkNotNull(
            checkNotNull(app.containerOrNull()).database.openHelper.databaseName
        )
        assertNotEquals("pushgo.db", databaseName)
        assertTrue(databaseName.startsWith("pushgo-quality-"))
        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.events.list").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.label_no_events_title)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.label_no_events_hint)).assertIsDisplayed()
        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.things.list").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.label_no_things_title)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.label_no_things_hint)).assertIsDisplayed()
        composeRule.onNodeWithTag("nav.item.channels").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.channels.list").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.channel_list_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.channel_list_empty_hint)).assertIsDisplayed()
        composeRule.onNodeWithTag("action.channels.add").assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithTag("action.channels.settings").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.settings.content").assertIsDisplayed()
    }

}
