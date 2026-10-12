package io.ethan.pushgo.macrobenchmark

import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushGoMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val instrumentationArguments = InstrumentationRegistry.getArguments()
    private val maximumStartupMilliseconds = requiredPositiveBudget("pushgo.maxStartupMs")
    private val maximumDetailMilliseconds = requiredPositiveBudget("pushgo.maxDetailMs")

    @Test
    fun coldStartupReachesAccurateLargeStoreContent() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        withPreparedFixture(device, MACROBENCHMARK_TARGET_PACKAGE) {
            benchmarkRule.measureRepeated(
                packageName = MACROBENCHMARK_TARGET_PACKAGE,
                metrics = listOf(StartupTimingMetric()),
                compilationMode = CompilationMode.None(),
                startupMode = StartupMode.COLD,
                iterations = 10,
                setupBlock = {
                    pressHome()
                },
                measureBlock = {
                    val startedAt = SystemClock.elapsedRealtime()
                    startActivityAndWait()
                    val title = device.wait(
                        Until.findObject(By.text(EXPECTED_TITLE)),
                        maximumStartupMilliseconds + UI_WAIT_SLACK_MILLISECONDS,
                    )
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    checkNotNull(title) { "accurate 1k-store sentinel did not become visible" }
                    check(elapsed <= maximumStartupMilliseconds) {
                        "cold startup-to-accurate-content took ${elapsed}ms; budget=${maximumStartupMilliseconds}ms"
                    }
                },
            )
        }
    }

    @Test
    fun openAccurateMessageDetailStaysWithinFrameAndPurposeBudget() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        withPreparedFixture(device, MACROBENCHMARK_TARGET_PACKAGE) {
            benchmarkRule.measureRepeated(
                packageName = MACROBENCHMARK_TARGET_PACKAGE,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = CompilationMode.None(),
                startupMode = null,
                iterations = 10,
                setupBlock = {
                    killProcess()
                    startActivityAndWait()
                    checkNotNull(
                        device.wait(
                            Until.findObject(By.res(EXPECTED_ROW_RESOURCE_ID)),
                            DEFAULT_UI_WAIT_MILLISECONDS,
                        )
                    ) { "benchmark setup did not reach the exact sentinel list row" }
                    checkNotNull(device.findObject(By.text(EXPECTED_TITLE))) {
                        "benchmark setup row did not expose the exact persisted title"
                    }
                },
                measureBlock = {
                    val rowAction = checkNotNull(device.findObject(By.descContains(EXPECTED_TITLE))) {
                        "exact sentinel list action disappeared before the measured interaction"
                    }
                    val startedAt = SystemClock.elapsedRealtime()
                    rowAction.click()
                    val detailSheet = device.wait(
                        Until.findObject(By.res(DETAIL_SHEET_RESOURCE_ID)),
                        maximumDetailMilliseconds + UI_WAIT_SLACK_MILLISECONDS,
                    )
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    checkNotNull(detailSheet) { "sentinel row did not open the message-detail sheet" }
                    checkNotNull(device.findObject(By.text(EXPECTED_BODY))) {
                        "sentinel row did not open its matching persisted body"
                    }
                    check(elapsed <= maximumDetailMilliseconds) {
                        "message-detail task took ${elapsed}ms; budget=${maximumDetailMilliseconds}ms"
                    }
                },
            )
        }
    }

    private fun requiredPositiveBudget(name: String): Long {
        val raw = instrumentationArguments.getString(name)
        val parsed = raw?.toLongOrNull()
        check(parsed != null && parsed > 0) { "$name must be an explicit positive integer" }
        return parsed
    }
}
