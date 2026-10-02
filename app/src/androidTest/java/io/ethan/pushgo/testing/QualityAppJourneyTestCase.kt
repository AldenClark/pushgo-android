package io.ethan.pushgo.testing

import android.content.Intent
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.automation.PushGoAutomation
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule

abstract class QualityAppJourneyTestCase {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    protected val app: PushGoApp
        get() = ApplicationProvider.getApplicationContext()
    protected var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDownQualitySession() {
        scenario?.close()
        scenario = null
        val session = QualityRuntime.currentSession()
        val databaseName = session?.databaseName
        val sessionRoot = QualityRuntime.sessionRoot(app)
        app.releaseStorageForInstrumentationTest()
        if (databaseName != null) {
            app.deleteDatabase(databaseName)
        }
        session?.let {
            app.deleteSharedPreferences(it.securePreferencesName)
            app.deleteSharedPreferences(it.settingsCachePreferencesName)
        }
        sessionRoot?.deleteRecursively()
        QualityRuntime.configure(null)
    }

    protected fun configureAndLaunch(
        fixture: QualityFixture,
        faults: QualityFaults = QualityFaults(),
        messageRefreshScenario: QualityMessageRefreshScenario = QualityMessageRefreshScenario.NONE,
        eventCloseScenario: QualityEventCloseScenario = QualityEventCloseScenario.NONE,
        channelMutationScenario: QualityChannelMutationScenario = QualityChannelMutationScenario.NONE,
        expectedChannelMutationGatewayUrl: String? = null,
        expectedGatewayPreparationUrl: String? = null,
        transportSwitchScenario: QualityTransportSwitchScenario = QualityTransportSwitchScenario.NONE,
        updateScenario: QualityUpdateScenario = QualityUpdateScenario.NONE,
        systemCapabilities: Set<QualitySystemCapability> = emptySet(),
        awaitRuntimeReady: Boolean = true,
    ) {
        val current = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "android-ui-${System.nanoTime()}",
            fixture = fixture,
            faults = faults,
            messageRefreshScenario = messageRefreshScenario,
            eventCloseScenario = eventCloseScenario,
            channelMutationScenario = channelMutationScenario,
            expectedChannelMutationGatewayUrl = expectedChannelMutationGatewayUrl,
            expectedGatewayPreparationUrl = expectedGatewayPreparationUrl,
            transportSwitchScenario = transportSwitchScenario,
            updateScenario = updateScenario,
            systemCapabilities = systemCapabilities,
        )
        app.releaseStorageForInstrumentationTest()
        assertTrue(app.deleteDatabase(current.databaseName) || !app.getDatabasePath(current.databaseName).exists())
        QualityRuntime.configure(QualityRuntime.encode(current))
        scenario = launchMainActivity()
        if (awaitRuntimeReady) {
            awaitQualityRuntimeReady()
        }
    }

    protected fun launchMainActivity(
        configureIntent: Intent.() -> Unit = {},
    ): ActivityScenario<MainActivity> {
        val intent = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            configureIntent()
        }
        return ActivityScenario.launch(intent)
    }

    private fun awaitQualityRuntimeReady() {
        composeRule.waitUntil(timeoutMillis = 8_000) {
            check(app.startupStorageErrorMessage() == null) {
                "The app-owned quality fixture could not open persistent storage: " +
                    app.startupStorageErrorMessage()
            }
            check(PushGoAutomation.currentRuntimeErrorCount() == 0) {
                "The app-owned quality runtime reported fixture preparation failure"
            }
            QualityRuntime.fixtureInitializationWasRecorded(app.filesDir)
        }
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onNodeWithTag("nav.bottom").isDisplayed()
        }
    }

    protected fun relaunchCurrentQualitySessionWithFaults(
        faults: QualityFaults = QualityFaults(),
        reopenStorage: Boolean = true,
    ) {
        val current = checkNotNull(QualityRuntime.currentSession())
        scenario?.close()
        if (reopenStorage) {
            app.releaseStorageForInstrumentationTest()
        }
        QualityRuntime.configure(QualityRuntime.encode(current.copy(faults = faults)))
        scenario = launchMainActivity()
    }
}
