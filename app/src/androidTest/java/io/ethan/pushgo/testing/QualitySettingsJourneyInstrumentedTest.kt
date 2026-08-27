package io.ethan.pushgo.testing

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualitySettingsJourneyInstrumentedTest : QualityAppJourneyTestCase() {
    @Test
    fun eventPageVisibilityUsesRealControlsAndPersistsAcrossRelaunch() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_STANDARD)

        openPageVisibilitySettings()
        composeRule.onNodeWithTag("switch.settings.page.events")
            .assertIsSelected()
            .performClick()
            .assertIsNotSelected()
        leaveSettings()
        composeRule.onNodeWithTag("nav.item.events").assertDoesNotExist()

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithTag("nav.item.events").assertDoesNotExist()

        openPageVisibilitySettings()
        composeRule.onNodeWithTag("switch.settings.page.events")
            .assertIsNotSelected()
            .performClick()
            .assertIsSelected()
        leaveSettings()
        assertEventDestinationCanOpen()

        scenario?.close()
        scenario = launchMainActivity()
        assertEventDestinationCanOpen()
    }

    private fun openPageVisibilitySettings() {
        composeRule.onNodeWithTag("nav.item.channels").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("action.channels.settings").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.settings.content")
            .assertIsDisplayed()
            .performScrollToNode(hasTestTag("switch.settings.page.events"))
        composeRule.onNodeWithTag("switch.settings.page.events").assertIsDisplayed()
    }

    private fun leaveSettings() {
        scenario?.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithTag("screen.channels.list").assertIsDisplayed()
    }

    private fun assertEventDestinationCanOpen() {
        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.events.list").assertIsDisplayed()
    }
}
