package io.ethan.pushgo.data

import android.content.Context
import androidx.core.content.edit
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import io.ethan.pushgo.data.db.MessageChannelStatsDao
import io.ethan.pushgo.data.db.MessageDao
import io.ethan.pushgo.data.db.MessageEntity
import io.ethan.pushgo.data.db.MessageListRow
import io.ethan.pushgo.data.db.InboundDeliveryLedgerDao
import io.ethan.pushgo.data.db.MessageMetadataIndexDao
import io.ethan.pushgo.data.db.MessageMetadataIndexEntity
import io.ethan.pushgo.data.db.OperationLedgerDao
import io.ethan.pushgo.data.db.PendingThingMessageDao
import io.ethan.pushgo.data.db.PendingThingMessageEntity
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.db.ThingHeadDao
import io.ethan.pushgo.data.db.ThingSubMessageDao
import io.ethan.pushgo.data.db.ThingSubMessageEntity
import io.ethan.pushgo.data.model.MessageChannelCount
import io.ethan.pushgo.data.model.MessageFacetOptionCount
import io.ethan.pushgo.data.model.MessageFilter
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.data.model.MessageListItem
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.testing.QualityRuntime
import io.ethan.pushgo.util.SearchTextNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private val ENCRYPTED_RECOVERY_PROVENANCE_FIELDS = listOf(
    "ciphertext",
    "_pushgo_recovery_title_ciphertext",
    "_pushgo_recovery_body_ciphertext",
    "entity_type",
    "entity_id",
    "event_id",
    "thing_id",
    "event_state",
    "event_time",
    "observed_at",
    "occurred_at",
    "message_id",
    "delivery_id",
    "op_id",
    "channel_id",
    "server_id",
    "base_url",
    "provider_device_key",
    "severity",
    "ttl",
    "sent_at",
)

private val ENCRYPTED_RECOVERY_STATES = setOf(
    DecryptionState.NOT_CONFIGURED.name,
    DecryptionState.ALG_MISMATCH.name,
    DecryptionState.DECRYPT_FAILED.name,
)

internal fun hasSameEncryptedRecoveryProvenance(
    existing: PushMessage,
    replay: PushMessage,
): Boolean = encryptedRecoveryProvenance(existing.rawPayloadJson)
    ?.let { it == encryptedRecoveryProvenance(replay.rawPayloadJson) } == true

private fun encryptedRecoveryProvenance(rawPayloadJson: String): Map<String, String>? {
    val payload = runCatching { JSONObject(rawPayloadJson) }.getOrNull() ?: return null
    val encrypted = listOf(
        "ciphertext",
        "_pushgo_recovery_title_ciphertext",
        "_pushgo_recovery_body_ciphertext",
    ).any { key -> payload.optString(key, "").isNotBlank() }
    if (!encrypted) return null
    return ENCRYPTED_RECOVERY_PROVENANCE_FIELDS.associateWith { key ->
        if (payload.has(key) && !payload.isNull(key)) payload.optString(key, "") else ""
    }
}

private const val SQLITE_BIND_PARAMETER_CHUNK_SIZE = 900
private const val SQLITE_SEARCH_BIND_PARAMETER_BUDGET = SQLITE_BIND_PARAMETER_CHUNK_SIZE

