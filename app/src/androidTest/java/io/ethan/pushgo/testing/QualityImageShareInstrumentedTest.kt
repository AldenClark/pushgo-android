package io.ethan.pushgo.testing

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class QualityImageShareInstrumentedTest : QualityAppJourneyTestCase() {
    private fun recipientShell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand("run-as io.ethan.pushgo.test $command")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readText() }
    }

    @Test
    fun systemChooserGrantsTheCanonicalImageToASeparateUidRecipient() {
        recipientShell("rm -f files/quality-image-share-result.json")
        configureAndLaunch(QualityFixture.MESSAGES_STANDARD)

        val qualitySessionId = checkNotNull(QualityRuntime.currentSession()).sessionId
        val canonicalImage = File(
            app.filesDir,
            "quality-fixtures/$qualitySessionId/standard-message.png",
        )
        val canonicalDigest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalImage.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        composeRule.onNodeWithTag("message.row.quality-standard-message")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("message.image.0")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("dialog.image.preview").assertIsDisplayed()
        composeRule.onNodeWithTag("action.dialog.image_preview.share")
            .assertIsDisplayed()
            .performClick()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(
            "The system chooser did not expose the shared image preview.",
            device.wait(Until.hasObject(By.res("com.android.intentresolver", "image")), 5_000),
        )
        var recipient = device.wait(Until.findObject(By.textContains("PushGo QA")), 3_000)
        for (attempt in 0 until 6) {
            if (recipient != null) break
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 3,
                24,
            )
            recipient = device.wait(Until.findObject(By.textContains("PushGo QA")), 1_500)
        }
        checkNotNull(recipient) { "The isolated recipient was absent from the system share chooser" }
            .click()
        var recipientResult: String? = null
        composeRule.waitUntil(timeoutMillis = 10_000) {
            recipientResult = recipientShell("cat files/quality-image-share-result.json")
                .takeIf { it.trimStart().startsWith("{") }
            recipientResult != null
        }

        val result = JSONObject(checkNotNull(recipientResult))
        assertTrue("Recipient failed to open EXTRA_STREAM: $result", !result.has("error"))
        assertNotEquals(app.applicationInfo.uid, result.getInt("receiver_uid"))
        assertTrue(result.getInt("size") > 0)
        assertEquals(canonicalDigest, result.getString("sha256"))
    }
}
