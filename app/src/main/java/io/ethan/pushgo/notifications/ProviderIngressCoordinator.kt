package io.ethan.pushgo.notifications

import android.content.Context
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.InboundDeliveryLedgerRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.ProviderAckContract
import io.ethan.pushgo.data.ProviderAckDestination
import io.ethan.pushgo.data.ProviderAckIdentity
import io.ethan.pushgo.data.ProviderPullPage
import io.ethan.pushgo.data.ProviderPullContract
import io.ethan.pushgo.data.PullItem
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.data.db.LegacyProviderIngressEntity
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
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit = { _, _ -> },
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
                beforeMessageNotify = beforeMessageNotify,
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
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit = { _, _ -> },
    ): Int = ingressMutex.withLock {
        runCatching {
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
        val persistedCount = consumeProviderPullPages(
            requestedDeliveryId = deliveryId,
            pullPage = { channelRepository.pullMessages(deliveryId) },
        ) { page ->
            val destination = page.destination
                ?: error("provider pull page missing ACK destination")
            if (page.contract == ProviderPullContract.LEGACY) {
                inboundDeliveryLedgerRepository.stageLegacyPull(destination, page.items)
                return@consumeProviderPullPages ProviderPullPageProcessResult(
                    persistedCount = processPendingLegacyPull(
                        context = context,
                        messageRepository = messageRepository,
                        entityRepository = entityRepository,
                        inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                        settingsRepository = settingsRepository,
                        keyBytes = keyBytes,
                        beforeMessageNotify = beforeMessageNotify,
                    ),
                    hadPersistenceFailure = false,
                )
            }
            val pageIdentity = ProviderAckIdentity.create(
                destination = destination,
                contract = when (page.contract) {
                    ProviderPullContract.V2 -> ProviderAckContract.V2_BATCH
                    ProviderPullContract.LEGACY -> ProviderAckContract.LEGACY_SINGLE
                },
                source = "provider_pull",
            ) ?: error("provider pull page has invalid ACK destination")
            var pageHadPersistenceFailure = false
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
                    if (page.contract == ProviderPullContract.V2) {
                        val discardedIdentity = ProviderAckIdentity.create(
                            destination = destination,
                            contract = ProviderAckContract.V2_BATCH,
                            source = "provider_pull_discarded",
                        ) ?: error("provider pull page has invalid discarded ACK destination")
                        inboundDeliveryLedgerRepository.enqueueAcks(
                            deliveryIds = listOf(item.deliveryId),
                            identity = discardedIdentity,
                        )
                    }
                    continue
                }
                val outcome = InboundPersistenceCoordinator.persistAndNotify(
                    context = context,
                    messageRepository = messageRepository,
                    entityRepository = entityRepository,
                    inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                    settingsRepository = settingsRepository,
                    inbound = parsed,
                    beforeMessageNotify = beforeMessageNotify,
                )
                if (outcome.status != InboundPersistenceStatus.FAILED) {
                    pagePersisted += 1
                } else {
                    pageHadPersistenceFailure = true
                }
                if (outcome.shouldAck && page.contract == ProviderPullContract.V2) {
                    inboundDeliveryLedgerRepository.enqueueAcks(
                        deliveryIds = listOf(item.deliveryId),
                        identity = pageIdentity,
                    )
                }
            }

            if (page.contract == ProviderPullContract.V2) {
                drainPendingAcksNow(
                    channelRepository = channelRepository,
                    inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                    destination = destination,
                )
            }
            ProviderPullPageProcessResult(
                persistedCount = pagePersisted,
                hadPersistenceFailure = pageHadPersistenceFailure,
            ).also { result ->
                hadPersistenceFailure = hadPersistenceFailure || result.hadPersistenceFailure
            }
        }
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
                    beforeMessageNotify = beforeMessageNotify,
                )
                if (outcome.status == InboundPersistenceStatus.FAILED) {
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
            inboundDeliveryLedgerRepository.loadPendingAckIds(limit = 1).isNotEmpty()
        }.getOrDefault(false)
        if (hasPendingAcks) {
            InboundDeliveryAckWorker.enqueueDrain(context)
        }
    }

    private suspend fun drainPendingAcksNow(
        channelRepository: ChannelSubscriptionRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        destination: ProviderAckDestination,
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
                ackMessages = channelRepository::ackMessages,
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
    persistenceStatus == InboundPersistenceStatus.DUPLICATE -> DirectAckDrainSchedule.RECOVERY
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
)

internal suspend fun consumeProviderPullPages(
    requestedDeliveryId: String?,
    pullPage: suspend () -> ProviderPullPage,
    processPage: suspend (ProviderPullPage) -> ProviderPullPageProcessResult,
): Int {
    var totalPersisted = 0
    var pageCount = 0
    var nextPage = true
    while (nextPage) {
        pageCount += 1
        check(pageCount <= MAX_PROVIDER_PULL_PAGES_PER_RUN) {
            "provider pull exceeded $MAX_PROVIDER_PULL_PAGES_PER_RUN pages"
        }
        val page = pullPage()
        val result = processPage(page)
        totalPersisted += result.persistedCount
        nextPage = page.contract == ProviderPullContract.V2 &&
            page.hasMore &&
            requestedDeliveryId.isNullOrBlank() &&
            !result.hadPersistenceFailure
    }
    return totalPersisted
}

internal fun PullItem.authoritativePayload(): Map<String, String> =
    payload.toMutableMap().apply { this["delivery_id"] = deliveryId }

internal fun InboundPersistenceRequest.withProviderAckIdentity(
    identity: ProviderAckIdentity,
): InboundPersistenceRequest = when (this) {
    is InboundPersistenceRequest.Message -> copy(providerAckIdentity = identity)
    is InboundPersistenceRequest.Entity -> copy(providerAckIdentity = identity)
}

private const val MAX_PROVIDER_PULL_PAGES_PER_RUN = 1_000
