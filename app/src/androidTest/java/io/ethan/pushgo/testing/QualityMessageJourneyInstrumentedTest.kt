package io.ethan.pushgo.testing

import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.accessibility.hasContentDescriptionContaining
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityMessageJourneyInstrumentedTest : QualityAppJourneyTestCase() {

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
    fun standardFixtureShowsAccurateContentAndSurvivesActivityRelaunch() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_STANDARD)

        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Seeded from fixture.seed_messages for UI validation.")
            .assertIsDisplayed()

        scenario?.close()
        scenario = launchMainActivity()

        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(
                hasText("P2 Split Seed Message")
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("P2 Split Seed Message").assertExists()
    }

    @Test
    fun workflowFixtureLoadsSecondPageAndPersistsReadActions() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_WORKFLOW)

        composeRule.onNodeWithText("Quality workflow 51").assertIsDisplayed()
        composeRule.onNodeWithTag("action.messages.mark_all_read").assertIsDisplayed()
        for (attempt in 0 until 14) {
            if (composeRule.onAllNodes(hasText("Quality workflow 0")).fetchSemanticsNodes().isNotEmpty()) {
                break
            }
            composeRule.onNodeWithTag("screen.messages.list").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Quality workflow 0").assertExists()

        val workflowRow = composeRule.onNode(hasContentDescriptionContaining("Quality workflow 1"))
        val unreadLabel = app.getString(R.string.a11y_state_unread)
        val readLabel = app.getString(R.string.a11y_state_read)
        workflowRow.assert(hasStateDescription(unreadLabel)).performClick()
        composeRule.onNodeWithTag("sheet.message.detail").assertIsDisplayed()
        pressBack()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("sheet.message.detail"))
                .fetchSemanticsNodes().isEmpty()
        }
        workflowRow.assert(hasStateDescription(readLabel))

        for (attempt in 0 until 14) {
            if (composeRule.onAllNodes(hasTestTag("action.messages.mark_all_read"))
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                break
            }
            composeRule.onNodeWithTag("screen.messages.list").performTouchInput { swipeDown() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("action.messages.mark_all_read").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("action.messages.mark_all_read"))
                .fetchSemanticsNodes().isEmpty()
        }
        scenario?.close()
        scenario = launchMainActivity()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("screen.messages.list"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("action.messages.mark_all_read").assertDoesNotExist()

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
            composeRule.onAllNodes(hasText("Quality workflow 51"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Quality workflow 51").assertIsDisplayed()
        composeRule.onNodeWithTag("state.messages.empty").assertDoesNotExist()
    }

    @Test
    fun searchReturnsOnlyTheTargetAndOpensItsRealDetail() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_STANDARD)

        composeRule.onNodeWithTag("field.message.search")
            .assertIsDisplayed()
            .performTextInput("not-present-in-any-message")
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
        composeRule.onNodeWithText("Seeded from fixture.seed_messages for UI validation.")
            .assertIsDisplayed()
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
            faults = QualityFaults(messageRefreshDelayMs = 2_500),
        )

        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed()
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
        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed()
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
        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.events.list").assertIsDisplayed()
        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.things.list").assertIsDisplayed()
        composeRule.onNodeWithTag("nav.item.channels").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.channels.list").assertIsDisplayed()
        composeRule.onNodeWithTag("action.channels.settings").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.settings.content").assertIsDisplayed()
    }

}
