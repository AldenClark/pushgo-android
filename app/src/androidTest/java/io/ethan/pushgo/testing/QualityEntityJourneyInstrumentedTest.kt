package io.ethan.pushgo.testing

import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.R
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.ui.screens.EntityTargetUnavailableFeedbackTag
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
        assertCurrentTabDoubleTapReturnsToTop(
            navigationTag = "nav.item.events",
            screenTag = "screen.events.list",
            offscreenRowTag = "event.row.quality-event-navigation-00",
            topRowTag = "event.row.quality-event",
        )
        composeRule.onNodeWithTag("event.row.quality-event").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("sheet.event.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.event.detail.summary")
            .assertTextEquals("Cooling loop temperature crossed the quality threshold.")

        composeRule.onNodeWithTag("event.close.action").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("event.close.cancel").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("sheet.event.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.event.detail.status.ongoing").assertIsDisplayed()
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

        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("thing.row.quality-thing"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("thing.row.quality-thing").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("tab.thing.detail.events").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("event.row.quality-related-event")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("event.row.quality-event")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.event.detail.status.closed").assertIsDisplayed()
        composeRule.onNodeWithTag("event.timeline.count.3").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.event.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Cooling loop temperature crossed the quality threshold.")))

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("event.row.quality-event"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("event.row.quality-event").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("field.event.detail.status.closed").assertIsDisplayed()
        composeRule.onNodeWithTag("event.timeline.count.3").assertIsDisplayed()
        composeRule.onNodeWithTag("event.close.action").assertDoesNotExist()
    }

    @Test
    fun eventCloseFailureKeepsAccurateDetailBlocksDuplicateAndRetryPersists() {
        configureAndLaunch(
            fixture = QualityFixture.EVENT_STANDARD,
            eventCloseScenario = QualityEventCloseScenario.FAIL_ONCE_THEN_ACCEPTED_AND_DELIVERED,
        )

        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("event.row.quality-event"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("event.row.quality-event").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("sheet.event.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.event.detail.summary")
            .assertTextEquals("Cooling loop temperature crossed the quality threshold.")

        fun confirmClose() {
            composeRule.onNodeWithTag("event.close.action").assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("event.close.confirm").assertIsDisplayed().performClick()
        }

        fun assertCloseInProgress() {
            try {
                composeRule.waitUntil(timeoutMillis = 2_000) {
                    runCatching {
                        composeRule.onNodeWithTag("state.event.close.in_progress").isDisplayed()
                    }.getOrDefault(false)
                }
            } catch (failure: Throwable) {
                println(
                    composeRule.onAllNodes(isRoot(), useUnmergedTree = true)
                        .printToString(maxDepth = 20),
                )
                println("Event close diagnostics: ${DiagnosticLogStore.snapshot().takeLast(20)}")
                throw failure
            }
            composeRule.onNodeWithTag("state.event.close.in_progress").assertIsDisplayed()
            composeRule.onNodeWithTag("event.close.action").assertDoesNotExist()
        }

        confirmClose()
        assertCloseInProgress()
        // A real Firebase callback can arrive while the quality-owned journey is
        // running. It must not start an unrelated provider sync that deletes the
        // fixture subscription and changes the retry outcome.
        app.handlePushTokenUpdate("quality-unsolicited-external-token")
        composeRule.onNodeWithTag("sheet.event.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.event.detail.summary")
            .assertTextEquals("Cooling loop temperature crossed the quality threshold.")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("feedback.event.close"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("feedback.event.close").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.event.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasTestTag("feedback.event.close")))
        composeRule.onNodeWithTag("field.event.detail.status.ongoing").assertIsDisplayed()
        composeRule.onNodeWithTag("event.close.action").assertIsDisplayed()

        confirmClose()
        try {
            composeRule.waitUntil(timeoutMillis = 12_000) {
                composeRule.onAllNodes(hasTestTag("sheet.event.detail"))
                    .fetchSemanticsNodes().isEmpty()
            }
        } catch (failure: Throwable) {
            println(
                composeRule.onAllNodes(isRoot(), useUnmergedTree = true)
                    .printToString(maxDepth = 20),
            )
            println("Event close retry diagnostics: ${DiagnosticLogStore.snapshot().takeLast(20)}")
            throw failure
        }

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("event.row.quality-event"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("event.row.quality-event").performClick()
        composeRule.onNodeWithTag("field.event.detail.status.closed").assertIsDisplayed()
        composeRule.onNodeWithTag("event.close.action").assertDoesNotExist()
    }

    @Test
    fun relatedEventCloseFailureStaysOwnedAndBlocksDuplicateSubmission() {
        configureAndLaunch(
            fixture = QualityFixture.THING_STANDARD,
            eventCloseScenario = QualityEventCloseScenario.FAIL_ONCE_THEN_ACCEPTED_AND_DELIVERED,
        )

        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("thing.row.quality-thing"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("thing.row.quality-thing").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("tab.thing.detail.events").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("event.row.quality-related-event")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("sheet.event.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.event.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("A deterministic event associated with Quality Reactor Alpha.")))

        composeRule.onNodeWithTag("event.close.action").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("event.close.confirm").assertIsDisplayed().performClick()
        try {
            composeRule.waitUntil(timeoutMillis = 2_000) {
                runCatching {
                    composeRule.onNodeWithTag("state.event.close.in_progress").isDisplayed()
                }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            println(
                composeRule.onAllNodes(isRoot(), useUnmergedTree = true)
                    .printToString(maxDepth = 20),
            )
            println("Related Event close diagnostics: ${DiagnosticLogStore.snapshot().takeLast(20)}")
            throw failure
        }
        composeRule.onNodeWithTag("event.close.action").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("feedback.event.close"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("feedback.event.close").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.event.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.event.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasTestTag("feedback.event.close")))
        composeRule.onNodeWithTag("field.event.detail.status.ongoing").assertIsDisplayed()
        composeRule.onNodeWithTag("event.close.action").assertIsDisplayed()
    }

    @Test
    fun thingFixtureShowsAccurateOverviewAndAllThreeRealRelationTabs() {
        configureAndLaunch(fixture = QualityFixture.THING_STANDARD)

        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("thing.row.quality-thing"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("thing.row.quality-thing-distractor").assertIsDisplayed()
        assertCurrentTabDoubleTapReturnsToTop(
            navigationTag = "nav.item.things",
            screenTag = "screen.things.list",
            offscreenRowTag = "thing.row.quality-thing-navigation-00",
            topRowTag = "thing.row.quality-thing",
        )
        composeRule.onNodeWithTag("thing.row.quality-thing-distractor").performClick()
        composeRule.onNodeWithTag("sheet.thing.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("action.thing.delete").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("thing.row.quality-thing-distractor").assertDoesNotExist()
        composeRule.onNodeWithTag("thing.row.quality-thing").assertIsDisplayed()
        composeRule.onNodeWithTag("action.pending_deletion.undo").assertIsDisplayed()
        composeRule.onNodeWithTag("thing.search.input").assertIsDisplayed().performTextInput("Reactor Alpha")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("thing.row.quality-thing-distractor"))
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("thing.row.quality-thing").assertIsDisplayed()
        composeRule.onNodeWithTag("thing.row.quality-thing-distractor").assertDoesNotExist()
        composeRule.onNodeWithTag("thing.row.quality-thing", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Quality Reactor Alpha")))
        composeRule.onNodeWithTag("thing.row.quality-thing").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("sheet.thing.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.thing.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Quality Reactor Alpha")))
        composeRule.onNodeWithText("A deterministic reactor with linked events, messages, and updates.")
            .assertIsDisplayed()

        composeRule.onNodeWithTag("tab.thing.detail.events").performClick()
        composeRule.onNodeWithTag("event.row.quality-related-event")
            .assertIsDisplayed()
            .assertTextContains("Quality Related Event")
            .performClick()
        composeRule.onNodeWithTag("sheet.event.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.event.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("A deterministic event associated with Quality Reactor Alpha.")))
        dismissTopSheet()
        composeRule.onNodeWithTag("event.row.quality-related-event").assertIsDisplayed()

        composeRule.onNodeWithTag("tab.thing.detail.messages").performClick()
        composeRule.onNodeWithTag("thing.related.message.quality-related-message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("sheet.thing.related.message.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("field.message.detail.title", useUnmergedTree = true)
            .assertTextEquals("Quality Related Message")
        composeRule.onNodeWithTag("field.message.detail.body", useUnmergedTree = true)
            .assertTextEquals("The linked reactor message is visible in the Messages tab.")
        dismissTopSheet()
        composeRule.onNodeWithTag("thing.related.message.quality-related-message").assertIsDisplayed()

        composeRule.onNodeWithTag("tab.thing.detail.updates").performClick()
        composeRule.onNodeWithTag("thing.related.update.quality-delivery-thing-initial")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("sheet.thing.related.update.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("content.thing.related.update.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Quality Initial Thing Snapshot")))
        dismissTopSheet()
        composeRule.onNodeWithTag("thing.related.update.quality-delivery-thing-initial")
            .performScrollTo()
            .assertIsDisplayed()

        scenario?.close()
        scenario = launchMainActivity {
            putExtra(NotificationHelper.EXTRA_ENTITY_TYPE, "thing")
            putExtra(NotificationHelper.EXTRA_ENTITY_ID, "quality-thing-distractor")
        }
        composeRule.onNodeWithTag(EntityTargetUnavailableFeedbackTag)
            .assertIsDisplayed()
            .assertTextEquals(app.getString(R.string.error_gateway_resource_not_found))
        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("thing.row.quality-thing"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("thing.row.quality-thing-distractor").assertDoesNotExist()
        composeRule.onNodeWithTag("screen.things.list").assertIsDisplayed()
        composeRule.onNodeWithTag("thing.row.quality-thing").assertIsDisplayed()
        composeRule.onNodeWithTag("thing.row.quality-thing").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("event.row.quality-related-event").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("sheet.event.detail").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet.event.detail", useUnmergedTree = true)
            .assert(hasAnyDescendant(hasText("Quality Related Event")))
    }

    private fun dismissTopSheet() {
        pressBack()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("sheet.thing.detail"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sheet.thing.detail").assertIsDisplayed()
    }

    private fun assertCurrentTabDoubleTapReturnsToTop(
        navigationTag: String,
        screenTag: String,
        offscreenRowTag: String,
        topRowTag: String,
    ) {
        val scrollOwner = composeRule.onNode(
            hasScrollAction() and hasAnyAncestor(hasTestTag(screenTag)),
            useUnmergedTree = true,
        )
        scrollOwner.performScrollToIndex(16)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(offscreenRowTag).assertIsDisplayed()
        for (attempt in 0 until 3) {
            if (runCatching { composeRule.onNodeWithTag(navigationTag).isDisplayed() }.getOrDefault(false)) {
                break
            }
            composeRule.onNodeWithTag(screenTag).performTouchInput { swipeDown() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag(topRowTag).assertIsNotDisplayed()
        composeRule.onNodeWithTag(navigationTag)
            .assertIsDisplayed()
            .performTouchInput { doubleClick() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { composeRule.onNodeWithTag(topRowTag).isDisplayed() }.getOrDefault(false) &&
                !runCatching {
                    composeRule.onNodeWithTag(offscreenRowTag).isDisplayed()
                }.getOrDefault(false)
        }
        composeRule.onNodeWithTag(topRowTag).assertIsDisplayed()
        composeRule.onNodeWithTag(offscreenRowTag).assertIsNotDisplayed()
    }
}
