package io.ethan.pushgo.testing

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import io.ethan.pushgo.R
import io.ethan.pushgo.notifications.NotificationIngressParser
import io.ethan.pushgo.update.UpdateCheckScheduler
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualitySettingsJourneyInstrumentedTest : QualityAppJourneyTestCase() {
    @After
    fun cleanUpQualityUpdateWork() {
        val updateScenario = QualityRuntime.currentSession()?.updateScenario
        if (updateScenario == null || updateScenario == QualityUpdateScenario.NONE) return

        val workManager = WorkManager.getInstance(app)
        cancelAndAwaitUniqueWork(workManager, UpdateCheckScheduler.PERIODIC_WORK_NAME)
        cancelAndAwaitUniqueWork(workManager, UpdateCheckScheduler.ONE_TIME_WORK_NAME)
        io.ethan.pushgo.update.UpdateNotifier.cancelAvailableNotification(app)
    }

    @Test
    fun encryptedMessageRecoversThroughRealSettingsEntryAndSurvivesRelaunch() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_ENCRYPTED_VALID)

        composeRule.onNodeWithText(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals("Configure decryption to read this message.")
        composeRule.onNodeWithTag("status.message.decryption.not_configured")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("action.message.configure_decryption")
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag("screen.settings.decryption").assertIsDisplayed()
        val wrongKey = Base64.getEncoder()
            .encodeToString(ByteArray(16) { 0x5A.toByte() })
        composeRule.onNodeWithTag("field.settings.decryption.key")
            .performTextInput(wrongKey)
        val protectedField = composeRule.onNode(hasSetTextAction())
        protectedField.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit),
        )
        val maskedRendering = protectedField.captureToImage()
        val visibilityToggle = composeRule.onNodeWithTag(
            listOf("action.settings", "decryption", "toggle_visibility").joinToString("."),
        )
        visibilityToggle
            .assertContentDescriptionEquals(app.getString(R.string.label_show_key))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    app.getString(R.string.a11y_state_off),
                ),
            )
            .performClick()
            .assertContentDescriptionEquals(app.getString(R.string.label_hide_key))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    app.getString(R.string.a11y_state_on),
                ),
            )
        protectedField.assertTextContains(wrongKey)
        val revealedRendering = protectedField.captureToImage()
        val revealedDifference = fieldBodyPixelDifference(maskedRendering, revealedRendering)
        assertTrue(
            "The visible-key action did not materially change the field's rendered value.",
            revealedDifference > 100,
        )
        visibilityToggle.performClick()
        protectedField.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit),
        )
        val hiddenAgainRendering = protectedField.captureToImage()
        val restoredDifference = fieldBodyPixelDifference(maskedRendering, hiddenAgainRendering)
        assertTrue(
            "The hide-key action did not restore the protected rendering.",
            restoredDifference * 4 < revealedDifference,
        )
        composeRule.onNodeWithTag("action.settings.decryption.save")
            .assertIsDisplayed()
            .performClick()
        waitForTagToDisappear("screen.settings.decryption")
        composeRule.onNodeWithText(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals(NotificationIngressParser.AUTHENTICATION_FAILED_BODY)
        composeRule.onNodeWithTag("status.message.decryption.decrypt_failed")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Recovered Quality Message").assertDoesNotExist()

        composeRule.onNodeWithTag("action.message.configure_decryption")
            .assertIsDisplayed()
            .performClick()
        val validKey = Base64.getEncoder().encodeToString(
            listOf("Quality", "Key", "123456").joinToString("").toByteArray(Charsets.UTF_8),
        )
        val decryptionFieldTag = listOf("field.settings.decryption", "key").joinToString(".")
        composeRule.onNodeWithTag(decryptionFieldTag).performTextInput(validKey)
        composeRule.onNodeWithTag("action.settings.decryption.save")
            .assertIsDisplayed()
            .performClick()
        waitForTagToDisappear("screen.settings.decryption")
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("Recovered Quality Message"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Recovered Quality Message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals("Recovered from the original encrypted payload.")
        composeRule.onNodeWithTag("status.message.decryption.decrypt_ok")
            .assertIsDisplayed()

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasText("Recovered Quality Message"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Recovered Quality Message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals("Recovered from the original encrypted payload.")
        composeRule.onNodeWithTag("status.message.decryption.decrypt_ok")
            .assertIsDisplayed()
    }

    @Test
    fun corruptEncryptedMessageFailsSafelyAndSurvivesRelaunch() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_ENCRYPTED_CORRUPT)

        composeRule.onNodeWithText(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("status.message.decryption.not_configured")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("action.message.configure_decryption").performClick()
        val validKey = Base64.getEncoder().encodeToString(
            listOf("Quality", "Key", "123456").joinToString("").toByteArray(Charsets.UTF_8),
        )
        val fieldTag = listOf("field.settings.decryption", "key").joinToString(".")
        composeRule.onNodeWithTag(fieldTag).performTextInput(validKey)
        composeRule.onNodeWithTag("action.settings.decryption.save").performClick()
        waitForTagToDisappear("screen.settings.decryption")
        composeRule.onNodeWithText(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals(NotificationIngressParser.AUTHENTICATION_FAILED_BODY)
        composeRule.onNodeWithTag("status.message.decryption.decrypt_failed")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Recovered Quality Message").assertDoesNotExist()

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithText(NotificationIngressParser.AUTHENTICATION_FAILED_TITLE)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals(NotificationIngressParser.AUTHENTICATION_FAILED_BODY)
        composeRule.onNodeWithTag("status.message.decryption.decrypt_failed")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Recovered Quality Message").assertDoesNotExist()
    }

    private fun fieldBodyPixelDifference(first: ImageBitmap, second: ImageBitmap): Int {
        require(first.width == second.width && first.height == second.height)
        val firstPixels = first.toPixelMap()
        val secondPixels = second.toPixelMap()
        val contentWidth = first.width * 3 / 4
        var difference = 0
        for (y in 0 until first.height) {
            for (x in 0 until contentWidth) {
                if (firstPixels[x, y].toArgb() != secondPixels[x, y].toArgb()) {
                    difference += 1
                }
            }
        }
        return difference
    }

    @Test
    fun dataPageVisibilityUsesRealControlsAndPersistsAcrossRelaunch() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            updateScenario = QualityUpdateScenario.AVAILABLE_STABLE_AND_BETA,
        )

        openSettings()
        scrollTo("row.settings.update.check_now")
        waitForTag("card.settings.update.available")
        composeRule.onNodeWithTag("card.settings.update.available").performScrollTo()
        composeRule.onNodeWithTag("row.settings.update.check_now")
            .assertTextContains(
                app.getString(R.string.label_update_status_available, "9.9.9-quality"),
            )
        composeRule.onNodeWithTag("switch.settings.update.auto_check")
            .assertUpdateToggleEnabled(true)
            .performClick()
            .assertUpdateToggleEnabled(false)
        assertActivePeriodicUpdateWorkCount(0)
        composeRule.onNodeWithTag("switch.settings.update.auto_check")
            .performClick()
            .assertUpdateToggleEnabled(true)
        assertActivePeriodicUpdateWorkCount(1)
        composeRule.onNodeWithTag("option.settings.update.channel.stable").assertIsSelected()
        composeRule.onNodeWithTag("option.settings.update.channel.beta")
            .assertIsNotSelected()
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("row.settings.update.check_now")
            .assertTextContains(
                app.getString(R.string.label_update_status_available, "10.0.0-beta-quality"),
            )
        composeRule.onNodeWithTag("action.settings.update.remind_later")
            .assertIsDisplayed()
            .performClick()
        waitForTagToDisappear("card.settings.update.available")
        composeRule.onNodeWithTag("row.settings.update.check_now")
            .assertTextContains(app.getString(R.string.label_update_status_cooldown))
        scrollTo("switch.settings.page.things")
        composeRule.onNodeWithTag("switch.settings.page.events")
            .assertIsSelected()
            .performClick()
            .assertIsNotSelected()
        composeRule.onNodeWithTag("switch.settings.page.things")
            .assertIsSelected()
            .performClick()
            .assertIsNotSelected()
        leaveSettings()
        composeRule.onNodeWithTag("nav.item.events").assertDoesNotExist()
        composeRule.onNodeWithTag("nav.item.things").assertDoesNotExist()
        composeRule.onNodeWithTag("screen.channels.list").assertIsDisplayed()

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithTag("nav.item.events").assertDoesNotExist()
        composeRule.onNodeWithTag("nav.item.things").assertDoesNotExist()

        openPageVisibilitySettings()
        assertActivePeriodicUpdateWorkCount(1)
        composeRule.onNodeWithTag("switch.settings.update.auto_check")
            .assertUpdateToggleEnabled(true)
        composeRule.onNodeWithTag("option.settings.update.channel.beta").assertIsSelected()
        scrollTo("row.settings.update.check_now")
        composeRule.onNodeWithTag("row.settings.update.check_now")
            .assertTextContains(app.getString(R.string.label_update_status_cooldown))
        composeRule.onNodeWithTag("card.settings.update.available").assertDoesNotExist()
        composeRule.onNodeWithTag("row.settings.update.check_now").performClick()
        waitForTag("card.settings.update.available")
        composeRule.onNodeWithTag("row.settings.update.check_now")
            .assertTextContains(
                app.getString(R.string.label_update_status_available, "10.0.0-beta-quality"),
            )
        composeRule.onNodeWithTag("option.settings.update.channel.stable")
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("row.settings.update.check_now")
            .assertTextContains(
                app.getString(R.string.label_update_status_available, "9.9.9-quality"),
            )
        composeRule.onNodeWithTag("option.settings.update.channel.beta")
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("row.settings.update.check_now")
            .assertTextContains(
                app.getString(R.string.label_update_status_available, "10.0.0-beta-quality"),
            )
        scrollTo("switch.settings.page.things")
        composeRule.onNodeWithTag("switch.settings.page.events")
            .assertIsNotSelected()
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag("switch.settings.page.things")
            .assertIsNotSelected()
            .performClick()
            .assertIsSelected()
        leaveSettings()
        assertDataDestinationsCanOpen()

        scenario?.close()
        scenario = launchMainActivity()
        assertDataDestinationsCanOpen()
    }

    private fun cancelAndAwaitUniqueWork(workManager: WorkManager, uniqueName: String) {
        workManager.cancelUniqueWork(uniqueName).result.get(5, TimeUnit.SECONDS)
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadlineNanos) {
            val active = workManager.getWorkInfosForUniqueWork(uniqueName)
                .get(2, TimeUnit.SECONDS)
                .count { !it.state.isFinished }
            if (active == 0) return
            Thread.sleep(25)
        }
        val active = workManager.getWorkInfosForUniqueWork(uniqueName)
            .get(2, TimeUnit.SECONDS)
            .count { !it.state.isFinished }
        assertEquals("Quality update work was not cancelled: $uniqueName", 0, active)
    }

    private fun assertActivePeriodicUpdateWorkCount(expected: Int) {
        val workManager = WorkManager.getInstance(app)
        composeRule.waitUntil(timeoutMillis = 8_000) {
            workManager.getWorkInfosForUniqueWork(UpdateCheckScheduler.PERIODIC_WORK_NAME)
                .get(2, TimeUnit.SECONDS)
                .count { !it.state.isFinished } == expected
        }
        val active = workManager
            .getWorkInfosForUniqueWork(UpdateCheckScheduler.PERIODIC_WORK_NAME)
            .get(2, TimeUnit.SECONDS)
            .count { !it.state.isFinished }
        assertEquals("Unexpected active periodic update work count", expected, active)
    }

    private fun SemanticsNodeInteraction.assertUpdateToggleEnabled(
        enabled: Boolean,
    ): SemanticsNodeInteraction = assert(
        SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            app.getString(if (enabled) R.string.a11y_state_on else R.string.a11y_state_off),
        ),
    )

    @Test
    fun serverConfigurationRejectsInvalidInputAndScopesDataAfterRelaunch() {
        val normalizedAddress = "https://quality-settings.invalid/api"
        configureAndLaunch(
            fixture = QualityFixture.CHANNELS_STANDARD,
            faults = QualityFaults(failGatewaySwitchValidationOnce = true),
            channelMutationScenario = QualityChannelMutationScenario.ACCEPTED,
            expectedChannelMutationGatewayUrl = normalizedAddress,
        )
        openSettings()
        scrollTo("row.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway").performClick()
        composeRule.onNodeWithTag("sheet.settings.gateway").assertIsDisplayed()

        val addressField = composeRule.onNodeWithTag("field.settings.gateway.address")
        val gatewayToken = "quality-gateway-token"
        val tokenField = composeRule.onNodeWithTag("field.settings.gateway.token")
        val tokenVisibilityToggle = composeRule.onNodeWithTag(
            "action.settings.gateway.token.toggle_visibility",
        )
        tokenField
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
            .performTextInput(gatewayToken)
        tokenVisibilityToggle
            .assertContentDescriptionEquals(app.getString(R.string.label_show_key))
            .performClick()
            .assertContentDescriptionEquals(app.getString(R.string.label_hide_key))
        tokenField.assertTextContains(gatewayToken)
        tokenVisibilityToggle.performClick()
        tokenField.assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
        addressField.performTextClearance()
        addressField.performTextInput("not a valid url")
        val hostErrorPresentationBaseline = QualityRuntime.globalErrorPresentationCount()
        composeRule.onNodeWithTag("action.settings.gateway.save").performClick()
        waitForTag("feedback.settings.gateway")
        composeRule.onNodeWithTag("sheet.settings.gateway").assertIsDisplayed()
        composeRule.onNodeWithTag("feedback.settings.gateway")
            .assertTextEquals(app.getString(R.string.error_invalid_server_address))
        assertEquals(
            "Gateway validation feedback must remain owned by the Sheet.",
            hostErrorPresentationBaseline,
            QualityRuntime.globalErrorPresentationCount(),
        )

        addressField.performTextClearance()
        addressField.performTextInput("$normalizedAddress/")
        composeRule.onNodeWithTag("action.settings.gateway.save").performClick()
        waitForTag("feedback.settings.gateway")
        composeRule.onNodeWithTag("sheet.settings.gateway").assertIsDisplayed()
        composeRule.onNodeWithTag("feedback.settings.gateway")
            .assertTextEquals(app.getString(R.string.error_request_failed))
        assertEquals(
            "Gateway candidate rejection feedback must remain owned by the Sheet.",
            hostErrorPresentationBaseline,
            QualityRuntime.globalErrorPresentationCount(),
        )
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(io.ethan.pushgo.data.AppConstants.defaultServerAddress)

        pressBack()
        composeRule.waitForIdle()
        pressBack()
        waitForTagToDisappear("sheet.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(io.ethan.pushgo.data.AppConstants.defaultServerAddress)
            .performClick()
        val retryAddressField = composeRule.onNodeWithTag("field.settings.gateway.address")
        retryAddressField.assertTextContains(io.ethan.pushgo.data.AppConstants.defaultServerAddress)
        val retryTokenField = composeRule.onNodeWithTag("field.settings.gateway.token")
        retryTokenField
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
            .performTextInput(gatewayToken)
        retryAddressField.performTextClearance()
        retryAddressField.performTextInput("$normalizedAddress/")
        composeRule.onNodeWithTag("action.settings.gateway.save").performClick()
        waitForTagToDisappear("sheet.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(normalizedAddress)

        leaveSettings()
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000001")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("action.channels.add").performClick()
        composeRule.onNodeWithTag("sheet.channels.entry").assertIsDisplayed()
        composeRule.onNodeWithTag("field.channels.create.name")
            .performTextInput("New Gateway Channel")
        composeRule.onNodeWithTag("field.channels.create.password")
            .performTextInput(listOf("quality", "x").joinToString(""))
        composeRule.onNodeWithTag("action.channels.entry.submit")
            .assertIsEnabled()
            .performClick()
        waitForTag("channel.row.01H00000000000000000000003")
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertTextContains("New Gateway Channel")

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithTag("nav.item.channels").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.channels.list").assertIsDisplayed()
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000001")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertIsDisplayed()
            .assertTextContains("New Gateway Channel")
        openSettings()
        scrollTo("row.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(normalizedAddress)
        composeRule.onNodeWithTag("row.settings.gateway").performClick()
        composeRule.onNodeWithTag("field.settings.gateway.address")
            .assertTextContains(normalizedAddress)
        val restoredTokenField = composeRule.onNodeWithTag("field.settings.gateway.token")
        restoredTokenField.assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
        composeRule.onNodeWithTag("action.settings.gateway.token.toggle_visibility")
            .performClick()
        restoredTokenField.assertTextContains(gatewayToken)
    }

    @Test
    fun gatewayLocalCommitFailureRollsBackBeforeRetryCommits() {
        configureAndLaunch(
            fixture = QualityFixture.CHANNELS_STANDARD,
            faults = QualityFaults(failGatewaySwitchCommitOnce = true),
            channelMutationScenario = QualityChannelMutationScenario.ACCEPTED,
        )
        openSettings()
        scrollTo("row.settings.gateway")
        val originalAddress = io.ethan.pushgo.data.AppConstants.defaultServerAddress
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(originalAddress)
            .performClick()
        val candidateAddress = "https://quality-commit.invalid/api"
        val addressField = composeRule.onNodeWithTag("field.settings.gateway.address")
        addressField.performTextClearance()
        addressField.performTextInput("$candidateAddress/")
        composeRule.onNodeWithTag("action.settings.gateway.save").performClick()

        waitForTag("feedback.settings.gateway")
        composeRule.onNodeWithTag("sheet.settings.gateway").assertIsDisplayed()
        composeRule.onNodeWithTag("row.settings.gateway").assertTextContains(originalAddress)

        relaunchCurrentQualitySessionWithFaults()
        openSettings()
        scrollTo("row.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(originalAddress)
            .performClick()
        val retryField = composeRule.onNodeWithTag("field.settings.gateway.address")
        retryField.assertTextContains(originalAddress)
        retryField.performTextClearance()
        retryField.performTextInput("$candidateAddress/")
        composeRule.onNodeWithTag("action.settings.gateway.save").performClick()
        waitForTagToDisappear("sheet.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway").assertTextContains(candidateAddress)

        scenario?.close()
        scenario = launchMainActivity()
        openSettings()
        scrollTo("row.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway").assertTextContains(candidateAddress)
    }

    @Test
    fun gatewaySyncFailureReportsCommittedGatewayAndPendingRecovery() {
        val candidateAddress = "https://quality-sync-pending.invalid/api"
        configureAndLaunch(
            fixture = QualityFixture.CHANNELS_STANDARD,
            faults = QualityFaults(failGatewayPostCommitSyncOnce = true),
            channelMutationScenario = QualityChannelMutationScenario.ACCEPTED,
            expectedChannelMutationGatewayUrl = candidateAddress,
        )
        openSettings()
        scrollTo("row.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(io.ethan.pushgo.data.AppConstants.defaultServerAddress)
            .performClick()
        val addressField = composeRule.onNodeWithTag("field.settings.gateway.address")
        addressField.performTextClearance()
        addressField.performTextInput("$candidateAddress/")
        composeRule.onNodeWithTag("action.settings.gateway.save").performClick()

        // A transient Android Toast is a system surface and its text is not
        // reliably exposed to UiAutomator on every supported API level. The
        // user-purpose contract is durable: the committed candidate remains
        // active, a recovery marker is persisted, and a normal relaunch later
        // reconciles it (asserted below with exact channel data).
        composeRule.waitUntil(timeoutMillis = 4_000) {
            app.container.settingsRepository.getGatewayRecoveryPending()
        }
        waitForTagToDisappear("sheet.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(candidateAddress)

        relaunchCurrentQualitySessionWithFaults()
        composeRule.onNodeWithTag("nav.item.channels").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.channels.list").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            app.container.settingsRepository.getGatewayRecoveryPending().not()
        }
        assertEquals(
            "A successful Channels-entry reconciliation must clear the durable marker.",
            false,
            app.container.settingsRepository.getGatewayRecoveryPending(),
        )
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000004")
            .assertTextContains("Quality Recovery Sync Completed")
        composeRule.onNodeWithTag("action.channels.add").performClick()
        composeRule.onNodeWithTag("sheet.channels.entry").assertIsDisplayed()
        composeRule.onNodeWithTag("field.channels.create.name")
            .performTextInput("Recovered Gateway Channel")
        composeRule.onNodeWithTag("field.channels.create.password")
            .performTextInput("quality-recovered")
        composeRule.onNodeWithTag("action.channels.entry.submit").performClick()
        waitForTag("channel.row.01H00000000000000000000003")
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertTextContains("Recovered Gateway Channel")
        openSettings()
        scrollTo("row.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(candidateAddress)
    }

    @Test
    fun decryptionRejectsInvalidKeyPersistsAndClearsValidKeyWithoutEchoingSecret() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_STANDARD)
        openSettings()
        scrollTo("row.settings.decryption")
        composeRule.onNodeWithTag("row.settings.decryption").performClick()
        composeRule.onNodeWithTag("sheet.settings.decryption").assertIsDisplayed()

        val keyField = composeRule.onNodeWithTag("field.settings.decryption.key")
        keyField.performTextInput("short")
        composeRule.onNodeWithTag("action.settings.decryption.save").performClick()
        waitForTag("feedback.settings.decryption")
        composeRule.onNodeWithTag("sheet.settings.decryption").assertIsDisplayed()

        val validKey = Base64.getEncoder().encodeToString(ByteArray(32) { 0x2A })
        keyField.performTextClearance()
        keyField.performTextInput(validKey)
        composeRule.onNodeWithTag("action.settings.decryption.save").performClick()
        waitForTagToDisappear("sheet.settings.decryption")
        scrollTo("row.settings.decryption")
        composeRule.onNodeWithTag("row.settings.decryption")
            .assertTextContains(app.getString(io.ethan.pushgo.R.string.label_decryption_configured))

        scenario?.close()
        scenario = launchMainActivity()
        openSettings()
        scrollTo("row.settings.decryption")
        composeRule.onNodeWithTag("row.settings.decryption")
            .assertTextContains(app.getString(io.ethan.pushgo.R.string.label_decryption_configured))
        composeRule.onNodeWithTag("row.settings.decryption").performClick()
        composeRule.onNodeWithTag("field.settings.decryption.key")
            .assert(!hasText(validKey, substring = true))
        composeRule.onNodeWithTag("action.settings.decryption.clear").performClick()
        waitForTagToDisappear("sheet.settings.decryption")
        scrollTo("row.settings.decryption")
        composeRule.onNodeWithTag("row.settings.decryption")
            .assertTextContains(app.getString(io.ethan.pushgo.R.string.label_decryption_not_configured))

        scenario?.close()
        scenario = launchMainActivity()
        openSettings()
        scrollTo("row.settings.decryption")
        composeRule.onNodeWithTag("row.settings.decryption")
            .assertTextContains(app.getString(io.ethan.pushgo.R.string.label_decryption_not_configured))
    }

    @Test
    fun protectedKeyPersistenceFailureDoesNotConfigureBeforeRetry() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(failNotificationKeyPersistenceOnce = true),
        )
        openSettings()
        scrollTo("row.settings.decryption")
        composeRule.onNodeWithTag("row.settings.decryption")
            .assertTextContains(app.getString(io.ethan.pushgo.R.string.label_decryption_not_configured))
            .performClick()
        val validKey = Base64.getEncoder().encodeToString(ByteArray(32) { 0x35 })
        composeRule.onNodeWithTag("field.settings.decryption.key").performTextInput(validKey)
        composeRule.onNodeWithTag("action.settings.decryption.save").performClick()

        waitForTag("feedback.settings.decryption")
        composeRule.onNodeWithTag("sheet.settings.decryption").assertIsDisplayed()

        relaunchCurrentQualitySessionWithFaults()
        openSettings()
        scrollTo("row.settings.decryption")
        composeRule.onNodeWithTag("row.settings.decryption")
            .assertTextContains(app.getString(io.ethan.pushgo.R.string.label_decryption_not_configured))
            .performClick()
        composeRule.onNodeWithTag("field.settings.decryption.key").performTextInput(validKey)
        composeRule.onNodeWithTag("action.settings.decryption.save").performClick()
        waitForTagToDisappear("sheet.settings.decryption")
        composeRule.onNodeWithTag("row.settings.decryption")
            .assertTextContains(app.getString(io.ethan.pushgo.R.string.label_decryption_configured))

        scenario?.close()
        scenario = launchMainActivity()
        openSettings()
        scrollTo("row.settings.decryption")
        composeRule.onNodeWithTag("row.settings.decryption")
            .assertTextContains(app.getString(io.ethan.pushgo.R.string.label_decryption_configured))
    }

    @Test
    fun transportRejectionsPreserveActiveRouteUntilRetryAndPersistAfterRelaunch() {
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            transportSwitchScenario =
                QualityTransportSwitchScenario.REJECT_ONCE_THEN_ACCEPTED,
        )
        openSettings()
        scrollTo("row.settings.notification_transport")

        val fcmOption = composeRule.onNodeWithTag("option.settings.notification_transport.fcm")
        val privateOption =
            composeRule.onNodeWithTag("option.settings.notification_transport.private")
        fcmOption.assertIsSelected()
        privateOption.assertIsNotSelected().performClick()

        waitForTag("feedback.settings.notification_transport")
        composeRule.onNodeWithTag("feedback.settings.notification_transport")
            .assertTextEquals(
                app.getString(io.ethan.pushgo.R.string.error_notification_transport_switch_failed)
            )
        fcmOption.assertIsSelected()
        privateOption.assertIsNotSelected().assertIsEnabled()
        composeRule.onNodeWithTag("dialog.settings.private_transport_whitelist")
            .assertDoesNotExist()

        privateOption.performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(
                hasTestTag("option.settings.notification_transport.private") and isSelected()
            ).fetchSemanticsNodes().isNotEmpty()
        }
        privateOption.assertIsSelected()
        fcmOption.assertIsNotSelected()
        composeRule.onNodeWithTag("feedback.settings.notification_transport")
            .assertDoesNotExist()
        waitForTag("dialog.settings.private_transport_whitelist")
        composeRule.onNodeWithTag("action.settings.private_transport_whitelist.dismiss")
            .performClick()
        waitForTagToDisappear("dialog.settings.private_transport_whitelist")
        scrollTo("row.settings.private_transport")

        scenario?.close()
        scenario = launchMainActivity()
        openSettings()
        scrollTo("row.settings.notification_transport")
        composeRule.onNodeWithTag("option.settings.notification_transport.private")
            .assertIsSelected()
        composeRule.onNodeWithTag("option.settings.notification_transport.fcm")
            .assertIsNotSelected()
        scrollTo("row.settings.private_transport")

        scrollTo("row.settings.notification_transport")
        val relaunchedFcmOption =
            composeRule.onNodeWithTag("option.settings.notification_transport.fcm")
        val relaunchedPrivateOption =
            composeRule.onNodeWithTag("option.settings.notification_transport.private")
        val privateModeTokenBeforeFcmAttempt = runBlocking {
            app.container.settingsRepository.getFcmToken()
        }
        val privateModeDeviceKeyBeforeFcmAttempt = runBlocking {
            app.container.settingsRepository.getDeviceKey()
        }
        relaunchedFcmOption.performClick()
        waitForTag("feedback.settings.notification_transport")
        composeRule.onNodeWithTag("feedback.settings.notification_transport")
            .assertTextEquals(
                app.getString(
                    io.ethan.pushgo.R.string.error_notification_transport_fcm_switch_failed
                )
        )
        relaunchedPrivateOption.assertIsSelected()
        relaunchedFcmOption.assertIsNotSelected().assertIsEnabled()
        assertEquals(
            privateModeTokenBeforeFcmAttempt,
            runBlocking { app.container.settingsRepository.getFcmToken() },
        )
        assertEquals(
            privateModeDeviceKeyBeforeFcmAttempt,
            runBlocking { app.container.settingsRepository.getDeviceKey() },
        )
        relaunchedFcmOption.performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(
                hasTestTag("option.settings.notification_transport.fcm") and isSelected()
            ).fetchSemanticsNodes().isNotEmpty()
        }
        relaunchedFcmOption.assertIsSelected()
        relaunchedPrivateOption.assertIsNotSelected()
        composeRule.onNodeWithTag("feedback.settings.notification_transport")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("row.settings.private_transport").assertDoesNotExist()

        scenario?.close()
        scenario = launchMainActivity()
        openSettings()
        scrollTo("row.settings.notification_transport")
        composeRule.onNodeWithTag("option.settings.notification_transport.fcm")
            .assertIsSelected()
        composeRule.onNodeWithTag("option.settings.notification_transport.private")
            .assertIsNotSelected()
        composeRule.onNodeWithTag("row.settings.private_transport").assertDoesNotExist()
    }

    @Test
    fun privateTransportLocalCommitFailureRollsBackBeforeRetryCommits() {
        configureAndLaunch(
            fixture = QualityFixture.CHANNELS_STANDARD,
            faults = QualityFaults(failTransportSelectionPersistenceOnce = true),
            transportSwitchScenario = QualityTransportSwitchScenario.ACCEPTED,
        )
        openSettings()
        scrollTo("row.settings.notification_transport")
        val originalToken = runBlocking {
            app.container.settingsRepository.getFcmToken()
        }
        composeRule.onNodeWithTag("option.settings.notification_transport.fcm")
            .assertIsSelected()
        composeRule.onNodeWithTag("option.settings.notification_transport.private")
            .assertIsNotSelected()
            .performClick()

        waitForTag("feedback.settings.notification_transport")
        composeRule.onNodeWithTag("feedback.settings.notification_transport")
            .assertTextEquals(
                app.getString(io.ethan.pushgo.R.string.error_notification_transport_switch_failed)
            )
        composeRule.onNodeWithTag("option.settings.notification_transport.fcm")
            .assertIsSelected()
        composeRule.onNodeWithTag("option.settings.notification_transport.private")
            .assertIsNotSelected()
        composeRule.onNodeWithTag("dialog.settings.private_transport_whitelist")
            .assertDoesNotExist()
        assertEquals(
            originalToken,
            runBlocking { app.container.settingsRepository.getFcmToken() },
        )

        // This is an Activity relaunch recovery check. Keep the controlled remote transition
        // endpoint alive; process/container recovery is a separate native acceptance boundary.
        relaunchCurrentQualitySessionWithFaults(reopenStorage = false)
        openSettings()
        scrollTo("row.settings.notification_transport")
        composeRule.onNodeWithTag("option.settings.notification_transport.fcm")
            .assertIsSelected()
        composeRule.onNodeWithTag("option.settings.notification_transport.private")
            .assertIsNotSelected()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(
                hasTestTag("option.settings.notification_transport.private") and isSelected()
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("option.settings.notification_transport.private")
            .assertIsSelected()
        waitForTag("dialog.settings.private_transport_whitelist")
        composeRule.onNodeWithTag("action.settings.private_transport_whitelist.dismiss")
            .performClick()

        scenario?.close()
        scenario = launchMainActivity()
        openSettings()
        scrollTo("row.settings.notification_transport")
        composeRule.onNodeWithTag("option.settings.notification_transport.private")
            .assertIsSelected()
        composeRule.onNodeWithTag("option.settings.notification_transport.fcm")
            .assertIsNotSelected()
    }

    private fun openPageVisibilitySettings() {
        openSettings()
        composeRule.onNodeWithTag("screen.settings.content")
            .assertIsDisplayed()
            .performScrollToNode(hasTestTag("switch.settings.page.things"))
        composeRule.onNodeWithTag("switch.settings.page.events").assertIsDisplayed()
        composeRule.onNodeWithTag("switch.settings.page.things").assertIsDisplayed()
    }

    private fun leaveSettings() {
        scenario?.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithTag("screen.channels.list").assertIsDisplayed()
    }

    private fun assertDataDestinationsCanOpen() {
        composeRule.onNodeWithTag("nav.item.events").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.events.list").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.label_no_events_title)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.label_no_events_hint)).assertIsDisplayed()
        composeRule.onNodeWithTag("nav.item.things").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.things.list").assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.label_no_things_title)).assertIsDisplayed()
        composeRule.onNodeWithText(app.getString(R.string.label_no_things_hint)).assertIsDisplayed()
    }

    private fun openSettings() {
        composeRule.onNodeWithTag("nav.item.channels").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("action.channels.settings").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.settings").assertIsDisplayed()
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag("screen.settings.content")
            .assertIsDisplayed()
            .performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun waitForTag(tag: String, timeoutMillis: Long = 8_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun waitForTagToDisappear(tag: String, timeoutMillis: Long = 8_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(tag).assertDoesNotExist()
    }
}
