package io.ethan.pushgo.data

import android.content.Context
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.PrivateChannelClient

class AppContainer(context: Context) {
    val appContext = context.applicationContext
    internal val database = PushGoDatabase.build(appContext)
    internal val secureSecretStore: SecureSecretStore = AndroidKeystoreSecretStore(appContext)

    val messageImageStore = MessageImageStore(appContext)
    val settingsRepository = SettingsRepository(
        appSettingsDao = database.appSettingsDao(),
        secretStore = secureSecretStore,
        settingsCache = appContext.getSharedPreferences("pushgo_settings_cache", Context.MODE_PRIVATE),
    )
    val inboundDeliveryLedgerRepository = InboundDeliveryLedgerRepository(
        inboundDeliveryLedgerDao = database.inboundDeliveryLedgerDao(),
        inboundDeliveryAckOutboxDao = database.inboundDeliveryAckOutboxDao(),
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
    )
    val privateChannelClient = PrivateChannelClient(
        appContext = appContext,
        channelRepository = channelRepository,
        inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
        messageRepository = messageRepository,
        entityRepository = entityRepository,
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
