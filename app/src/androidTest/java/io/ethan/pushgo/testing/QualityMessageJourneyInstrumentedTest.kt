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
import kotlinx.coroutines.runBlocking

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
    fun fatalStoreInitializationStopsReadWriteAndRecoversAfterRelaunch() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(failLocalStoreInitialization = true),
        )

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
        configureAndLaunch(fixture = QualityFixture.MESSAGES_STANDARD)

        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Seeded from fixture.seed_messages for UI validation.")

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
        assertUnreadNavigationBadge("39")
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
        waitForCanonicalUnreadCount(38)
        revealMessagesNavigation()
        assertUnreadNavigationBadge("38")

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
            composeRule.onAllNodes(hasText("Quality workflow 51"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Quality workflow 51").assertIsDisplayed()
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
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Seeded from fixture.seed_messages for UI validation.")
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
    fun refreshPersistsNewProviderResultOpensDetailAndSurvivesRelaunch() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            messageRefreshScenario = QualityMessageRefreshScenario.NEW_MESSAGE,
        )

        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed()
        composeRule.onNodeWithTag("screen.messages.list").performTouchInput { swipeDown() }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("P2 Refresh Result")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("P2 Split Seed Message").assertExists()
        composeRule.onNodeWithText("P2 Refresh Result").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextContains("Persisted through the provider refresh ingress path.")

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("P2 Refresh Result")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("P2 Refresh Result").assertExists()
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
