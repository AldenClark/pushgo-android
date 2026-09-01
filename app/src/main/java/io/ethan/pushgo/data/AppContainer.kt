package io.ethan.pushgo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessagePreviewExtractor
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import io.ethan.pushgo.notifications.InboundPersistenceStatus
import io.ethan.pushgo.notifications.NotificationIngressParser
import io.ethan.pushgo.notifications.PrivateChannelClient
import io.ethan.pushgo.notifications.PrivateChannelServiceManager
import io.ethan.pushgo.testing.InstrumentationRuntime
import io.ethan.pushgo.testing.QualityFixture
import io.ethan.pushgo.testing.QualityChannelMutationScenario
import io.ethan.pushgo.testing.QualityEventCloseScenario
import io.ethan.pushgo.testing.QualityRuntime
import io.ethan.pushgo.testing.QualityTransportSwitchException
import io.ethan.pushgo.testing.QualityTransportSwitchScenario
import io.ethan.pushgo.testing.QualityUpdateScenario
import io.ethan.pushgo.ui.PendingLocalDeletionDrainScheduler
import io.ethan.pushgo.ui.PendingLocalDeletionCoordinator
import io.ethan.pushgo.ui.WorkManagerPendingLocalDeletionDrainScheduler
import io.ethan.pushgo.update.UpdateManager
import io.ethan.pushgo.update.UpdateFeedEntry
import io.ethan.pushgo.update.UpdateFeedClient
import io.ethan.pushgo.update.UpdateFeedPayload
import io.ethan.pushgo.util.UrlValidators
import io.ethan.pushgo.util.FcmSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    private val qualitySession = QualityRuntime.currentSession()
    private val qualityTransportScenario =
        qualitySession?.transportSwitchScenario ?: QualityTransportSwitchScenario.NONE
    init {
        check(qualitySession?.faults?.failLocalStoreInitialization != true) {
            "Quality-injected local persistent storage initialization failure."
        }
    }
    val coroutineDispatchers = AppCoroutineDispatchers()
    val pushTokenProvider: PushTokenProvider =
        if (qualityTransportScenario != QualityTransportSwitchScenario.NONE) {
            object : PushTokenProvider {
                override suspend fun fetchToken(timeoutMs: Long): String =
                    "quality-transport-fcm-token"
            }
        } else {
            FirebasePushTokenProvider()
        }
    internal val database = qualitySession?.let { session ->
            PushGoDatabase.buildForTest(appContext, session.databaseName)
        }
        ?: PushGoDatabase.build(appContext)
    internal val secureSecretStore: SecureSecretStore = AndroidKeystoreSecretStore(
        context = appContext,
        preferenceFileName = qualitySession?.securePreferencesName
            ?: AndroidKeystoreSecretStore.PRODUCTION_PREFERENCE_FILE,
    )

    val messageImageStore = MessageImageStore(appContext)
    val settingsRepository = SettingsRepository(
        appSettingsDao = database.appSettingsDao(),
        secretStore = secureSecretStore,
        settingsCache = appContext.getSharedPreferences(
            qualitySession?.settingsCachePreferencesName ?: "pushgo_settings_cache",
            Context.MODE_PRIVATE,
        ),
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
        eventCloseRoundTrip = QualityRuntime.currentSession()?.eventCloseScenario
            ?.takeUnless { it == QualityEventCloseScenario.NONE }
            ?.let { scenario ->
                val closeAttempts = AtomicInteger(0)
                val closeInFlight = AtomicBoolean(false)
                EventCloseRoundTrip { outbound ->
                    check(closeInFlight.compareAndSet(false, true)) {
                        "a second event close crossed the boundary while the first was in flight"
                    }
                    try {
                        val attempt = closeAttempts.incrementAndGet()
                        if (scenario == QualityEventCloseScenario.FAIL_ONCE_THEN_ACCEPTED_AND_DELIVERED) {
                            delay(2_500)
                            if (attempt == 1) {
                                throw ChannelSubscriptionException.local(
                                    message = "Event close was rejected. Try again.",
                                    code = "quality_event_close_rejected_once",
                                    category = GatewayErrorCategory.UPSTREAM,
                                )
                            }
                        }
                        val eventId = outbound.optString("event_id").trim()
                        check(eventId.isNotEmpty()) { "quality event close response missing event_id" }
                        val delivered = buildMap {
                            outbound.keys().forEach { key -> put(key, outbound.opt(key)?.toString().orEmpty()) }
                            put("entity_type", "event")
                            put("entity_id", eventId)
                            put("event_state", "closed")
                            put("delivery_id", "quality-event-close-$eventId")
                            put("sent_at", "2026-01-15T08:03:00Z")
                        }
                        val parsed = checkNotNull(
                            NotificationIngressParser.parse(
                                data = delivered,
                                transportMessageId = "quality-event-close-$eventId",
                                keyBytes = null,
                                textLocalizer = NotificationIngressParser.NotificationTextLocalizer.fromContext(appContext),
                            ) as? InboundPersistenceRequest.Entity
                        ) { "quality event close response did not parse as an event" }
                        val outcome = InboundPersistenceCoordinator.persistAndNotify(
                            context = appContext,
                            messageRepository = messageRepository,
                            entityRepository = entityRepository,
                            inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                            settingsRepository = settingsRepository,
                            inbound = parsed.copy(shouldNotify = false),
                        )
                        check(
                            outcome.status == InboundPersistenceStatus.PERSISTED_MAIN ||
                                outcome.status == InboundPersistenceStatus.DUPLICATE
                        ) { "quality event close response did not reach the canonical store" }
                    } finally {
                        closeInFlight.set(false)
                    }
                }
            },
        channelMutationRoundTrip = QualityRuntime.currentSession()?.channelMutationScenario
            ?.takeUnless { it == QualityChannelMutationScenario.NONE }
            ?.let { scenario ->
            object : ChannelMutationRoundTrip {
                private var subscribeAttempts = 0
                private var renameAttempts = 0
                private val activeCreatedChannelIds = mutableSetOf<String>()

                private fun requireExpectedGateway(gatewayUrl: String) {
                    val expected = qualitySession?.expectedChannelMutationGatewayUrl ?: return
                    check(
                        UrlValidators.normalizeGatewayBaseUrl(gatewayUrl) ==
                            UrlValidators.normalizeGatewayBaseUrl(expected)
                    ) {
                        "quality channel operation was routed through the wrong gateway"
                    }
                }

                override suspend fun ensureProviderRoute(
                    gatewayUrl: String,
                    providerToken: String,
                ): String {
                    requireExpectedGateway(gatewayUrl)
                    check(providerToken.isNotBlank()) { "quality channel route requires a provider token" }
                    return "quality-channel-device"
                }

                override suspend fun sync(
                    gatewayUrl: String,
                    channels: List<ChannelSyncItem>,
                ): List<ChannelSyncResult> {
                    requireExpectedGateway(gatewayUrl)
                    return channels.map { item ->
                        ChannelSyncResult(
                            channelId = item.channelId,
                            channelName = if (
                                item.channelId == "01H00000000000000000000004"
                            ) {
                                "Quality Recovery Sync Completed"
                            } else {
                                null
                            },
                            subscribed = true,
                            errorCode = null,
                            error = null,
                        )
                    }
                }

                override suspend fun subscribe(
                    gatewayUrl: String,
                    channelId: String?,
                    channelName: String?,
                    password: String,
                ): ChannelSubscribeResult {
                    requireExpectedGateway(gatewayUrl)
                    check(password.isNotBlank()) { "quality channel subscribe requires a password" }
                    subscribeAttempts += 1
                    if (
                        scenario == QualityChannelMutationScenario.REJECT_ONCE_THEN_ACCEPTED &&
                        subscribeAttempts == 1
                    ) {
                        throw ChannelSubscriptionException.local(
                            message = "Channel password is incorrect. Check the password and try again.",
                            code = "password_mismatch",
                            category = GatewayErrorCategory.CONFLICT,
                        )
                    }
                    val resolvedId = channelId ?: "01H00000000000000000000003"
                    if (
                        scenario == QualityChannelMutationScenario.REQUIRE_CREATE_COMPENSATION &&
                        channelId == null &&
                        resolvedId in activeCreatedChannelIds
                    ) {
                        throw ChannelSubscriptionException.local(
                            message = "The previous channel creation was not compensated.",
                            code = "channel_compensation_missing",
                            category = GatewayErrorCategory.CONFLICT,
                        )
                    }
                    if (channelId == null) {
                        activeCreatedChannelIds += resolvedId
                    }
                    return ChannelSubscribeResult(
                        channelId = resolvedId,
                        channelName = channelName?.trim()?.ifEmpty { null } ?: resolvedId,
                        created = channelId == null,
                        subscribed = true,
                    )
                }

                override suspend fun rename(
                    gatewayUrl: String,
                    channelId: String,
                    channelName: String,
                    password: String,
                ): ChannelRenameResult {
                    requireExpectedGateway(gatewayUrl)
                    check(password.isNotBlank()) { "quality channel rename requires a password" }
                    renameAttempts += 1
                    if (
                        scenario == QualityChannelMutationScenario.RENAME_REJECT_ONCE_THEN_ACCEPTED &&
                        renameAttempts == 1
                    ) {
                        throw ChannelSubscriptionException.local(
                            message = "The channel rename was rejected. Check the name and try again.",
                            code = "channel_rename_rejected",
                            category = GatewayErrorCategory.CONFLICT,
                        )
                    }
                    return ChannelRenameResult(channelId = channelId, channelName = channelName)
                }

                override suspend fun unsubscribe(gatewayUrl: String, channelId: String) {
                    requireExpectedGateway(gatewayUrl)
                    check(channelId.isNotBlank()) { "quality channel unsubscribe requires an id" }
                    activeCreatedChannelIds -= channelId
                }
            }
        },
    )
    val privateChannelClient = PrivateChannelClient(
        appContext = appContext,
        channelRepository = channelRepository,
        inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
        messageRepository = messageRepository,
        entityRepository = entityRepository,
        settingsRepository = settingsRepository,
    )
    val transportSwitchCoordinator = TransportSwitchCoordinator(
        store = RoomTransportTransitionStore(database.transportTransitionDao()),
        secretStore = secureSecretStore,
        gateway = if (qualityTransportScenario == QualityTransportSwitchScenario.NONE) {
            privateChannelClient
        } else {
            object : TransportTransitionGateway {
                private var routeRevision = 1L
                private var state = TransportTransitionRemoteState.PREPARED
                private var channelType = "fcm"
                private var privateAttempts = 0
                private var fcmAttempts = 0
                private var activeOperationId: String? = null

                override suspend fun loadContext() = TransportTransitionContext(
                    gatewayUrl = "https://quality.invalid",
                    deviceKey = "quality-transport-device",
                    routeRevision = routeRevision,
                    routeTransitionV2 = true,
                )

                override suspend fun prepare(
                    operationId: String,
                    context: TransportTransitionContext,
                    channelType: String,
                    providerToken: String?,
                ): PreparedTransportTransition {
                    val attempt = if (channelType == "fcm") ++fcmAttempts else ++privateAttempts
                    if (
                        qualityTransportScenario ==
                            QualityTransportSwitchScenario.REJECT_ONCE_THEN_ACCEPTED &&
                        attempt == 1
                    ) {
                        throw QualityTransportSwitchException()
                    }
                    this.channelType = channelType
                    state = TransportTransitionRemoteState.PREPARED
                    activeOperationId = operationId
                    return PreparedTransportTransition("quality-$operationId", context.routeRevision)
                }

                override suspend fun commit(
                    operationId: String,
                    transitionId: String,
                ): CommittedTransportTransition {
                    routeRevision += 1
                    state = TransportTransitionRemoteState.COMMITTED
                    return CommittedTransportTransition(routeRevision, channelType)
                }

                override suspend fun abort(
                    operationId: String,
                    transitionId: String,
                ): TransportTransitionSnapshot {
                    state = TransportTransitionRemoteState.ABORTED
                    return TransportTransitionSnapshot(state, routeRevision, channelType)
                }

                override suspend fun query(
                    operationId: String,
                    transitionId: String?,
                    deviceKey: String,
                ): TransportTransitionSnapshot {
                    if (activeOperationId != operationId) {
                        throw TransportTransitionNotFoundException("quality operation not found")
                    }
                    return TransportTransitionSnapshot(
                        state = state,
                        routeRevision = routeRevision,
                        channelType = channelType,
                        transitionId = "quality-$operationId",
                        committedRevision = routeRevision.takeIf {
                            state == TransportTransitionRemoteState.COMMITTED
                        },
                        candidateChannelType = channelType.takeIf {
                            state == TransportTransitionRemoteState.COMMITTED
                        },
                    )
                }
            }
        },
        selectionApplier = object : TransportSelectionApplier {
            override suspend fun apply(useFcm: Boolean, providerToken: String?) {
                QualityRuntime.afterTransportSelectionPersistence()
                settingsRepository.setFcmToken(providerToken.takeIf { useFcm })
                settingsRepository.setUseFcmChannel(useFcm)
                privateChannelClient.setRuntime(
                    fcmAvailable = useFcm,
                    systemToken = providerToken.takeIf { useFcm },
                )
                PrivateChannelServiceManager.refreshForMode(appContext, useFcm)
            }
        },
    )
    val fcmSupportChecker: (Context) -> Boolean =
        if (
            qualityTransportScenario != QualityTransportSwitchScenario.NONE ||
            qualitySession?.channelMutationScenario != QualityChannelMutationScenario.NONE
        ) {
            { true }
        } else {
            FcmSupport::isAvailable
        }
    val gatewayPrivateChannelEnabledFetcher: suspend () -> Boolean? =
        if (
            qualityTransportScenario != QualityTransportSwitchScenario.NONE ||
            qualitySession?.channelMutationScenario != QualityChannelMutationScenario.NONE
        ) {
            // Gateway mutation journeys own a controlled transport boundary.
            // Do not let a post-commit sync Oracle spend its budget on a real
            // capability/DNS probe; production sessions still use the real
            // gateway profile fetch below.
            { true }
        } else {
            { privateChannelClient.gatewayPrivateChannelEnabled() }
        }
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
        feedFetcher = qualitySession?.updateScenario
            ?.takeUnless { it == QualityUpdateScenario.NONE }
            ?.let { scenario ->
                {
                    check(
                        scenario == QualityUpdateScenario.AVAILABLE_STABLE ||
                            scenario == QualityUpdateScenario.AVAILABLE_STABLE_AND_BETA
                    )
                    val artifact = qualitySession.updateArtifact
                    UpdateFeedPayload(
                        entries = buildList {
                            add(
                                UpdateFeedEntry(
                                    channel = "stable",
                                    versionCode = artifact?.versionCode ?: Int.MAX_VALUE - 1,
                                    versionName = artifact?.versionName ?: "9.9.9-quality",
                                    apkUrl = artifact?.apkUrl
                                        ?: "https://quality.invalid/pushgo-9.9.9.apk",
                                    apkSha256 = artifact?.apkSha256 ?: "0".repeat(64),
                                    notes = "Quality update improves message delivery reliability.",
                                )
                            )
                            if (scenario == QualityUpdateScenario.AVAILABLE_STABLE_AND_BETA) {
                                add(
                                    UpdateFeedEntry(
                                        channel = "beta",
                                        versionCode = Int.MAX_VALUE,
                                        versionName = "10.0.0-beta-quality",
                                        apkUrl = "https://quality.invalid/pushgo-10.0.0-beta.apk",
                                        apkSha256 = "1".repeat(64),
                                        notes = "Quality beta update exercises the opt-in channel.",
                                    )
                                )
                            }
                        },
                    )
                }
            }
            ?: UpdateFeedClient(appContext)::fetchFeed,
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
        if (QualityRuntime.fixtureInitializationWasRecorded(appContext.filesDir)) return
        val messages = when (session.fixture) {
            QualityFixture.EMPTY_CLEAN -> emptyList()
            QualityFixture.MESSAGES_STANDARD -> listOf(qualityMessage(index = 0, includesMedia = true))
            QualityFixture.MESSAGES_ENCRYPTED_VALID,
            QualityFixture.MESSAGES_ENCRYPTED_CORRUPT -> emptyList()
            // 134 rows produce exactly 100 unread messages (every fourth row is read).
            // That keeps the normal workflow fixture small while exercising the real
            // navigation-badge boundary: 100 -> "99+", then one read -> 99.
            QualityFixture.MESSAGES_WORKFLOW -> (0 until 134).map(::qualityWorkflowMessage)
            QualityFixture.MESSAGES_FILTERS -> qualityFilterMessages()
            QualityFixture.MESSAGES_CLEANUP -> qualityCleanupMessages()
            QualityFixture.MESSAGES_MARKDOWN -> listOf(qualityMarkdownMessage())
            QualityFixture.MESSAGES_LARGE -> (0 until 1_000).map { qualityMessage(index = it) }
            QualityFixture.CHANNELS_STANDARD -> listOf(
                qualityChannelMessage(
                    id = "quality-channel-keep-message",
                    title = "Quality Keep History Message",
                    channelId = "01H00000000000000000000001",
                    receivedAt = Instant.parse("2026-01-15T08:01:00Z"),
                ),
                qualityChannelMessage(
                    id = "quality-channel-delete-message",
                    title = "Quality Delete History Message",
                    channelId = "01H00000000000000000000002",
                    receivedAt = Instant.parse("2026-01-15T09:02:00Z"),
                ),
            )
            QualityFixture.EVENT_STANDARD,
            QualityFixture.THING_STANDARD -> emptyList()
        }
        messageRepository.insertAll(messages)
        when (session.fixture) {
            QualityFixture.MESSAGES_ENCRYPTED_VALID,
            QualityFixture.MESSAGES_ENCRYPTED_CORRUPT -> {
                val isCorrupt = session.fixture == QualityFixture.MESSAGES_ENCRYPTED_CORRUPT
                val messageId = if (isCorrupt) {
                    "quality-corrupt-encrypted-message"
                } else {
                    "quality-encrypted-message"
                }
                val parsed = checkNotNull(
                    NotificationIngressParser.parse(
                        data = qualityEncryptedMessagePayload(corruptCiphertext = isCorrupt),
                        transportMessageId = if (isCorrupt) {
                            "quality-corrupt-encrypted-delivery"
                        } else {
                            "quality-encrypted-delivery"
                        },
                        keyBytes = null,
                        textLocalizer = NotificationIngressParser.NotificationTextLocalizer.fromContext(appContext),
                    ) as? InboundPersistenceRequest.Message
                ) { "${session.fixture.wireValue} did not parse as a message" }
                check(messageRepository.insertIncoming(parsed.message)) {
                    "${session.fixture.wireValue} did not reach the canonical store"
                }
                val stored = checkNotNull(
                    messageRepository.getByMessageId(messageId)
                ) { "${session.fixture.wireValue} canonical message is missing" }
                check(stored.decryptionState == io.ethan.pushgo.data.model.DecryptionState.NOT_CONFIGURED)
                check(stored.body == "Configure decryption to read this message.")
            }
            QualityFixture.EVENT_STANDARD -> {
                seedQualityEventSubscription()
                entityRepository.insertIncoming(qualityThing(
                    title = "Quality Initial Thing Snapshot",
                    deliverySuffix = "initial",
                    receivedAt = Instant.parse("2026-01-15T08:00:00Z"),
                ))
                entityRepository.insertIncoming(qualityThing(
                    title = "Quality Reactor Alpha",
                    deliverySuffix = "current",
                    receivedAt = Instant.parse("2026-01-15T08:01:00Z"),
                ))
                entityRepository.insertIncoming(qualityEvent())
                entityRepository.insertIncoming(
                    qualityEvent(
                        thingId = "quality-thing",
                        relatedIdentity = false,
                        deliverySuffix = "thing-projection",
                    )
                )
                (0 until 16).forEach { index ->
                    entityRepository.insertIncoming(qualityNavigationEvent(index))
                }
                check(entityRepository.eventCount() == 17) {
                    "event.standard did not reach its canonical projection"
                }
                check(entityRepository.thingCount() == 1) {
                    "event.standard linked Thing did not reach its canonical projection"
                }
            }
            QualityFixture.THING_STANDARD -> {
                seedQualityEventSubscription()
                entityRepository.insertIncoming(qualityThing(
                    title = "Quality Initial Thing Snapshot",
                    deliverySuffix = "initial",
                    receivedAt = Instant.parse("2026-01-15T08:00:00Z"),
                ))
                entityRepository.insertIncoming(qualityThing(
                    title = "Quality Reactor Alpha",
                    deliverySuffix = "current",
                    receivedAt = Instant.parse("2026-01-15T08:01:00Z"),
                ))
                entityRepository.insertIncoming(qualityThingDistractor())
                (0 until 16).forEach { index ->
                    entityRepository.insertIncoming(qualityNavigationThing(index))
                }
                entityRepository.insertIncoming(qualityEvent(thingId = "quality-thing"))
                messageRepository.insertIncoming(qualityThingMessage())
                check(entityRepository.thingCount() == 18) {
                    "thing.standard did not reach its canonical projection"
                }
            }
            QualityFixture.CHANNELS_STANDARD -> {
                val rawGateway = settingsRepository.getServerAddress()
                    ?.trim()
                    ?.ifEmpty { null }
                    ?: AppConstants.defaultServerAddress
                val gateway = UrlValidators.normalizeGatewayBaseUrl(rawGateway)
                    ?: AppConstants.defaultServerAddress
                settingsRepository.setFcmToken("quality-channel-provider-token")
                if (session.faults.failGatewayPostCommitSyncOnce) {
                    // The pending-recovery journey must exercise the same FCM
                    // route used by production syncSubscriptionsIfNeeded(),
                    // rather than silently taking the private-route branch.
                    settingsRepository.setUseFcmChannel(true)
                }
                channelStore.upsertSubscription(
                    gateway,
                    "01H00000000000000000000001",
                    "Quality Keep History",
                    "quality-channel-password",
                )
                channelStore.upsertSubscription(
                    gateway,
                    "01H00000000000000000000002",
                    "Quality Delete History",
                    "quality-channel-password",
                )
                session.expectedChannelMutationGatewayUrl
                    ?.let(UrlValidators::normalizeGatewayBaseUrl)
                    ?.let { candidateGateway ->
                        // This hidden candidate-scoped credential gives the
                        // post-restart recovery sync a concrete business
                        // effect.  The UI later asserts its renamed row before
                        // creating a separate channel.
                        channelStore.upsertSubscription(
                            candidateGateway,
                            "01H00000000000000000000004",
                            "Quality Recovery Sync Pending",
                            "quality-channel-password",
                        )
                    }
            }
            else -> Unit
        }
        // Record completion only after every store mutation and canonical-projection
        // check succeeds. Live row counts may legitimately change during the journey.
        QualityRuntime.recordFixtureInitialization(appContext.filesDir)
    }

    /**
     * The composition root owns the first settings read.  Resolve an
     * interrupted gateway transition before UI, services, workers, or startup
     * sync can observe a mixed address/token/device-key identity.
     */
    internal suspend fun recoverGatewayTransitionBeforeUse(): GatewayTransitionStartupRecovery =
        settingsRepository.recoverGatewayTransitionAtStartup()

    private suspend fun seedQualityEventSubscription() {
        val rawGateway = settingsRepository.getServerAddress()
            ?.trim()
            ?.ifEmpty { null }
            ?: AppConstants.defaultServerAddress
        val gateway = UrlValidators.normalizeGatewayBaseUrl(rawGateway)
            ?: AppConstants.defaultServerAddress
        channelStore.upsertSubscription(
            gateway,
            "01H00000000000000000000000",
            "Quality",
            "quality-fixture-value",
        )
    }

    private fun qualityEncryptedMessagePayload(
        corruptCiphertext: Boolean = false,
    ): Map<String, String> {
        val keyBytes = "QualityKey123456".toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12) { index -> index.toByte() }
        val plaintext = JSONObject()
            .put("title", "Recovered Quality Message")
            .put("body", "Recovered from the original encrypted payload.")
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, iv),
        )
        val ciphertextAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val envelope = ByteArray(ciphertextAndTag.size + iv.size)
        System.arraycopy(ciphertextAndTag, 0, envelope, 0, ciphertextAndTag.size)
        System.arraycopy(iv, 0, envelope, ciphertextAndTag.size, iv.size)
        if (corruptCiphertext && envelope.isNotEmpty()) {
            envelope[0] = (envelope[0].toInt() xor 0x01).toByte()
        }
        return mapOf(
            "entity_type" to "message",
            "message_id" to if (corruptCiphertext) {
                "quality-corrupt-encrypted-message"
            } else {
                "quality-encrypted-message"
            },
            "delivery_id" to if (corruptCiphertext) {
                "quality-corrupt-encrypted-delivery"
            } else {
                "quality-encrypted-delivery"
            },
            "title" to if (corruptCiphertext) "Corrupt Encrypted Message" else "Encrypted Quality Message",
            "body" to "Configure decryption to read this message.",
            "ciphertext" to Base64.getEncoder().encodeToString(envelope),
            "sent_at" to "2026-01-15T08:00:00Z",
        )
    }

    suspend fun handlePushTokenUpdate(deviceToken: String) {
        settingsRepository.setFcmToken(deviceToken.trim().ifEmpty { null })
    }

    private fun qualityMessage(index: Int, includesMedia: Boolean = false): PushMessage {
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
            .apply {
                if (includesMedia) {
                    put("severity", "high")
                    val image = qualityStandardMessageImage()
                    put("images", JSONArray(listOf(QUALITY_STANDARD_MESSAGE_IMAGE_URL)).toString())
                    put(MessageImageStore.KEY_IMAGE_LOCAL_PATH, image.absolutePath)
                    put(MessageImageStore.KEY_IMAGE_THUMBNAIL_LOCAL_PATH, image.absolutePath)
                    put("url", QUALITY_STANDARD_MESSAGE_URL)
                }
            }
            .toString()
        return PushMessage(
            id = stableId,
            messageId = stableId,
            title = title,
            body = body,
            channel = "quality",
            url = if (includesMedia) QUALITY_STANDARD_MESSAGE_URL else null,
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

    private fun qualityStandardMessageImage(): File {
        val sessionID = qualitySession?.sessionId.orEmpty().filter { it.isLetterOrDigit() || it == '-' }
        val imageDirectory = File(appContext.filesDir, "quality-fixtures/$sessionID").apply { mkdirs() }
        val image = File(imageDirectory, "standard-message.png")
        if (!image.exists()) {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(32, 122, 255))
            image.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            bitmap.recycle()
        }
        File(image.parentFile, "${image.name}.meta.json").writeText(
            JSONObject()
                .put("expiresAtEpochMillis", System.currentTimeMillis() + QUALITY_IMAGE_TTL_MS)
                .toString(),
        )
        return image
    }

    private fun qualityCleanupMessages(): List<PushMessage> {
        val now = Instant.now()
        return listOf(
            qualityCleanupMessage(
                id = "quality-cleanup-old",
                title = "Quality Old Cleanup Target",
                receivedAt = now.minus(45, java.time.temporal.ChronoUnit.DAYS),
            ),
            qualityCleanupMessage(
                id = "quality-cleanup-recent",
                title = "Quality Recent Cleanup Control",
                receivedAt = now.minus(2, java.time.temporal.ChronoUnit.DAYS),
            ),
        )
    }

    private fun qualityCleanupMessage(
        id: String,
        title: String,
        receivedAt: Instant,
    ): PushMessage {
        val body = "Deterministic cleanup boundary message."
        return PushMessage(
            id = id,
            messageId = id,
            title = title,
            body = body,
            channel = "quality-cleanup",
            url = null,
            isRead = false,
            receivedAt = receivedAt,
            rawPayloadJson = JSONObject()
                .put("entity_type", "message")
                .put("message_id", id)
                .put("delivery_id", "quality-delivery-$id")
                .put("title", title)
                .put("body", body)
                .put("sent_at", receivedAt.toString())
                .toString(),
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = "quality-session",
            bodyPreview = body,
        )
    }

    private companion object {
        const val QUALITY_IMAGE_TTL_MS = 24L * 60L * 60L * 1000L
        const val QUALITY_STANDARD_MESSAGE_IMAGE_URL =
            "https://quality-media.pushgo.dev/standard-message.png"
        const val QUALITY_STANDARD_MESSAGE_URL = "https://pushgo.dev/quality-message"
    }

    private fun qualityWorkflowMessage(index: Int): PushMessage {
        val stableId = "quality-workflow-$index"
        val title = "Quality workflow $index"
        val body = "Cross-page deterministic workflow row $index."
        val channel = if (index % 2 == 0) "workflow-alpha" else "workflow-beta"
        // Preserve the 100-unread badge oracle while making the two navigation
        // outcomes visibly different: single reselect -> row 119, double -> row 133.
        val isRead = index >= 120 || (index % 4 == 0 && index >= 40)
        val rawPayload = JSONObject()
            .put("entity_type", "message")
            .put("message_id", stableId)
            .put("delivery_id", "quality-delivery-$stableId")
            .put("title", title)
            .put("body", body)
            .put("tags", org.json.JSONArray(listOf("workflow", if (index % 2 == 0) "even" else "odd")))
            .toString()
        return PushMessage(
            id = stableId,
            messageId = stableId,
            title = title,
            body = body,
            channel = channel,
            url = null,
            isRead = isRead,
            receivedAt = Instant.parse("2026-01-15T08:00:00Z").plusSeconds(index.toLong()),
            rawPayloadJson = rawPayload,
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = "quality-session",
            bodyPreview = body,
        )
    }

    private fun qualityMarkdownMessage(): PushMessage {
        val stableId = "quality-markdown-message"
        val title = "Quality Markdown Structure"
        val longContent = (1..24).joinToString(separator = "\n\n") { index ->
            "Long content paragraph $index: multilingual text 中文繁體 العربية emoji 👩🏽‍💻 remains readable after wrapping."
        }
        val baseBody = """
            # Quality Markdown Heading

            - [x] Completed deployment check
            - Pending operator review

            > Production quote remains visible

            | Service | State |
            | --- | --- |
            | Gateway | Healthy |

            `pushgo status` and [Open quality guide](https://example.com/pushgo-quality)

            ```json
            {"environment":"quality"}
            ```
        """.trimIndent()
        val body = "$baseBody\n\n$longContent\n\n" +
            "## Unicode completion sentinel 终点 終點 Ω مرحبا 👩🏽‍💻"
        return PushMessage(
            id = stableId,
            messageId = stableId,
            title = title,
            body = body,
            channel = "quality-markdown",
            url = null,
            isRead = false,
            receivedAt = Instant.parse("2026-01-15T08:00:00Z"),
            rawPayloadJson = JSONObject()
                .put("entity_type", "message")
                .put("message_id", stableId)
                .put("delivery_id", "quality-delivery-markdown")
                .put("title", title)
                .put("body", body)
                .toString(),
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = "quality-session",
            bodyPreview = MessagePreviewExtractor.listPreview(body),
        )
    }

    private fun qualityFilterMessages(): List<PushMessage> = listOf(
        qualityFilterMessage(
            id = "quality-filter-alpha-even",
            title = "Quality filter alpha even",
            channel = "filter-alpha",
            tags = listOf("workflow", "even"),
            isRead = false,
            offsetSeconds = 5,
        ),
        qualityFilterMessage(
            id = "quality-filter-alpha-odd",
            title = "Quality filter alpha odd",
            channel = "filter-alpha",
            tags = listOf("workflow", "odd"),
            isRead = true,
            offsetSeconds = 4,
        ),
        qualityFilterMessage(
            id = "quality-filter-beta-odd",
            title = "Quality filter beta odd",
            channel = "filter-beta",
            tags = listOf("workflow", "odd"),
            isRead = false,
            offsetSeconds = 3,
        ),
        qualityFilterMessage(
            id = "quality-filter-beta-even",
            title = "Quality filter beta even",
            channel = "filter-beta",
            tags = listOf("ops", "even"),
            isRead = false,
            offsetSeconds = 2,
        ),
        qualityFilterMessage(
            id = "quality-filter-ungrouped",
            title = "Quality filter ungrouped orphan",
            channel = "",
            tags = listOf("orphan"),
            isRead = false,
            offsetSeconds = 1,
        ),
    )

    private fun qualityFilterMessage(
        id: String,
        title: String,
        channel: String,
        tags: List<String>,
        isRead: Boolean,
        offsetSeconds: Long,
    ): PushMessage {
        val body = "Exact filter fixture body for $id."
        return PushMessage(
            id = id,
            messageId = id,
            title = title,
            body = body,
            channel = channel,
            url = null,
            isRead = isRead,
            receivedAt = Instant.parse("2026-01-15T08:00:00Z").plusSeconds(offsetSeconds),
            rawPayloadJson = JSONObject()
                .put("entity_type", "message")
                .put("message_id", id)
                .put("delivery_id", "quality-delivery-$id")
                .put("channel_id", channel)
                .put("title", title)
                .put("body", body)
                .put("tags", org.json.JSONArray(tags))
                .toString(),
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = "quality-session",
            bodyPreview = body,
        )
    }

    private fun qualityChannelMessage(
        id: String,
        title: String,
        channelId: String,
        receivedAt: Instant,
    ): PushMessage {
        val body = "Deterministic history owned by $channelId."
        return PushMessage(
            id = id,
            messageId = id,
            title = title,
            body = body,
            channel = channelId,
            url = null,
            isRead = false,
            receivedAt = receivedAt,
            rawPayloadJson = JSONObject()
                .put("entity_type", "message")
                .put("message_id", id)
                .put("delivery_id", "quality-delivery-$id")
                .put("channel_id", channelId)
                .put("title", title)
                .put("body", body)
                .toString(),
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = "quality-session",
            bodyPreview = body,
        )
    }

    private fun qualityEvent(
        thingId: String? = null,
        relatedIdentity: Boolean = thingId != null,
        deliverySuffix: String? = null,
    ): IncomingEntityRecord {
        val stableId = if (relatedIdentity) "quality-related-event" else "quality-event"
        val title = if (relatedIdentity) "Quality Related Event" else "Quality Cooling Alert"
        val description = if (!relatedIdentity) {
            "Cooling loop temperature crossed the quality threshold."
        } else {
            "A deterministic event associated with Quality Reactor Alpha."
        }
        val receivedAt = if (!relatedIdentity) {
            Instant.parse("2026-01-15T08:02:00Z")
        } else {
            Instant.parse("2026-01-15T08:03:00Z")
        }
        val deliveryIdentity = deliverySuffix
            ?.let { "$stableId-$it" }
            ?: stableId
        val payload = JSONObject()
            .put("entity_type", "event")
            .put("entity_id", stableId)
            .put("event_id", stableId)
            .put("delivery_id", "quality-delivery-$deliveryIdentity")
            .put("op_id", "quality-op-$deliveryIdentity")
            .put("event_state", "ONGOING")
            .put("event_time", receivedAt.toString())
            .put("title", title)
            .put("description", description)
            .put("status", "investigating")
            .put("message", "Inspect the deterministic cooling fixture.")
            .put("severity", "high")
            .put("tags", JSONArray(listOf("quality", "cooling")))
        thingId?.let { payload.put("thing_id", it) }
        return IncomingEntityRecord(
            entityType = "event",
            entityId = stableId,
            channel = "01H00000000000000000000000",
            title = title,
            body = "Inspect the deterministic cooling fixture.",
            rawPayloadJson = payload.toString(),
            receivedAt = receivedAt,
            opId = "quality-op-$deliveryIdentity",
            deliveryId = "quality-delivery-$deliveryIdentity",
            serverId = "quality-session",
            eventId = stableId,
            thingId = thingId,
            eventState = "ONGOING",
            eventTimeEpoch = receivedAt.toEpochMilli(),
            observedTimeEpoch = null,
        )
    }

    private fun qualityNavigationEvent(index: Int): IncomingEntityRecord {
        val suffix = index.toString().padStart(2, '0')
        val eventId = "quality-event-navigation-$suffix"
        val title = "Navigation Event $suffix"
        val receivedAt = Instant.parse("2026-01-15T07:$suffix:00Z")
        val summary = "Deterministic off-screen Event used to prove current-tab return-to-top."
        val payload = JSONObject()
            .put("entity_type", "event")
            .put("entity_id", eventId)
            .put("event_id", eventId)
            .put("delivery_id", "quality-delivery-$eventId")
            .put("op_id", "quality-op-$eventId")
            .put("event_state", "CLOSED")
            .put("event_time", receivedAt.toString())
            .put("title", title)
            .put("description", summary)
            .put("status", "closed")
            .put("message", summary)
            .put("severity", "normal")
        return IncomingEntityRecord(
            entityType = "event",
            entityId = eventId,
            channel = "quality-navigation",
            title = title,
            body = summary,
            rawPayloadJson = payload.toString(),
            receivedAt = receivedAt,
            opId = "quality-op-$eventId",
            deliveryId = "quality-delivery-$eventId",
            serverId = "quality-session",
            eventId = eventId,
            thingId = null,
            eventState = "CLOSED",
            eventTimeEpoch = receivedAt.toEpochMilli(),
            observedTimeEpoch = null,
        )
    }

    private fun qualityThing(
        title: String,
        deliverySuffix: String,
        receivedAt: Instant,
    ): IncomingEntityRecord {
        val payload = JSONObject()
            .put("entity_type", "thing")
            .put("entity_id", "quality-thing")
            .put("thing_id", "quality-thing")
            .put("delivery_id", "quality-delivery-thing-$deliverySuffix")
            .put("op_id", "quality-op-thing-$deliverySuffix")
            .put("observed_at", receivedAt.toString())
            .put("title", title)
            .put("description", "A deterministic reactor with linked events, messages, and updates.")
            .put("state", "active")
            .put("tags", JSONArray(listOf("quality", "reactor")))
            .put("attrs", JSONObject().put("temperature_c", 72).put("zone", "rack-7"))
            .put("metadata", JSONObject().put("owner", "quality-suite"))
        return IncomingEntityRecord(
            entityType = "thing",
            entityId = "quality-thing",
            channel = "quality",
            title = title,
            body = "A deterministic reactor with linked events, messages, and updates.",
            rawPayloadJson = payload.toString(),
            receivedAt = receivedAt,
            opId = "quality-op-thing-$deliverySuffix",
            deliveryId = "quality-delivery-thing-$deliverySuffix",
            serverId = "quality-session",
            eventId = null,
            thingId = "quality-thing",
            eventState = null,
            eventTimeEpoch = null,
            observedTimeEpoch = receivedAt.toEpochMilli(),
        )
    }

    private fun qualityNavigationThing(index: Int): IncomingEntityRecord {
        val suffix = index.toString().padStart(2, '0')
        val thingId = "quality-thing-navigation-$suffix"
        val title = "Navigation Thing $suffix"
        val observedAt = Instant.parse("2026-01-15T07:$suffix:00Z")
        val summary = "Deterministic off-screen Thing used to prove current-tab return-to-top."
        val payload = JSONObject()
            .put("entity_type", "thing")
            .put("entity_id", thingId)
            .put("thing_id", thingId)
            .put("delivery_id", "quality-delivery-$thingId")
            .put("op_id", "quality-op-$thingId")
            .put("observed_at", observedAt.toString())
            .put("title", title)
            .put("description", summary)
            .put("state", "active")
        return IncomingEntityRecord(
            entityType = "thing",
            entityId = thingId,
            channel = "quality-navigation",
            title = title,
            body = summary,
            rawPayloadJson = payload.toString(),
            receivedAt = observedAt,
            opId = "quality-op-$thingId",
            deliveryId = "quality-delivery-$thingId",
            serverId = "quality-session",
            eventId = null,
            thingId = thingId,
            eventState = null,
            eventTimeEpoch = null,
            observedTimeEpoch = observedAt.toEpochMilli(),
        )
    }

    private fun qualityThingMessage(): PushMessage {
        val receivedAt = Instant.parse("2026-01-15T08:04:00Z")
        val payload = JSONObject()
            .put("entity_type", "message")
            .put("entity_id", "quality-related-message")
            .put("message_id", "quality-related-message")
            .put("thing_id", "quality-thing")
            .put("delivery_id", "quality-delivery-related-message")
            .put("op_id", "quality-op-related-message")
            .put("occurred_at", receivedAt.toString())
            .put("tags", JSONArray(listOf("quality", "reactor")))
        return PushMessage(
            id = "quality-related-message",
            messageId = "quality-related-message",
            title = "Quality Related Message",
            body = "The linked reactor message is visible in the Messages tab.",
            channel = "quality",
            url = null,
            isRead = false,
            receivedAt = receivedAt,
            rawPayloadJson = payload.toString(),
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = "quality-session",
            bodyPreview = "The linked reactor message is visible in the Messages tab.",
        )
    }

    private fun qualityThingDistractor(): IncomingEntityRecord {
        val thingId = "quality-thing-distractor"
        val receivedAt = Instant.parse("2026-01-15T08:00:30Z")
        val summary = "Secondary fixture that must be excluded by the target search."
        val payload = JSONObject()
            .put("entity_type", "thing")
            .put("entity_id", thingId)
            .put("thing_id", thingId)
            .put("delivery_id", "quality-delivery-thing-distractor")
            .put("op_id", "quality-op-thing-distractor")
            .put("observed_at", receivedAt.toString())
            .put("title", "Quality Pump Beta")
            .put("description", summary)
            .put("state", "active")
            .put("tags", JSONArray(listOf("quality", "pump")))
            .put("attrs", JSONObject().put("temperature_c", 21).put("zone", "rack-2"))
        return IncomingEntityRecord(
            entityType = "thing",
            entityId = thingId,
            channel = "quality-secondary",
            title = "Quality Pump Beta",
            body = summary,
            rawPayloadJson = payload.toString(),
            receivedAt = receivedAt,
            opId = "quality-op-thing-distractor",
            deliveryId = "quality-delivery-thing-distractor",
            serverId = "quality-session",
            eventId = null,
            thingId = thingId,
            eventState = null,
            eventTimeEpoch = null,
            observedTimeEpoch = receivedAt.toEpochMilli(),
        )
    }
}
