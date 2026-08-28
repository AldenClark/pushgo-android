package io.ethan.pushgo.testing

import android.app.LocaleManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.os.LocaleListCompat
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QualityAccessibilityLocalizationJourneyInstrumentedTest : QualityAppJourneyTestCase() {
    private var originalLocaleTags: String? = null
    private var originalCompatLocales: LocaleListCompat? = null
    private var originalFontScale: String? = null

    @After
    fun restoreUserDisplayConfiguration() {
        scenario?.close()
        scenario = null
        originalCompatLocales?.let { locales ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                AppCompatDelegate.setApplicationLocales(locales)
            }
        }
        val localeTags = originalLocaleTags
        val fontScale = originalFontScale
        localeTags?.let { tags ->
            val localeArgument = if (tags.isEmpty()) "" else " --locales $tags"
            shell("cmd locale set-app-locales io.ethan.pushgo --user 0$localeArgument")
        }
        fontScale?.let { scale -> shell("settings put system font_scale $scale") }

        localeTags?.let { tags ->
            assertEquals(
                "The test must restore the app locale instead of contaminating later journeys",
                tags,
                appLocaleTags(),
            )
        }
        fontScale?.let { scale ->
            assertEquals(
                "The test must restore the emulator font scale instead of contaminating later journeys",
                scale.toFloat(),
                shell("settings get system font_scale").trim().toFloat(),
                0.001f,
            )
        }
    }

    @Test
    fun simplifiedChineseAtLargeFontCompletesMessageDetailAndAddChannelJourney() {
        assertTrue("This quality journey requires Android 13+ per lane preflight", Build.VERSION.SDK_INT >= 33)
        originalLocaleTags = appLocaleTags()
        originalCompatLocales = AppCompatDelegate.getApplicationLocales()
        originalFontScale = shell("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null }
        assertTrue("Unable to capture the current emulator font scale", originalFontScale != null)

        shell("cmd locale set-app-locales io.ethan.pushgo --user 0 --locales zh-CN")
        val localeManager = app.getSystemService(LocaleManager::class.java)
        val deadline = System.currentTimeMillis() + 5_000
        while (localeManager.applicationLocales.toLanguageTags() != "zh-CN" && System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.yield()
        }
        assertEquals("zh-CN", localeManager.applicationLocales.toLanguageTags())
        shell("settings put system font_scale 1.5")
        configureAndLaunch(
            fixture = QualityFixture.MESSAGES_STANDARD,
            channelMutationScenario = QualityChannelMutationScenario.ACCEPTED,
        )
        scenario?.onActivity {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            var language = ""
            scenario?.onActivity { activity ->
                language = activity.resources.configuration.locales[0].language
            }
            language == "zh"
        }

        scenario?.onActivity { activity ->
            assertEquals("zh", activity.resources.configuration.locales[0].language)
            assertTrue(
                "The real Activity must render at the requested large font scale",
                activity.resources.configuration.fontScale >= 1.49f,
            )
        }
        composeRule.onNodeWithTag("nav.item.messages")
            .assertTextEquals("消息")
            .assertIsDisplayed()
        composeRule.onNodeWithText("P2 Split Seed Message").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertIsDisplayed()
            .assertTextContains("Seeded from fixture.seed_messages for UI validation.")

        pressBack()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag("sheet.message.detail")).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("nav.item.channels")
            .assertTextEquals("频道")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("screen.channels.list").assertIsDisplayed()
        composeRule.onNodeWithTag("action.channels.add")
            .assert(hasContentDescription("添加频道"))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("sheet.channels.entry").assertIsDisplayed()
        composeRule.onNodeWithText("添加频道").assertIsDisplayed()
        val channelName = composeRule.onNodeWithTag("field.channels.create.name")
        channelName
            .assertIsDisplayed()
            .performTextInput("大字体测试频道")
        channelName.assertTextContains("大字体测试频道")
        val channelPassword = composeRule.onNodeWithTag("field.channels.create.password")
        channelPassword
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
            .performTextInput("quality-channel-password")
        channelPassword.assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
        composeRule.onNodeWithTag("action.channels.entry.submit")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            val rowExists = composeRule
                .onAllNodes(hasTestTag("channel.row.01H00000000000000000000003"))
                .fetchSemanticsNodes().isNotEmpty()
            val failureExists = composeRule
                .onAllNodes(hasTestTag("feedback.channels.entry"))
                .fetchSemanticsNodes().isNotEmpty()
            rowExists || failureExists
        }
        composeRule.onNodeWithTag("feedback.channels.entry").assertDoesNotExist()
        composeRule.onNodeWithTag("channel.row.01H00000000000000000000003")
            .assertIsDisplayed()
            .assertTextContains("大字体测试频道")
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        }
    }

    private fun appLocaleTags(): String {
        val output = shell("cmd locale get-app-locales io.ethan.pushgo --user 0").trim()
        return output.substringAfterLast('[', missingDelimiterValue = "")
            .substringBefore(']')
            .trim()
    }
}
