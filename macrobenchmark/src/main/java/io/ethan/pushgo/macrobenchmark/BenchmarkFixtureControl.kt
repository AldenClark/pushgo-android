package io.ethan.pushgo.macrobenchmark

import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.util.Base64

internal const val MACROBENCHMARK_TARGET_PACKAGE = "io.ethan.pushgo.benchmark"
internal const val BASELINE_PROFILE_TARGET_PACKAGE = "io.ethan.pushgo"
internal const val EXPECTED_TITLE = "Quality message 999"
internal const val EXPECTED_BODY = "Deterministic app-owned performance fixture row 999."
internal const val EXPECTED_ROW_RESOURCE_ID = "message.row.quality-large-999"
internal const val DETAIL_SHEET_RESOURCE_ID = "sheet.message.detail"
internal const val DEFAULT_UI_WAIT_MILLISECONDS = 15_000L
internal const val UI_WAIT_SLACK_MILLISECONDS = 2_000L

private val encodedSession: String by lazy {
    val sessionId = "android-performance-${Process.myPid()}-${SystemClock.elapsedRealtime()}"
    val rawLoadDelay = InstrumentationRegistry.getArguments()
        .getString("pushgo.fixtureLoadDelayMs")
    val loadDelayMilliseconds = if (rawLoadDelay == null) {
        0L
    } else {
        checkNotNull(rawLoadDelay.toLongOrNull()) {
            "pushgo.fixtureLoadDelayMs must be an integer"
        }
    }
    check(loadDelayMilliseconds in 0L..30_000L) {
        "pushgo.fixtureLoadDelayMs must be between 0 and 30000"
    }
    val json = """{"schema_version":1,"session_id":"$sessionId","fixture":"messages.large","faults":{"message_load_delay_ms":$loadDelayMilliseconds}}"""
    Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
}

internal fun prepareFixture(device: UiDevice, targetPackage: String) {
    if (targetPackage == BASELINE_PROFILE_TARGET_PACKAGE) {
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") {
            "baseline fixture reset refuses to clear the production applicationId on a physical device"
        }
    }
    val clearOutput = device.executeShellCommand("pm clear $targetPackage")
    check(clearOutput.contains("Success")) {
        "benchmark target data could not be reset: $clearOutput"
    }
    quiesceTargetProcess(device, targetPackage)
    val providerUri = "content://$targetPackage.quality-fixture"
    val output = device.executeShellCommand(
        "content call --uri $providerUri --method prepare --arg $encodedSession"
    )
    check(output.contains("status=ready")) { "benchmark fixture preparation failed: $output" }
    check(output.contains("count=1000")) { "benchmark fixture did not contain 1,000 canonical rows: $output" }
    check(output.contains("title=$EXPECTED_TITLE")) { "benchmark fixture sentinel was not exact: $output" }
    ensureNotificationPermission(device, targetPackage)
}

internal fun clearFixture(device: UiDevice, targetPackage: String) {
    quiesceTargetProcess(device, targetPackage)
    val providerUri = "content://$targetPackage.quality-fixture"
    val output = device.executeShellCommand(
        "content call --uri $providerUri --method clear"
    )
    check(output.contains("status=cleared")) { "benchmark fixture cleanup failed: $output" }
}

internal inline fun <T> withPreparedFixture(
    device: UiDevice,
    targetPackage: String,
    block: () -> T,
): T {
    prepareFixture(device, targetPackage)
    var primaryFailure: Throwable? = null
    return try {
        block()
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        try {
            clearFixture(device, targetPackage)
        } catch (cleanupFailure: Throwable) {
            primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
        }
    }
}

private fun quiesceTargetProcess(device: UiDevice, targetPackage: String) {
    val launchOutput = device.executeShellCommand(
        "am start -n $targetPackage/io.ethan.pushgo.testing.BenchmarkUnstopActivity"
    )
    check(
        launchOutput.contains("Starting: Intent") &&
            !launchOutput.contains("Error", ignoreCase = true) &&
            !launchOutput.contains("Exception", ignoreCase = true)
    ) {
        "benchmark package could not leave Android stopped-state: $launchOutput"
    }
    device.pressHome()
    device.waitForIdle()
    SystemClock.sleep(200)
    val killOutput = device.executeShellCommand("am kill $targetPackage")
    check(!killOutput.contains("Error", ignoreCase = true)) {
        "benchmark target could not reach a fixture-safe process boundary: $killOutput"
    }
    repeat(50) {
        if (device.executeShellCommand("pidof $targetPackage").trim().isEmpty()) return
        SystemClock.sleep(100)
    }
    error("benchmark target process remained alive before fixture control")
}

private fun ensureNotificationPermission(device: UiDevice, targetPackage: String) {
    val apiLevel = device.executeShellCommand("getprop ro.build.version.sdk").trim().toIntOrNull()
        ?: error("unable to resolve device API level")
    if (apiLevel < 33) return

    val grantOutput = device.executeShellCommand(
        "pm grant $targetPackage android.permission.POST_NOTIFICATIONS"
    ).trim()
    check(grantOutput.isEmpty()) { "notification permission grant failed: $grantOutput" }
    val permissionState = device.executeShellCommand(
        "dumpsys package $targetPackage | grep 'android.permission.POST_NOTIFICATIONS: granted=true'"
    )
    check(permissionState.contains("granted=true")) {
        "notification permission was not granted for the measured journey"
    }
}
