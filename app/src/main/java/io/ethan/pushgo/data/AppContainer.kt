package io.ethan.pushgo.data

import android.content.Context
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.PrivateChannelClient
import io.ethan.pushgo.testing.InstrumentationRuntime
import io.ethan.pushgo.ui.PendingLocalDeletionDrainScheduler
import io.ethan.pushgo.ui.PendingLocalDeletionCoordinator
import io.ethan.pushgo.ui.WorkManagerPendingLocalDeletionDrainScheduler
import io.ethan.pushgo.update.UpdateManager
import kotlinx.coroutines.CoroutineScope

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
    internal val database = PushGoDatabase.build(appContext)
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

    suspend fun handlePushTokenUpdate(deviceToken: String) {
        settingsRepository.setFcmToken(deviceToken.trim().ifEmpty { null })
    }
}
