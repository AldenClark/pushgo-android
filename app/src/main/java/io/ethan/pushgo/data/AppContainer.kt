package io.ethan.pushgo.data

import android.content.Context
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import io.ethan.pushgo.notifications.InboundPersistenceStatus
import io.ethan.pushgo.notifications.NotificationIngressParser
import io.ethan.pushgo.notifications.PrivateChannelClient
import io.ethan.pushgo.testing.InstrumentationRuntime
import io.ethan.pushgo.testing.QualityFixture
import io.ethan.pushgo.testing.QualityChannelMutationScenario
import io.ethan.pushgo.testing.QualityEventCloseScenario
import io.ethan.pushgo.testing.QualityRuntime
import io.ethan.pushgo.ui.PendingLocalDeletionDrainScheduler
import io.ethan.pushgo.ui.PendingLocalDeletionCoordinator
import io.ethan.pushgo.ui.WorkManagerPendingLocalDeletionDrainScheduler
import io.ethan.pushgo.update.UpdateManager
import io.ethan.pushgo.util.UrlValidators
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Base64
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
    val coroutineDispatchers = AppCoroutineDispatchers()
    val pushTokenProvider: PushTokenProvider = FirebasePushTokenProvider()
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
        eventCloseRoundTrip = if (
            QualityRuntime.currentSession()?.eventCloseScenario == QualityEventCloseScenario.ACCEPTED_AND_DELIVERED
        ) {
            EventCloseRoundTrip { outbound ->
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
            }
        } else {
            null
        },
        channelMutationRoundTrip = QualityRuntime.currentSession()?.channelMutationScenario
            ?.takeUnless { it == QualityChannelMutationScenario.NONE }
            ?.let { scenario ->
            object : ChannelMutationRoundTrip {
                private var subscribeAttempts = 0
                private val activeCreatedChannelIds = mutableSetOf<String>()

                override suspend fun ensureProviderRoute(providerToken: String): String {
                    check(providerToken.isNotBlank()) { "quality channel route requires a provider token" }
                    return "quality-channel-device"
                }

                override suspend fun subscribe(
                    channelId: String?,
                    channelName: String?,
                    password: String,
                ): ChannelSubscribeResult {
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
                    channelId: String,
                    channelName: String,
                    password: String,
                ): ChannelRenameResult {
                    check(password.isNotBlank()) { "quality channel rename requires a password" }
                    return ChannelRenameResult(channelId = channelId, channelName = channelName)
                }

                override suspend fun unsubscribe(channelId: String) {
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
        if (QualityRuntime.fixtureInitializationWasRecorded(appContext.filesDir)) return
        val messages = when (session.fixture) {
            QualityFixture.EMPTY_CLEAN -> emptyList()
            QualityFixture.MESSAGES_STANDARD -> listOf(qualityMessage(index = 0))
            QualityFixture.MESSAGES_ENCRYPTED_VALID,
            QualityFixture.MESSAGES_ENCRYPTED_CORRUPT -> emptyList()
            QualityFixture.MESSAGES_WORKFLOW -> (0 until 52).map(::qualityWorkflowMessage)
            QualityFixture.MESSAGES_LARGE -> (0 until 1_000).map(::qualityMessage)
            QualityFixture.CHANNELS_STANDARD -> listOf(
                qualityChannelMessage(
                    id = "quality-channel-keep-message",
                    title = "Quality Keep History Message",
                    channelId = "01H00000000000000000000001",
                ),
                qualityChannelMessage(
                    id = "quality-channel-delete-message",
                    title = "Quality Delete History Message",
                    channelId = "01H00000000000000000000002",
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
                entityRepository.insertIncoming(qualityEvent())
                check(entityRepository.eventCount() == 1) {
                    "event.standard did not reach its canonical projection"
                }
            }
            QualityFixture.THING_STANDARD -> {
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
                entityRepository.insertIncoming(qualityEvent(thingId = "quality-thing"))
                messageRepository.insertIncoming(qualityThingMessage())
                check(entityRepository.thingCount() == 1) {
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
            }
            else -> Unit
        }
        // Record completion only after every store mutation and canonical-projection
        // check succeeds. Live row counts may legitimately change during the journey.
        QualityRuntime.recordFixtureInitialization(appContext.filesDir)
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

    private fun qualityWorkflowMessage(index: Int): PushMessage {
        val stableId = "quality-workflow-$index"
        val title = "Quality workflow $index"
        val body = "Cross-page deterministic workflow row $index."
        val channel = if (index % 2 == 0) "workflow-alpha" else "workflow-beta"
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
            isRead = index % 4 == 0,
            receivedAt = Instant.parse("2026-01-15T08:00:00Z").plusSeconds(index.toLong()),
            rawPayloadJson = rawPayload,
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = "quality-session",
            bodyPreview = body,
        )
    }

    private fun qualityChannelMessage(id: String, title: String, channelId: String): PushMessage {
        val body = "Deterministic history owned by $channelId."
        return PushMessage(
            id = id,
            messageId = id,
            title = title,
            body = body,
            channel = channelId,
            url = null,
            isRead = false,
            receivedAt = Instant.parse("2026-01-15T08:00:00Z"),
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

    private fun qualityEvent(thingId: String? = null): IncomingEntityRecord {
        val stableId = if (thingId == null) "quality-event" else "quality-related-event"
        val title = if (thingId == null) "Quality Cooling Alert" else "Quality Related Event"
        val description = if (thingId == null) {
            "Cooling loop temperature crossed the quality threshold."
        } else {
            "A deterministic event associated with Quality Reactor Alpha."
        }
        val receivedAt = if (thingId == null) {
            Instant.parse("2026-01-15T08:02:00Z")
        } else {
            Instant.parse("2026-01-15T08:03:00Z")
        }
        val payload = JSONObject()
            .put("entity_type", "event")
            .put("entity_id", stableId)
            .put("event_id", stableId)
            .put("delivery_id", "quality-delivery-$stableId")
            .put("op_id", "quality-op-$stableId")
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
            opId = "quality-op-$stableId",
            deliveryId = "quality-delivery-$stableId",
            serverId = "quality-session",
            eventId = stableId,
            thingId = thingId,
            eventState = "ONGOING",
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
}
