package io.ethan.pushgo.testing

import androidx.compose.ui.test.*
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Base64
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualitySettingsJourneyInstrumentedTest : QualityAppJourneyTestCase() {
    @Test
    fun encryptedMessageRecoversThroughRealSettingsEntryAndSurvivesRelaunch() {
        configureAndLaunch(fixture = QualityFixture.MESSAGES_ENCRYPTED_VALID)

        composeRule.onNodeWithText("Encrypted Quality Message")
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
        composeRule.onNodeWithTag("action.settings.decryption.save")
            .assertIsDisplayed()
            .performClick()
        waitForTagToDisappear("screen.settings.decryption")
        composeRule.onNodeWithText("Encrypted Quality Message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals("Configure decryption to read this message.")
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

        composeRule.onNodeWithText("Corrupt Encrypted Message")
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
        composeRule.onNodeWithText("Corrupt Encrypted Message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals("Configure decryption to read this message.")
        composeRule.onNodeWithTag("status.message.decryption.decrypt_failed")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Recovered Quality Message").assertDoesNotExist()

        scenario?.close()
        scenario = launchMainActivity()
        composeRule.onNodeWithText("Corrupt Encrypted Message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertTextEquals("Configure decryption to read this message.")
        composeRule.onNodeWithTag("status.message.decryption.decrypt_failed")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Recovered Quality Message").assertDoesNotExist()
    }

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

    @Test
    fun serverConfigurationRejectsInvalidInputAndScopesDataAfterRelaunch() {
        configureAndLaunch(
            fixture = QualityFixture.CHANNELS_STANDARD,
            faults = QualityFaults(failGatewaySwitchValidationOnce = true),
            channelMutationScenario = QualityChannelMutationScenario.ACCEPTED,
        )
        openSettings()
        scrollTo("row.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway").performClick()
        composeRule.onNodeWithTag("sheet.settings.gateway").assertIsDisplayed()

        val addressField = composeRule.onNodeWithTag("field.settings.gateway.address")
        addressField.performTextClearance()
        addressField.performTextInput("not a valid url")
        composeRule.onNodeWithTag("action.settings.gateway.save").performClick()
        waitForTag("feedback.settings.gateway")
        composeRule.onNodeWithTag("sheet.settings.gateway").assertIsDisplayed()

        val normalizedAddress = "https://quality-settings.invalid/api"
        addressField.performTextClearance()
        addressField.performTextInput("$normalizedAddress/")
        composeRule.onNodeWithTag("action.settings.gateway.save").performClick()
        waitForTag("feedback.settings.gateway")
        composeRule.onNodeWithTag("sheet.settings.gateway").assertIsDisplayed()
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
        retryAddressField.performTextClearance()
        retryAddressField.performTextInput("$normalizedAddress/")
        composeRule.onNodeWithTag("action.settings.gateway.save").performClick()
        waitForTagToDisappear("sheet.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(normalizedAddress)

        leaveSettings()
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000001")
            .assertDoesNotExist()

        scenario?.close()
        scenario = launchMainActivity()
        openSettings()
        scrollTo("row.settings.gateway")
        composeRule.onNodeWithTag("row.settings.gateway")
            .assertTextContains(normalizedAddress)
        composeRule.onNodeWithTag("row.settings.gateway").performClick()
        composeRule.onNodeWithTag("field.settings.gateway.address")
            .assertTextContains(normalizedAddress)
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

    private fun openPageVisibilitySettings() {
        openSettings()
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
