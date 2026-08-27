package io.ethan.pushgo.testing

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.PushGoApp
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
        val databaseName = QualityRuntime.currentSession()?.databaseName
        val sessionRoot = QualityRuntime.sessionRoot(app)
        app.releaseStorageForInstrumentationTest()
        if (databaseName != null) {
            app.deleteDatabase(databaseName)
        }
        sessionRoot?.deleteRecursively()
        QualityRuntime.configure(null)
    }

    protected fun configureAndLaunch(
        fixture: QualityFixture,
        faults: QualityFaults = QualityFaults(),
        messageRefreshScenario: QualityMessageRefreshScenario = QualityMessageRefreshScenario.NONE,
    ) {
        val current = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "android-ui-${System.nanoTime()}",
            fixture = fixture,
            faults = faults,
            messageRefreshScenario = messageRefreshScenario,
        )
        app.releaseStorageForInstrumentationTest()
        assertTrue(app.deleteDatabase(current.databaseName) || !app.getDatabasePath(current.databaseName).exists())
        QualityRuntime.configure(QualityRuntime.encode(current))
        scenario = launchMainActivity()
    }

    protected fun launchMainActivity(): ActivityScenario<MainActivity> {
        val intent = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return ActivityScenario.launch(intent)
    }
}
