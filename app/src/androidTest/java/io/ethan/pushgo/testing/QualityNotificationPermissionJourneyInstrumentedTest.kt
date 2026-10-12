package io.ethan.pushgo.testing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantic verifier for the host-driven real Android permission journey. */
@RunWith(AndroidJUnit4::class)
class QualityNotificationPermissionJourneyInstrumentedTest {
    @get:Rule
    val composeRule = androidx.compose.ui.test.junit4.createEmptyComposeRule()

    private val app: PushGoApp
        get() = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun closeActivityWithoutClearingTheHostOwnedSession() {
        scenario?.close()
        scenario = null
    }

    @Test
    fun enabledSystemDecisionRefreshesTheRealAppAndRemovesDisabledDeliveryState() {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "QUALITY_PRECONDITION notification permission journey requires Android 13 or newer"
        }
        val session = checkNotNull(QualityRuntime.currentSession()) {
            "QUALITY_PRECONDITION App-owned notification permission session was not restored"
        }
        assertTrue(
            session.systemCapabilities.contains(
                QualitySystemCapability.NOTIFICATION_PERMISSION_JOURNEY,
            ),
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS),
        )
        assertTrue(NotificationManagerCompat.from(app).areNotificationsEnabled())

        scenario = ActivityScenario.launch(
            android.content.Intent(app, MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("nav.item.channels")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(app.getString(R.string.label_enable_notifications))
            .assertDoesNotExist()
        composeRule.onNodeWithTag("nav.item.channels").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("action.channels.settings").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.settings").assertIsDisplayed()
        composeRule.onNodeWithTag("banner.settings.notifications_disabled").assertDoesNotExist()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        composeRule.onNodeWithTag("screen.settings.content")
            .assertIsDisplayed()
            .performScrollToNode(hasTestTag("row.settings.system_notification_settings"))
        composeRule.onNodeWithTag("row.settings.system_notification_settings")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertTrue(
            "The persistent Settings entry must open Android's notification settings.",
            device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 8_000),
        )
        assertTrue(
            "Android notification settings must target PushGo, not a generic or different app page.",
            device.wait(
                Until.hasObject(
                    By.res("com.android.settings", "switch_text")
                        .textContains(app.getString(R.string.app_name)),
                ),
                3_000,
            ),
        )
        assertTrue(
            "The PushGo notification settings page must expose the real notification switch.",
            device.hasObject(By.res("com.android.settings", "main_switch_bar")),
        )
        device.pressBack()
        assertTrue(
            "Returning from Android notification settings must resume PushGo.",
            device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), 8_000),
        )
        composeRule.onNodeWithTag("screen.settings").assertIsDisplayed()
        composeRule.onNodeWithTag("banner.settings.notifications_disabled").assertDoesNotExist()
    }
}
