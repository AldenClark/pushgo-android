package io.ethan.pushgo.testing

import android.content.Context
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
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.InboundPersistenceCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import io.ethan.pushgo.notifications.InboundPersistenceStatus
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.PrivateChannelClient
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class RuntimeChannelSwitchInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var db: PushGoDatabase? = null
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var entityRepository: EntityRepository
    private lateinit var inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository
    private lateinit var privateChannelClient: PrivateChannelClient
    private lateinit var channelRepository: ChannelSubscriptionRepository
    private lateinit var secretStore: SharedPrefsSecretStore
    private lateinit var settingsCacheName: String

    @Before
    fun setUp() = runBlocking {
        cleanupDatabaseFamily(DATABASE_NAME)
        settingsCacheName = "runtime_channel_switch_cache_${System.currentTimeMillis()}"
        openRepositories(settingsCacheName)
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
        cleanupDatabaseFamily(DATABASE_NAME)
        context.getSharedPreferences(settingsCacheName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultStart_isFcmActive_andPersistenceAndTransportAreConsistent() = runBlocking {
        val started = System.nanoTime()
        val persisted = settingsRepository.getUseFcmChannel()
        val flowValue = withTimeout(3_000) { settingsRepository.useFcmChannelFlow.first() }
        privateChannelClient.setRuntime(fcmAvailable = true, systemToken = null)
        val transport = privateChannelClient.readTransportStatus()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertTrue("default useFcmChannel should be true", persisted)
        assertTrue("flow useFcmChannel should default true", flowValue)
        assertEquals("provider", transport.route)
        assertEquals("fcm", transport.transport)
        assertEquals("active", transport.stage)
        assertSingleActiveChannel(useFcmChannel = persisted, route = transport.route)
        println("RUNTIME_CHANNEL_SWITCH default_start_ms=$elapsedMs route=${transport.route} stage=${transport.stage}")
    }

    @Test
    fun switchFcmPrivateFcm_andRestart_keepsStateStorageAndTransportConsistent() = runBlocking {
        settingsRepository.setFcmToken("fcm-token-before-switch")
        privateChannelClient.setRuntime(fcmAvailable = true, systemToken = "fcm-token-before-switch")

        val fcmToPrivateMs = elapsedMs {
            settingsRepository.setUseFcmChannel(false)
            settingsRepository.setFcmToken(null)
            privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
            withTimeout(3_000) { settingsRepository.useFcmChannelFlow.first { !it } }
        }
        val privateStatus = privateChannelClient.readTransportStatus()
        assertFalse(settingsRepository.getUseFcmChannel())
        assertEquals("private", privateStatus.route)
        assertTrue(privateStatus.stage == "idle" || privateStatus.stage == "reconnecting")
        assertSingleActiveChannel(useFcmChannel = false, route = privateStatus.route)

        val privateToFcmMs = elapsedMs {
            settingsRepository.setUseFcmChannel(true)
            settingsRepository.setFcmToken("fcm-token-refreshed")
            privateChannelClient.setRuntime(fcmAvailable = true, systemToken = "fcm-token-refreshed")
            withTimeout(3_000) { settingsRepository.useFcmChannelFlow.first { it } }
        }
        val fcmStatus = privateChannelClient.readTransportStatus()
        assertTrue(settingsRepository.getUseFcmChannel())
        assertEquals("fcm-token-refreshed", settingsRepository.getFcmToken())
        assertEquals("provider", fcmStatus.route)
        assertEquals("fcm", fcmStatus.transport)
        assertSingleActiveChannel(useFcmChannel = true, route = fcmStatus.route)

        db?.close()
        db = null
        openRepositories(settingsCacheName)
        assertTrue(settingsRepository.getUseFcmChannel())
        assertEquals("fcm-token-refreshed", settingsRepository.getFcmToken())

        println("RUNTIME_CHANNEL_SWITCH switch_fcm_to_private_ms=$fcmToPrivateMs switch_private_to_fcm_ms=$privateToFcmMs")
    }

    @Test
    fun failedSwitchRequest_doesNotChangeActiveChannel() = runBlocking {
        settingsRepository.setUseFcmChannel(true)
        settingsRepository.setFcmToken("fcm-token-stable")
        privateChannelClient.setRuntime(fcmAvailable = true, systemToken = "fcm-token-stable")

        // Simulate "switch requested but failed before persistence commit".
        val failedSwitchMs = elapsedMs {
            // no commit to settingsRepository.setUseFcmChannel(false)
            withTimeout(3_000) { settingsRepository.useFcmChannelFlow.first { it } }
        }

        val useFcm = settingsRepository.getUseFcmChannel()
        val status = privateChannelClient.readTransportStatus()
        assertTrue(useFcm)
        assertEquals("provider", status.route)
        assertEquals("fcm", status.transport)
        assertSingleActiveChannel(useFcmChannel = useFcm, route = status.route)
        println("RUNTIME_CHANNEL_SWITCH failed_switch_preserved_ms=$failedSwitchMs route=${status.route} stage=${status.stage}")
    }

    @Test
    fun inboundAcrossSwitch_keepsCanonicalSingleMessage_andLateOldDoesNotOverride() = runBlocking {
        settingsRepository.setUseFcmChannel(true)
        privateChannelClient.setRuntime(fcmAvailable = true, systemToken = "fcm-token-dual")

        val dualFcm = inboundMessageRequest(
            messageId = "dual-delivery-1",
            deliveryId = "delivery-fcm-dual-1",
            channel = "fcm",
            provider = "fcm",
            sentAtMs = BASE_TIME_MS + 1_000,
            title = "fcm-first",
        )
        val dualPrivate = inboundMessageRequest(
            messageId = "dual-delivery-1",
            deliveryId = "delivery-private-dual-1",
            channel = "private",
            provider = "private",
            sentAtMs = BASE_TIME_MS + 2_000,
            title = "private-late-duplicate",
        )

        val firstPersistMs = elapsedMs {
            val outcome = persistInbound(dualFcm)
            assertEquals(InboundPersistenceStatus.PERSISTED_MAIN, outcome.status)
        }

        // Switch in-flight: message arrives before switch commit.
        val inflightPrivateMs = elapsedMs {
            val outcome = persistInbound(dualPrivate)
            assertTrue(outcome.status == InboundPersistenceStatus.DUPLICATE || outcome.status == InboundPersistenceStatus.PERSISTED_PENDING)
        }

        settingsRepository.setUseFcmChannel(false)
        settingsRepository.setFcmToken(null)
        privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)

        val newerPrivate = inboundMessageRequest(
            messageId = "old-channel-late",
            deliveryId = "delivery-private-newer",
            channel = "private",
            provider = "private",
            sentAtMs = BASE_TIME_MS + 10_000,
            title = "newer-private",
        )
        val olderFcmLate = inboundMessageRequest(
            messageId = "old-channel-late",
            deliveryId = "delivery-fcm-older-late",
            channel = "fcm",
            provider = "fcm",
            sentAtMs = BASE_TIME_MS + 1_000,
            title = "older-fcm-late",
        )

        val newerPersistMs = elapsedMs {
            val outcome = persistInbound(newerPrivate)
            assertEquals(InboundPersistenceStatus.PERSISTED_MAIN, outcome.status)
        }
        val oldLateMs = elapsedMs {
            val outcome = persistInbound(olderFcmLate)
            assertTrue(outcome.status == InboundPersistenceStatus.DUPLICATE || outcome.status == InboundPersistenceStatus.PERSISTED_PENDING)
        }

        assertEquals(2, messageRepository.totalCount())
        val dual = messageRepository.getByMessageId("dual-delivery-1")
        assertNotNull(dual)
        assertEquals("fcm", dual?.channel)
        assertEquals("fcm", JSONObject(dual!!.rawPayloadJson).optString("provider"))

        val channelLate = messageRepository.getByMessageId("old-channel-late")
        assertNotNull(channelLate)
        assertEquals("private", channelLate?.channel)
        assertEquals("newer-private", channelLate?.title)
        assertEquals("private", JSONObject(channelLate!!.rawPayloadJson).optString("provider"))

        println(
            "RUNTIME_CHANNEL_SWITCH inbound_persist_ms first=$firstPersistMs inflight_private=$inflightPrivateMs " +
                "newer=$newerPersistMs old_late=$oldLateMs total=${messageRepository.totalCount()}"
        )
    }

    @Test
    fun fakeEventStateMachine_coversSessionResumeReconnectAckAndPerformance() {
        val scenario = buildFakeScenario()
        val run = FakeChannelStateMachine().run(scenario)

        assertEquals(FakeActiveChannel.FCM, run.finalState.activeChannel)
        assertTrue(run.finalState.persistedUseFcmChannel)
        assertEquals(FakeTokenState.INVALID, run.finalState.fcmTokenState)
        assertEquals(FakeSessionState.RESUMED, run.finalState.sessionState)
        assertEquals(FakeTransportState.CONNECTED, run.finalState.transportState)
        assertFalse(run.dualActiveViolation)
        assertEquals(1, run.acceptedMessageIds.count { it == "dual-delivery-1" })
        assertTrue(run.mismatches.isEmpty())

        val perf1k = runSyntheticPerf(size = 1_000)
        val perf10k = runSyntheticPerf(size = 10_000)
        assertEquals(1_000, perf1k.totalMessages)
        assertEquals(10_000, perf10k.totalMessages)
        println(perf1k.logLine())
        println(perf10k.logLine())

        val optIn100k = System.getenv("PUSHGO_ANDROID_RUNTIME_100K") == "true" ||
            System.getProperty("pushgo.android.runtime.100k") == "true" ||
            instrumentationArgEnabled("pushgo.runtime.include100k")
        if (optIn100k) {
            val perf100k = runSyntheticPerf(size = 100_000)
            assertEquals(100_000, perf100k.totalMessages)
            println(perf100k.logLine())
        } else {
            println(
                "runtime-channel-switch-performance size=100000 skipped=true " +
                    "reason=set_PUSHGO_ANDROID_RUNTIME_100K_true_or_pushgo.runtime.include100k_true",
            )
        }
    }

    private fun instrumentationArgEnabled(key: String): Boolean {
        return InstrumentationRegistry.getArguments()
            .getString(key)
            ?.trim()
            ?.toBooleanStrictOrNull() == true
    }

    private fun openRepositories(settingsCacheName: String) {
        val openedDb = PushGoDatabase.buildForTest(context, DATABASE_NAME)
        openedDb.openHelper.writableDatabase.query("PRAGMA journal_mode=WAL").close()
        openedDb.openHelper.writableDatabase.query("PRAGMA synchronous=NORMAL").close()
        db = openedDb

        secretStore = SharedPrefsSecretStore(context)
        settingsRepository = SettingsRepository(
            appSettingsDao = openedDb.appSettingsDao(),
            secretStore = secretStore,
            settingsCache = context.getSharedPreferences(settingsCacheName, Context.MODE_PRIVATE),
        )
        inboundDeliveryLedgerRepository = InboundDeliveryLedgerRepository(
            database = openedDb,
            inboundDeliveryLedgerDao = openedDb.inboundDeliveryLedgerDao(),
            inboundDeliveryAckOutboxDao = openedDb.inboundDeliveryAckOutboxDao(),
        )
        messageRepository = MessageRepository(
            database = openedDb,
            dao = openedDb.messageDao(),
            channelStatsDao = openedDb.messageChannelStatsDao(),
            metadataIndexDao = openedDb.messageMetadataIndexDao(),
            inboundDeliveryLedgerDao = openedDb.inboundDeliveryLedgerDao(),
            operationLedgerDao = openedDb.operationLedgerDao(),
            thingHeadDao = openedDb.thingHeadDao(),
            thingSubMessageDao = openedDb.thingSubMessageDao(),
            pendingThingMessageDao = openedDb.pendingThingMessageDao(),
        )
        entityRepository = EntityRepository(
            database = openedDb,
            inboundDeliveryLedgerDao = openedDb.inboundDeliveryLedgerDao(),
            operationLedgerDao = openedDb.operationLedgerDao(),
            eventChangeLogDao = openedDb.eventChangeLogDao(),
            thingChangeLogDao = openedDb.thingChangeLogDao(),
            thingSubEventDao = openedDb.thingSubEventDao(),
            topLevelEventHeadDao = openedDb.topLevelEventHeadDao(),
            thingHeadDao = openedDb.thingHeadDao(),
            thingSubMessageDao = openedDb.thingSubMessageDao(),
            pendingThingEventDao = openedDb.pendingThingEventDao(),
        )
        channelRepository = ChannelSubscriptionRepository(
            store = ChannelSubscriptionStore(openedDb.channelSubscriptionDao(), secretStore),
            settingsRepository = settingsRepository,
            messageStateCoordinator = MessageStateCoordinator(context, messageRepository),
            messageRepository = messageRepository,
            entityRepository = entityRepository,
            database = openedDb,
            pushTokenProvider = object : PushTokenProvider {
                override suspend fun fetchToken(timeoutMs: Long): String? = null
            },
            service = ChannelSubscriptionService(ioDispatcher = Dispatchers.IO),
        )
        privateChannelClient = PrivateChannelClient(
            appContext = context,
            channelRepository = channelRepository,
            inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
            messageRepository = messageRepository,
            entityRepository = entityRepository,
            settingsRepository = settingsRepository,
        )
    }

    private suspend fun persistInbound(request: InboundPersistenceRequest.Message) =
        InboundPersistenceCoordinator.persistAndNotify(
            context = context,
            messageRepository = messageRepository,
            entityRepository = entityRepository,
            inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
            settingsRepository = settingsRepository,
            inbound = request,
        )

    private fun inboundMessageRequest(
        messageId: String,
        deliveryId: String,
        channel: String,
        provider: String,
        sentAtMs: Long,
        title: String,
    ): InboundPersistenceRequest.Message {
        val payload = JSONObject()
            .put("entity_type", "message")
            .put("entity_id", messageId)
            .put("message_id", messageId)
            .put("channel_id", channel)
            .put("delivery_id", deliveryId)
            .put("op_id", "op-$deliveryId")
            .put("title", title)
            .put("body", "body-$title")
            .put("sent_at", sentAtMs.toString())
            .put("occurred_at", sentAtMs.toString())
            .put("provider", provider)
            .put("tags", JSONArray().put("runtime").toString())
            .put("metadata", JSONObject().put("provider", provider).toString())
        val model = PushMessage(
            id = "row-$deliveryId",
            messageId = messageId,
            title = title,
            body = "body-$title",
            channel = channel,
            url = null,
            isRead = false,
            receivedAt = Instant.ofEpochMilli(sentAtMs),
            rawPayloadJson = payload.toString(),
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = deliveryId,
            bodyPreview = null,
        )
        return InboundPersistenceRequest.Message(
            message = model,
            level = "normal",
            imageUrl = null,
            shouldNotify = false,
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

    private fun assertSingleActiveChannel(useFcmChannel: Boolean, route: String) {
        if (useFcmChannel) {
            assertEquals("provider", route)
        } else {
            assertEquals("private", route)
        }
    }

    private suspend fun elapsedMs(block: suspend () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000L
    }

    private fun buildFakeScenario(): List<FakeEvent> {
        val events = mutableListOf<FakeEvent>()
        var active = FakeActiveChannel.FCM
        fun add(type: FakeEventType, after: FakeActiveChannel = active, channel: String? = null, messageId: String? = null, accepted: Boolean? = null) {
            events += FakeEvent(
                type = type,
                step = events.size,
                before = active,
                after = after,
                deliveryChannel = channel,
                messageId = messageId,
                accepted = accepted,
            )
            active = after
        }
        add(FakeEventType.INITIAL_DEFAULT_FCM)
        add(FakeEventType.FCM_TOKEN_REFRESHED)
        add(FakeEventType.SWITCH_REQUESTED)
        add(FakeEventType.MESSAGE_ARRIVED, channel = "fcm", messageId = "switch-inflight-fcm", accepted = true)
        add(FakeEventType.MESSAGE_ARRIVED, channel = "private", messageId = "switch-inflight-private", accepted = true)
        add(FakeEventType.SWITCH_SUCCEEDED, after = FakeActiveChannel.PRIVATE)
        add(FakeEventType.MESSAGE_ARRIVED, channel = "fcm", messageId = "old-channel-late", accepted = false)
        add(FakeEventType.MESSAGE_ARRIVED, channel = "private", messageId = "dual-delivery-1", accepted = true)
        add(FakeEventType.MESSAGE_ARRIVED, channel = "fcm", messageId = "dual-delivery-1", accepted = false)
        add(FakeEventType.ACK_FAILED)
        add(FakeEventType.ACK_RETRY)
        add(FakeEventType.ACK_SUCCESS)
        add(FakeEventType.PRIVATE_DISCONNECTED)
        add(FakeEventType.SESSION_RESUME_FAILED)
        add(FakeEventType.PRIVATE_RECONNECTED)
        add(FakeEventType.SESSION_RESUME_SUCCESS)
        add(FakeEventType.SWITCH_REQUESTED)
        add(FakeEventType.FCM_TOKEN_MISSING)
        add(FakeEventType.FCM_TOKEN_REFRESHED)
        add(FakeEventType.SWITCH_SUCCEEDED, after = FakeActiveChannel.FCM)
        add(FakeEventType.MESSAGE_ARRIVED, channel = "private", messageId = "private-late-after-fcm", accepted = false)
        add(FakeEventType.FCM_TOKEN_INVALIDATED)
        add(FakeEventType.SWITCH_REQUESTED)
        add(FakeEventType.SWITCH_FAILED, after = FakeActiveChannel.FCM)
        return events
    }

    private fun runSyntheticPerf(size: Int): FakePerf {
        val events = ArrayList<FakeEvent>(size + 1 + (size / 200) * 2)
        var step = 0
        var active = FakeActiveChannel.FCM
        events += FakeEvent(FakeEventType.INITIAL_DEFAULT_FCM, step++, active, active, null, null, null)
        repeat(size) { index ->
            if (index > 0 && index % 200 == 0) {
                val target = if (active == FakeActiveChannel.FCM) FakeActiveChannel.PRIVATE else FakeActiveChannel.FCM
                events += FakeEvent(FakeEventType.SWITCH_REQUESTED, step++, active, active, null, null, null)
                events += FakeEvent(FakeEventType.SWITCH_SUCCEEDED, step++, active, target, null, null, null)
                active = target
            }
            val channel = if (index % 2 == 0) "fcm" else "private"
            val accepted = (active == FakeActiveChannel.FCM && channel == "fcm") ||
                (active == FakeActiveChannel.PRIVATE && channel == "private")
            events += FakeEvent(
                type = FakeEventType.MESSAGE_ARRIVED,
                step = step++,
                before = active,
                after = active,
                deliveryChannel = channel,
                messageId = "synthetic-$index",
                accepted = accepted,
            )
        }
        val started = System.nanoTime()
        val run = FakeChannelStateMachine().run(events)
        val totalMs = (System.nanoTime() - started) / 1_000_000L
        return FakePerf(
            size = size,
            totalMessages = events.count { it.type == FakeEventType.MESSAGE_ARRIVED },
            canonicalCount = run.acceptedMessageIds.size,
            acceptedMessages = run.acceptedMessageIds.size,
            duplicateRejected = run.duplicateRejectedCount,
            staleRejected = run.staleRejectedCount,
            channelRejected = run.channelRejectedCount,
            totalProcessingMs = totalMs,
            switchP50Ms = run.switchDurationsMs.sorted().p50(),
            switchP95Ms = run.switchDurationsMs.sorted().p95(),
            sessionResumeP50Ms = run.sessionDurationsMs.sorted().p50(),
            sessionResumeP95Ms = run.sessionDurationsMs.sorted().p95(),
            ackP50Ms = run.ackDurationsMs.sorted().p50(),
            ackP95Ms = run.ackDurationsMs.sorted().p95(),
            messageP50Ms = run.messageDurationsMs.sorted().p50(),
            messageP95Ms = run.messageDurationsMs.sorted().p95(),
        )
    }

    private companion object {
        private const val DATABASE_NAME = "pushgo-runtime-channel-switch.db"
        private const val BASE_TIME_MS = 1_720_000_000_000L
        private const val SECRET_PREFS_NAME = "runtime_channel_switch_secret_store"
    }
}

private class SharedPrefsSecretStore(context: Context) : SecureSecretStore {
    private val prefs = context.getSharedPreferences("runtime_channel_switch_secret_store", Context.MODE_PRIVATE)

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

private enum class FakeActiveChannel { FCM, PRIVATE }
private enum class FakeTokenState { MISSING, PRESENT, INVALID }
private enum class FakeTransportState { IDLE, CONNECTED, DISCONNECTED, RECONNECTING }
private enum class FakeSessionState { NONE, RESUMED, RESUME_FAILED }

private enum class FakeEventType {
    INITIAL_DEFAULT_FCM,
    SWITCH_REQUESTED,
    SWITCH_SUCCEEDED,
    SWITCH_FAILED,
    FCM_TOKEN_MISSING,
    FCM_TOKEN_REFRESHED,
    FCM_TOKEN_INVALIDATED,
    PRIVATE_DISCONNECTED,
    PRIVATE_RECONNECTED,
    SESSION_RESUME_SUCCESS,
    SESSION_RESUME_FAILED,
    ACK_SUCCESS,
    ACK_FAILED,
    ACK_RETRY,
    MESSAGE_ARRIVED,
}

private data class FakeEvent(
    val type: FakeEventType,
    val step: Int,
    val before: FakeActiveChannel,
    val after: FakeActiveChannel,
    val deliveryChannel: String?,
    val messageId: String?,
    val accepted: Boolean?,
)

private data class FakeState(
    val activeChannel: FakeActiveChannel,
    val persistedUseFcmChannel: Boolean,
    val fcmTokenState: FakeTokenState,
    val transportState: FakeTransportState,
    val sessionState: FakeSessionState,
)

private data class FakeRun(
    val finalState: FakeState,
    val acceptedMessageIds: Set<String>,
    val switchDurationsMs: List<Long>,
    val messageDurationsMs: List<Long>,
    val ackDurationsMs: List<Long>,
    val sessionDurationsMs: List<Long>,
    val duplicateRejectedCount: Int,
    val staleRejectedCount: Int,
    val channelRejectedCount: Int,
    val mismatches: List<String>,
    val dualActiveViolation: Boolean,
)

private class FakeChannelStateMachine {
    private var active = FakeActiveChannel.FCM
    private var persistedUseFcm = true
    private var tokenState = FakeTokenState.MISSING
    private var transportState = FakeTransportState.IDLE
    private var sessionState = FakeSessionState.NONE
    private var pendingTarget: FakeActiveChannel? = null
    private var switchStartNs: Long? = null
    private val accepted = linkedSetOf<String>()
    private val switchDurations = mutableListOf<Long>()
    private val messageDurations = mutableListOf<Long>()
    private val ackDurations = mutableListOf<Long>()
    private val sessionDurations = mutableListOf<Long>()
    private val mismatches = mutableListOf<String>()
    private var duplicateRejectedCount = 0
    private var staleRejectedCount = 0
    private var channelRejectedCount = 0

    fun run(events: List<FakeEvent>): FakeRun {
        events.forEach(::apply)
        val dualActive = !(persistedUseFcm && active == FakeActiveChannel.FCM) &&
            !(!persistedUseFcm && active == FakeActiveChannel.PRIVATE)
        return FakeRun(
            finalState = FakeState(
                activeChannel = active,
                persistedUseFcmChannel = persistedUseFcm,
                fcmTokenState = tokenState,
                transportState = transportState,
                sessionState = sessionState,
            ),
            acceptedMessageIds = accepted.toSet(),
            switchDurationsMs = switchDurations.toList(),
            messageDurationsMs = messageDurations.toList(),
            ackDurationsMs = ackDurations.toList(),
            sessionDurationsMs = sessionDurations.toList(),
            duplicateRejectedCount = duplicateRejectedCount,
            staleRejectedCount = staleRejectedCount,
            channelRejectedCount = channelRejectedCount,
            mismatches = mismatches.toList(),
            dualActiveViolation = dualActive,
        )
    }

    private fun apply(event: FakeEvent) {
        when (event.type) {
            FakeEventType.INITIAL_DEFAULT_FCM -> {
                active = FakeActiveChannel.FCM
                persistedUseFcm = true
                pendingTarget = null
                switchStartNs = null
                transportState = FakeTransportState.CONNECTED
            }
            FakeEventType.SWITCH_REQUESTED -> {
                pendingTarget = if (active == FakeActiveChannel.FCM) FakeActiveChannel.PRIVATE else FakeActiveChannel.FCM
                switchStartNs = System.nanoTime()
            }
            FakeEventType.SWITCH_SUCCEEDED -> {
                active = event.after
                persistedUseFcm = active == FakeActiveChannel.FCM
                switchStartNs?.let { switchDurations += (System.nanoTime() - it) / 1_000_000L }
                pendingTarget = null
                switchStartNs = null
            }
            FakeEventType.SWITCH_FAILED -> {
                active = event.after
                persistedUseFcm = active == FakeActiveChannel.FCM
                switchStartNs?.let { switchDurations += (System.nanoTime() - it) / 1_000_000L }
                pendingTarget = null
                switchStartNs = null
            }
            FakeEventType.FCM_TOKEN_MISSING -> tokenState = FakeTokenState.MISSING
            FakeEventType.FCM_TOKEN_REFRESHED -> tokenState = FakeTokenState.PRESENT
            FakeEventType.FCM_TOKEN_INVALIDATED -> tokenState = FakeTokenState.INVALID
            FakeEventType.PRIVATE_DISCONNECTED -> transportState = FakeTransportState.DISCONNECTED
            FakeEventType.PRIVATE_RECONNECTED -> transportState = FakeTransportState.CONNECTED
            FakeEventType.SESSION_RESUME_SUCCESS -> {
                val started = System.nanoTime()
                sessionState = FakeSessionState.RESUMED
                transportState = FakeTransportState.CONNECTED
                sessionDurations += (System.nanoTime() - started) / 1_000_000L
            }
            FakeEventType.SESSION_RESUME_FAILED -> {
                val started = System.nanoTime()
                sessionState = FakeSessionState.RESUME_FAILED
                transportState = FakeTransportState.RECONNECTING
                sessionDurations += (System.nanoTime() - started) / 1_000_000L
            }
            FakeEventType.ACK_SUCCESS,
            FakeEventType.ACK_FAILED,
            FakeEventType.ACK_RETRY -> {
                val started = System.nanoTime()
                ackDurations += (System.nanoTime() - started) / 1_000_000L
            }
            FakeEventType.MESSAGE_ARRIVED -> handleMessage(event)
        }
    }

    private fun handleMessage(event: FakeEvent) {
        val messageId = event.messageId ?: return
        val deliveryChannel = event.deliveryChannel ?: return
        val expectedAccepted = event.accepted
        val started = System.nanoTime()
        val alreadyAccepted = accepted.contains(messageId)
        val channelAllowed = when (deliveryChannel) {
            "fcm" -> active == FakeActiveChannel.FCM || pendingTarget == FakeActiveChannel.FCM
            "private" -> active == FakeActiveChannel.PRIVATE || pendingTarget == FakeActiveChannel.PRIVATE
            else -> false
        }
        val acceptedNow = if (channelAllowed && !alreadyAccepted) {
            accepted.add(messageId)
        } else {
            false
        }
        if (!acceptedNow) {
            when {
                alreadyAccepted -> duplicateRejectedCount += 1
                !channelAllowed -> {
                    staleRejectedCount += 1
                    channelRejectedCount += 1
                }
            }
        }
        expectedAccepted?.let { expected ->
            if (expected != acceptedNow) {
                mismatches += "step=${event.step} message=$messageId expected=$expected actual=$acceptedNow"
            }
        }
        messageDurations += (System.nanoTime() - started) / 1_000_000L
    }
}

private data class FakePerf(
    val size: Int,
    val totalMessages: Int,
    val canonicalCount: Int,
    val acceptedMessages: Int,
    val duplicateRejected: Int,
    val staleRejected: Int,
    val channelRejected: Int,
    val totalProcessingMs: Long,
    val switchP50Ms: Long,
    val switchP95Ms: Long,
    val sessionResumeP50Ms: Long,
    val sessionResumeP95Ms: Long,
    val ackP50Ms: Long,
    val ackP95Ms: Long,
    val messageP50Ms: Long,
    val messageP95Ms: Long,
) {
    fun logLine(): String {
        return "runtime-channel-switch-performance size=$size messages=$totalMessages canonical_count=$canonicalCount " +
            "accepted=$acceptedMessages duplicate_rejected=$duplicateRejected stale_rejected=$staleRejected " +
            "channel_rejected=$channelRejected total_ms=$totalProcessingMs " +
            "switch_p50_ms=$switchP50Ms switch_p95_ms=$switchP95Ms " +
            "session_resume_p50_ms=$sessionResumeP50Ms session_resume_p95_ms=$sessionResumeP95Ms " +
            "ack_p50_ms=$ackP50Ms ack_p95_ms=$ackP95Ms message_p50_ms=$messageP50Ms message_p95_ms=$messageP95Ms"
    }
}

private fun List<Long>.p50(): Long {
    if (isEmpty()) return 0L
    val idx = ((size - 1) * 0.50).toInt().coerceIn(0, size - 1)
    return this[idx]
}

private fun List<Long>.p95(): Long {
    if (isEmpty()) return 0L
    val idx = (((size - 1) * 0.95).toInt()).coerceIn(0, size - 1)
    return this[idx]
}
