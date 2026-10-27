package io.ethan.pushgo.testing

import android.app.Notification
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.ethan.pushgo.R
import io.ethan.pushgo.notifications.PrivateChannelForegroundService
import io.ethan.pushgo.notifications.PrivateChannelServiceManager
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Positive system-boundary journey for the Private transport keepalive.
 *
 * The App-owned session deliberately blocks the real private network loop. Existing fake-native
 * integration owns connect/reconnect/ACK correctness; this journey owns the production Settings
 * action, durable selection, foreground Service, system notification/PendingIntent, and stop path.
 */
@RunWith(AndroidJUnit4::class)
class QualityPrivateForegroundServiceJourneyInstrumentedTest : QualityAppJourneyTestCase() {
    private val notificationManager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    @After
    fun stopPrivateService() {
        PrivateChannelServiceManager.stopNow(app)
        notificationManager.cancel(PrivateChannelForegroundService.NOTIFICATION_ID)
    }

    @Test
    fun privateSelectionStartsDurableSystemServiceAndFcmSelectionStopsIt() = runBlocking {
        configureAndLaunch(
            fixture = QualityFixture.EMPTY_CLEAN,
            transportSwitchScenario = QualityTransportSwitchScenario.ACCEPTED,
            systemCapabilities = setOf(QualitySystemCapability.PRIVATE_FOREGROUND_SERVICE),
        )
        NotificationPermissionTestSupport.requireEnabled(app)
        notificationManager.cancel(PrivateChannelForegroundService.NOTIFICATION_ID)

        openTransportSettings()
        val fcmOption = composeRule.onNodeWithTag("option.settings.notification_transport.fcm")
        val privateOption = composeRule.onNodeWithTag("option.settings.notification_transport.private")
        fcmOption.assertIsSelected()
        privateOption.assertIsNotSelected().performClick()
        try {
            composeRule.waitUntil(timeoutMillis = 8_000) {
                composeRule.onAllNodes(
                    hasTestTag("option.settings.notification_transport.private") and isSelected(),
                ).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            runCatching {
                val container = app.container
                QualityUiFailureDiagnostics.logText("FGS private selection wait",
                    "session=${QualityRuntime.currentSession()?.sessionId} " +
                        "expectedDB=${QualityRuntime.currentSession()?.databaseName} actualDB=${container.database.openHelper.databaseName} " +
                        "localUseFcm=${container.settingsRepository.getUseFcmChannel()} " +
                        "tokenPresent=${!container.settingsRepository.getFcmToken().isNullOrBlank()} " +
                        "lnpGranted=${io.ethan.pushgo.util.LocalNetworkPermission.isGranted(app)} " +
                        "${container.qualityTransportWriterObservation?.snapshot()} ${container.transportSwitchCoordinator.diagnosticLockState()}")
                QualityUiFailureDiagnostics.logText("FGS actual transport feedback",
                    composeRule.onAllNodes(hasTestTag("feedback.settings.notification_transport"))
                        .fetchSemanticsNodes().joinToString("\n") { it.config.toString() })
            }.onFailure { QualityUiFailureDiagnostics.logText("FGS selection observation unavailable", it.javaClass.simpleName) }
            throw failure
        }
        privateOption.assertIsSelected()
        fcmOption.assertIsNotSelected()
        awaitTag("dialog.settings.private_transport_whitelist")
        composeRule.onNodeWithTag("action.settings.private_transport_whitelist.dismiss")
            .performClick()

        assertFalse(app.container.settingsRepository.getUseFcmChannel())
        val serviceNotification = awaitPrivateServiceNotification(expected = true)
        assertEquals(
            app.getString(R.string.private_channel_service_notification_title),
            serviceNotification?.notification?.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString(),
        )
        val systemStatusText = serviceNotification?.notification?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            ?.trim()
        assertTrue("Private Service notification status was empty", !systemStatusText.isNullOrEmpty())
        assertTrue("Private notification was not a promoted foreground Service",
            checkNotNull(serviceNotification).notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        composeRule.onNodeWithTag("screen.settings.content")
            .performScrollToNode(hasTestTag("row.settings.private_transport"))
        composeRule.onNodeWithTag("row.settings.private_transport")
            .assertIsDisplayed()
            .assertTextContains(checkNotNull(systemStatusText))
        awaitServiceRegistration(expected = true)
        // Existing instances also owe foreground promotion for every new start request.
        PrivateChannelServiceManager.refreshForMode(app, useFcmChannel = false)
        PrivateChannelServiceManager.refreshForMode(app, useFcmChannel = false)
        assertTrue("Repeated Private starts lost foreground promotion",
            checkNotNull(awaitPrivateServiceNotification(expected = true)).notification.flags and
                Notification.FLAG_FOREGROUND_SERVICE != 0)
        awaitServiceRegistration(expected = true)

        scenario?.close()
        scenario = null
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        assertTrue(
            "Private foreground Service notification did not survive leaving PushGo",
            awaitPrivateServiceNotification(expected = true) != null,
        )
        device.openNotification()
        val title = app.getString(R.string.private_channel_service_notification_title)
        try {
            assertTrue(
                "System shade did not expose the exact Private Service notification title",
                device.wait(Until.hasObject(By.text(title)), 8_000),
            )
        } catch (failure: Throwable) {
            runCatching {
                QualityUiFailureDiagnostics.logText("FGS shade notification state",
                    "pnGranted=${androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED} " +
                        "serviceRegistered=${shell("dumpsys activity services ${app.packageName}").contains("PrivateChannelForegroundService")} " +
                        "privateSelected=${!app.container.settingsRepository.getUseFcmChannel()} " +
                        "records=" + notificationManager.activeNotifications.filter {
                            it.id == PrivateChannelForegroundService.NOTIFICATION_ID
                        }.joinToString { record ->
                            "id=${record.id} flags=${record.notification.flags} " +
                                "titleMatches=${record.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title}"
                        })
                val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                QualityUiFailureDiagnostics.logWindowState(device, "FGS shade title missing",
                    output?.let { File(it, "fgs-shade-title-window.xml") })
            }.onFailure { QualityUiFailureDiagnostics.logText("FGS shade observation unavailable", it.javaClass.simpleName) }
            throw failure
        }
        device.findObject(By.text(title)).click()
        assertTrue(
            "Private Service PendingIntent did not return to PushGo",
            device.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), 8_000),
        )

        scenario = launchMainActivity()
        openTransportSettings()
        val relaunchedFcm = composeRule.onNodeWithTag("option.settings.notification_transport.fcm")
        val relaunchedPrivate =
            composeRule.onNodeWithTag("option.settings.notification_transport.private")
        relaunchedPrivate.assertIsSelected()
        relaunchedFcm.assertIsNotSelected().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(
                hasTestTag("option.settings.notification_transport.fcm") and isSelected(),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        relaunchedFcm.assertIsSelected()
        relaunchedPrivate.assertIsNotSelected()
        // The selected semantics alone do not prove the asynchronous durable transition finished.
        // Poll the real repository; a missing commit still fails this bounded acceptance.
        var committed = false
        repeat(160) {
            if (!committed) {
                committed = app.container.settingsRepository.getUseFcmChannel()
                if (!committed) delay(50)
            }
        }
        assertTrue("FCM selection did not commit to durable settings", committed)
        assertTrue(app.container.settingsRepository.getUseFcmChannel())
        awaitPrivateServiceNotification(expected = false)
        awaitServiceRegistration(expected = false)
    }

    private fun openTransportSettings() {
        composeRule.onNodeWithTag("nav.item.channels").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("action.channels.settings").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("screen.settings").assertIsDisplayed()
        composeRule.onNodeWithTag("screen.settings.content")
            .assertIsDisplayed()
            .performScrollToNode(hasTestTag("row.settings.notification_transport"))
        composeRule.onNodeWithTag("row.settings.notification_transport").assertIsDisplayed()
    }

    private fun awaitTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private suspend fun awaitPrivateServiceNotification(
        expected: Boolean,
    ): android.service.notification.StatusBarNotification? {
        repeat(160) {
            val match = notificationManager.activeNotifications.firstOrNull { status ->
                status.id == PrivateChannelForegroundService.NOTIFICATION_ID
            }
            if ((match != null) == expected) return match
            delay(50)
        }
        throw AssertionError("Private Service notification presence did not become $expected")
    }

    private suspend fun awaitServiceRegistration(expected: Boolean) {
        repeat(160) {
            val registered = shell("dumpsys activity services ${app.packageName}")
                .contains("PrivateChannelForegroundService")
            if (registered == expected) return
            delay(50)
        }
        throw AssertionError("System Service registration did not become $expected")
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        }
    }
}
