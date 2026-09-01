package io.ethan.pushgo.testing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class QualityChannelJourneyInstrumentedTest : QualityAppJourneyTestCase() {
    @Test
    fun remoteRejectionStaysInSheetAndRetryPersists() {
        assertCreateFailureThenRetry(
            scenario = QualityChannelMutationScenario.REJECT_ONCE_THEN_ACCEPTED,
            faults = QualityFaults(),
            expectedFailureText = "Channel password is incorrect. Check the password and try again.",
        )
    }

    @Test
    fun localPersistenceFailureCompensatesRemoteBeforeRetry() {
        assertCreateFailureThenRetry(
            scenario = QualityChannelMutationScenario.REQUIRE_CREATE_COMPENSATION,
            faults = QualityFaults(failChannelSubscriptionPersistenceOnce = true),
            expectedFailureText = null,
        )
    }

    @Test
    fun createRenameAndBothUnsubscribeOutcomesReachAccuratePersistentUserResults() {
        configureAndLaunch(
            fixture = QualityFixture.CHANNELS_STANDARD,
            channelMutationScenario = QualityChannelMutationScenario.RENAME_REJECT_ONCE_THEN_ACCEPTED,
        )

        openChannels()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = targetContext.resources.configuration.locales[0]
        val datePattern = DateFormat.getBestDateTimePattern(locale, "yMMMdjm")
        val expectedLatest = DateTimeFormatter.ofPattern(datePattern, locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse("2026-01-15T08:01:00Z"))
        val expectedActivity = targetContext.getString(
            R.string.channel_stats_summary,
            1,
            1,
            expectedLatest,
        )
        composeRule.onNodeWithTag(
            "channel.stats.01H00000000000000000000001",
            useUnmergedTree = true,
        )
            .assertIsDisplayed()
            .assertTextEquals(expectedActivity)
        val expectedCopiedChannelId = "01H00000000000000000000001"
        val clipboard = targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val savedClip = clipboard.primaryClip
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("quality", "pushgo-quality-copy-sentinel"))
            composeRule.onNodeWithTag("channel.row.$expectedCopiedChannelId")
                .assertIsDisplayed()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 3_000) {
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(targetContext)
                    ?.toString() == expectedCopiedChannelId
            }
            assertEquals(
                expectedCopiedChannelId,
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(targetContext)?.toString(),
            )
        } finally {
            if (savedClip != null) {
                clipboard.setPrimaryClip(savedClip)
            } else {
                clipboard.clearPrimaryClip()
            }
        }
        composeRule.onNodeWithTag("action.channels.add").performClick()
        composeRule.onNodeWithTag("sheet.channels.entry").assertIsDisplayed()
        composeRule.onNodeWithTag("mode.channels.entry.subscribe").performClick()
        waitForNode("field.channels.subscribe.id")
        waitForNode("field.channels.subscribe.password")
        composeRule.onNodeWithTag("field.channels.subscribe.id")
            .performTextInput("01H00000000000000000000004")
        composeRule.onNodeWithTag("field.channels.subscribe.password")
            .performTextInput("quality-channel-password")
        composeRule.onNodeWithTag("action.channels.entry.submit").performClick()
        waitForNode("channel.row.01H00000000000000000000004")
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000004")
            .assertIsDisplayed()
            .assertTextContains("01H00000000000000000000004")

        composeRule.onNodeWithTag("action.channels.add").performClick()
        composeRule.onNodeWithTag("sheet.channels.entry").assertIsDisplayed()
        composeRule.onNodeWithTag("field.channels.create.name").performTextInput("Quality Created Channel")
        composeRule.onNodeWithTag("action.channels.entry.submit").performClick()
        waitForNode("feedback.channels.entry")
        composeRule.onNodeWithTag("sheet.channels.entry").assertIsDisplayed()

        composeRule.onNodeWithTag("field.channels.create.password")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
            .performTextInput("quality-channel-password")
        composeRule.onNodeWithTag("feedback.channels.entry").assertDoesNotExist()
        waitForEnabledNode("action.channels.entry.submit")
        composeRule.onNodeWithTag("action.channels.entry.submit").performClick()
        waitForNode("channel.row.01H00000000000000000000003")
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertTextContains("Quality Created Channel")

        openChannelAction("01H00000000000000000000003", "rename")
        composeRule.onNodeWithTag("field.channel.rename.alias").apply {
            performTextClearance()
            performTextInput("Cancelled Rename")
        }
        composeRule.onNodeWithTag("action.channel.rename.cancel").performClick()
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertTextContains("Quality Created Channel")

        openChannelAction("01H00000000000000000000003", "rename")
        val renameField = composeRule.onNodeWithTag("field.channel.rename.alias")
        renameField.performTextClearance()
        renameField.performTextInput("x".repeat(129))
        composeRule.onNodeWithTag("action.channel.rename.save").performClick()
        waitForNode("feedback.channel.rename")
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertTextContains("Quality Created Channel")

        renameField.performTextClearance()
        renameField.performTextInput("Quality Renamed Channel")
        composeRule.onNodeWithTag("action.channel.rename.save").performClick()
        waitForNode("feedback.channel.rename")
        composeRule.onNodeWithTag("field.channel.rename.alias")
            .assertTextContains("Quality Renamed Channel")
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertTextContains("Quality Created Channel")
        composeRule.onNodeWithTag("action.channel.rename.save").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(
                hasTestTag("channel.row.01H00000000000000000000003") and
                    hasText("Quality Renamed Channel", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertTextContains("Quality Renamed Channel")

        relaunchAndOpenChannels()
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertIsDisplayed()
            .assertTextContains("Quality Renamed Channel")
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000004")
            .assertIsDisplayed()
            .assertTextContains("01H00000000000000000000004")

        openChannelAction("01H00000000000000000000001", "unsubscribe")
        composeRule.onNodeWithTag("action.channel.unsubscribe.keep_history").performClick()
        waitForNodeToDisappear("channel.row.01H00000000000000000000001")
        openMessages()
        composeRule.onNodeWithText("Quality Keep History Message").assertIsDisplayed()

        relaunchAndOpenChannels()
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000001").assertDoesNotExist()
        openMessages()
        composeRule.onNodeWithText("Quality Keep History Message").assertIsDisplayed()

        openChannels()
        openChannelAction("01H00000000000000000000002", "unsubscribe")
        composeRule.onNodeWithTag("action.channel.unsubscribe.delete_history").performClick()
        waitForNodeToDisappear("channel.row.01H00000000000000000000002")
        waitForNode("state.pending_deletion")
        waitForNodeToDisappear("state.pending_deletion", timeoutMillis = 15_000)

        openMessages()
        composeRule.onNodeWithText("Quality Keep History Message").assertIsDisplayed()
        composeRule.onNodeWithText("Quality Delete History Message").assertDoesNotExist()

        relaunchAndOpenChannels(recreateAppContainer = true)
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000002").assertDoesNotExist()
        openMessages()
        composeRule.onNodeWithText("Quality Keep History Message").assertIsDisplayed()
        composeRule.onNodeWithText("Quality Delete History Message").assertDoesNotExist()
    }

    private fun assertCreateFailureThenRetry(
        scenario: QualityChannelMutationScenario,
        faults: QualityFaults,
        expectedFailureText: String?,
    ) {
        configureAndLaunch(
            fixture = QualityFixture.CHANNELS_STANDARD,
            faults = faults,
            channelMutationScenario = scenario,
        )

        openChannels()
        composeRule.onNodeWithTag("action.channels.add").performClick()
        composeRule.onNodeWithTag("sheet.channels.entry").assertIsDisplayed()
        composeRule.onNodeWithTag("field.channels.create.name")
            .performTextInput("Quality Retry Channel")
        composeRule.onNodeWithTag("field.channels.create.password")
            .performTextInput("q".repeat(8))
        val hostErrorPresentationBaseline = QualityRuntime.globalErrorPresentationCount()
        composeRule.onNodeWithTag("action.channels.entry.submit").performClick()

        waitForNode("feedback.channels.entry")
        composeRule.onAllNodesWithTag("feedback.channels.entry").assertCountEquals(1)
        composeRule.onNodeWithTag("sheet.channels.entry").assertIsDisplayed()
        composeRule.onNodeWithTag("field.channels.create.name")
            .assertTextContains("Quality Retry Channel")
        composeRule.onNodeWithTag("field.channels.create.password")
            .assertTextContains("q".repeat(8))
        composeRule.onNodeWithTag("action.channels.entry.submit").assertIsEnabled()
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003").assertDoesNotExist()
        assertEquals(
            "Channel-entry failure must remain owned by the Sheet instead of becoming a host Toast.",
            hostErrorPresentationBaseline,
            QualityRuntime.globalErrorPresentationCount(),
        )
        expectedFailureText?.let { expected ->
            composeRule.onNodeWithTag("feedback.channels.entry").assertTextEquals(expected)
        }

        composeRule.onNodeWithText("Cancel").performClick()
        openMessages()
        openChannels()
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003").assertDoesNotExist()
        composeRule.onNodeWithTag("action.channels.add").performClick()
        composeRule.onNodeWithTag("field.channels.create.name")
            .performTextInput("Quality Retry Channel")
        composeRule.onNodeWithTag("field.channels.create.password")
            .performTextInput("q".repeat(8))
        composeRule.onNodeWithTag("action.channels.entry.submit").performClick()
        waitForNode("channel.row.01H00000000000000000000003")
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertTextContains("Quality Retry Channel")

        relaunchAndOpenChannels(recreateAppContainer = true)
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertIsDisplayed()
            .assertTextContains("Quality Retry Channel")
    }

    private fun openChannels() {
        composeRule.onNodeWithTag("nav.item.channels").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.channels.list").assertIsDisplayed()
    }

    private fun openMessages() {
        composeRule.onNodeWithTag("nav.item.messages").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.messages.list").assertIsDisplayed()
    }

    private fun relaunchAndOpenChannels(recreateAppContainer: Boolean = false) {
        scenario?.close()
        if (recreateAppContainer) {
            app.releaseStorageForInstrumentationTest()
        }
        scenario = launchMainActivity()
        openChannels()
    }

    private fun openChannelAction(channelId: String, action: String) {
        composeRule.onNodeWithTag("action.channel.$channelId.menu").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("action.channel.$channelId.$action").assertIsDisplayed().performClick()
    }

    private fun waitForNode(tag: String, timeoutMillis: Long = 8_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun waitForEnabledNode(tag: String, timeoutMillis: Long = 8_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
    }

    private fun waitForNodeToDisappear(tag: String, timeoutMillis: Long = 8_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
        }
    }
}
