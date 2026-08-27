package io.ethan.pushgo.testing

import androidx.compose.ui.test.*
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
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
