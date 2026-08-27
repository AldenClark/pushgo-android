package io.ethan.pushgo.testing

import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityEntityJourneyInstrumentedTest : QualityAppJourneyTestCase() {
    @Test
    fun eventFixtureUsesTheProductionProjectionAndOpensAccurateDetail() {
        configureAndLaunch(fixture = QualityFixture.EVENT_STANDARD)

        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("event.row.quality-event"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("event.row.quality-event", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Quality Cooling Alert")))
        composeRule.onNodeWithTag("event.row.quality-event").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("sheet.event.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.event.detail.summary")
            .assertTextEquals("Cooling loop temperature crossed the quality threshold.")

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("event.row.quality-event"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("event.row.quality-event").assertIsDisplayed()
    }

    @Test
    fun thingFixtureShowsAccurateOverviewAndAllThreeRealRelationTabs() {
        configureAndLaunch(fixture = QualityFixture.THING_STANDARD)

        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("thing.row.quality-thing"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("thing.row.quality-thing", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Quality Reactor Alpha")))
        composeRule.onNodeWithTag("thing.row.quality-thing").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("sheet.thing.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.thing.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Quality Reactor Alpha")))
        composeRule.onNodeWithText("A deterministic reactor with linked events, messages, and updates.")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("tab.thing.detail.events").performClick()
        composeRule.onNodeWithText("Quality Related Event").assertIsDisplayed()
        composeRule.onNodeWithTag("tab.thing.detail.messages").performClick()
        composeRule.onNodeWithText("Quality Related Message").assertIsDisplayed()
        composeRule.onNodeWithTag("tab.thing.detail.updates").performClick()
        composeRule.onNodeWithText("Quality Initial Thing Snapshot").assertIsDisplayed()

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("thing.row.quality-thing"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("thing.row.quality-thing").assertIsDisplayed()
    }
}
