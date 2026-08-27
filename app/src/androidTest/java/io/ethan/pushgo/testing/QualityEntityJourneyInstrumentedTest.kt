package io.ethan.pushgo.testing

import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.util.DiagnosticLogStore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityEntityJourneyInstrumentedTest : QualityAppJourneyTestCase() {
    @Test
    fun eventClosePersistsAndOngoingFilterReflectsTheRealProjection() {
        configureAndLaunch(
            fixture = QualityFixture.EVENT_STANDARD,
            eventCloseScenario = QualityEventCloseScenario.ACCEPTED_AND_DELIVERED,
        )

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

        composeRule.onNodeWithTag("event.close.action").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("event.close.confirm").assertIsDisplayed().performClick()
        try {
            composeRule.waitUntil(timeoutMillis = 8_000) {
                composeRule.onAllNodes(hasTestTag("sheet.event.detail"))
                    .fetchSemanticsNodes().isEmpty()
            }
        } catch (failure: Throwable) {
            println("Event close diagnostics: ${DiagnosticLogStore.snapshot().takeLast(20)}")
            throw failure
        }
        composeRule.onNodeWithTag("event.row.quality-event").assertIsDisplayed()
        composeRule.onNodeWithTag("event.filters.action").performClick()
        composeRule.onNodeWithTag("event.filters.ongoing").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("event.row.quality-event"))
                .fetchSemanticsNodes().isEmpty()
        }

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("event.row.quality-event"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("event.row.quality-event").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("field.event.detail.status.closed").assertIsDisplayed()
        composeRule.onNodeWithTag("event.close.action").assertDoesNotExist()
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
