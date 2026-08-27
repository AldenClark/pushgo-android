package io.ethan.pushgo.data

import android.content.Context
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.PrivateChannelClient
import io.ethan.pushgo.testing.InstrumentationRuntime
import io.ethan.pushgo.testing.QualityFixture
import io.ethan.pushgo.testing.QualityRuntime
import io.ethan.pushgo.ui.PendingLocalDeletionDrainScheduler
import io.ethan.pushgo.ui.PendingLocalDeletionCoordinator
import io.ethan.pushgo.ui.WorkManagerPendingLocalDeletionDrainScheduler
import io.ethan.pushgo.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import java.time.Instant

class AppContainer(
    context: Context,
    appScope: CoroutineScope,
    pendingLocalDeletionDrainScheduler: PendingLocalDeletionDrainScheduler = if (
        InstrumentationRuntime.isUnderInstrumentationTest()
    ) {
        PendingLocalDeletionDrainScheduler.None
    } else {
        WorkManagerPendingLocalDeletionDrainScheduler(context.applicationContext)
    },
) {
    val appContext = context.applicationContext
    val coroutineDispatchers = AppCoroutineDispatchers()
    val pushTokenProvider: PushTokenProvider = FirebasePushTokenProvider()
    internal val database = QualityRuntime.currentSession()?.let { session ->
            PushGoDatabase.buildForTest(appContext, session.databaseName)
        }
        ?: PushGoDatabase.build(appContext)
    internal val secureSecretStore: SecureSecretStore = AndroidKeystoreSecretStore(appContext)

    val messageImageStore = MessageImageStore(appContext)
    val settingsRepository = SettingsRepository(
        appSettingsDao = database.appSettingsDao(),
        secretStore = secureSecretStore,
        settingsCache = appContext.getSharedPreferences("pushgo_settings_cache", Context.MODE_PRIVATE),
    )
    val inboundDeliveryLedgerRepository = InboundDeliveryLedgerRepository(
        database = database,
        inboundDeliveryLedgerDao = database.inboundDeliveryLedgerDao(),
        inboundDeliveryAckOutboxDao = database.inboundDeliveryAckOutboxDao(),
        legacyProviderIngressDao = database.legacyProviderIngressDao(),
    )
    internal val channelStore = ChannelSubscriptionStore(
        dao = database.channelSubscriptionDao(),
        secretStore = secureSecretStore,
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
    val messageStateCoordinator = MessageStateCoordinator(
        context = appContext,
        repository = messageRepository,
    )
    val channelRepository = ChannelSubscriptionRepository(
        store = channelStore,
        settingsRepository = settingsRepository,
        messageStateCoordinator = messageStateCoordinator,
        messageRepository = messageRepository,
        entityRepository = entityRepository,
        database = database,
        pushTokenProvider = pushTokenProvider,
        service = ChannelSubscriptionService(ioDispatcher = coroutineDispatchers.io),
    )
    val privateChannelClient = PrivateChannelClient(
        appContext = appContext,
        channelRepository = channelRepository,
        inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
        messageRepository = messageRepository,
        entityRepository = entityRepository,
        settingsRepository = settingsRepository,
    )
    private val pendingLocalDeletionRepository = RoomPendingLocalDeletionRepository(
        database = database,
        dao = database.pendingLocalDeletionDao(),
    )
    private val pendingChannelDeletionExecutor = DurablePendingChannelDeletionExecutor(
        backend = RepositoryPendingChannelDeletionBackend(
            context = appContext,
            store = channelStore,
            settingsRepository = settingsRepository,
            channelRepository = channelRepository,
            privateChannelClient = privateChannelClient,
        ),
    )
    val pendingLocalDeletionCoordinator = PendingLocalDeletionCoordinator(
        appScope = appScope,
        repository = pendingLocalDeletionRepository,
        operationExecutor = RepositoryPendingLocalDeletionExecutor(
            context = appContext,
            messageStateCoordinator = messageStateCoordinator,
            entityRepository = entityRepository,
            channelExecutor = pendingChannelDeletionExecutor,
        ),
        drainScheduler = pendingLocalDeletionDrainScheduler,
    )
    val updateManager = UpdateManager(
        context = appContext,
        settingsRepository = settingsRepository,
    )
    val automationController = AppAutomationController(
        appContext = appContext,
        operationLedgerDao = database.operationLedgerDao(),
        settingsRepository = settingsRepository,
        channelStore = channelStore,
        messageRepository = messageRepository,
        entityRepository = entityRepository,
        messageStateCoordinator = messageStateCoordinator,
        channelRepository = channelRepository,
        privateChannelClient = privateChannelClient,
        inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
        messageImageStore = messageImageStore,
    )

    internal suspend fun initializeQualityFixtureIfNeeded() {
        val session = QualityRuntime.currentSession() ?: return
        val messages = when (session.fixture) {
            QualityFixture.EMPTY_CLEAN -> emptyList()
            QualityFixture.MESSAGES_STANDARD -> listOf(qualityMessage(index = 0))
            QualityFixture.MESSAGES_LARGE -> (0 until 1_000).map(::qualityMessage)
        }
        messageRepository.insertAll(messages)
    }

    suspend fun handlePushTokenUpdate(deviceToken: String) {
        settingsRepository.setFcmToken(deviceToken.trim().ifEmpty { null })
    }

    private fun qualityMessage(index: Int): PushMessage {
        val stableId = if (index == 0) "quality-standard-message" else "quality-large-$index"
        val title = if (index == 0) "P2 Split Seed Message" else "Quality message $index"
        val body = if (index == 0) {
            "Seeded from fixture.seed_messages for UI validation."
        } else {
            "Deterministic app-owned performance fixture row $index."
        }
        val receivedAt = Instant.parse("2026-01-15T08:00:00Z").plusSeconds(index.toLong())
        val rawPayload = JSONObject()
            .put("entity_type", "message")
            .put("message_id", stableId)
            .put("delivery_id", "quality-delivery-$stableId")
            .put("title", title)
            .put("body", body)
            .toString()
        return PushMessage(
            id = stableId,
            messageId = stableId,
            title = title,
            body = body,
            channel = "quality",
            url = null,
            isRead = false,
            receivedAt = receivedAt,
            rawPayloadJson = rawPayload,
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = "quality-session",
            bodyPreview = body,
        )
    }
}
