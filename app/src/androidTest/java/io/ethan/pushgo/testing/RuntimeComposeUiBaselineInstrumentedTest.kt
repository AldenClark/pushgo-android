package io.ethan.pushgo.testing

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.PushTokenProvider
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.ui.viewmodel.MessageListViewModel
import io.ethan.pushgo.ui.viewmodel.MessageSearchViewModel
import io.ethan.pushgo.ui.viewmodel.SettingsUiState
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import io.ethan.pushgo.update.UpdateManager
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeComposeUiBaselineInstrumentedTest {
    private lateinit var context: Context
    private lateinit var container: AppContainer

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        container = AppContainer(context, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        container.privateChannelClient.resetForAutomation()
        container.privateChannelClient.setForeground(true)
        container.settingsRepository.setUseFcmChannel(true)
        container.settingsRepository.setFcmToken("runtime-compose-token")
        container.messageRepository.deleteAll()
        container.entityRepository.deleteAll()
        container.inboundDeliveryLedgerRepository.clearAll()
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
    fun composeRuntime_environmentProbe_reports_inputManagerReflectionGap() {
        val hasInputManagerGetInstance = runCatching {
            InputManager::class.java.getDeclaredMethod("getInstance")
            true
        }.getOrDefault(false)

        println(
            "RUNTIME_COMPOSE_ENV " +
                "input_manager_get_instance=$hasInputManagerGetInstance " +
                "fallback_mode=${!hasInputManagerGetInstance} " +
                "note=compose_ui_rule_uses_espresso_onIdle",
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            assertFalse(
                "expected API17 image to miss InputManager.getInstance reflective entry",
                hasInputManagerGetInstance,
            )
        }
    }

    @Test
    fun composeRuntime_messageListBaseline_proxyThroughViewModelAndRepository() = runBlocking {
        val scale = if (include100k()) 100_000 else 10_000
        insertFixtureMessages(scale)

        val memoryBefore = usedHeapBytes()
        val listViewModel = MessageListViewModel(
            repository = container.messageRepository,
            stateCoordinator = MessageStateCoordinator(context, container.messageRepository),
            settingsRepository = container.settingsRepository,
        )
        val searchViewModel = MessageSearchViewModel(repository = container.messageRepository)

        val firstPageReadyMs = elapsedMs {
            val total = container.messageRepository.totalCount()
            val newest = container.messageRepository.getByMessageId("runtime-message-${scale - 1}")
            println("RUNTIME_COMPOSE_LIST_PROXY checkpoint_total=$total newest_present=${newest != null}")
        }

        val searchReadyMs = elapsedMs {
            searchViewModel.updateQuery("tag:task")
            val result = withTimeout(20_000) {
                container.messageRepository.searchMessagesSnapshot("tag:task", unreadOnly = false, limit = 100)
            }
            println("RUNTIME_COMPOSE_LIST_PROXY checkpoint_search_result_size=${result.size}")
        }

        val unreadToggleMs = elapsedMs {
            listViewModel.toggleUnreadOnlyFilter()
            val unreadState = listViewModel.filterState.value.unreadOnly
            val unread = withTimeout(10_000) {
                container.messageRepository.searchMessagesSnapshot("runtime", unreadOnly = true, limit = 100)
            }
            println(
                "RUNTIME_COMPOSE_LIST_PROXY checkpoint_unread_state=$unreadState " +
                    "unread_result_size=${unread.size}",
            )
        }

        val channelFilterMs = elapsedMs {
            listViewModel.toggleChannel("runtime-channel-1")
            val selected = listViewModel.filterState.value.channels.contains("runtime-channel-1")
            val channelScoped = withTimeout(10_000) {
                container.messageRepository.searchMessagesSnapshot("runtime channel", unreadOnly = false, limit = 100)
            }
            println(
                "RUNTIME_COMPOSE_LIST_PROXY checkpoint_channel_selected=$selected " +
                    "channel_result_size=${channelScoped.size}",
            )
        }

        val tagFilterMs = elapsedMs {
            listViewModel.toggleTag("task")
            val selected = listViewModel.filterState.value.tags.contains("task")
            val tagged = withTimeout(10_000) {
                container.messageRepository.searchMessagesSnapshot("tag:task", unreadOnly = false, limit = 100)
            }
            println(
                "RUNTIME_COMPOSE_LIST_PROXY checkpoint_tag_selected=$selected " +
                    "tag_result_size=${tagged.size}",
            )
        }

        val memoryAfter = usedHeapBytes()
        println(
            "RUNTIME_COMPOSE_LIST_PROXY " +
                "scale=$scale " +
                "first_ready_ms=$firstPageReadyMs " +
                "search_ready_ms=$searchReadyMs " +
                "unread_toggle_ms=$unreadToggleMs " +
                "channel_filter_ms=$channelFilterMs " +
                "tag_filter_ms=$tagFilterMs " +
                "memory_delta_bytes=${memoryAfter - memoryBefore}",
        )
    }

    @Test
    fun composeRuntime_detailAndSettingsBaseline_proxyThroughViewModelState() = runBlocking {
        insertFixtureMessages(size = 30)

        val detailOpenMs = elapsedMs {
            val detail = withTimeout(10_000) {
                container.messageRepository.getByMessageId("runtime-message-29")
            }
            assertNotNull(detail)
            assertTrue(detail!!.body.isNotBlank())
        }

        container.settingsRepository.setUseFcmChannel(true)
        container.settingsRepository.setFcmToken("runtime-settings-token")
        container.privateChannelClient.setRuntime(fcmAvailable = true, systemToken = "runtime-settings-token")

        val viewModel = SettingsViewModel(
            settingsRepository = container.settingsRepository,
            channelRepository = container.channelRepository,
            messageRepository = container.messageRepository,
            messageStateCoordinator = container.messageStateCoordinator,
            privateChannelClient = container.privateChannelClient,
            updateManager = UpdateManager(context, container.settingsRepository),
            pushTokenProvider = object : PushTokenProvider {
                override suspend fun fetchToken(timeoutMs: Long): String? = null
            },
            gatewayPrivateChannelEnabledFetcher = { true },
        )

        val switchPrivateMs = elapsedMs {
            container.settingsRepository.setUseFcmChannel(false)
            container.settingsRepository.setFcmToken(null)
            container.privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
            viewModel.refreshChannelUiStateForTesting()
            println("RUNTIME_COMPOSE_SETTINGS_PROXY checkpoint_switch_private_state=${viewModel.uiState.value.useFcmChannel}")
        }

        container.settingsRepository.setFcmToken(null)
        viewModel.refreshChannelUiStateForTesting()
        println("RUNTIME_COMPOSE_SETTINGS_PROXY checkpoint_token_missing=${viewModel.uiState.value.deviceToken}")

        container.settingsRepository.setFcmToken("runtime-settings-token-restored")
        viewModel.refreshChannelUiStateForTesting()
        println("RUNTIME_COMPOSE_SETTINGS_PROXY checkpoint_token_restored=${viewModel.uiState.value.deviceToken}")

        container.settingsRepository.setFcmToken(null)
        viewModel.refreshChannelUiStateForTesting()
        println("RUNTIME_COMPOSE_SETTINGS_PROXY checkpoint_token_missing_again=${viewModel.uiState.value.deviceToken}")

        val switchFcmMs = elapsedMs {
            container.settingsRepository.setUseFcmChannel(true)
            container.settingsRepository.setFcmToken("runtime-settings-token-final")
            container.privateChannelClient.setRuntime(fcmAvailable = true, systemToken = "runtime-settings-token-final")
            viewModel.refreshChannelUiStateForTesting()
            println("RUNTIME_COMPOSE_SETTINGS_PROXY checkpoint_switch_fcm_state=${viewModel.uiState.value.useFcmChannel}")
        }

        val status = viewModel.uiState.value.privateTransportStatus
        println(
            "RUNTIME_COMPOSE_SETTINGS_PROXY " +
                "detail_open_ms=$detailOpenMs " +
                "switch_private_ms=$switchPrivateMs " +
                "switch_fcm_ms=$switchFcmMs " +
                "transport_status=$status",
        )
    }

    @Test
    fun composeRuntime_settingsPrivateStagesAndTokenRecovery_matchViewModelUiState() = runBlocking {
        container.settingsRepository.setUseFcmChannel(false)
        container.settingsRepository.setFcmToken(null)
        container.privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)

        val viewModel = buildSettingsViewModel()
        withContext(Dispatchers.Main) { viewModel.refreshChannelUiStateForTesting() }

        val connectingMs = elapsedMs {
            container.privateChannelClient.injectSessionEventForTesting(
                eventJson = reconnectingEventJson(attempt = 1, backoffMs = 120L),
            )
            withContext(Dispatchers.Main) { viewModel.refreshChannelUiStateForTesting() }
        }
        val connectingState = awaitUiState(viewModel) { state ->
            !state.useFcmChannel && state.privateTransportStatus.isNotBlank()
        }
        val expectedConnectingStatus = container.privateChannelClient.summarizeConnectionStatus(
            snapshot = container.privateChannelClient.readConnectionSnapshot(),
            privateModeEnabled = true,
        )
        assertEquals(expectedConnectingStatus, connectingState.privateTransportStatus)

        val connectedMs = elapsedMs {
            container.privateChannelClient.injectSessionEventForTesting(
                eventJson = connectedEventJson(transport = "quic", elapsedMs = 16L),
            )
            withContext(Dispatchers.Main) { viewModel.refreshChannelUiStateForTesting() }
        }
        val expectedConnectedStatus = container.privateChannelClient.summarizeConnectionStatus(
            snapshot = container.privateChannelClient.readConnectionSnapshot(),
            privateModeEnabled = true,
        )
        val connectedState = awaitUiState(viewModel) { state ->
            !state.useFcmChannel && state.privateTransportStatus == expectedConnectedStatus
        }
        assertEquals(expectedConnectedStatus, connectedState.privateTransportStatus)

        val reconnectingMs = elapsedMs {
            container.privateChannelClient.injectSessionEventForTesting(
                eventJson = reconnectingEventJson(attempt = 2, backoffMs = 350L),
            )
            withContext(Dispatchers.Main) { viewModel.refreshChannelUiStateForTesting() }
        }
        val expectedReconnectingStatus = container.privateChannelClient.summarizeConnectionStatus(
            snapshot = container.privateChannelClient.readConnectionSnapshot(),
            privateModeEnabled = true,
        )
        val reconnectingState = awaitUiState(viewModel) { state ->
            !state.useFcmChannel && state.privateTransportStatus == expectedReconnectingStatus
        }
        assertEquals(expectedReconnectingStatus, reconnectingState.privateTransportStatus)

        val errorMs = elapsedMs {
            container.privateChannelClient.injectSessionEventForTesting(
                eventJson = fatalEventJson(error = "transport probe failed"),
            )
            withContext(Dispatchers.Main) { viewModel.refreshChannelUiStateForTesting() }
        }
        val expectedErrorStatus = container.privateChannelClient.summarizeConnectionStatus(
            snapshot = container.privateChannelClient.readConnectionSnapshot(),
            privateModeEnabled = true,
        )
        val errorState = awaitUiState(viewModel) { state ->
            !state.useFcmChannel && state.privateTransportStatus == expectedErrorStatus
        }
        assertEquals(expectedErrorStatus, errorState.privateTransportStatus)

        val tokenMissingMs = elapsedMs {
            container.settingsRepository.setFcmToken(null)
            withContext(Dispatchers.Main) { viewModel.refreshChannelUiStateForTesting() }
        }
        val tokenMissingState = awaitUiState(viewModel) { state -> state.deviceToken == null }
        assertTrue(tokenMissingState.deviceToken.isNullOrBlank())

        val tokenRecoveredMs = elapsedMs {
            container.settingsRepository.setFcmToken("runtime-settings-token-restored")
            withContext(Dispatchers.Main) { viewModel.refreshChannelUiStateForTesting() }
        }
        val tokenRecoveredState = awaitUiState(viewModel) { state ->
            state.deviceToken == "runtime-settings-token-restored"
        }
        assertEquals("runtime-settings-token-restored", tokenRecoveredState.deviceToken)

        val tokenMissingAgainMs = elapsedMs {
            container.settingsRepository.setFcmToken(null)
            withContext(Dispatchers.Main) { viewModel.refreshChannelUiStateForTesting() }
        }
        val tokenMissingAgainState = awaitUiState(viewModel) { state -> state.deviceToken == null }
        assertTrue(tokenMissingAgainState.deviceToken.isNullOrBlank())

        println(
            "RUNTIME_COMPOSE_SETTINGS_STAGE_PROXY " +
                "connecting_ms=$connectingMs " +
                "connected_ms=$connectedMs " +
                "reconnecting_ms=$reconnectingMs " +
                "error_ms=$errorMs " +
                "token_missing_ms=$tokenMissingMs " +
                "token_recovered_ms=$tokenRecoveredMs " +
                "token_missing_again_ms=$tokenMissingAgainMs " +
                "status_connecting=${normalizeMetricText(expectedConnectingStatus)} " +
                "status_connected=${normalizeMetricText(expectedConnectedStatus)} " +
                "status_reconnecting=${normalizeMetricText(expectedReconnectingStatus)} " +
                "status_error=${normalizeMetricText(expectedErrorStatus)}",
        )
    }

    private suspend fun insertFixtureMessages(size: Int) {
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
                    .put("entity_id", "runtime-message-$index")
                    .put("message_id", "runtime-message-$index")
                    .put("channel_id", "runtime-channel-${index % 4}")
                    .put("delivery_id", "runtime-delivery-$index")
                    .put("op_id", "runtime-op-$index")
                    .put("title", "Runtime title $index")
                    .put("body", body)
                    .put("sent_at", (BASE_TIME_MS + index).toString())
                    .put("occurred_at", (BASE_TIME_MS + index).toString())
                    .put("provider", if (index % 2 == 0) "fcm" else "private")
                    .put("tags", JSONArray(tags).toString())
                    .put("metadata", JSONObject().put("state", if (index % 5 == 0) "open" else "normal").toString())
                    .toString()

                PushMessage(
                    id = "runtime-row-$index",
                    messageId = "runtime-message-$index",
                    title = "Runtime title $index",
                    body = body,
                    channel = "runtime-channel-${index % 4}",
                    url = if (index % 13 == 0) "https://sandbox.pushgo.dev/messages/$index" else null,
                    isRead = index % 3 == 0,
                    receivedAt = Instant.ofEpochMilli(BASE_TIME_MS + index),
                    rawPayloadJson = payload,
                    status = MessageStatus.NORMAL,
                    decryptionState = null,
                    notificationId = "runtime-notification-$index",
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

    private fun buildSettingsViewModel(): SettingsViewModel {
        return SettingsViewModel(
            settingsRepository = container.settingsRepository,
            channelRepository = container.channelRepository,
            messageRepository = container.messageRepository,
            messageStateCoordinator = container.messageStateCoordinator,
            privateChannelClient = container.privateChannelClient,
            updateManager = UpdateManager(context, container.settingsRepository),
            pushTokenProvider = object : PushTokenProvider {
                override suspend fun fetchToken(timeoutMs: Long): String? = null
            },
            gatewayPrivateChannelEnabledFetcher = { true },
        )
    }

    private suspend fun awaitUiState(
        viewModel: SettingsViewModel,
        predicate: (SettingsUiState) -> Boolean,
    ): SettingsUiState {
        return withTimeout(8_000) {
            while (true) {
                val state = viewModel.uiState.value
                if (predicate(state)) {
                    return@withTimeout state
                }
                withContext(Dispatchers.Main) {
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                }
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            error("awaitUiState timeout")
        }
    }

    private fun connectedEventJson(transport: String, elapsedMs: Long): String {
        return JSONObject()
            .put("type", "connected")
            .put("transport", transport)
            .put("elapsed_ms", elapsedMs)
            .toString()
    }

    private fun reconnectingEventJson(attempt: Int, backoffMs: Long): String {
        return JSONObject()
            .put("type", "reconnecting")
            .put("attempt", attempt)
            .put("backoff_ms", backoffMs)
            .toString()
    }

    private fun fatalEventJson(error: String): String {
        return JSONObject()
            .put("type", "fatal")
            .put("error", error)
            .toString()
    }

    private fun normalizeMetricText(value: String): String {
        return value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(' ', '_')
            .trim('_')
            .ifEmpty { "none" }
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private companion object {
        private const val BASE_TIME_MS = 1_720_000_000_000L
    }
}
