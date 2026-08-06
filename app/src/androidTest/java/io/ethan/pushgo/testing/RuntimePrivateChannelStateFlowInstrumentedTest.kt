package io.ethan.pushgo.testing

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.ChannelSubscriptionService
import io.ethan.pushgo.data.ChannelSubscriptionStore
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.InboundDeliveryLedgerRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.PushTokenProvider
import io.ethan.pushgo.data.SecureSecretStore
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.PrivateChannelClient
import io.ethan.pushgo.notifications.WarpLinkNativeBridge
import io.ethan.pushgo.ui.viewmodel.SettingsUiState
import io.ethan.pushgo.ui.viewmodel.SettingsViewModel
import io.ethan.pushgo.update.UpdateManager
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimePrivateChannelStateFlowInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var harness: RuntimeHarness
    private lateinit var fakeRuntime: FakeNativeRuntime

    @Before
    fun setUp() = runBlocking {
        cleanupDatabaseFamily(DATABASE_NAME)
        fakeRuntime = FakeNativeRuntime()
        WarpLinkNativeBridge.installTestRuntime(fakeRuntime)
        harness = openHarness()
    }

    @After
    fun tearDown() {
        harness.close()
        WarpLinkNativeBridge.installTestRuntime(null)
        cleanupDatabaseFamily(DATABASE_NAME)
        context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun privateChannelClient_stateFlow_covers_session_ack_reconnect_and_fcm_switch() = runBlocking {
        harness.settingsRepository.setUseFcmChannel(false)
        harness.privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)

        val startConvergeMs = elapsedMs {
            awaitTransportStage(expected = setOf("idle", "reconnecting", "connecting"))
        }

        val connectedMs = elapsedMs {
            harness.privateChannelClient.injectSessionEventForTesting(
                eventJson = connectedEventJson(transport = "quic", elapsedMs = 12L),
            )
            awaitTransportStage(expected = setOf("connected"))
        }

        val resumeSuccessMs = elapsedMs {
            harness.privateChannelClient.injectSessionEventForTesting(
                eventJson = welcomeEventJson(resumeToken = "resume-token-1"),
            )
            assertEquals("connected", awaitTransportStage(expected = setOf("connected")).stage)
        }

        fakeRuntime.enqueueResolveResult(true)
        val ackSuccessMs = elapsedMs {
            harness.privateChannelClient.injectSessionEventForTesting(
                eventJson = messageEventJson(
                    deliveryId = "delivery-ack-success",
                    messageId = "ack-success-message",
                    ackId = 101L,
                    seq = 11L,
                    decodeOk = true,
                ),
            )
            assertFalse(harness.inboundDeliveryLedgerRepository.shouldAck("delivery-ack-success"))
        }
        assertNotNull(harness.messageRepository.getByMessageId("ack-success-message"))

        fakeRuntime.enqueueResolveResult(false)
        val ackFailedMs = elapsedMs {
            harness.privateChannelClient.injectSessionEventForTesting(
                eventJson = messageEventJson(
                    deliveryId = "delivery-ack-failed",
                    messageId = "ack-failed-message",
                    ackId = 102L,
                    seq = 12L,
                    decodeOk = true,
                ),
            )
            assertTrue(harness.inboundDeliveryLedgerRepository.shouldAck("delivery-ack-failed"))
        }

        val ackRetryMs = elapsedMs {
            harness.privateChannelClient.injectSessionEventForTesting(
                eventJson = messageEventJson(
                    deliveryId = "delivery-ack-retry",
                    messageId = "ack-retry-message",
                    ackId = 103L,
                    seq = 13L,
                    decodeOk = false,
                ),
            )
            assertNull(harness.messageRepository.getByMessageId("ack-retry-message"))
            fakeRuntime.enqueueResolveResult(true)
            harness.privateChannelClient.injectSessionEventForTesting(
                eventJson = messageEventJson(
                    deliveryId = "delivery-ack-retry",
                    messageId = "ack-retry-message",
                    ackId = 104L,
                    seq = 14L,
                    decodeOk = true,
                ),
            )
            assertFalse(harness.inboundDeliveryLedgerRepository.shouldAck("delivery-ack-retry"))
        }

        harness.privateChannelClient.injectSessionEventForTesting(
            eventJson = disconnectedEventJson(transport = "quic", reason = "network_lost"),
        )
        assertTrue(awaitTransportStage(expected = setOf("closed", "goaway")).route == "private")

        val reconnectMs = elapsedMs {
            harness.privateChannelClient.injectSessionEventForTesting(
                eventJson = reconnectingEventJson(attempt = 2, backoffMs = 350L),
            )
            assertEquals("backoff", awaitTransportStage(expected = setOf("backoff")).stage)
            harness.privateChannelClient.injectSessionEventForTesting(
                eventJson = connectedEventJson(transport = "tcp", elapsedMs = 27L),
            )
            assertEquals("connected", awaitTransportStage(expected = setOf("connected")).stage)
        }

        val resumeFailedMs = elapsedMs {
            harness.privateChannelClient.injectSessionEventForTesting(
                eventJson = sessionEndedEventJson(reason = "resume_rejected", error = "stale resume token"),
                welcomeReceived = false,
            )
            val status = awaitTransportStage(expected = setOf("reconnecting", "backoff"))
            assertNotEquals("connected", status.stage)
        }

        harness.settingsRepository.setUseFcmChannel(true)
        harness.settingsRepository.setFcmToken("fcm-token-final")
        harness.privateChannelClient.setRuntime(fcmAvailable = true, systemToken = "fcm-token-final")
        val fcmStatus = awaitTransportStage(expected = setOf("active"))
        assertEquals("provider", fcmStatus.route)
        assertEquals("fcm", fcmStatus.transport)
        assertTrue(harness.settingsRepository.getUseFcmChannel())

        println(
            "RUNTIME_PRIVATE_STATEFLOW " +
                "start_ms=$startConvergeMs connected_ms=$connectedMs resume_success_ms=$resumeSuccessMs " +
                "ack_success_ms=$ackSuccessMs ack_failed_ms=$ackFailedMs ack_retry_ms=$ackRetryMs " +
                "reconnect_ms=$reconnectMs resume_failed_ms=$resumeFailedMs"
        )
    }

    @Test
    fun settingsViewModel_uiState_stays_consistent_with_repository_and_transport() = runBlocking {
        harness.settingsRepository.setServerAddress("http://127.0.0.1:9")
        harness.settingsRepository.setUseFcmChannel(true)
        harness.settingsRepository.setFcmToken("fcm-token-ui-default")
        harness.privateChannelClient.setRuntime(fcmAvailable = true, systemToken = "fcm-token-ui-default")

        val vm = buildSettingsViewModelOnMain()
        withContext(Dispatchers.Main) { vm.refreshChannelUiStateForTesting() }
        val defaultState = awaitUiState(vm) { state ->
            state.isChannelModeLoaded && state.useFcmChannel
        }
        assertTrue(defaultState.useFcmChannel)
        assertEquals(harness.settingsRepository.getUseFcmChannel(), defaultState.useFcmChannel)
        assertEquals("fcm-token-ui-default", defaultState.deviceToken)

        val switchToPrivateUiMs = elapsedMs {
            harness.settingsRepository.setUseFcmChannel(false)
            harness.settingsRepository.setFcmToken(null)
            harness.privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
            harness.privateChannelClient.injectSessionEventForTesting(
                eventJson = connectedEventJson(transport = "quic", elapsedMs = 15L),
            )
            withContext(Dispatchers.Main) { vm.refreshChannelUiStateForTesting() }
        }
        val privateState = awaitUiState(vm) { state ->
            state.isChannelModeLoaded && !state.useFcmChannel
        }
        assertFalse(privateState.useFcmChannel)
        assertNull(privateState.deviceToken)
        val expectedPrivateSummary = harness.privateChannelClient.summarizeConnectionStatus(
            snapshot = harness.privateChannelClient.readConnectionSnapshot(),
            privateModeEnabled = true,
        )
        assertEquals(expectedPrivateSummary, privateState.privateTransportStatus)

        harness.privateChannelClient.injectSessionEventForTesting(
            eventJson = reconnectingEventJson(attempt = 1, backoffMs = 120L),
        )
        withContext(Dispatchers.Main) { vm.refreshChannelUiStateForTesting() }
        val reconnectUiState = awaitUiState(vm) { state ->
            !state.useFcmChannel && state.privateTransportStatus.isNotBlank()
        }
        assertFalse(reconnectUiState.useFcmChannel)

        harness.privateChannelClient.injectSessionEventForTesting(
            eventJson = fatalEventJson(error = "transport probe failed"),
        )
        withContext(Dispatchers.Main) { vm.refreshChannelUiStateForTesting() }
        val fatalUiState = awaitUiState(vm) { state ->
            !state.useFcmChannel && state.privateTransportStatus.isNotBlank()
        }
        assertFalse(fatalUiState.useFcmChannel)

        // Failed switch simulation: repository stays in FCM mode, UI must not show private active channel.
        harness.settingsRepository.setUseFcmChannel(true)
        harness.settingsRepository.setFcmToken("fcm-token-after-failed-switch")
        harness.privateChannelClient.setRuntime(fcmAvailable = true, systemToken = "fcm-token-after-failed-switch")
        harness.privateChannelClient.injectSessionEventForTesting(
            eventJson = connectedEventJson(transport = "tcp", elapsedMs = 9L),
        )
        withContext(Dispatchers.Main) { vm.refreshChannelUiStateForTesting() }
        val failedSwitchState = awaitUiState(vm) { state ->
            state.isChannelModeLoaded && state.useFcmChannel
        }
        assertTrue(failedSwitchState.useFcmChannel)
        assertEquals("fcm-token-after-failed-switch", failedSwitchState.deviceToken)
        val expectedFcmSummary = harness.privateChannelClient.summarizeConnectionStatus(
            snapshot = harness.privateChannelClient.readConnectionSnapshot(),
            privateModeEnabled = false,
        )
        assertEquals(expectedFcmSummary, failedSwitchState.privateTransportStatus)

        val switchBackToFcmUiMs = elapsedMs { withContext(Dispatchers.Main) { vm.refreshChannelUiStateForTesting() } }

        harness.close()
        harness = openHarness()
        val vmReopened = buildSettingsViewModelOnMain()
        withContext(Dispatchers.Main) { vmReopened.refreshChannelUiStateForTesting() }
        val reopenedState = awaitUiState(vmReopened) { state ->
            state.isChannelModeLoaded && state.useFcmChannel
        }
        assertTrue(reopenedState.useFcmChannel)
        assertEquals(harness.settingsRepository.getUseFcmChannel(), reopenedState.useFcmChannel)
        assertEquals("fcm-token-after-failed-switch", harness.settingsRepository.getFcmToken())

        println(
            "RUNTIME_SETTINGS_UI " +
                "switch_fcm_to_private_ui_ms=$switchToPrivateUiMs switch_private_to_fcm_ui_ms=$switchBackToFcmUiMs"
        )
    }

    @Test
    fun settingsViewModel_saveDecryptionConfig_preservesUntouchedExistingKey() = runBlocking {
        val original = "0123456789abcdef".toByteArray()
        harness.settingsRepository.setNotificationKeyBytes(original)
        harness.settingsRepository.setKeyEncoding(KeyEncoding.BASE64)

        val vm = buildSettingsViewModelOnMain()
        val initialState = awaitUiState(vm) { state ->
            state.isDecryptionConfigured && state.keyEncoding == KeyEncoding.BASE64
        }
        assertTrue(initialState.decryptionKeyInput.isEmpty())

        withContext(Dispatchers.Main) {
            vm.updateKeyEncoding(KeyEncoding.HEX)
            vm.saveDecryptionConfig()
        }
        val savedState = awaitUiState(vm) { state ->
            state.isDecryptionConfigured &&
                state.keyEncoding == KeyEncoding.HEX &&
                !state.isSavingDecryption &&
                state.successMessage != null
        }

        assertTrue(savedState.isDecryptionConfigured)
        assertArrayEquals(original, harness.settingsRepository.getNotificationKeyBytes())
        assertEquals(KeyEncoding.HEX, harness.settingsRepository.getKeyEncoding())
        assertNotNull(harness.settingsRepository.getNotificationKeyUpdatedAt())
    }

    private fun buildSettingsViewModel(): SettingsViewModel {
        return SettingsViewModel(
            settingsRepository = harness.settingsRepository,
            channelRepository = harness.channelRepository,
            messageRepository = harness.messageRepository,
            messageStateCoordinator = MessageStateCoordinator(context, harness.messageRepository),
            privateChannelClient = harness.privateChannelClient,
            updateManager = UpdateManager(context, harness.settingsRepository),
            pushTokenProvider = object : PushTokenProvider {
                override suspend fun fetchToken(timeoutMs: Long): String? = null
            },
            gatewayPrivateChannelEnabledFetcher = { true },
        )
    }

    private fun buildSettingsViewModelOnMain(): SettingsViewModel {
        lateinit var vm: SettingsViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = buildSettingsViewModel()
        }
        return vm
    }

    private suspend fun awaitTransportStage(
        expected: Set<String>,
    ): PrivateChannelClient.TransportStatus {
        return withTimeout(8_000) {
            harness.privateChannelClient.transportStatusFlow.first { status ->
                expected.contains(status.stage)
            }
        }
    }

    private suspend fun awaitUiState(
        vm: SettingsViewModel,
        predicate: (SettingsUiState) -> Boolean,
    ): SettingsUiState {
        return withTimeout<SettingsUiState>(8_000) {
            while (true) {
                val state = vm.uiState.value
                if (predicate(state)) {
                    return@withTimeout state
                }
                withContext(Dispatchers.Main) {
                    Snapshot.sendApplyNotifications()
                }
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            error("awaitUiState timeout loop exited unexpectedly")
        }
    }

    private fun connectedEventJson(transport: String, elapsedMs: Long): String {
        return JSONObject()
            .put("type", "connected")
            .put("transport", transport)
            .put("elapsed_ms", elapsedMs)
            .toString()
    }

    private fun welcomeEventJson(resumeToken: String): String {
        return JSONObject()
            .put("type", "welcome")
            .put("wire_version", 2)
            .put("payload_version", 1)
            .put("resume_token", resumeToken)
            .put("max_backoff_secs", 60)
            .toString()
    }

    private fun disconnectedEventJson(transport: String, reason: String): String {
        return JSONObject()
            .put("type", "disconnected")
            .put("transport", transport)
            .put("reason", reason)
            .toString()
    }

    private fun reconnectingEventJson(attempt: Int, backoffMs: Long): String {
        return JSONObject()
            .put("type", "reconnecting")
            .put("attempt", attempt)
            .put("backoff_ms", backoffMs)
            .toString()
    }

    private fun sessionEndedEventJson(reason: String, error: String?): String {
        return JSONObject()
            .put("type", "session_ended")
            .put("reason", reason)
            .put("error", error ?: JSONObject.NULL)
            .toString()
    }

    private fun fatalEventJson(error: String): String {
        return JSONObject()
            .put("type", "fatal")
            .put("error", error)
            .toString()
    }

    private fun messageEventJson(
        deliveryId: String,
        messageId: String,
        ackId: Long,
        seq: Long,
        decodeOk: Boolean,
    ): String {
        val sentAt = Instant.now().toEpochMilli()
        val payload = JSONObject()
            .put("entity_type", "message")
            .put("entity_id", messageId)
            .put("message_id", messageId)
            .put("channel_id", "runtime-channel")
            .put("delivery_id", deliveryId)
            .put("op_id", "op-$deliveryId")
            .put("title", "title-$messageId")
            .put("body", "body-$messageId")
            .put("sent_at", sentAt.toString())
            .put("occurred_at", sentAt.toString())
            .put("provider", "private")
            .put("tags", JSONArray().put("runtime").toString())
            .put("metadata", JSONObject().put("source", "private-test").toString())

        return JSONObject()
            .put("type", "message")
            .put("delivery_id", deliveryId)
            .put("ack_id", ackId)
            .put("seq", seq)
            .put("decode_ok", decodeOk)
            .put("payload", payload)
            .toString()
    }

    private fun openHarness(): RuntimeHarness {
        val database = PushGoDatabase.build(context)
        database.openHelper.writableDatabase.query("PRAGMA journal_mode=WAL").close()
        database.openHelper.writableDatabase.query("PRAGMA synchronous=NORMAL").close()
        val secretStore = StateflowSecretStore(context)
        val settingsRepository = SettingsRepository(
            appSettingsDao = database.appSettingsDao(),
            secretStore = secretStore,
            settingsCache = context.getSharedPreferences("runtime_private_stateflow_cache", Context.MODE_PRIVATE),
        )
        val inbound = InboundDeliveryLedgerRepository(
            database = database,
            inboundDeliveryLedgerDao = database.inboundDeliveryLedgerDao(),
            inboundDeliveryAckOutboxDao = database.inboundDeliveryAckOutboxDao(),
        )
        val messageRepository = MessageRepository(
            database = database,
            dao = database.messageDao(),
            channelStatsDao = database.messageChannelStatsDao(),
            metadataIndexDao = database.messageMetadataIndexDao(),
            inboundDeliveryLedgerDao = database.inboundDeliveryLedgerDao(),
            operationLedgerDao = database.operationLedgerDao(),
            thingHeadDao = database.thingHeadDao(),
            thingSubMessageDao = database.thingSubMessageDao(),
            pendingThingMessageDao = database.pendingThingMessageDao(),
        )
        val entityRepository = EntityRepository(
            database = database,
            inboundDeliveryLedgerDao = database.inboundDeliveryLedgerDao(),
            operationLedgerDao = database.operationLedgerDao(),
            eventChangeLogDao = database.eventChangeLogDao(),
            thingChangeLogDao = database.thingChangeLogDao(),
            thingSubEventDao = database.thingSubEventDao(),
            topLevelEventHeadDao = database.topLevelEventHeadDao(),
            thingHeadDao = database.thingHeadDao(),
            thingSubMessageDao = database.thingSubMessageDao(),
            pendingThingEventDao = database.pendingThingEventDao(),
        )
        val channelRepository = ChannelSubscriptionRepository(
            store = ChannelSubscriptionStore(database.channelSubscriptionDao(), secretStore),
            settingsRepository = settingsRepository,
            messageStateCoordinator = MessageStateCoordinator(context, messageRepository),
            entityRepository = entityRepository,
            pushTokenProvider = object : PushTokenProvider {
                override suspend fun fetchToken(timeoutMs: Long): String? = null
            },
            service = ChannelSubscriptionService(ioDispatcher = Dispatchers.IO),
        )
        val privateChannelClient = PrivateChannelClient(
            appContext = context,
            channelRepository = channelRepository,
            inboundDeliveryLedgerRepository = inbound,
            messageRepository = messageRepository,
            entityRepository = entityRepository,
            settingsRepository = settingsRepository,
        )
        return RuntimeHarness(
            database = database,
            settingsRepository = settingsRepository,
            messageRepository = messageRepository,
            inboundDeliveryLedgerRepository = inbound,
            channelRepository = channelRepository,
            privateChannelClient = privateChannelClient,
        )
    }

    private fun cleanupDatabaseFamily(name: String) {
        val base = context.getDatabasePath(name)
        listOf(base, File(base.path + "-wal"), File(base.path + "-shm")).forEach { file ->
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private suspend fun elapsedMs(block: suspend () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000L
    }

    private companion object {
        private const val DATABASE_NAME = "pushgo-runtime-private-stateflow.db"
        private const val SECRET_PREFS_NAME = "runtime_private_stateflow_secret"
    }
}

private data class RuntimeHarness(
    val database: PushGoDatabase,
    val settingsRepository: SettingsRepository,
    val messageRepository: MessageRepository,
    val inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
    val channelRepository: ChannelSubscriptionRepository,
    val privateChannelClient: PrivateChannelClient,
) {
    fun close() {
        database.close()
    }
}

private class StateflowSecretStore(context: Context) : SecureSecretStore {
    private val prefs = context.getSharedPreferences("runtime_private_stateflow_secret", Context.MODE_PRIVATE)

    override fun gatewayToken(): String? = prefs.getString("gateway_token", null)?.trim()?.ifEmpty { null }
    override fun setGatewayToken(token: String?) {
        prefs.edit().putString("gateway_token", token?.trim()?.ifEmpty { null }).commit()
    }

    override fun gatewayAckToken(gatewayUrl: String): String? =
        prefs.getString("gateway_ack_token:${gatewayUrl.trim()}", null)?.trim()?.ifEmpty { null }

    override fun setGatewayAckToken(gatewayUrl: String, token: String?) {
        prefs.edit()
            .putString("gateway_ack_token:${gatewayUrl.trim()}", token?.trim()?.ifEmpty { null })
            .commit()
    }

    override fun fcmToken(): String? = prefs.getString("fcm_token", null)?.trim()?.ifEmpty { null }
    override fun setFcmToken(token: String?) {
        prefs.edit().putString("fcm_token", token?.trim()?.ifEmpty { null }).commit()
    }

    override fun deviceKey(): String? = prefs.getString("device_key", null)?.trim()?.ifEmpty { null }
    override fun setDeviceKey(deviceKey: String?) {
        prefs.edit().putString("device_key", deviceKey?.trim()?.ifEmpty { null }).commit()
    }

    override fun notificationKeyBytes(): ByteArray? {
        val raw = prefs.getString("notification_key_bytes", null) ?: return null
        return runCatching { android.util.Base64.decode(raw, android.util.Base64.NO_WRAP) }.getOrNull()
    }

    override fun setNotificationKeyBytes(value: ByteArray?) {
        val encoded = value?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
        prefs.edit().putString("notification_key_bytes", encoded).commit()
    }

    override fun channelPassword(gatewayUrl: String, channelId: String): String? {
        return prefs.getString("channel_password:${gatewayUrl.trim()}::${channelId.trim()}", null)
            ?.trim()
            ?.ifEmpty { null }
    }

    override fun setChannelPassword(gatewayUrl: String, channelId: String, password: String?) {
        prefs.edit()
            .putString("channel_password:${gatewayUrl.trim()}::${channelId.trim()}", password?.trim()?.ifEmpty { null })
            .commit()
    }

    override fun removeChannelPassword(gatewayUrl: String, channelId: String) {
        prefs.edit().remove("channel_password:${gatewayUrl.trim()}::${channelId.trim()}").commit()
    }

    override fun clearAll() {
        prefs.edit().clear().commit()
    }
}

private class FakeNativeRuntime : WarpLinkNativeBridge.SessionRuntime {
    private var nextHandle = 42L
    private val resolveResults = ArrayDeque<Boolean>()

    fun enqueueResolveResult(result: Boolean) {
        resolveResults.addLast(result)
    }

    override fun isAvailable(): Boolean = true
    override fun sessionStart(configJson: String): Long = nextHandle++
    override fun sessionPollEvent(handle: Long, timeoutMs: Int): String? = null
    override fun sessionStop(handle: Long) = Unit
    override fun sessionReplaceAuthToken(handle: Long, authToken: String?): Boolean = true
    override fun sessionResolveMessage(handle: Long, ackId: Long, status: Int): Boolean {
        return if (resolveResults.isEmpty()) true else resolveResults.removeFirst()
    }
    override fun sessionSetPowerHint(handle: Long, appState: String?, powerTier: String?): Boolean = true
    override fun sessionRequestProbe(handle: Long): Boolean = true
    override fun sessionForceReconnect(handle: Long): Boolean = true
    override fun sessionPinTransport(handle: Long, transport: String, ttlMs: Long): Boolean = true
    override fun sessionClearPin(handle: Long): Boolean = true
}
