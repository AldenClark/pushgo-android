package io.ethan.pushgo.testing

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.R
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.update.UpdateNotifier
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeComposeUiAutomatorInstrumentedTest {
    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var device: UiDevice
    private lateinit var automationDir: File
    private lateinit var automationStateFile: File
    private lateinit var automationEventsFile: File
    private lateinit var automationTraceFile: File

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            val app = context as PushGoApp
            container = checkNotNull(app.containerOrNull()) { "PushGoApp container unavailable" }
            device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

            automationDir = File(context.filesDir, "runtime-compose-ui-automation")
            automationStateFile = File(automationDir, "state.json")
            automationEventsFile = File(automationDir, "events.jsonl")
            automationTraceFile = File(automationDir, "trace.jsonl")
            automationDir.mkdirs()
            automationStateFile.delete()
            automationEventsFile.delete()
            automationTraceFile.delete()

            container.privateChannelClient.resetForAutomation()
            container.privateChannelClient.setForeground(true)
            container.settingsRepository.setUseFcmChannel(true)
            container.settingsRepository.setFcmToken("runtime-compose-ui-token")
            container.settingsRepository.setServerAddress("https://sandbox.pushgo.dev")
            container.settingsRepository.setGatewayToken(null)
            container.settingsRepository.setMessagePageEnabled(true)
            container.settingsRepository.setEventPageEnabled(true)
            container.settingsRepository.setThingPageEnabled(true)
            container.messageRepository.deleteAll()
            container.entityRepository.deleteAll()
            container.inboundDeliveryLedgerRepository.clearAll()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            runCatching {
                container.privateChannelClient.setForeground(false)
                container.privateChannelClient.resetForAutomation()
                container.messageRepository.deleteAll()
                container.entityRepository.deleteAll()
                container.inboundDeliveryLedgerRepository.clearAll()
            }
        }
    }

    @Test
    fun composeRuntime_uiAutomatorBaseline_messageListDetailAndSettings() = runBlocking {
        val scale = if (include100k()) 100_000 else 10_000
        val datasetPrefix = "ui-auto-${System.currentTimeMillis()}"
        insertFixtureMessages(scale, datasetPrefix)
        val fixtureTotalCount = container.messageRepository.totalCount()
        assertTrue("fixture insert did not reach target scale", fixtureTotalCount >= scale)
        val meminfoBeforeText = runShellCommand("dumpsys meminfo ${context.packageName}")
        val totalPssBeforeKb = parseTotalPssKb(meminfoBeforeText)

        val launchReadyMs: Long
        val searchReadyMs: Long
        val unreadFilterToggleMs: Long
        val scrollBurstMs: Long

        val messageScenario = launchScenario(openSettings = false, openMessageId = null)
        try {
            dismissSystemPermissionDialogIfPresent()
            launchReadyMs = elapsedMs {
                val ready = waitForState(25_000) { state ->
                    state.optString("active_tab").isNotBlank()
                }
                assertNotNull("message list state did not become ready in time", ready)
            }
            clickIfPresent(
                selectors = listOf(
                    By.text(context.getString(R.string.tab_messages)),
                    By.textContains(context.getString(R.string.tab_messages)),
                ),
            )
            waitForState(6_000) { state ->
                state.optString("visible_screen") == "screen.messages.list"
            }
            // Reset framestats after initial launch/readiness to reduce cold-start noise.
            runShellCommand("dumpsys gfxinfo ${context.packageName} reset")

            scrollBurstMs = elapsedMs {
                val width = device.displayWidth
                val height = device.displayHeight
                repeat(5) {
                    device.swipe(width / 2, (height * 0.78).toInt(), width / 2, (height * 0.28).toInt(), 28)
                    SystemClock.sleep(140)
                }
            }

            searchReadyMs = elapsedMs {
                val searchResult = withTimeout(12_000) {
                    container.messageRepository.searchMessagesSnapshot(
                        rawQuery = "runtime",
                        unreadOnly = false,
                        limit = 50,
                    )
                }
                assertTrue("proxy search result should not be empty", searchResult.isNotEmpty())
            }

            unreadFilterToggleMs = elapsedMs {
                val unreadResult = withTimeout(12_000) {
                    container.messageRepository.searchMessagesSnapshot(
                        rawQuery = "runtime",
                        unreadOnly = true,
                        limit = 100,
                    )
                }
                assertTrue("proxy unread filter result should not be empty", unreadResult.isNotEmpty())
            }

        } finally {
            messageScenario.close()
        }

        val detailOpenMs: Long
        val detailCloseMs: Long
        val detailScenario = launchScenario(openSettings = false, openMessageId = "$datasetPrefix-row-${scale - 1}")
        try {
            dismissSystemPermissionDialogIfPresent()
            detailOpenMs = elapsedMs {
                val opened = waitForState(12_000) { state ->
                    state.optString("opened_message_id").isNotBlank()
                }
                assertNotNull("detail screen did not expose opened_message_id", opened)
            }
            detailCloseMs = elapsedMs {
                device.pressBack()
                val closed = waitForState(10_000) { state ->
                    state.optString("opened_message_id").isBlank()
                }
                assertNotNull("detail screen did not close back to list", closed)
            }
        } finally {
            detailScenario.close()
        }

        val settingsReadyMs: Long
        val switchPrivateMs: Long
        var switchFcmMs: Long = -1L
        var switchFcmSupported = false
        var switchFcmSkipReason = "unknown"
        var switchFcmVisibilityHint = "unknown"
        var switchFcmStateSummary = "unknown"
        val settingsScenario = launchScenario(openSettings = true, openMessageId = null)
        try {
            dismissSystemPermissionDialogIfPresent()
            settingsReadyMs = elapsedMs {
                val settingsHeader = device.wait(
                    Until.hasObject(By.textContains(context.getString(R.string.tab_settings))),
                    12_000,
                )
                assertTrue("settings page header not visible", settingsHeader)
            }

            val privateLabel = context.getString(R.string.label_transport_private)
            val fcmLabel = context.getString(R.string.label_transport_fcm)
            val fcmOptionSelectors = listOf(
                By.desc("option.settings.notification_transport.fcm"),
                By.text(fcmLabel),
                By.textContains(fcmLabel),
            )
            val privateOptionSelectors = listOf(
                By.desc("option.settings.notification_transport.private"),
                By.text(privateLabel),
                By.textContains(privateLabel),
            )
            val segmentedRowSelectors = listOf(
                By.desc("segmented.settings.notification_transport"),
            )

            switchPrivateMs = elapsedMs {
                val switched = clickTransportSegment(
                    selectFcm = false,
                    directSelectors = privateOptionSelectors,
                    segmentedRowSelectors = segmentedRowSelectors,
                )
                assertTrue("private transport option click failed", switched)
                val privateState = waitForState(10_000) { state ->
                    !state.optBoolean("use_fcm_channel", true) &&
                        state.optString("private_route").isNotBlank()
                }
                assertNotNull("switch to private mode not reflected in automation state", privateState)
            }
            dismissPrivateWhitelistDialogIfPresent()

            val settingsSnapshot = readAutomationState()
            val transportRowVisible = findUiObject(
                timeoutMs = 2_000,
                selectors = listOf(By.textContains(context.getString(R.string.label_notification_transport))),
            ) != null
            val segmentedVisible = findUiObject(
                timeoutMs = 2_000,
                selectors = listOf(By.textContains(privateLabel), By.textContains(fcmLabel)),
            ) != null
            val privateOptionVisible = findUiObject(
                timeoutMs = 2_000,
                selectors = listOf(By.text(privateLabel), By.textContains(privateLabel)),
            ) != null
            val fcmOption = findUiObject(
                timeoutMs = 3_000,
                selectors = fcmOptionSelectors,
            )
            switchFcmVisibilityHint = buildString {
                append("row=").append(transportRowVisible)
                append(",segmented=").append(segmentedVisible)
                append(",private_option=").append(privateOptionVisible)
                append(",fcm_option=").append(fcmOption != null)
            }
            switchFcmStateSummary = settingsSnapshot?.let { snapshot ->
                buildString {
                    append("active_tab=").append(snapshot.optString("active_tab", ""))
                    append(",screen=").append(snapshot.optString("visible_screen", ""))
                    append(",use_fcm_channel=").append(snapshot.optBoolean("use_fcm_channel", true))
                    append(",provider_mode=").append(snapshot.optString("provider_mode", ""))
                    append(",private_stage=").append(snapshot.optString("private_stage", ""))
                }
            } ?: "state_unavailable"

            if (fcmOption != null && !fcmOption.isEnabled) {
                switchFcmSkipReason = "fcm_option_disabled"
            } else {
                switchFcmSupported = true
                switchFcmMs = elapsedMs {
                    val clicked = clickTransportSegment(
                        selectFcm = true,
                        directSelectors = fcmOptionSelectors,
                        segmentedRowSelectors = segmentedRowSelectors,
                    )
                    if (!clicked) {
                        error("fcm transport option click failed")
                    }
                    val fcmState = waitForState(12_000) { state ->
                        state.optBoolean("use_fcm_channel", false)
                    }
                    assertNotNull("switch back to fcm mode not reflected in automation state", fcmState)
                    val convergedState = waitForState(12_000) { state ->
                        state.optBoolean("use_fcm_channel", false) &&
                            state.optString("private_route").equals("provider", ignoreCase = true) &&
                            !state.optString("private_stage").equals("connected", ignoreCase = true)
                    }
                    assertNotNull("switch back to fcm mode did not converge to single-active provider state", convergedState)
                }
                switchFcmSkipReason = "none"
            }
        } finally {
            settingsScenario.close()
        }

        val meminfoText = runShellCommand("dumpsys meminfo ${context.packageName}")
        val gfxinfoText = runShellCommand("dumpsys gfxinfo ${context.packageName} framestats")
        val totalPssKb = parseTotalPssKb(meminfoText)
        val totalPssDeltaKb = if (totalPssKb >= 0 && totalPssBeforeKb >= 0) {
            totalPssKb - totalPssBeforeKb
        } else {
            -1L
        }
        val jankyFrames = parseJankyFrames(gfxinfoText)
        val totalFrames = parseTotalFrames(gfxinfoText)

        println(
                "RUNTIME_COMPOSE_UI_AUTOMATOR " +
                "scale=$scale " +
                "dataset_prefix=$datasetPrefix " +
                "fixture_total_count=$fixtureTotalCount " +
                "launch_ready_ms=$launchReadyMs " +
                "scroll_burst_ms=$scrollBurstMs " +
                "search_ready_ms=$searchReadyMs " +
                "unread_filter_toggle_ms=$unreadFilterToggleMs " +
                "detail_open_ms=$detailOpenMs " +
                "detail_close_ms=$detailCloseMs " +
                "settings_ready_ms=$settingsReadyMs " +
                "switch_private_ms=$switchPrivateMs " +
                "switch_fcm_ms=$switchFcmMs " +
                "switch_fcm_supported=$switchFcmSupported " +
                "switch_fcm_skip_reason=$switchFcmSkipReason " +
                "switch_fcm_visibility_hint=$switchFcmVisibilityHint " +
                "switch_fcm_state_summary=$switchFcmStateSummary " +
                "total_pss_before_kb=$totalPssBeforeKb " +
                "total_pss_kb=$totalPssKb " +
                "total_pss_delta_kb=$totalPssDeltaKb " +
                "janky_frames=$jankyFrames " +
                "total_frames=$totalFrames",
        )
    }

    private fun clickSegmentLeftOf(privateOption: UiObject2) {
        val bounds = Rect(privateOption.visibleBounds)
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        val x = (bounds.left - width / 2).coerceAtLeast(1)
        val y = (bounds.top + height / 2).coerceAtMost(device.displayHeight - 1)
        device.click(x, y)
        SystemClock.sleep(180)
    }

    private fun clickTransportSegment(
        selectFcm: Boolean,
        directSelectors: List<androidx.test.uiautomator.BySelector>,
        segmentedRowSelectors: List<androidx.test.uiautomator.BySelector>,
    ): Boolean {
        runCatching {
            clickUiObject(
                timeoutMs = 1_800,
                name = if (selectFcm) "fcm transport option" else "private transport option",
                selectors = directSelectors,
            )
            return true
        }

        val segmentedRow = findUiObject(timeoutMs = 2_500, selectors = segmentedRowSelectors) ?: return false
        val bounds = Rect(segmentedRow.visibleBounds)
        val width = bounds.width().coerceAtLeast(2)
        val height = bounds.height().coerceAtLeast(2)
        val x = if (selectFcm) {
            bounds.left + width / 4
        } else {
            bounds.left + (width * 3) / 4
        }.coerceIn(1, device.displayWidth - 1)
        val y = (bounds.top + height / 2).coerceIn(1, device.displayHeight - 1)
        device.click(x, y)
        SystemClock.sleep(220)
        return true
    }

    private fun launchScenario(
        openSettings: Boolean,
        openMessageId: String?,
    ): ActivityScenario<MainActivity> {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(PushGoAutomation.EXTRA_STATE_PATH, automationStateFile.absolutePath)
            putExtra(PushGoAutomation.EXTRA_EVENTS_PATH, automationEventsFile.absolutePath)
            putExtra(PushGoAutomation.EXTRA_TRACE_PATH, automationTraceFile.absolutePath)
            putExtra(PushGoAutomation.EXTRA_GATEWAY_BASE_URL, "https://sandbox.pushgo.dev")
            if (openSettings) {
                putExtra(UpdateNotifier.EXTRA_OPEN_SETTINGS, true)
            }
            if (!openMessageId.isNullOrBlank()) {
                putExtra(NotificationHelper.EXTRA_MESSAGE_ID, openMessageId)
            }
        }
        return ActivityScenario.launch(intent)
    }

    private fun waitForState(
        timeoutMs: Long,
        predicate: (JSONObject) -> Boolean,
    ): JSONObject? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = readAutomationState()
            if (state != null && predicate(state)) {
                return state
            }
            SystemClock.sleep(120)
        }
        return null
    }

    private fun readAutomationState(): JSONObject? {
        if (!automationStateFile.exists()) return null
        return runCatching {
            JSONObject(automationStateFile.readText())
        }.getOrNull()
    }

    private fun requireUiObject(
        timeoutMs: Long,
        name: String,
        selectors: List<androidx.test.uiautomator.BySelector>,
    ): UiObject2 {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            selectors.forEach { selector ->
                val found = device.findObject(selector)
                if (found != null) return found
            }
            SystemClock.sleep(120)
        }
        error("ui object not found: $name")
    }

    private fun findUiObject(
        timeoutMs: Long,
        selectors: List<androidx.test.uiautomator.BySelector>,
    ): UiObject2? {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            selectors.forEach { selector ->
                val found = device.findObject(selector)
                if (found != null) return found
            }
            SystemClock.sleep(120)
        }
        return null
    }

    private fun clickUiObject(
        timeoutMs: Long,
        name: String,
        selectors: List<androidx.test.uiautomator.BySelector>,
    ) {
        val start = SystemClock.elapsedRealtime()
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val candidate = runCatching { requireUiObject(800, name, selectors) }.getOrNull()
            if (candidate != null) {
                val clicked = runCatching {
                    candidate.click()
                    true
                }.getOrElse {
                    lastError = it
                    false
                }
                if (clicked) return
            }
            SystemClock.sleep(100)
        }
        error("ui click failed: $name error=${lastError?.javaClass?.simpleName}:${lastError?.message}")
    }

    private fun dismissSystemPermissionDialogIfPresent() {
        val allow = device.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button"))
            ?: device.findObject(By.text("Allow"))
            ?: device.findObject(By.text("允许"))
        allow?.click()
        SystemClock.sleep(200)
    }

    private fun dismissPrivateWhitelistDialogIfPresent() {
        val gotIt = context.getString(R.string.label_got_it)
        val title = context.getString(R.string.dialog_private_channel_whitelist_title)
        val action = findUiObject(
            timeoutMs = 2_000,
            selectors = listOf(By.text(gotIt), By.textContains(gotIt), By.text(title), By.textContains(title)),
        )
        action?.click()
        SystemClock.sleep(180)
    }

    private fun clickIfPresent(
        selectors: List<androidx.test.uiautomator.BySelector>,
    ) {
        selectors.forEach { selector ->
            val found = device.findObject(selector)
            if (found != null) {
                runCatching { found.click() }
                SystemClock.sleep(120)
                return
            }
        }
    }

    private suspend fun insertFixtureMessages(
        size: Int,
        datasetPrefix: String,
    ) {
        val batchSize = 1_000
        var start = 0
        while (start < size) {
            val end = minOf(size, start + batchSize)
            val batch = (start until end).map { index ->
                val isMarkdown = index % 7 == 0
                val body = if (isMarkdown) {
                    buildString {
                        append("# Runtime Markdown ").append(index).append('\n')
                        append("- channel: runtime-channel-").append(index % 4).append('\n')
                        append("- tag: task").append('\n')
                        append("- image: https://sandbox.pushgo.dev/static/").append(index).append(".png")
                        if (index % 17 == 0) {
                            append('\n').append("Long body: ").append("x".repeat(4_000))
                        }
                    }
                } else {
                    "Runtime plain body $index"
                }
                val tags = if (index % 5 == 0) listOf("task", "ops") else listOf("ops")
                val payload = JSONObject()
                    .put("entity_type", "message")
                    .put("entity_id", "$datasetPrefix-message-$index")
                    .put("message_id", "$datasetPrefix-message-$index")
                    .put("channel_id", "runtime-channel-${index % 4}")
                    .put("delivery_id", "$datasetPrefix-delivery-$index")
                    .put("op_id", "$datasetPrefix-op-$index")
                    .put("title", "Runtime title $index")
                    .put("body", body)
                    .put("sent_at", (BASE_TIME_MS + index).toString())
                    .put("occurred_at", (BASE_TIME_MS + index).toString())
                    .put("provider", if (index % 2 == 0) "fcm" else "private")
                    .put("tags", JSONArray(tags).toString())
                    .put("metadata", JSONObject().put("state", if (index % 5 == 0) "open" else "normal").toString())
                    .toString()

                PushMessage(
                    id = "$datasetPrefix-row-$index",
                    messageId = "$datasetPrefix-message-$index",
                    title = "Runtime title $index",
                    body = body,
                    channel = "runtime-channel-${index % 4}",
                    url = if (index % 13 == 0) "https://sandbox.pushgo.dev/messages/$index" else null,
                    isRead = index % 3 == 0,
                    receivedAt = Instant.ofEpochMilli(BASE_TIME_MS + index),
                    rawPayloadJson = payload,
                    status = MessageStatus.NORMAL,
                    decryptionState = null,
                    notificationId = "$datasetPrefix-notification-$index",
                    serverId = "runtime-server",
                    bodyPreview = null,
                )
            }
            container.messageRepository.insertAll(batch)
            start = end
        }
    }

    private fun include100k(): Boolean {
        return InstrumentationRegistry.getArguments()
            .getString("pushgo.runtime.include100k")
            ?.toBooleanStrictOrNull() == true
    }

    private suspend fun elapsedMs(block: suspend () -> Unit): Long {
        val started = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - started
    }

    private fun runShellCommand(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val parcel = automation.executeShellCommand(command)
        return try {
            FileInputStream(parcel.fileDescriptor).bufferedReader().use { it.readText() }
        } finally {
            parcel.close()
        }
    }

    private fun parseTotalPssKb(text: String): Long {
        val regex = Regex("""TOTAL\s+(\d+)""")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
    }

    private fun parseJankyFrames(text: String): Int {
        val regex = Regex("""Janky frames:\s+(\d+)""")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private fun parseTotalFrames(text: String): Int {
        val regex = Regex("""Total frames rendered:\s+(\d+)""")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
    }

    private companion object {
        private const val BASE_TIME_MS = 1_720_000_000_000L
    }
}
