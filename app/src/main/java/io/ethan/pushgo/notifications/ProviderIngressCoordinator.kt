package io.ethan.pushgo.notifications

import android.content.Context
import io.ethan.pushgo.data.inboundDeliveryScope
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.ChannelSubscriptionException
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.InboundDeliveryLedgerRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.ProviderAckContract
import io.ethan.pushgo.data.ProviderAckDestination
import io.ethan.pushgo.data.ProviderAckIdentity
import io.ethan.pushgo.data.ProviderPullPage
import io.ethan.pushgo.data.ProviderPullContext
import io.ethan.pushgo.data.ProviderPullTurnEnded
import io.ethan.pushgo.data.PullItem
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.data.db.LegacyProviderIngressEntity
import io.ethan.pushgo.testing.QualityRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

object ProviderIngressCoordinator {
    suspend fun pullPersistAndDrainAcks(
        context: Context,
        channelRepository: ChannelSubscriptionRepository,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        deliveryId: String? = null,
        reason: String = "unspecified",
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit = { _, _ -> },
        expectedGatewayUrl: String? = null,
        expectedDeviceKey: String? = null,
        strictConfiguredTarget: Boolean = false,
    ): Int {
        return try {
            pullAndPersist(
                context = context,
                channelRepository = channelRepository,
                messageRepository = messageRepository,
                entityRepository = entityRepository,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                settingsRepository = settingsRepository,
                deliveryId = deliveryId,
                reason = reason,
                beforeMessageNotify = beforeMessageNotify,
                expectedGatewayUrl = expectedGatewayUrl,
                expectedDeviceKey = expectedDeviceKey,
                strictConfiguredTarget = strictConfiguredTarget,
            )
        } finally {
            enqueueAckDrainIfNeeded(
                context = context,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
            )
            runCatching {
                pruneAckedTombstonesInBatches(
                    pruneBatch = { limit ->
                        inboundDeliveryLedgerRepository.pruneAckedTombstones(limit = limit)
                    },
                )
            }.onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(
                    "ProviderIngress",
                    "provider ACK tombstone maintenance failed",
                    error,
                )
            }
        }
    }

    suspend fun pullAndPersist(
        context: Context,
        channelRepository: ChannelSubscriptionRepository,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        deliveryId: String? = null,
        reason: String = "unspecified",
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit = { _, _ -> },
        expectedGatewayUrl: String? = null,
        expectedDeviceKey: String? = null,
        strictConfiguredTarget: Boolean = false,
    ): Int = ingressMutex.withLock {
        // Inline FCM is only an untrusted hint. Do not bootstrap a new FCM route
        // or guess a default target before validating its existing configuration.
        var pullContext: ProviderPullContext? = if (strictConfiguredTarget) channelRepository.preparePullContext(
            deliveryId, expectedGatewayUrl, expectedDeviceKey, true) else null
        if (!strictConfiguredTarget) runCatching {
            repairProviderRouteSnapshotIfNeeded(
                channelRepository = channelRepository,
                settingsRepository = settingsRepository,
            )
        }
        val keyBytes = settingsRepository.getNotificationKeyBytes()
        val recoveredLegacyCount = processPendingLegacyPull(
            context = context,
            messageRepository = messageRepository,
            entityRepository = entityRepository,
            inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
            settingsRepository = settingsRepository,
            keyBytes = keyBytes,
            beforeMessageNotify = beforeMessageNotify,
        )
        var hadPersistenceFailure = false
        var ackFailure: ChannelSubscriptionException? = null
        val persistedCount = consumeProviderPullPages(
            requestedDeliveryId = deliveryId,
            propagateContextEnd = strictConfiguredTarget,
            pullPage = {
                val override = if (reason == "messages_pull_to_refresh") QualityRuntime.takeMessageRefreshPullOverride() else null
                if (override != null) override.getOrThrow() else {
                    val frozen = pullContext ?: channelRepository.preparePullContext(deliveryId).also { pullContext = it }
                    channelRepository.pullMessages(frozen, deliveryId)
                }
            },
            checkpointPage = { page ->
                pullContext?.let { frozen -> pullContext = channelRepository.checkpointPullPage(frozen, page) }
            },
            afterCheckpointPage = { page ->
                try {
                    drainPendingAcksNow(channelRepository, inboundDeliveryLedgerRepository,
                        requireNotNull(page.destination), pullContext)
                    false
                } catch (error: ChannelSubscriptionException) {
                    if (page.scanComplete == null || !error.retryable) throw error
                    if (ackFailure == null) ackFailure = error
                    true
                }
            },
        ) { page ->
            val destination = page.destination
                ?: error("provider pull page missing ACK destination")
            val pageIdentity = ProviderAckIdentity.create(
                destination = destination,
                contract = ProviderAckContract.V2_BATCH,
                source = "provider_pull",
            ) ?: error("provider pull page has invalid ACK destination")
            var pageHadPersistenceFailure = false
            var pageHadHardFailure = false
            var pagePersisted = 0
            for (item in page.items) {
                val authoritativePayload = item.authoritativePayload()
                val parsed = NotificationIngressParser.parse(
                    data = authoritativePayload,
                    transportMessageId = item.deliveryId,
                    keyBytes = keyBytes,
                    textLocalizer = NotificationIngressParser.NotificationTextLocalizer.fromContext(context),
                )?.withProviderAckIdentity(pageIdentity)
                if (parsed == null) {
                    pageHadPersistenceFailure = true
                    // Only an independently malformed ordinary item has no parent
                    // business boundary. Its original server delivery remains unACKed.
                    val ordinary = authoritativePayload["entity_type"].orEmpty().lowercase() in setOf("", "message") &&
                        authoritativePayload["thing_id"].isNullOrBlank() && authoritativePayload["event_id"].isNullOrBlank()
                    if (!ordinary) { pageHadHardFailure = true; break }
                    continue
                }
                val outcome = InboundPersistenceCoordinator.persistAndNotify(
                    context = context,
                    messageRepository = messageRepository,
                    entityRepository = entityRepository,
                    inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                    settingsRepository = settingsRepository,
                    inbound = parsed,
                    deliveryScope = pageIdentity.inboundDeliveryScope(),
                    beforeMessageNotify = beforeMessageNotify,
                )
                if (outcome.isCanonicalPersistenceComplete()) {
                    pagePersisted += 1
                } else {
                    pageHadPersistenceFailure = true
                    // A proven per-item conflict/ambiguity keeps its server obligation.
                    // Modern finite scans may pass only this fully rolled-back item.
                    // These reasons are emitted only after a per-item acceptance
                    // transaction rolled back. Parent/child scope checks retain
                    // their own failed responsibility; unrelated items may progress.
                    val independentIdentityConflict = page.scanComplete != null && outcome.identityConflict != null
                    pageHadHardFailure = !independentIdentityConflict
                }
                if (outcome.shouldAck) {
                    inboundDeliveryLedgerRepository.enqueueAcks(
                        deliveryIds = listOf(item.deliveryId),
                        identity = pageIdentity,
                    )
                }
                if (pageHadHardFailure) break
            }

            ProviderPullPageProcessResult(
                persistedCount = pagePersisted,
                hadPersistenceFailure = pageHadPersistenceFailure,
                hadHardFailure = pageHadHardFailure,
            ).also { result ->
                hadPersistenceFailure = hadPersistenceFailure || result.hadPersistenceFailure
            }
        }
        if (deliveryId.isNullOrBlank() && channelRepository.hasPullContinuation()) {
            pullContext?.let(channelRepository::schedulePullContinuation)
        }
        ackFailure?.let { throw it }
        if (hadPersistenceFailure) {
            throw InboundMessageProcessor.InboundRetryableException(
                "provider Pull retained at least one item after local persistence failure"
            )
        }
        recoveredLegacyCount + persistedCount
    }

    private suspend fun processPendingLegacyPull(
        context: Context,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        keyBytes: ByteArray?,
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit,
    ): Int {
        var processedCount = 0
        var batchCount = 0
        while (true) {
            check(++batchCount <= MAX_LEGACY_STAGING_BATCHES_PER_RUN) {
                "legacy provider staging exceeded recovery limit"
            }
            val records = inboundDeliveryLedgerRepository.loadPendingLegacyPull()
            if (records.isEmpty()) return processedCount
            var hadFailure = false
            records.forEach { record ->
                val authoritativePayload = record.payloadMap()
                val identity = ProviderAckIdentity.create(
                    destination = ProviderAckDestination(record.gatewayUrl, record.deviceKey),
                    contract = ProviderAckContract.LEGACY_SINGLE,
                    source = "legacy_provider_staging",
                ) ?: error("legacy provider staging has invalid destination")
                val parsed = NotificationIngressParser.parse(
                    data = authoritativePayload,
                    transportMessageId = record.deliveryId,
                    keyBytes = keyBytes,
                    textLocalizer = NotificationIngressParser.NotificationTextLocalizer.fromContext(context),
                )?.withProviderAckIdentity(identity)
                if (parsed == null) {
                    inboundDeliveryLedgerRepository.deleteLegacyPull(record)
                    return@forEach
                }
                val outcome = InboundPersistenceCoordinator.persistAndNotify(
                    context = context,
                    messageRepository = messageRepository,
                    entityRepository = entityRepository,
                    inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                    settingsRepository = settingsRepository,
                    inbound = parsed,
                    deliveryScope = identity.inboundDeliveryScope(),
                    beforeMessageNotify = beforeMessageNotify,
                )
                if (!shouldCompleteLegacyPull(parsed, outcome)) {
                    hadFailure = true
                } else {
                    // Legacy Pull already removed the server row. Commit the local terminal
                    // tombstone and staging deletion atomically so a crash can only leave the
                    // durable staging row to replay, never an unbounded pending ledger row.
                    inboundDeliveryLedgerRepository.completeLegacyPull(record)
                    processedCount += 1
                }
            }
            if (hadFailure) {
                throw InboundMessageProcessor.InboundRetryableException(
                    "legacy provider staging retained at least one item after local persistence failure"
                )
            }
            if (records.size < LEGACY_STAGING_BATCH_SIZE) return processedCount
        }
    }

    private suspend fun repairProviderRouteSnapshotIfNeeded(
        channelRepository: ChannelSubscriptionRepository,
        settingsRepository: SettingsRepository,
    ) {
        if (!settingsRepository.getUseFcmChannel()) {
            return
        }
        val token = settingsRepository.getFcmToken()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        channelRepository.syncProviderDeviceToken(token)
        channelRepository.syncSubscriptionsIfNeeded(token)
    }

    suspend fun ackDirectDeliveryIfNeeded(
        context: Context,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        inbound: InboundPersistenceRequest,
        outcome: InboundPersistenceOutcome,
    ) {
        if (!outcome.shouldAck) return
        val deliveryId = inboundDeliveryId(inbound) ?: return
        val identity = inboundProviderAckIdentity(inbound) ?: return
        val shouldKickDrain = inboundDeliveryLedgerRepository.enqueueAcks(
            deliveryIds = listOf(deliveryId),
            identity = identity,
        )
        // A duplicate can be the retry after the process committed the first outbox row but
        // died before WorkManager durably registered its drain. Re-registering the unique work
        // is idempotent and closes that DB-to-scheduler handoff window.
        when (directAckDrainSchedule(shouldKickDrain, outcome.status)) {
            DirectAckDrainSchedule.PRIMARY -> InboundDeliveryAckWorker.enqueue(context, deliveryId)
            DirectAckDrainSchedule.RECOVERY -> InboundDeliveryAckWorker.ensureDrain(context, deliveryId)
            DirectAckDrainSchedule.NONE -> Unit
        }
    }

    private suspend fun enqueueAckDrainIfNeeded(
        context: Context,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
    ) {
        val hasPendingAcks = runCatching {
            inboundDeliveryLedgerRepository.hasEligiblePendingAcks()
        }.getOrDefault(false)
        if (hasPendingAcks) {
            InboundDeliveryAckWorker.enqueueDrain(context)
        }
    }

    private suspend fun drainPendingAcksNow(
        channelRepository: ChannelSubscriptionRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        destination: ProviderAckDestination,
        pullContext: ProviderPullContext? = null,
    ) {
        repeat(MAX_ACK_BATCHES_PER_PULL_PAGE) {
            val result = ProviderAckDrainCoordinator.drainPendingAcks(
                loadPendingAcks = { limit ->
                    inboundDeliveryLedgerRepository.loadPendingAcks(
                        destination = destination,
                        contract = ProviderAckContract.V2_BATCH,
                        limit = limit,
                    )
                },
                beginAttempt = inboundDeliveryLedgerRepository::beginAckAttempt,
                ackMessages = { target, contract, ids ->
                    if (pullContext == null) channelRepository.ackMessages(target, contract, ids)
                    else channelRepository.ackMessagesForPull(pullContext, target, contract, ids)
                },
                markAcked = inboundDeliveryLedgerRepository::markAckRecordsAcked,
            )
            if (result.hasFailures) {
                inboundDeliveryLedgerRepository.deferFailedAcks(
                    result.uncertainFailures,
                    lastAttemptUncertain = true,
                )
                inboundDeliveryLedgerRepository.deferFailedAcks(
                    result.definitiveFailures,
                    lastAttemptUncertain = false,
                )
                result.failureCause?.let { throw it }
                error("provider ACK failed for ${result.failedIds.size} deliveries")
            }
            if (result.attempted.isEmpty()) return
        }
        error("provider ACK drain exceeded $MAX_ACK_BATCHES_PER_PULL_PAGE batches")
    }

    fun inboundDeliveryId(inbound: InboundPersistenceRequest): String? {
        val raw = when (inbound) {
            is InboundPersistenceRequest.Message -> inbound.message.deliveryId
            is InboundPersistenceRequest.Entity -> inbound.record.deliveryId
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun inboundProviderAckIdentity(inbound: InboundPersistenceRequest): ProviderAckIdentity? {
        return when (inbound) {
            is InboundPersistenceRequest.Message -> inbound.providerAckIdentity
            is InboundPersistenceRequest.Entity -> inbound.providerAckIdentity
        }
    }

    private const val MAX_ACK_BATCHES_PER_PULL_PAGE = 100
    private const val LEGACY_STAGING_BATCH_SIZE = 200
    private const val MAX_LEGACY_STAGING_BATCHES_PER_RUN = 100
    private val ingressMutex = Mutex()
}

internal enum class DirectAckDrainSchedule { NONE, PRIMARY, RECOVERY }

internal fun directAckDrainSchedule(
    shouldKickDrain: Boolean,
    persistenceStatus: InboundPersistenceStatus,
): DirectAckDrainSchedule = when {
    shouldKickDrain -> DirectAckDrainSchedule.PRIMARY
    persistenceStatus == InboundPersistenceStatus.DUPLICATE ||
        persistenceStatus == InboundPersistenceStatus.PERSISTED_PENDING -> DirectAckDrainSchedule.RECOVERY
    else -> DirectAckDrainSchedule.NONE
}

private fun LegacyProviderIngressEntity.payloadMap(): Map<String, String> {
    val json = JSONObject(payloadJson)
    return buildMap {
        json.keys().forEach { key -> put(key, json.getString(key)) }
        put("delivery_id", deliveryId)
    }
}

internal data class ProviderPullPageProcessResult(
    val persistedCount: Int,
    val hadPersistenceFailure: Boolean,
    val hadAckFailure: Boolean = false,
    val hadHardFailure: Boolean = false,
)

internal suspend fun consumeProviderPullPages(
    requestedDeliveryId: String?,
    pullPage: suspend () -> ProviderPullPage,
    checkpointPage: suspend (ProviderPullPage) -> Unit = {},
    afterCheckpointPage: suspend (ProviderPullPage) -> Boolean = { false },
    propagateContextEnd: Boolean = false,
    processPage: suspend (ProviderPullPage) -> ProviderPullPageProcessResult,
): Int {
    var totalPersisted = 0
    var pageCount = 0
    var nextPage = true
    var progressPages = 0
    val started = System.nanoTime()
    while (nextPage) {
        pageCount += 1
        check(pageCount <= MAX_PROVIDER_PULL_PAGES_PER_RUN) {
            "provider pull exceeded $MAX_PROVIDER_PULL_PAGES_PER_RUN pages"
        }
        val page = try { pullPage() } catch (ended: ProviderPullTurnEnded) {
            // A foreground broad scan can yield to its next due turn. A durable
            // provider hint must retain its existing Worker retry after a switch.
            if (propagateContextEnd) throw ended
            break
        }
        val result = processPage(page)
        totalPersisted += result.persistedCount
        if (!result.hadHardFailure) {
            try { checkpointPage(page) } catch (ended: ProviderPullTurnEnded) {
                afterCheckpointPage(page) // already durable ACKs still use the frozen page context
                if (propagateContextEnd) throw ended
                break
            }
        }
        val ackFailed = afterCheckpointPage(page) || result.hadAckFailure
        if (result.hadHardFailure) break
        nextPage = if (!requestedDeliveryId.isNullOrBlank()) false else if (page.scanComplete != null) {
            progressPages++
            // Check only between pages. Existing HTTP/DB deadlines still govern an
            // individual page; this is not a claim of a five-second hard request cap.
            !page.scanComplete && progressPages < MAX_PROGRESS_PULL_PAGES_PER_TURN &&
                System.nanoTime() - started < PROGRESS_PULL_TURN_NANOS
        } else page.hasMore && !result.hadPersistenceFailure && !ackFailed
    }
    return totalPersisted
}

internal fun PullItem.authoritativePayload(): Map<String, String> =
    payload.toMutableMap().apply { this["delivery_id"] = deliveryId }

internal fun InboundPersistenceRequest.withProviderAckIdentity(
    identity: ProviderAckIdentity,
): InboundPersistenceRequest = when (this) {
    is InboundPersistenceRequest.Message -> copy(
        providerAckIdentity = identity.takeIf { securityDisposition.allowsSuccessfulAck },
    )
    is InboundPersistenceRequest.Entity -> copy(
        providerAckIdentity = identity.takeIf { securityDisposition.allowsSuccessfulAck },
    )
}

internal fun shouldCompleteLegacyPull(
    inbound: InboundPersistenceRequest,
    outcome: InboundPersistenceOutcome,
): Boolean {
    val allowsSuccessfulAck = when (inbound) {
        is InboundPersistenceRequest.Message -> inbound.securityDisposition.allowsSuccessfulAck
        is InboundPersistenceRequest.Entity -> inbound.securityDisposition.allowsSuccessfulAck
    }
    return outcome.status != InboundPersistenceStatus.FAILED &&
        outcome.shouldAck &&
        allowsSuccessfulAck
}

internal fun InboundPersistenceOutcome.isCanonicalPersistenceComplete(): Boolean =
    status != InboundPersistenceStatus.FAILED && status != InboundPersistenceStatus.REJECTED

private const val MAX_PROVIDER_PULL_PAGES_PER_RUN = 1_000
private const val MAX_PROGRESS_PULL_PAGES_PER_TURN = 4
private const val PROGRESS_PULL_TURN_NANOS = 5_000_000_000L
