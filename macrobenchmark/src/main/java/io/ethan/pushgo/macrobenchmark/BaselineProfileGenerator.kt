package io.ethan.pushgo.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupToAccurateLargeStoreContent() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        withPreparedFixture(device, BASELINE_PROFILE_TARGET_PACKAGE) {
            baselineProfileRule.collect(
                packageName = BASELINE_PROFILE_TARGET_PACKAGE,
                includeInStartupProfile = true,
                filterPredicate = ::isPushGoProfileRule,
            ) {
                killProcess()
                startActivityAndWait()
                checkNotNull(
                    device.wait(
                        Until.findObject(By.res(EXPECTED_ROW_RESOURCE_ID)),
                        DEFAULT_UI_WAIT_MILLISECONDS,
                    )
                ) { "baseline-profile journey did not reach the exact sentinel row" }
                checkNotNull(device.findObject(By.text(EXPECTED_TITLE))) {
                    "baseline-profile journey did not expose the exact persisted title"
                }
            }
        }
    }

    @Test
    fun openAccurateMessageDetail() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        withPreparedFixture(device, BASELINE_PROFILE_TARGET_PACKAGE) {
            baselineProfileRule.collect(
                packageName = BASELINE_PROFILE_TARGET_PACKAGE,
                includeInStartupProfile = false,
                filterPredicate = ::isPushGoProfileRule,
            ) {
                killProcess()
                startActivityAndWait()
                checkNotNull(
                    device.wait(
                        Until.findObject(By.res(EXPECTED_ROW_RESOURCE_ID)),
                        DEFAULT_UI_WAIT_MILLISECONDS,
                    )
                ) { "baseline-profile detail journey did not reach the exact sentinel row" }
                checkNotNull(device.findObject(By.text(EXPECTED_TITLE))) {
                    "baseline-profile detail journey did not expose the exact persisted title"
                }
                val rowAction = checkNotNull(device.findObject(By.descContains(EXPECTED_TITLE))) {
                    "baseline-profile detail journey did not reach the exact sentinel action"
                }
                rowAction.click()
                checkNotNull(
                    device.wait(Until.findObject(By.res(DETAIL_SHEET_RESOURCE_ID)), DEFAULT_UI_WAIT_MILLISECONDS)
                ) { "baseline-profile detail journey did not reach the detail sheet" }
                checkNotNull(device.findObject(By.text(EXPECTED_BODY))) {
                    "baseline-profile detail journey did not reach the matching persisted body"
                }
            }
        }
    }

    private fun isPushGoProfileRule(rule: String): Boolean =
        PUSHGO_PROFILE_RULE.matches(rule) &&
            EXCLUDED_QUALITY_CONTROL_PATHS.none(rule::contains)

    private companion object {
        val PUSHGO_PROFILE_RULE = Regex("^[HSP]*Lio/ethan/pushgo/.*")
        val EXCLUDED_QUALITY_CONTROL_PATHS = listOf(
            "Lio/ethan/pushgo/automation/",
            "Lio/ethan/pushgo/testing/",
        )
    }
}