class MessageRepository(
    private val database: PushGoDatabase,
    private val dao: MessageDao,
    private val channelStatsDao: MessageChannelStatsDao,
    private val metadataIndexDao: MessageMetadataIndexDao,
    private val inboundDeliveryLedgerDao: InboundDeliveryLedgerDao,
    private val operationLedgerDao: OperationLedgerDao,
    private val thingHeadDao: ThingHeadDao,
    private val thingSubMessageDao: ThingSubMessageDao,
    private val pendingThingMessageDao: PendingThingMessageDao,
) {
    private val searchIndexBackfillMutex = Mutex()
    private val summaryProjectionBackfillMutex = Mutex()
    private val derivedWriteFailureGeneration = AtomicLong(0)
    @Volatile
    private var searchIndexReady = false
    @Volatile
    private var summaryProjectionReady = false

    suspend fun currentStoreRevision(): Long = channelStatsDao.storeRevision()

    private companion object {
        private const val TAG_METADATA_BACKFILL_PREFS = "pushgo_message_search_maintenance"
        private const val TAG_METADATA_BACKFILL_KEY = "search_metadata_backfill_v3"
        private const val SEARCH_TEXT_INDEX_VERSION = "normalization_v1"
        private const val SUMMARY_PROJECTION_VERSION = "projection_v1"
        private const val METADATA_BACKFILL_BATCH_SIZE = 100
        /** Search input is bounded before tokenization so a pasted query cannot grow SQL work unboundedly. */
        const val MAX_QUERY_LENGTH = 512
        const val MAX_QUERY_TOKENS = 6
        const val MAX_TOKEN_LENGTH = 32
        const val MAX_TAG_COUNT = 128
        const val MAX_TAG_LENGTH = 32
    }

    suspend fun wouldPersistAsPending(message: PushMessage): Boolean {
        val canonical = canonicalMessage(message)
        return isThingScopedMessage(canonical) && !hasThingHead(canonical.thingId)
    }

    private data class SearchQueryPlan(
        val textTokens: List<String>,
        val tags: List<String>,
    ) {
        val hasText: Boolean
            get() = textTokens.isNotEmpty()
        val hasTags: Boolean
            get() = tags.isNotEmpty()
        val isEmpty: Boolean
            get() = !hasText && !hasTags

        val normalizedTextQuery: String
            get() = textTokens.joinToString(SearchTextNormalizer.TOKEN_SEPARATOR.toString())
    }

    fun observeMessages(
        filter: MessageFilter,
        excludedIds: Set<String> = emptySet(),
    ): Flow<PagingData<MessageListItem>> {
        val normalizedExcludedIds = normalizeSearchExcludedIds(
            excludedIds,
            reservedCount = filter.tags.size + filter.channels.size,
        )
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 50
            ),
            pagingSourceFactory = {
                val source = dao.observeMessages(
                    readState = if (filter.unreadOnly) false else null,
                    withUrl = if (filter.withUrlOnly) 1 else 0,
                    channels = filter.channels.toList(),
                    channelCount = filter.channels.size,
                    tags = filter.tags.toList(),
                    tagCount = filter.tags.size,
                    serverId = filter.serverId,
                    prioritizeUnread = 0,
                    excludedIds = normalizedExcludedIds,
                    excludedCount = normalizedExcludedIds.size,
                )
                if (QualityRuntime.currentSession() == null) source
                else QualityFaultPagingSource(source)
            }
        ).flow.map { pagingData ->
            pagingData.map(MessageListRow::asListItem)
        }
    }

    fun searchMessages(
        rawQuery: String,
        unreadOnly: Boolean,
        excludedIds: Set<String> = emptySet(),
        channels: Set<String> = emptySet(),
        facetTags: Set<String> = emptySet(),
    ): Flow<PagingData<MessageListItem>> {
        val plan = parseSearchQuery(rawQuery)
        if (plan.isEmpty) {
            return kotlinx.coroutines.flow.flowOf(PagingData.empty())
        }
        // Empty is the canonical key for the user-visible "Ungrouped" facet.
        val normalizedChannels = channels.map(String::trim).distinct()
        val normalizedFacetTags = facetTags.map { it.trim().lowercase(Locale.ROOT) }
            .filter(String::isNotEmpty)
            .distinct()
        val normalizedExcludedIds = normalizeSearchExcludedIds(
            excludedIds,
            reservedCount = plan.tags.size + normalizedChannels.size + normalizedFacetTags.size,
        )
        val readState = if (unreadOnly) false else null
        val pagingFlow = Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false, initialLoadSize = 50),
            pagingSourceFactory = {
                val source = searchPagingSource(
                    plan,
                    readState,
                    normalizedExcludedIds,
                    normalizedChannels,
                    normalizedFacetTags,
                )
                if (QualityRuntime.currentSession() == null) source
                else QualityFaultPagingSource(source) { QualityRuntime.beforeMessageSearchLoad() }
            },
        ).flow.map { pagingData -> pagingData.map(MessageListRow::asListItem) }
        return flow {
            ensureSearchIndexReady()
            emitAll(pagingFlow)
        }
    }

    /** Explicitly bounded snapshot for tests and automation; UI must use [searchMessages]. */
    suspend fun searchMessagesSnapshot(
        rawQuery: String,
        unreadOnly: Boolean,
        limit: Int,
        excludedIds: Set<String> = emptySet(),
    ): List<MessageListItem> {
        require(limit > 0)
        val plan = parseSearchQuery(rawQuery)
        if (plan.isEmpty) return emptyList()
        ensureSearchIndexReady()
        val normalizedExcludedIds = normalizeSearchExcludedIds(excludedIds, reservedCount = plan.tags.size)
        val source = searchPagingSource(
            plan,
            if (unreadOnly) false else null,
            normalizedExcludedIds,
            emptyList(),
            emptyList(),
        )
        return when (val result = source.load(PagingSource.LoadParams.Refresh(null, limit, false))) {
            is PagingSource.LoadResult.Page -> result.data.map(MessageListRow::asListItem)
            is PagingSource.LoadResult.Error -> throw result.throwable
            is PagingSource.LoadResult.Invalid -> emptyList()
        }
    }

    private fun searchPagingSource(
        plan: SearchQueryPlan,
        readState: Boolean?,
        excludedIds: List<String>,
        channels: List<String>,
        facetTags: List<String>,
    ): PagingSource<Int, MessageListRow> = when {
        plan.hasText && plan.hasTags -> dao.searchMessagesByTextAndTags(
            normalizedTokens = plan.normalizedTextQuery,
            searchTextVersion = SEARCH_TEXT_INDEX_VERSION,
            tags = plan.tags,
            tagCount = plan.tags.size,
            readState = readState,
            channels = channels,
            channelCount = channels.size,
            facetTags = facetTags,
            facetTagCount = facetTags.size,
            excludedIds = excludedIds,
            excludedCount = excludedIds.size,
        )

        plan.hasText -> dao.searchMessages(
            normalizedTokens = plan.normalizedTextQuery,
            searchTextVersion = SEARCH_TEXT_INDEX_VERSION,
            readState = readState,
            channels = channels,
            channelCount = channels.size,
            facetTags = facetTags,
            facetTagCount = facetTags.size,
            excludedIds = excludedIds,
            excludedCount = excludedIds.size,
        )

        else -> dao.searchMessagesByTags(
            tags = plan.tags,
            tagCount = plan.tags.size,
            readState = readState,
            channels = channels,
            channelCount = channels.size,
            facetTags = facetTags,
            facetTagCount = facetTags.size,
            excludedIds = excludedIds,
            excludedCount = excludedIds.size,
        )
    }

    private fun normalizeSearchExcludedIds(
        excludedIds: Set<String>,
        reservedCount: Int,
    ): List<String> {
        val available = (SQLITE_SEARCH_BIND_PARAMETER_BUDGET - reservedCount).coerceAtLeast(0)
        return excludedIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(available)
            .toList()
    }

    fun observeChannelCounts(): Flow<List<MessageChannelCount>> = channelStatsDao.observeChannelCounts()

    fun observeUnreadCount(): Flow<Int> = channelStatsDao.observeUnreadCount().distinctUntilChanged()

    fun observeUnreadCount(
        filter: MessageFilter,
        excludedIds: Set<String> = emptySet(),
    ): Flow<Int> {
        val normalizedExcludedIds = excludedIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        return dao.observeUnreadCountForFilter(
            withUrl = if (filter.withUrlOnly) 1 else 0,
            channels = filter.channels.toList(),
            channelCount = filter.channels.size,
            tags = filter.tags.toList(),
            tagCount = filter.tags.size,
            serverId = filter.serverId,
            excludedIds = normalizedExcludedIds,
            excludedCount = normalizedExcludedIds.size,
        ).distinctUntilChanged()
    }

    fun observeFacetChannelCounts(excludedIds: Set<String> = emptySet()): Flow<List<MessageFacetOptionCount>> {
        val normalizedExcludedIds = excludedIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        return dao.observeFacetChannelCounts(
            excludedIds = normalizedExcludedIds,
            excludedCount = normalizedExcludedIds.size,
        ).map { list ->
            list.map { row -> MessageFacetOptionCount(value = row.value.trim(), count = row.count) }
        }
    }

    fun observeFacetTagCounts(excludedIds: Set<String> = emptySet()): Flow<List<MessageFacetOptionCount>> {
        val normalizedExcludedIds = excludedIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        return dao.observeFacetTagCounts(
            excludedIds = normalizedExcludedIds,
            excludedCount = normalizedExcludedIds.size,
        ).map { list ->
            list.map { row -> MessageFacetOptionCount(value = row.value.trim().lowercase(), count = row.count) }
                .filter { row -> row.value.isNotEmpty() }
        }
    }

    internal val postProcessDatabase: PushGoDatabase get() = database

    internal suspend fun postProcessLocator(id: String, scope: InboundDeliveryScope? = null): PostProcessLocator? {
        val expectedOriginScope = scope?.let { MessageImageStore.originScope(it) }
        return database.postProcessLocator("messages", id, expectedOriginScope)
            ?: database.postProcessLocator("thing_sub_messages", id, expectedOriginScope)
    }

    suspend fun getById(id: String): PushMessage? {
        return dao.getById(id)?.asModel()
            ?: thingSubMessageDao.getById(id)?.asModel()
    }

    suspend fun getByMessageId(messageId: String): PushMessage? {
        return dao.getByMessageId(messageId)?.asModel()
            ?: thingSubMessageDao.getByMessageId(messageId)?.asModel()
    }

    internal suspend fun getCanonicalInMessageDomain(incoming: PushMessage): PushMessage? {
        val stableId = incoming.messageId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return if (isThingScopedMessage(incoming)) thingSubMessageDao.getByMessageId(stableId)?.asModel()
            else dao.getByMessageId(stableId)?.asModel()
    }

    /** Replay follows the canonical domain and verified owner, never a same-ID row in another domain. */
    internal suspend fun getCanonicalInboundReplay(incoming: PushMessage, scope: InboundDeliveryScope?): PushMessage? = database.withTransaction {
        val canonical = getCanonicalInMessageDomain(incoming)
        if (canonical == null) return@withTransaction null
        try {
            if (!requireExistingInboundOwner(incoming, scope)) return@withTransaction null
        } catch (_: InboundIdentityConflictException) {
            return@withTransaction null
        }
        canonical
    }

    suspend fun getByNotificationId(notificationId: String): PushMessage? {
        return dao.getByNotificationId(notificationId)?.asModel()
    }

    suspend fun loadAllForExport(): List<PushMessage> = dao.loadAllForExport().map(MessageEntity::asModel)

    suspend fun loadEncryptedRecoveryCandidates(): List<PushMessage> =
        dao.loadEncryptedRecoveryCandidates().map(MessageEntity::asModel)

    suspend fun replaceEncryptedRecoveryCandidate(
        existingId: String,
        reparsed: PushMessage,
    ): Boolean {
        val committedReplacement = database.withTransaction {
            replaceEncryptedRecoveryCandidateInTransaction(existingId, reparsed)
        }
        committedReplacement?.let { replacement ->
            upsertRealtimeDerivedDataSafely(
                messageId = replacement.id,
                message = replacement,
                updateListPayload = true,
            )
        }
        return committedReplacement != null
    }

    /** Canonical mutation only; the caller publishes derived data after its outer commit. */
    private suspend fun replaceEncryptedRecoveryCandidateInTransaction(
        existingId: String,
        reparsed: PushMessage,
    ): PushMessage? {
        check(database.inTransaction())
        val existingEntity = dao.getById(existingId) ?: return null
        if (existingEntity.decryptionState !in ENCRYPTED_RECOVERY_STATES) return null
        val existing = existingEntity.asModel()
        val stableMessageId = existing.messageId?.trim()?.takeIf(String::isNotEmpty)
        if (stableMessageId == null || reparsed.messageId?.trim() != stableMessageId) return null
        if (!hasSameEncryptedRecoveryProvenance(existing, reparsed)) return null
        val replacement = reparsed.copy(
            id = existing.id,
            messageId = existing.messageId,
            isRead = existing.isRead,
            receivedAt = existing.receivedAt,
            status = existing.status,
            notificationId = existing.notificationId,
            serverId = existing.serverId,
        )
        val replacementEntity = MessageEntity.fromModel(replacement).copy(postProcessPending = existingEntity.postProcessPending)
        val changed = existingEntity.title != replacementEntity.title ||
            existingEntity.body != replacementEntity.body ||
            existingEntity.channel != replacementEntity.channel ||
            existingEntity.url != replacementEntity.url ||
            existingEntity.rawPayloadJson != replacementEntity.rawPayloadJson ||
            existingEntity.decryptionState != replacementEntity.decryptionState ||
            existingEntity.bodyPreview != replacementEntity.bodyPreview
        if (!changed || dao.update(replacementEntity) != 1) return null
        return replacement
    }

    /** Called only after the existing top-level owner has passed acceptance checks. */
    private suspend fun promoteVerifiedTopLevelEncryptedRecoveryInTransaction(
        incoming: PushMessage,
        existing: MessageEntity? = null,
    ): PushMessage? {
        check(database.inTransaction())
        if (incoming.decryptionState != DecryptionState.DECRYPT_OK || isThingScopedMessage(incoming)) return null
        val previous = existing ?: incoming.messageId?.let { dao.getByMessageId(it) } ?: return null
        if (previous.decryptionState !in ENCRYPTED_RECOVERY_STATES) return null
        return checkNotNull(replaceEncryptedRecoveryCandidateInTransaction(previous.id, incoming)) {
            "Authenticated encrypted recovery did not replace its canonical message"
        }
    }

    suspend fun getIdsBefore(readState: Boolean?, cutoff: Long): List<String> {
        return dao.getIdsBefore(readState, cutoff)
    }

    suspend fun getIdsByChannelRead(channel: String?, readState: Boolean?): List<String> {
        return dao.getIdsByChannelRead(channel, readState)
    }

    suspend fun getAllMessageIdsByChannel(channel: String): List<String> {
        val normalizedChannel = channel.trim()
        if (normalizedChannel.isEmpty()) return emptyList()
        return database.withTransaction {
            buildList {
                addAll(dao.getIdsByChannelRead(normalizedChannel, null))
                addAll(thingSubMessageDao.getIdsByChannel(normalizedChannel))
                addAll(pendingThingMessageDao.getIdsByChannel(normalizedChannel))
            }.distinct()
        }
    }

    suspend fun getUnreadIds(
        filter: MessageFilter,
        excludedIds: Set<String> = emptySet(),
    ): List<String> {
        val normalizedExcludedIds = excludedIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        return dao.getUnreadIdsForFilter(
            withUrl = if (filter.withUrlOnly) 1 else 0,
            channels = filter.channels.toList(),
            channelCount = filter.channels.size,
            tags = filter.tags.toList(),
            tagCount = filter.tags.size,
            serverId = filter.serverId,
            excludedIds = normalizedExcludedIds,
            excludedCount = normalizedExcludedIds.size,
        )
    }

    suspend fun insertIncoming(
        message: PushMessage,
        providerAckIdentity: ProviderAckIdentity? = null,
        deliveryScope: InboundDeliveryScope? = providerAckIdentity.inboundDeliveryScope(),
        claimIngressIdentity: Boolean = true,
        thingWriteTicket: ThingWriteTicket? = null,
        postProcessFlags: Int = 0,
        beforeAcceptanceCommit: (suspend () -> Unit)? = null,
        onPreviouslyAcceptedReplay: (() -> Unit)? = null,
    ): Boolean {
        if (!isMessageEntity(message)) {
            return false
        }
        val canonicalMessage = canonicalMessage(message)
        // The canonical message and its idempotency claims are durable user data.
        // Keep the optional search/summary projections outside this transaction:
        // a projection failure must mark those projections stale, never roll back
        // a successfully accepted notification.
        var committedTopLevel: Pair<String, PushMessage>? = null
        var committedEncryptedRecovery: PushMessage? = null
        val accepted = database.withTransaction {
            thingWriteTicket?.requireCurrent()
            beforeAcceptanceCommit?.invoke()
            val deliveryClaimed = claimInboundDelivery(
                inboundDeliveryLedgerDao = inboundDeliveryLedgerDao,
                channelId = canonicalMessage.channel,
                entityType = canonicalMessage.entityType,
                entityId = operationScopeEntityId(canonicalMessage),
                deliveryId = canonicalMessage.deliveryId.takeIf { claimIngressIdentity },
                opId = canonicalMessage.opId,
                appliedAt = canonicalMessage.receivedAt.toEpochMilli(),
                providerAckIdentity = providerAckIdentity,
                deliveryScope = deliveryScope,
                acceptanceProven = true,
            )
            val previouslyAccepted = !deliveryClaimed && hasProvenInboundAcceptance(database, deliveryScope,
                canonicalMessage.deliveryId, canonicalMessage.channel, canonicalMessage.entityType,
                operationScopeEntityId(canonicalMessage), canonicalMessage.opId)
            val recoveringLegacyReceipt = !deliveryClaimed && !previouslyAccepted && isLegacyStagingRecovery(database, deliveryScope,
                canonicalMessage.deliveryId, providerAckIdentity)
            if (!deliveryClaimed && !recoveringLegacyReceipt) {
                if (!previouslyAccepted) {
                    requireCurrentMessageAcceptance(canonicalMessage, deliveryScope)
                    committedEncryptedRecovery = promoteVerifiedTopLevelEncryptedRecoveryInTransaction(canonicalMessage)
                    proveInboundAcceptance(database, deliveryScope, canonicalMessage.deliveryId)
                } else if (!mayReplayCurrentCanonical(canonicalMessage, deliveryScope)) {
                    onPreviouslyAcceptedReplay?.invoke()
                } else {
                    committedEncryptedRecovery = promoteVerifiedTopLevelEncryptedRecoveryInTransaction(canonicalMessage)
                }
                return@withTransaction false
            }
            val claimed = claimOperationScope(
                operationLedgerDao = operationLedgerDao,
                channelId = canonicalMessage.channel,
                entityType = canonicalMessage.entityType,
                entityId = operationScopeEntityId(canonicalMessage),
                opId = canonicalMessage.opId.takeIf { claimIngressIdentity },
                deliveryId = canonicalMessage.deliveryId.takeIf { claimIngressIdentity },
                appliedAt = canonicalMessage.receivedAt.toEpochMilli(),
                providerAckIdentity = providerAckIdentity,
                deliveryScope = deliveryScope,
            )
            if (!claimed && !recoveringLegacyReceipt) {
                if (!hasProvenOperationAcceptance(database, deliveryScope, canonicalMessage.deliveryId,
                        canonicalMessage.channel, canonicalMessage.entityType,
                        operationScopeEntityId(canonicalMessage), canonicalMessage.opId)) {
                    requireCurrentMessageAcceptance(canonicalMessage, deliveryScope)
                    committedEncryptedRecovery = promoteVerifiedTopLevelEncryptedRecoveryInTransaction(canonicalMessage)
                } else if (!mayReplayCurrentCanonical(canonicalMessage, deliveryScope)) {
                    onPreviouslyAcceptedReplay?.invoke()
                } else {
                    committedEncryptedRecovery = promoteVerifiedTopLevelEncryptedRecoveryInTransaction(canonicalMessage)
                }
                return@withTransaction false
            }
            val stableMessageId = canonicalMessage.messageId?.trim()?.takeIf { it.isNotEmpty() }
            if (canonicalMessage.decryptionState == DecryptionState.DECRYPT_OK) {
                requireUnchangedEncryptedRecoveryTopology(canonicalMessage, deliveryScope)
            }
            if (isThingScopedMessage(canonicalMessage)) {
                if (!hasThingHead(canonicalMessage.thingId, deliveryScope)) {
                    enqueuePendingThingMessage(canonicalMessage, deliveryScope)
                    if (recoveringLegacyReceipt) proveInboundAcceptance(database, deliveryScope, canonicalMessage.deliveryId)
                    return@withTransaction false
                }
                if (stableMessageId != null) {
                    pruneThingSubMessageDuplicates(setOf(stableMessageId))
                }
                if (stableMessageId != null && thingSubMessageDao.getByMessageId(stableMessageId) != null) {
                    check(requireExistingInboundOwner(canonicalMessage, deliveryScope))
                    if (recoveringLegacyReceipt) proveInboundAcceptance(database, deliveryScope, canonicalMessage.deliveryId)
                    return@withTransaction false
                }
                val inserted = tryInsertThingSubMessage(ThingSubMessageEntity.fromModel(canonicalMessage))
                if (!inserted) requireCurrentMessageAcceptance(canonicalMessage, deliveryScope)
                if (recoveringLegacyReceipt) proveInboundAcceptance(database, deliveryScope, canonicalMessage.deliveryId)
                if (inserted) {
                    database.recordResourceOrigin(canonicalMessage.id, canonicalMessage.receivedAt.toEpochMilli(), deliveryScope)
                    database.markPostProcessPending("thing_sub_messages", canonicalMessage.id, postProcessFlags)
                }
                return@withTransaction inserted
            }

            if (stableMessageId != null) {
                pruneTopLevelMessageDuplicates(setOf(stableMessageId))
            }
            val existingCanonical = stableMessageId?.let { dao.getByMessageId(it) }
            if (existingCanonical != null) {
                check(requireExistingInboundOwner(canonicalMessage, deliveryScope, existingCanonical))
                committedEncryptedRecovery = promoteVerifiedTopLevelEncryptedRecoveryInTransaction(canonicalMessage, existingCanonical)
                if (recoveringLegacyReceipt) proveInboundAcceptance(database, deliveryScope, canonicalMessage.deliveryId)
                return@withTransaction false
            }

            val entity = MessageEntity.fromModel(canonicalMessage)
            val existing = dao.getById(entity.id)
            val inserted = if (existing != null) {
                dao.update(entity.copy(postProcessPending = existing.postProcessPending))
                true
            } else {
                tryInsertTopLevelMessage(entity)
            }
            if (!inserted) {
                requireCurrentMessageAcceptance(canonicalMessage, deliveryScope)
                committedEncryptedRecovery = promoteVerifiedTopLevelEncryptedRecoveryInTransaction(canonicalMessage)
                if (recoveringLegacyReceipt) proveInboundAcceptance(database, deliveryScope, canonicalMessage.deliveryId)
                return@withTransaction false
            }
            if (recoveringLegacyReceipt) proveInboundAcceptance(database, deliveryScope, canonicalMessage.deliveryId)
            database.recordResourceOrigin(entity.id, canonicalMessage.receivedAt.toEpochMilli(), deliveryScope)
            database.markPostProcessPending("messages", entity.id, postProcessFlags)
            committedTopLevel = entity.id to canonicalMessage
            true
        }
        committedEncryptedRecovery?.let { replacement ->
            upsertRealtimeDerivedDataSafely(
                messageId = replacement.id,
                message = replacement,
                updateListPayload = true,
            )
        }
        if (!accepted) {
            return false
        }
        committedTopLevel?.let { (messageId, committedMessage) ->
            upsertRealtimeDerivedDataSafely(messageId, committedMessage)
        }
        return true
    }

    private data class DuplicateOwnerProof(val id: String, val hash: String?, val gateway: String?, val device: String?)

    /** A retained failed envelope cannot acquire a new parent/domain on successful replay. */
    private suspend fun requireUnchangedEncryptedRecoveryTopology(
        incoming: PushMessage,
        scope: InboundDeliveryScope?,
    ) {
        if (scope == null) return
        val stableId = incoming.messageId?.trim()?.takeIf(String::isNotEmpty) ?: return
        val deliveryId = incoming.deliveryId?.trim()?.takeIf(String::isNotEmpty) ?: return
        fun candidate(state: String?, raw: String): Boolean {
            if (state !in ENCRYPTED_RECOVERY_STATES) return false
            return encryptedRecoveryProvenance(raw)?.get("delivery_id") == deliveryId
        }
        // Indexed, bounded domain lookups only on authenticated encrypted recovery.
        // A changed message_id is outside this guard; it never scans other identities.
        // Earlier proven-receipt returns retain their deletion/replay ACK authority.
        dao.getByMessageId(stableId)?.let { previous ->
            if (candidate(previous.decryptionState, previous.rawPayloadJson) &&
                hasMatchingEncryptedRecoveryOrigin("messages", previous.id, scope)) {
                requireExistingInboundOwner(incoming, scope, previous, sourceTable = "messages")
            }
        }
        thingSubMessageDao.getByMessageId(stableId)?.let { previous ->
            if (candidate(previous.decryptionState, previous.rawPayloadJson) &&
                hasMatchingEncryptedRecoveryOrigin("thing_sub_messages", previous.id, scope)) {
                requireExistingInboundOwner(incoming, scope, sourceTable = "thing_sub_messages")
            }
        }
        pendingThingMessageDao.getByMessageId(stableId)?.let { previous ->
            if (!candidate(previous.decryptionState, previous.rawPayloadJson)) return@let
            val previousScope = InboundDeliveryScope.create(previous.authenticatedGatewayUrl, previous.authenticatedDeviceKey)
                ?: return@let
            if (previousScope.gatewayUrl != scope.gatewayUrl) return@let
            if (encryptedRecoveryProvenance(previous.rawPayloadJson) != encryptedRecoveryProvenance(incoming.rawPayloadJson)) {
                throw InboundIdentityConflictException(InboundIdentityConflictReason.DIFFERENT_CONTENT)
            }
        }
    }

    /** Only positively matched persisted ownership may connect otherwise independent domains. */
    private fun hasMatchingEncryptedRecoveryOrigin(
        sourceTable: String,
        ownerId: String,
        scope: InboundDeliveryScope,
    ): Boolean = database.openHelper.readableDatabase.query(
        "SELECT origin_scope,authenticated_gateway_url,authenticated_device_key FROM message_resource_owners WHERE source_table=? AND owner_id=? AND owner_slot=0",
        arrayOf(sourceTable, ownerId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use false
        val origin = cursor.getString(0) ?: return@use false
        if (origin == MessageImageStore.originScope(scope)) return@use true
        val previousScope = InboundDeliveryScope.create(cursor.getString(1), cursor.getString(2)) ?: return@use false
        origin == MessageImageStore.originScope(previousScope) && previousScope.gatewayUrl == scope.gatewayUrl
    }

    private suspend fun mayReplayCurrentCanonical(incoming: PushMessage, scope: InboundDeliveryScope?): Boolean = try {
        requireCurrentMessageAcceptance(incoming, scope)
        true
    } catch (_: InboundIdentityConflictException) {
        // A true processed fact still owns its ACK after deletion, without adopting
        // the current foreign/replaced content or reviving its derived work.
        false
    }

    private suspend fun requireCurrentMessageAcceptance(incoming: PushMessage, scope: InboundDeliveryScope?) {
        if (scope == null) return // Trusted internal/local calls keep their existing contract.
        val canonical = requireExistingInboundOwner(incoming, scope)
        val pending = if (isThingScopedMessage(incoming)) requireExistingPendingOwner(incoming, scope) else false
        if (!canonical && !pending) throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
    }

    /** Only duplicate branches pay for this check, inside their acceptance transaction. */
    private suspend fun requireExistingInboundOwner(
        incoming: PushMessage,
        scope: InboundDeliveryScope?,
        existing: MessageEntity? = null,
        sourceTable: String? = null,
    ): Boolean {
        if (scope == null) return true
        val table = sourceTable ?: if (isThingScopedMessage(incoming)) "thing_sub_messages" else "messages"
        val owner = if (existing != null) {
            database.openHelper.readableDatabase.query(
                "SELECT origin_scope,authenticated_gateway_url,authenticated_device_key FROM message_resource_owners WHERE source_table=? AND owner_id=? AND owner_slot=0",
                arrayOf(table, existing.id),
            ).use { cursor ->
                if (cursor.moveToFirst()) DuplicateOwnerProof(existing.id, cursor.getString(0), cursor.getString(1), cursor.getString(2))
                else DuplicateOwnerProof(existing.id, null, null, null)
            }
        } else {
            val stableId = incoming.messageId ?: return false
            database.openHelper.readableDatabase.query(
                "SELECT m.id,o.origin_scope,o.authenticated_gateway_url,o.authenticated_device_key FROM $table m LEFT JOIN message_resource_owners o ON o.source_table=? AND o.owner_id=m.id AND o.owner_slot=0 WHERE m.message_id=? LIMIT 1",
                arrayOf(table, stableId),
            ).use { cursor -> if (cursor.moveToFirst())
                DuplicateOwnerProof(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)) else null }
                ?: return false
        }
        val ownerOrigin = owner.hash
        if (ownerOrigin.isNullOrBlank()) throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
        val actualPrevious = (existing?.asModel() ?: if (table == "messages") dao.getById(owner.id)?.asModel()
            else thingSubMessageDao.getById(owner.id)?.asModel())
            ?: throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
        if (ownerOrigin != MessageImageStore.originScope(scope)) {
            val deliveryId = actualPrevious.deliveryId?.trim()?.takeIf(String::isNotEmpty)
                ?: throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
            // A newly inserted CURRENT claim cannot match the DIFFERENT old originHash.
            val durableScope = if (owner.gateway != null || owner.device != null) {
                InboundDeliveryScope.create(owner.gateway, owner.device)?.takeIf { MessageImageStore.originScope(it) == ownerOrigin }
                    ?: throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
            } else null
            val previousGateway = durableScope?.gatewayUrl ?: database.provenInboundGateway(deliveryId, originHash = ownerOrigin) { fact ->
                fact.channelId == actualPrevious.channel && fact.entityType == actualPrevious.entityType &&
                    fact.entityId == operationScopeEntityId(actualPrevious) && fact.opId == actualPrevious.opId &&
                    fact.appliedAt == actualPrevious.receivedAt.toEpochMilli()
            } ?: throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
            if (previousGateway != scope.gatewayUrl) throw InboundIdentityConflictException(InboundIdentityConflictReason.FOREIGN_GATEWAY)
        }
        val authenticatedRecovery = incoming.decryptionState == DecryptionState.DECRYPT_OK &&
            actualPrevious.decryptionState in setOf(DecryptionState.NOT_CONFIGURED, DecryptionState.ALG_MISMATCH, DecryptionState.DECRYPT_FAILED) &&
            hasSameEncryptedRecoveryProvenance(actualPrevious, incoming)
        if (!sameCanonicalInboundContent(actualPrevious.rawPayloadJson, incoming.rawPayloadJson) && !authenticatedRecovery) {
            throw InboundIdentityConflictException(InboundIdentityConflictReason.DIFFERENT_CONTENT)
        }
        return true
    }

    private fun sameCanonicalInboundContent(previous: String, incoming: String): Boolean {
        fun content(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()?.apply {
            // Target/transport observations are not the immutable business representation.
            // Keep all business times, metadata, parent IDs, media and encrypted material.
            listOf("base_url", "gateway_url", "provider_device_key", "delivery_id", "ack_contract",
                "_fcm_message_id", "decryption_state", MessageImageStore.KEY_IMAGE_LOCAL_PATH,
                MessageImageStore.KEY_IMAGE_THUMBNAIL_LOCAL_PATH).forEach(::remove)
        }
        val old = content(previous) ?: return false
        val new = content(incoming) ?: return false
        return notificationJsonMatches(old, new)
    }

    suspend fun insert(message: PushMessage) {
        if (!isMessageEntity(message)) {
            return
        }
        val canonicalMessage = canonicalMessage(message)
        var committedTopLevel: Pair<String, PushMessage>? = null
        database.withTransaction {
            val deliveryClaimed = claimInboundDelivery(
                inboundDeliveryLedgerDao = inboundDeliveryLedgerDao,
                channelId = canonicalMessage.channel,
                entityType = canonicalMessage.entityType,
                entityId = operationScopeEntityId(canonicalMessage),
                deliveryId = canonicalMessage.deliveryId,
                opId = canonicalMessage.opId,
                appliedAt = canonicalMessage.receivedAt.toEpochMilli(),
            )
            if (!deliveryClaimed) {
                return@withTransaction
            }
            val claimed = claimOperationScope(
                operationLedgerDao = operationLedgerDao,
                channelId = canonicalMessage.channel,
                entityType = canonicalMessage.entityType,
                entityId = operationScopeEntityId(canonicalMessage),
                opId = canonicalMessage.opId,
                deliveryId = canonicalMessage.deliveryId,
                appliedAt = canonicalMessage.receivedAt.toEpochMilli(),
            )
            if (!claimed) {
                return@withTransaction
            }
            val stableMessageId = canonicalMessage.messageId?.trim()?.takeIf { it.isNotEmpty() }
            if (isThingScopedMessage(canonicalMessage)) {
                if (!hasThingHead(canonicalMessage.thingId)) {
                    enqueuePendingThingMessage(canonicalMessage)
                    return@withTransaction
                }
                if (stableMessageId != null) {
                    pruneThingSubMessageDuplicates(setOf(stableMessageId))
                }
                if (stableMessageId != null && thingSubMessageDao.getByMessageId(stableMessageId) != null) {
                    return@withTransaction
                }
                val inserted = tryInsertThingSubMessage(ThingSubMessageEntity.fromModel(canonicalMessage))
                if (!inserted) {
                    return@withTransaction
                }
                return@withTransaction
            }
            if (stableMessageId != null) {
                pruneTopLevelMessageDuplicates(setOf(stableMessageId))
            }
            if (stableMessageId != null && dao.getByMessageId(stableMessageId) != null) {
                return@withTransaction
            }
            val entity = MessageEntity.fromModel(canonicalMessage)
            val existing = dao.getById(entity.id)
            val inserted = if (existing != null) {
                dao.update(entity.copy(postProcessPending = existing.postProcessPending))
                true
            } else {
                tryInsertTopLevelMessage(entity)
            }
            if (!inserted) {
                return@withTransaction
            }
            committedTopLevel = entity.id to canonicalMessage
        }
        committedTopLevel?.let { (messageId, committedMessage) ->
            upsertRealtimeDerivedDataSafely(messageId, committedMessage)
        }
    }

    suspend fun insertAll(messages: List<PushMessage>) {
        if (messages.isEmpty()) return
        val committedTopLevel = mutableListOf<Pair<String, PushMessage>>()
        database.withTransaction {
            val topLevelMessages = mutableListOf<PushMessage>()
            val thingScopedMessages = mutableListOf<PushMessage>()
            messages.forEach { message ->
                if (!isMessageEntity(message)) {
                    return@forEach
                }
                val canonicalMessage = canonicalMessage(message)
                val deliveryClaimed = claimInboundDelivery(
                    inboundDeliveryLedgerDao = inboundDeliveryLedgerDao,
                    channelId = canonicalMessage.channel,
                    entityType = canonicalMessage.entityType,
                    entityId = operationScopeEntityId(canonicalMessage),
                    deliveryId = canonicalMessage.deliveryId,
                    opId = canonicalMessage.opId,
                    appliedAt = canonicalMessage.receivedAt.toEpochMilli(),
                )
                if (!deliveryClaimed) {
                    return@forEach
                }
                val claimed = claimOperationScope(
                    operationLedgerDao = operationLedgerDao,
                    channelId = canonicalMessage.channel,
                    entityType = canonicalMessage.entityType,
                    entityId = operationScopeEntityId(canonicalMessage),
                    opId = canonicalMessage.opId,
                    deliveryId = canonicalMessage.deliveryId,
                    appliedAt = canonicalMessage.receivedAt.toEpochMilli(),
                )
                if (!claimed) {
                    return@forEach
                }
                if (isThingScopedMessage(canonicalMessage)) {
                    if (!hasThingHead(canonicalMessage.thingId)) {
                        enqueuePendingThingMessage(canonicalMessage)
                        return@forEach
                    }
                    thingScopedMessages += canonicalMessage
                } else {
                    topLevelMessages += canonicalMessage
                }
            }

            if (topLevelMessages.isNotEmpty()) {
                val stableMessageIds = topLevelMessages
                    .mapNotNull { it.messageId?.trim()?.takeIf { value -> value.isNotEmpty() } }
                    .toSet()
                if (stableMessageIds.isNotEmpty()) {
                    pruneTopLevelMessageDuplicates(stableMessageIds)
                }
                val existingByStableId = if (stableMessageIds.isEmpty()) {
                    emptyMap()
                } else {
                    getMessagesByMessageIdsChunked(stableMessageIds)
                        .asSequence()
                        .filter { !it.messageId.isNullOrBlank() }
                        .fold(linkedMapOf<String, MessageEntity>()) { acc, entity ->
                            val key = entity.messageId!!.trim()
                            val current = acc[key]
                            acc[key] = if (current == null || isMessageEntityNewer(entity, current)) {
                                entity
                            } else {
                                current
                            }
                            acc
                        }
                }
                val resolved = linkedMapOf<String, Pair<MessageEntity, PushMessage>>()
                topLevelMessages.forEach { message ->
                    val stableMessageId = message.messageId?.trim()?.takeIf { value -> value.isNotEmpty() }
                    val baseEntity = MessageEntity.fromModel(message)
                    val existingStable = stableMessageId?.let(existingByStableId::get)
                    val targetEntity = if (existingStable != null && existingStable.id != baseEntity.id) {
                        baseEntity.copy(id = existingStable.id)
                    } else {
                        baseEntity
                    }
                    val current = resolved[targetEntity.id]
                    if (current == null || isPushMessageNewer(message, current.second)) {
                        resolved[targetEntity.id] = targetEntity to message
                    }
                }
                val entities = resolved.values.map { it.first }
                val existingById = if (entities.isEmpty()) {
                    emptyMap()
                } else {
                    getMessagesByIdsChunked(entities.map { it.id }).associateBy { it.id }
                }
                resolved.values.forEach { (entity, message) ->
                    val persisted = if (existingById.containsKey(entity.id)) {
                        dao.update(entity.copy(postProcessPending = existingById.getValue(entity.id).postProcessPending))
                        true
                    } else {
                        tryInsertTopLevelMessage(entity)
                    }
                    if (!persisted) {
                        return@forEach
                    }
                    committedTopLevel += entity.id to message
                }
            }
            if (thingScopedMessages.isNotEmpty()) {
                val stableMessageIds = thingScopedMessages
                    .mapNotNull { it.messageId?.trim()?.takeIf { value -> value.isNotEmpty() } }
                    .toSet()
                if (stableMessageIds.isNotEmpty()) {
                    pruneThingSubMessageDuplicates(stableMessageIds)
                }
                val existingByStableId = if (stableMessageIds.isEmpty()) {
                    emptyMap()
                } else {
                    getThingSubMessagesByMessageIdsChunked(stableMessageIds)
                        .asSequence()
                        .filter { !it.messageId.isNullOrBlank() }
                        .fold(linkedMapOf<String, ThingSubMessageEntity>()) { acc, entity ->
                            val key = entity.messageId!!.trim()
                            val current = acc[key]
                            acc[key] = if (current == null || isThingSubMessageEntityNewer(entity, current)) {
                                entity
                            } else {
                                current
                            }
                            acc
                        }
                }
                val resolved = linkedMapOf<String, ThingSubMessageEntity>()
                thingScopedMessages.forEach { message ->
                    val stableMessageId = message.messageId?.trim()?.takeIf { value -> value.isNotEmpty() }
                    val baseEntity = ThingSubMessageEntity.fromModel(message)
                    val existingStable = stableMessageId?.let(existingByStableId::get)
                    val targetEntity = if (existingStable != null && existingStable.id != baseEntity.id) {
                        baseEntity.copy(id = existingStable.id)
                    } else {
                        baseEntity
                    }
                    val current = resolved[targetEntity.id]
                    if (current == null || isThingSubMessageEntityNewer(targetEntity, current)) {
                        resolved[targetEntity.id] = targetEntity
                    }
                }
                if (resolved.isNotEmpty()) {
                    val entities = resolved.values.toList()
                    val existingById = getThingSubMessagesByIdsChunked(entities.map { it.id }).associateBy { it.id }
                    entities.forEach { entity ->
                        if (existingById.containsKey(entity.id)) {
                            thingSubMessageDao.update(entity.copy(postProcessPending = existingById.getValue(entity.id).postProcessPending))
                        } else {
                            tryInsertThingSubMessage(entity)
                        }
                    }
                }
            }
        }
        committedTopLevel.forEach { (messageId, committedMessage) ->
            upsertRealtimeDerivedDataSafely(messageId, committedMessage)
        }
    }

    suspend fun markRead(id: String) {
        database.withTransaction {
            val existing = dao.getById(id) ?: return@withTransaction
            if (existing.isRead) {
                return@withTransaction
            }
            dao.markRead(id)
        }
    }

    /** Serialize the final exact-row check and synchronous OS publication with local deletion. */
    suspend fun publishNotificationIfCurrent(message: PushMessage, publish: () -> Boolean): Boolean? =
        database.withTransaction {
            val canonical = canonicalMessage(message)
            if (isThingScopedMessage(canonical)) {
                val current = thingSubMessageDao.getById(canonical.id) ?: return@withTransaction null
                val expected = ThingSubMessageEntity.fromModel(canonical)
                if (!notificationPayloadMatches(current.rawPayloadJson, expected.rawPayloadJson) ||
                    current != expected.copy(bodyPreview = current.bodyPreview, rawPayloadJson = current.rawPayloadJson, postProcessPending = current.postProcessPending)
                ) return@withTransaction null
            } else {
                val current = dao.getById(canonical.id) ?: return@withTransaction null
                val expected = MessageEntity.fromModel(canonical)
                if (!notificationPayloadMatches(current.rawPayloadJson, expected.rawPayloadJson) ||
                    current != expected.copy(
                        isRead = current.isRead,
                        bodyPreview = current.bodyPreview,
                        listPayloadJson = current.listPayloadJson,
                        rawPayloadJson = current.rawPayloadJson,
                        postProcessPending = current.postProcessPending,
                    )
                ) return@withTransaction null
            }
            publish()
        }

    private fun notificationPayloadMatches(current: String, expected: String): Boolean {
        if (current == expected) return true
        fun payload(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()?.apply {
            remove(MessageImageStore.KEY_IMAGE_LOCAL_PATH)
            remove(MessageImageStore.KEY_IMAGE_THUMBNAIL_LOCAL_PATH)
        }
        val currentPayload = payload(current) ?: return false
        val expectedPayload = payload(expected) ?: return false
        return notificationJsonMatches(currentPayload, expectedPayload)
    }

    private fun notificationJsonMatches(current: Any?, expected: Any?): Boolean = when {
        current is JSONObject && expected is JSONObject -> {
            val keys = current.keys().asSequence().toSet()
            keys == expected.keys().asSequence().toSet() &&
                keys.all { notificationJsonMatches(current.get(it), expected.get(it)) }
        }
        current is JSONArray && expected is JSONArray -> current.length() == expected.length() &&
            (0 until current.length()).all { notificationJsonMatches(current.get(it), expected.get(it)) }
        else -> current == expected
    }

    suspend fun markRead(ids: Collection<String>): Int {
        val normalizedIds = ids
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (normalizedIds.isEmpty()) {
            return 0
        }
        return database.withTransaction {
            dao.markReadByIds(normalizedIds)
        }
    }

    suspend fun markAllRead() {
        database.withTransaction {
            dao.markAllRead()
        }
    }

    suspend fun updateRawPayload(id: String, rawPayloadJson: String) {
        var committedUpdate: PushMessage? = null
        database.withTransaction {
            val existing = dao.getById(id) ?: return@withTransaction
            dao.updateRawPayload(id, rawPayloadJson)
            committedUpdate = existing.asModel().copy(rawPayloadJson = rawPayloadJson)
        }
        committedUpdate?.let { updatedMessage ->
            upsertRealtimeDerivedDataSafely(
                messageId = id,
                message = updatedMessage,
                updateListPayload = true,
            )
        }
    }

    suspend fun deleteById(id: String) {
        database.withTransaction {
            if (dao.getById(id) != null) {
                dao.deleteById(id)
            } else if (thingSubMessageDao.getById(id) != null) {
                thingSubMessageDao.deleteByIds(listOf(id))
            }
        }
    }

    suspend fun deleteByIds(ids: Collection<String>): Int {
        val normalizedIds = ids
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (normalizedIds.isEmpty()) {
            return 0
        }
        return database.withTransaction {
            normalizedIds
                .chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
                .sumOf { chunk ->
                    dao.deleteByIds(chunk) + thingSubMessageDao.deleteByIds(chunk)
                }
        }
    }

    suspend fun deleteByChannel(channel: String): Int {
        val normalizedChannel = channel.trim()
        if (normalizedChannel.isEmpty()) return 0
        return database.withTransaction {
            dao.deleteByChannelRead(normalizedChannel, null) +
                thingSubMessageDao.deleteByChannel(normalizedChannel) +
                pendingThingMessageDao.deleteByChannel(normalizedChannel)
        }
    }

    suspend fun deleteByChannelRead(channel: String?, readState: Boolean?): Int {
        return database.withTransaction {
            val normalizedChannel = channel?.trim()
            dao.deleteByChannelRead(normalizedChannel, readState)
        }
    }

    suspend fun deleteOldestReadMessages(limit: Int): Int {
        return database.withTransaction {
            dao.deleteOldestRead(limit)
        }
    }

    suspend fun deleteOldestReadMessages(limit: Int, excludedChannels: List<String>): Int {
        if (excludedChannels.isEmpty()) {
            return deleteOldestReadMessages(limit)
        }
        return database.withTransaction {
            dao.deleteOldestReadExcludingChannels(
                limit = limit,
                excludedChannels = excludedChannels,
                excludedSize = excludedChannels.size,
            )
        }
    }

    suspend fun getOldestReadIds(limit: Int, excludedChannels: List<String>): List<String> {
        if (excludedChannels.isEmpty()) {
            return dao.getOldestReadIds(limit)
        }
        return dao.getOldestReadIdsExcludingChannels(
            limit = limit,
            excludedChannels = excludedChannels,
            excludedSize = excludedChannels.size,
        )
    }

    suspend fun deleteAll() {
        database.withTransaction {
            dao.deleteAll()
        }
    }

    suspend fun deleteAllRead() {
        database.withTransaction {
            dao.deleteAllRead()
        }
    }

    suspend fun deleteBefore(readState: Boolean?, cutoff: Long) {
        database.withTransaction {
            dao.deleteBefore(readState, cutoff)
        }
    }

    suspend fun totalCount(): Int = channelStatsDao.totalCount()

    suspend fun unreadCount(): Int = channelStatsDao.unreadCount()

    suspend fun countMessages(readState: Boolean?, cutoff: Long?): Int {
        return dao.countMessages(readState, cutoff)
    }

    private fun parseSearchQuery(raw: String): SearchQueryPlan {
        val textTokens = linkedSetOf<String>()
        val tags = linkedSetOf<String>()
        raw.take(MAX_QUERY_LENGTH)
            .split("[\\p{Z}\\s]+".toRegex())
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { token ->
                val tag = parseTagToken(token)
                if (tag != null) {
                    if (tags.size < MAX_TAG_COUNT) {
                        tags += tag.take(MAX_TAG_LENGTH)
                    }
                } else {
                    val textToken = SearchTextNormalizer
                        .normalize(token.replace("\"", ""))
                        .take(MAX_TOKEN_LENGTH)
                    if (textToken.isNotEmpty()) {
                        textTokens += textToken
                    }
                }
            }
        return SearchQueryPlan(
            textTokens = textTokens.take(MAX_QUERY_TOKENS),
            tags = tags.toList(),
        )
    }

    private fun parseTagToken(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val value = when {
            trimmed.startsWith("#") -> trimmed.drop(1)
            trimmed.startsWith("tag:", ignoreCase = true) -> trimmed.drop(4)
            else -> return null
        }
        val normalized = value.trim().lowercase(Locale.ROOT)
        return normalized.takeIf { it.isNotEmpty() }
    }

    private suspend fun upsertMetadataIndex(message: PushMessage) {
        upsertMetadataIndex(message.id, message)
    }

    private suspend fun upsertRealtimeDerivedDataSafely(
        messageId: String,
        message: PushMessage,
        updateListPayload: Boolean = false,
    ) {
        try {
            // Keep the derived consumers atomic with one another, but separate
            // from the canonical transaction. A partial projection must not be
            // observable as a successful update, and a projection failure must
            // never roll back the already-committed canonical row.
            database.withTransaction {
                if (updateListPayload) {
                    metadataIndexDao.deleteSummaryProjectionMarker(messageId)
                    dao.updateListPayload(messageId, MessageEntity.buildListPayloadJson(message.rawPayloadJson))
                }
                upsertMetadataIndex(messageId, message)
                metadataIndexDao.insertAll(
                    listOf(summaryProjectionMarker(messageId, message.receivedAt.toEpochMilli()))
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            derivedWriteFailureGeneration.incrementAndGet()
            searchIndexReady = false
            summaryProjectionReady = false
            val description = error.toString().take(1_000)
            database.openHelper.writableDatabase.execSQL(
                """
                UPDATE message_derived_state
                SET status = 'stale', cursor_local_message_id = ?,
                    updated_at_epoch_ms = ?, last_error = ?
                WHERE component IN ('message_metadata_index', 'message_summary_projection')
                """.trimIndent(),
                arrayOf<Any>(messageId, System.currentTimeMillis(), description),
            )
        }
    }

    private suspend fun upsertMetadataIndex(messageId: String, message: PushMessage) {
        val metadataRows = message.metadata
            .asSequence()
            .map { (key, value) ->
                MessageMetadataIndexEntity(
                    messageId = messageId,
                    keyName = "metadata_${key.trim().lowercase(Locale.ROOT)}",
                    valueNorm = value.trim().lowercase(Locale.ROOT),
                    label = null,
                    receivedAt = message.receivedAt.toEpochMilli(),
                )
            }
            .filter { it.keyName.isNotEmpty() && it.valueNorm.isNotEmpty() }
            .toList()
        val tagRows = message.tags
            .asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .map { tag ->
                MessageMetadataIndexEntity(
                    messageId = messageId,
                    keyName = "tag",
                    valueNorm = tag,
                    label = null,
                    receivedAt = message.receivedAt.toEpochMilli(),
                )
            }
            .toList()
        val searchText = SearchTextNormalizer.joinedCandidates(
            listOf(message.title, message.body, message.channel),
        )
        val searchRows = listOf(
            MessageMetadataIndexEntity(
                messageId = messageId,
                keyName = "search_text",
                valueNorm = SEARCH_TEXT_INDEX_VERSION,
                label = searchText,
                receivedAt = message.receivedAt.toEpochMilli(),
            )
        )
        val rows = (metadataRows + tagRows + searchRows)
            .distinctBy { "${it.keyName}\u001F${it.valueNorm}" }

        metadataIndexDao.deleteByMessageId(messageId)
        if (rows.isNotEmpty()) {
            metadataIndexDao.insertAll(rows)
        }
    }

    suspend fun backfillTagMetadataIndexIfNeeded(context: Context) {
        ensureSearchIndexReady()
        ensureSummaryProjectionReady()
        val prefs = context.getSharedPreferences(TAG_METADATA_BACKFILL_PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            putBoolean(TAG_METADATA_BACKFILL_KEY, true)
        }
    }

    private suspend fun ensureSearchIndexReady() {
        if (searchIndexReady) return
        searchIndexBackfillMutex.withLock {
            while (!searchIndexReady) {
                val failureGeneration = derivedWriteFailureGeneration.get()
                val canReuseReadyState = repairSearchIndexIfNeeded(failureGeneration)
                if (derivedWriteFailureGeneration.get() != failureGeneration) continue
                if (!canReuseReadyState) {
                    markDerivedComponentReady("message_metadata_index")
                }
                if (derivedWriteFailureGeneration.get() != failureGeneration) continue
                searchIndexReady = true
                if (derivedWriteFailureGeneration.get() != failureGeneration) {
                    searchIndexReady = false
                }
            }
        }
    }

    private suspend fun repairSearchIndexIfNeeded(failureGeneration: Long): Boolean {
        val state = readDerivedState("message_metadata_index")
        if (state.lastError != null) {
            state.cursorMessageId
                ?.let { dao.getById(it) }
                ?.let { upsertMetadataIndex(it.id, it.asModel()) }
        }
        val missingBefore = metadataIndexDao.countMessagesMissingSearchText(SEARCH_TEXT_INDEX_VERSION)
        var beforeReceivedAt = Long.MAX_VALUE
        var beforeId = "\uFFFF"
        while (metadataIndexDao.countMessagesMissingSearchText(SEARCH_TEXT_INDEX_VERSION) > 0) {
            val messages = dao.getMissingSearchMetadataPage(
                searchTextVersion = SEARCH_TEXT_INDEX_VERSION,
                beforeReceivedAt = beforeReceivedAt,
                beforeId = beforeId,
                limit = METADATA_BACKFILL_BATCH_SIZE,
            )
            if (messages.isEmpty() && derivedWriteFailureGeneration.get() != failureGeneration) {
                return false
            }
            check(messages.isNotEmpty()) { "search metadata repair made no progress" }
            database.withTransaction {
                for (entity in messages) {
                    upsertMetadataIndex(entity.id, entity.asModel())
                }
            }
            beforeReceivedAt = messages.last().receivedAt
            beforeId = messages.last().id
            kotlinx.coroutines.yield()
        }
        if (derivedWriteFailureGeneration.get() != failureGeneration) return false
        check(metadataIndexDao.countMessagesMissingSearchText(SEARCH_TEXT_INDEX_VERSION) == 0)
        return state.isReusableReady && missingBefore == 0
    }

    private suspend fun ensureSummaryProjectionReady() {
        if (summaryProjectionReady) return
        summaryProjectionBackfillMutex.withLock {
            while (!summaryProjectionReady) {
                val failureGeneration = derivedWriteFailureGeneration.get()
                val canReuseReadyState = repairSummaryProjectionIfNeeded(failureGeneration)
                if (derivedWriteFailureGeneration.get() != failureGeneration) continue
                if (!canReuseReadyState) {
                    markDerivedComponentReady("message_summary_projection")
                }
                if (derivedWriteFailureGeneration.get() != failureGeneration) continue
                summaryProjectionReady = true
                if (derivedWriteFailureGeneration.get() != failureGeneration) {
                    summaryProjectionReady = false
                }
            }
        }
    }

    private suspend fun repairSummaryProjectionIfNeeded(failureGeneration: Long): Boolean {
        val state = readDerivedState("message_summary_projection")
        if (state.lastError != null) {
            state.cursorMessageId
                ?.let { dao.getById(it) }
                ?.let { repairSummaryProjection(it) }
        }
        val missingBefore = dao.countMessagesMissingSummaryProjection(SUMMARY_PROJECTION_VERSION)
        var beforeReceivedAt = Long.MAX_VALUE
        var beforeId = "\uFFFF"
        while (dao.countMessagesMissingSummaryProjection(SUMMARY_PROJECTION_VERSION) > 0) {
            val messages = dao.getMissingSummaryProjectionPage(
                summaryProjectionVersion = SUMMARY_PROJECTION_VERSION,
                beforeReceivedAt = beforeReceivedAt,
                beforeId = beforeId,
                limit = METADATA_BACKFILL_BATCH_SIZE,
            )
            if (messages.isEmpty() && derivedWriteFailureGeneration.get() != failureGeneration) {
                return false
            }
            check(messages.isNotEmpty()) { "message summary repair made no progress" }
            database.withTransaction {
                messages.forEach { repairSummaryProjection(it) }
            }
            beforeReceivedAt = messages.last().receivedAt
            beforeId = messages.last().id
            kotlinx.coroutines.yield()
        }
        if (derivedWriteFailureGeneration.get() != failureGeneration) return false
        check(dao.countMessagesMissingSummaryProjection(SUMMARY_PROJECTION_VERSION) == 0)
        return state.isReusableReady && missingBefore == 0
    }

    private suspend fun repairSummaryProjection(entity: MessageEntity) {
        dao.updateListPayload(
            entity.id,
            MessageEntity.buildListPayloadJson(entity.rawPayloadJson),
        )
        metadataIndexDao.insertAll(listOf(summaryProjectionMarker(entity.id, entity.receivedAt)))
    }

    private fun summaryProjectionMarker(messageId: String, receivedAt: Long) =
        MessageMetadataIndexEntity(
            messageId = messageId,
            keyName = "summary_projection",
            valueNorm = SUMMARY_PROJECTION_VERSION,
            label = null,
            receivedAt = receivedAt,
        )

    private fun readDerivedState(component: String): DerivedState {
        return database.openHelper.writableDatabase.query(
            """
            SELECT status, cursor_local_message_id, last_error
            FROM message_derived_state
            WHERE component = ?
            """.trimIndent(),
            arrayOf(component),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use DerivedState(null, null, null)
            DerivedState(
                status = cursor.getString(0),
                cursorMessageId = cursor.takeUnless { it.isNull(1) }?.getString(1),
                lastError = cursor.takeUnless { it.isNull(2) }?.getString(2),
            )
        }
    }

    private fun markDerivedComponentReady(component: String) {
        val revision = database.openHelper.writableDatabase.query(
            "SELECT revision FROM message_store_revision WHERE id = 1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE message_derived_state
            SET status = 'ready', source_revision = ?, cursor_local_message_id = NULL,
                updated_at_epoch_ms = ?, last_error = NULL
            WHERE component = ?
            """.trimIndent(),
            arrayOf<Any>(revision, System.currentTimeMillis(), component),
        )
    }

    private data class DerivedState(
        val status: String?,
        val cursorMessageId: String?,
        val lastError: String?,
    ) {
        val isReusableReady: Boolean
            get() = status == "ready" && cursorMessageId == null && lastError == null
    }

    private suspend fun pruneTopLevelMessageDuplicates(stableMessageIds: Set<String>) {
        if (stableMessageIds.isEmpty()) {
            return
        }
        val duplicates = mutableListOf<MessageEntity>()
        val existing = getMessagesByMessageIdsChunked(stableMessageIds)
            .filter { !it.messageId.isNullOrBlank() }
        existing
            .groupBy { entity -> entity.messageId!!.trim() }
            .forEach { (_, candidates) ->
                if (candidates.size <= 1) {
                    return@forEach
                }
                val keep = candidates.reduce { best, next ->
                    if (isMessageEntityNewer(next, best)) next else best
                }
                candidates.forEach { candidate ->
                    if (candidate.id != keep.id) {
                        duplicates += candidate
                    }
                }
            }
        if (duplicates.isEmpty()) {
            return
        }
        duplicates.map { it.id }
            .chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
            .forEach { dao.deleteByIds(it) }
    }

    private suspend fun pruneThingSubMessageDuplicates(stableMessageIds: Set<String>) {
        if (stableMessageIds.isEmpty()) {
            return
        }
        val duplicates = mutableListOf<ThingSubMessageEntity>()
        val existing = getThingSubMessagesByMessageIdsChunked(stableMessageIds)
            .filter { !it.messageId.isNullOrBlank() }
        existing
            .groupBy { entity -> entity.messageId!!.trim() }
            .forEach { (_, candidates) ->
                if (candidates.size <= 1) {
                    return@forEach
                }
                val keep = candidates.reduce { best, next ->
                    if (isThingSubMessageEntityNewer(next, best)) next else best
                }
                candidates.forEach { candidate ->
                    if (candidate.id != keep.id) {
                        duplicates += candidate
                    }
                }
            }
        if (duplicates.isEmpty()) {
            return
        }
        duplicates.map { it.id }
            .chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
            .forEach { thingSubMessageDao.deleteByIds(it) }
    }

    private suspend fun getMessagesByMessageIdsChunked(messageIds: Collection<String>): List<MessageEntity> {
        return messageIds
            .chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
            .flatMap { dao.getByMessageIds(it) }
    }

    private suspend fun getMessagesByIdsChunked(ids: Collection<String>): List<MessageEntity> {
        return ids
            .chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
            .flatMap { dao.getByIds(it) }
    }

    private suspend fun getThingSubMessagesByMessageIdsChunked(
        messageIds: Collection<String>,
    ): List<ThingSubMessageEntity> {
        return messageIds
            .chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
            .flatMap { thingSubMessageDao.getByMessageIds(it) }
    }

    private suspend fun getThingSubMessagesByIdsChunked(ids: Collection<String>): List<ThingSubMessageEntity> {
        return ids
            .chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
            .flatMap { thingSubMessageDao.getByIds(it) }
    }

    private fun isPushMessageNewer(candidate: PushMessage, current: PushMessage): Boolean {
        val candidateMs = candidate.receivedAt.toEpochMilli()
        val currentMs = current.receivedAt.toEpochMilli()
        if (candidateMs != currentMs) {
            return candidateMs > currentMs
        }
        return candidate.id > current.id
    }

    private fun isMessageEntityNewer(candidate: MessageEntity, current: MessageEntity): Boolean {
        if (candidate.receivedAt != current.receivedAt) {
            return candidate.receivedAt > current.receivedAt
        }
        return candidate.id > current.id
    }

    private fun isThingSubMessageEntityNewer(
        candidate: ThingSubMessageEntity,
        current: ThingSubMessageEntity,
    ): Boolean {
        if (candidate.receivedAt != current.receivedAt) {
            return candidate.receivedAt > current.receivedAt
        }
        return candidate.id > current.id
    }

    private suspend fun hasThingHead(thingId: String?, scope: InboundDeliveryScope? = null): Boolean {
        val normalizedThingId = thingId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (scope == null) return thingHeadDao.existsByThingId(normalizedThingId)
        val head = database.openHelper.readableDatabase.query(
            "SELECT source_id,message_id FROM thing_heads WHERE thing_id=? LIMIT 1", arrayOf(normalizedThingId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null } ?: return false
        database.requireInboundHeadGateway(scope, head.first, head.second)
        return true
    }

    suspend fun replayPendingForThing(
        thingId: String,
        thingWriteTicket: ThingWriteTicket? = null,
        onReplayPage: ((Int) -> Unit)? = null,
        deliveryScope: InboundDeliveryScope? = null,
        verifiedParentGateway: String? = null,
    ) {
        // Only the shared canonical transaction may carry a proof from a headless
        // visible group; a caller cannot infer ownership from a bare thing ID.
        if (verifiedParentGateway != null) check(database.inTransaction())
        val normalizedThingId = thingId.trim().takeIf { it.isNotEmpty() } ?: return
        database.withTransaction {
            thingWriteTicket?.requireCurrent()
            val head = thingHeadDao.getByThingId(normalizedThingId)
            val headDeliveryId = head?.messageId?.trim()?.takeIf(String::isNotEmpty)
            val targetGateway = if (head != null) {
                if (deliveryScope != null) {
                    database.requireInboundHeadGateway(deliveryScope, head.sourceId, headDeliveryId)
                    deliveryScope.gatewayUrl
                } else database.authenticatedSourceGateway(head.sourceId)
                    ?: headDeliveryId?.let { database.provenInboundGateway(it, scopedSourceId = head.sourceId) }
                    ?: return@withTransaction
            } else verifiedParentGateway ?: return@withTransaction
            if (deliveryScope != null && targetGateway != deliveryScope.gatewayUrl) {
                throw InboundIdentityConflictException(InboundIdentityConflictReason.FOREIGN_GATEWAY)
            }
            // Promotion has no per-child external effect. Source id/message_id are
            // unique; visible ordering uses unchanged business times, never rowid.
            // Keep the entire original transaction/rollback boundary, while holding
            // at most 64 full payloads and 64 consumed IDs at once.
            var afterId: String? = null
            while (true) {
                val pending = if (afterId == null) pendingThingMessageDao.firstReplayPage(normalizedThingId)
                    else pendingThingMessageDao.nextReplayPage(normalizedThingId, afterId)
                if (pending.isEmpty()) break
                onReplayPage?.invoke(pending.size)
                val consumedIds = mutableListOf<String>()
                pending.forEach { row ->
                    val rowScope = InboundDeliveryScope.create(row.authenticatedGatewayUrl, row.authenticatedDeviceKey)
                    if (rowScope?.gatewayUrl != targetGateway) return@forEach
                    val inserted = tryInsertThingSubMessage(
                        ThingSubMessageEntity(
                            id = row.id,
                            messageId = row.messageId,
                            title = row.title,
                            body = row.body,
                            channel = row.channel,
                            url = row.url,
                            receivedAt = row.receivedAt,
                            rawPayloadJson = row.rawPayloadJson,
                            status = row.status,
                            decryptionState = row.decryptionState,
                            notificationId = row.notificationId,
                            serverId = row.serverId,
                            bodyPreview = row.bodyPreview,
                            entityType = row.entityType,
                            entityId = row.entityId,
                            eventId = row.eventId,
                            thingId = row.thingId,
                            eventState = row.eventState,
                            eventTimeEpoch = row.eventTimeEpoch,
                            occurredAtEpoch = row.occurredAtEpoch,
                        )
                    )
                    if (inserted) {
                        database.recordResourceOrigin(row.id, row.receivedAt, rowScope)
                        consumedIds += row.id
                    }
                }
                if (consumedIds.isNotEmpty()) pendingThingMessageDao.deleteByIds(consumedIds)
                afterId = pending.last().id
            }
        }
    }

    private suspend fun tryInsertTopLevelMessage(entity: MessageEntity): Boolean {
        return runCatching {
            dao.insert(entity)
            true
        }.getOrElse { error ->
            if (isMessageIdUniqueConflict(error)) {
                false
            } else {
                throw error
            }
        }
    }

    private suspend fun tryInsertThingSubMessage(entity: ThingSubMessageEntity): Boolean {
        return runCatching {
            thingSubMessageDao.insert(entity)
            true
        }.getOrElse { error ->
            if (isMessageIdUniqueConflict(error)) {
                false
            } else {
                throw error
            }
        }
    }

    private fun isMessageIdUniqueConflict(error: Throwable): Boolean {
        val trace = buildString {
            var current: Throwable? = error
            while (current != null) {
                current.message?.let { append(it.lowercase()).append(' ') }
                current = current.cause
            }
        }
        val isUniqueConflict = trace.contains("unique constraint failed")
            || trace.contains("sqlite_constraint_unique")
            || trace.contains("constraint_unique")
        if (!isUniqueConflict) {
            return false
        }
        return trace.contains("messages.messageid")
            || trace.contains("thing_sub_messages.messageid")
            || trace.contains("index_messages_messageid_unique")
            || trace.contains("index_thing_sub_messages_messageid_unique")
    }

    private fun isThingScopedMessage(message: PushMessage): Boolean {
        val entityType = message.entityType.trim().lowercase()
        val thingId = message.thingId?.trim()?.takeIf { it.isNotEmpty() }
        return entityType == "message" && thingId != null
    }

    private suspend fun enqueuePendingThingMessage(message: PushMessage, scope: InboundDeliveryScope? = null) {
        val thingScoped = ThingSubMessageEntity.fromModel(message)
        val thingId = thingScoped.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val stableMessageId = thingScoped.messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val inserted = pendingThingMessageDao.insert(
            PendingThingMessageEntity.fromThingScopedMessage(
                thingScoped.copy(
                    thingId = thingId,
                    messageId = stableMessageId,
                )
            ).copy(authenticatedGatewayUrl = scope?.gatewayUrl, authenticatedDeviceKey = scope?.deviceKey)
        )
        if (inserted != -1L || scope == null) return
        val previous = pendingThingMessageDao.getByMessageId(stableMessageId)
            ?: error("Pending insert was ignored without an existing canonical identity")
        requireExistingPendingOwner(message, scope, previous)
    }

    private suspend fun requireExistingPendingOwner(
        incoming: PushMessage,
        scope: InboundDeliveryScope,
        existing: PendingThingMessageEntity? = null,
    ): Boolean {
        val previous = existing ?: incoming.messageId?.let { pendingThingMessageDao.getByMessageId(it) } ?: return false
        val oldScope = InboundDeliveryScope.create(previous.authenticatedGatewayUrl, previous.authenticatedDeviceKey)
            ?: throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
        if (oldScope.gatewayUrl != scope.gatewayUrl) {
            throw InboundIdentityConflictException(InboundIdentityConflictReason.FOREIGN_GATEWAY)
        }
        if (!sameCanonicalInboundContent(previous.rawPayloadJson, incoming.rawPayloadJson)) {
            throw InboundIdentityConflictException(InboundIdentityConflictReason.DIFFERENT_CONTENT)
        }
        return true
    }

    private fun isMessageEntity(message: PushMessage): Boolean {
        return message.entityType.trim().lowercase() == "message"
    }

    private fun canonicalMessage(message: PushMessage): PushMessage {
        val normalizedChannel = message.channel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val stableMessageId = message.messageId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: message.deliveryId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: message.id.trim().takeIf { it.isNotEmpty() }
        val normalizedMessage = if (message.channel == normalizedChannel) {
            message
        } else {
            message.copy(channel = normalizedChannel)
        }
        if (stableMessageId == null) {
            return normalizedMessage
        }
        return if (normalizedMessage.messageId == stableMessageId) {
            normalizedMessage
        } else {
            normalizedMessage.copy(messageId = stableMessageId)
        }
    }

    private fun operationScopeEntityId(message: PushMessage): String? {
        val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
        val payloadEntityId = payload
            ?.optString("entity_id", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (payloadEntityId != null) {
            return payloadEntityId
        }
        val eventId = message.eventId?.trim()?.takeIf { it.isNotEmpty() }
        if (eventId != null) {
            return eventId
        }
        return message.thingId?.trim()?.takeIf { it.isNotEmpty() }
    }
}
