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
import io.ethan.pushgo.data.model.MessageListItem
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

private const val SQLITE_BIND_PARAMETER_CHUNK_SIZE = 900

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
    private companion object {
        private const val TAG_METADATA_BACKFILL_PREFS = "pushgo_message_search_maintenance"
        private const val TAG_METADATA_BACKFILL_KEY = "tag_metadata_backfill_v2"
        private const val METADATA_BACKFILL_BATCH_SIZE = 100
        const val MAX_QUERY_TOKENS = 6
        const val MAX_TOKEN_LENGTH = 32
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

        val ftsQuery: String
            get() = textTokens.joinToString(" AND ") { "${it}*" }
    }

    fun observeMessages(
        filter: MessageFilter,
        excludedIds: Set<String> = emptySet(),
    ): Flow<PagingData<MessageListItem>> {
        val normalizedExcludedIds = excludedIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 50
            ),
            pagingSourceFactory = {
                dao.observeMessages(
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
            }
        ).flow.map { pagingData ->
            pagingData.map(MessageListRow::asListItem)
        }
    }

    fun searchMessages(
        rawQuery: String,
        unreadOnly: Boolean,
        excludedIds: Set<String> = emptySet(),
    ): Flow<PagingData<MessageListItem>> {
        val plan = parseSearchQuery(rawQuery)
        if (plan.isEmpty) {
            return kotlinx.coroutines.flow.flowOf(PagingData.empty())
        }
        val normalizedExcludedIds = excludedIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        val readState = if (unreadOnly) false else null
        return Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false, initialLoadSize = 50),
            pagingSourceFactory = {
                searchPagingSource(plan, readState, normalizedExcludedIds)
            },
        ).flow.map { pagingData -> pagingData.map(MessageListRow::asListItem) }
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
        val normalizedExcludedIds = excludedIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        val source = searchPagingSource(plan, if (unreadOnly) false else null, normalizedExcludedIds)
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
    ): PagingSource<Int, MessageListRow> = when {
        plan.hasText && plan.hasTags -> dao.searchMessagesByTextAndTags(
            query = plan.ftsQuery,
            tags = plan.tags,
            tagCount = plan.tags.size,
            readState = readState,
            excludedIds = excludedIds,
            excludedCount = excludedIds.size,
        )

        plan.hasText -> dao.searchMessages(
            query = plan.ftsQuery,
            readState = readState,
            excludedIds = excludedIds,
            excludedCount = excludedIds.size,
        )

        else -> dao.searchMessagesByTags(
            tags = plan.tags,
            tagCount = plan.tags.size,
            readState = readState,
            excludedIds = excludedIds,
            excludedCount = excludedIds.size,
        )
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

    suspend fun getById(id: String): PushMessage? = dao.getById(id)?.asModel()

    suspend fun getByMessageId(messageId: String): PushMessage? {
        return dao.getByMessageId(messageId)?.asModel()
            ?: thingSubMessageDao.getByMessageId(messageId)?.asModel()
    }

    suspend fun getByNotificationId(notificationId: String): PushMessage? {
        return dao.getByNotificationId(notificationId)?.asModel()
    }

    suspend fun loadAllForExport(): List<PushMessage> = dao.loadAllForExport().map(MessageEntity::asModel)

    suspend fun getIdsBefore(readState: Boolean?, cutoff: Long): List<String> {
        return dao.getIdsBefore(readState, cutoff)
    }

    suspend fun getIdsByChannelRead(channel: String?, readState: Boolean?): List<String> {
        return dao.getIdsByChannelRead(channel, readState)
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
    ): Boolean {
        if (!isMessageEntity(message)) {
            return false
        }
        val canonicalMessage = canonicalMessage(message)
        return database.withTransaction {
            val deliveryClaimed = claimInboundDelivery(
                inboundDeliveryLedgerDao = inboundDeliveryLedgerDao,
                channelId = canonicalMessage.channel,
                entityType = canonicalMessage.entityType,
                entityId = operationScopeEntityId(canonicalMessage),
                deliveryId = canonicalMessage.deliveryId,
                opId = canonicalMessage.opId,
                appliedAt = canonicalMessage.receivedAt.toEpochMilli(),
                providerAckIdentity = providerAckIdentity,
            )
            if (!deliveryClaimed) {
                return@withTransaction false
            }
            val claimed = claimOperationScope(
                operationLedgerDao = operationLedgerDao,
                channelId = canonicalMessage.channel,
                entityType = canonicalMessage.entityType,
                entityId = operationScopeEntityId(canonicalMessage),
                opId = canonicalMessage.opId,
                deliveryId = canonicalMessage.deliveryId,
                appliedAt = canonicalMessage.receivedAt.toEpochMilli(),
                providerAckIdentity = providerAckIdentity,
            )
            if (!claimed) {
                return@withTransaction false
            }
            val stableMessageId = canonicalMessage.messageId?.trim()?.takeIf { it.isNotEmpty() }
            if (isThingScopedMessage(canonicalMessage)) {
                if (!hasThingHead(canonicalMessage.thingId)) {
                    enqueuePendingThingMessage(canonicalMessage)
                    return@withTransaction false
                }
                if (stableMessageId != null) {
                    pruneThingSubMessageDuplicates(setOf(stableMessageId))
                }
                if (stableMessageId != null && thingSubMessageDao.getByMessageId(stableMessageId) != null) {
                    return@withTransaction false
                }
                val inserted = tryInsertThingSubMessage(ThingSubMessageEntity.fromModel(canonicalMessage))
                return@withTransaction inserted
            }

            if (stableMessageId != null) {
                pruneTopLevelMessageDuplicates(setOf(stableMessageId))
            }
            if (stableMessageId != null && dao.getByMessageId(stableMessageId) != null) {
                return@withTransaction false
            }

            val entity = MessageEntity.fromModel(canonicalMessage)
            val existing = dao.getById(entity.id)
            val inserted = if (existing != null) {
                dao.update(entity)
                true
            } else {
                tryInsertTopLevelMessage(entity)
            }
            if (!inserted) {
                return@withTransaction false
            }
            upsertRealtimeDerivedDataSafely(entity.id, canonicalMessage)
            true
        }
    }

    suspend fun insert(message: PushMessage) {
        if (!isMessageEntity(message)) {
            return
        }
        val canonicalMessage = canonicalMessage(message)
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
                dao.update(entity)
                true
            } else {
                tryInsertTopLevelMessage(entity)
            }
            if (!inserted) {
                return@withTransaction
            }
            upsertRealtimeDerivedDataSafely(entity.id, canonicalMessage)
        }
    }

    suspend fun insertAll(messages: List<PushMessage>) {
        if (messages.isEmpty()) return
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
                        dao.update(entity)
                        true
                    } else {
                        tryInsertTopLevelMessage(entity)
                    }
                    if (!persisted) {
                        return@forEach
                    }
                    upsertRealtimeDerivedDataSafely(entity.id, message)
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
                            thingSubMessageDao.update(entity)
                        } else {
                            tryInsertThingSubMessage(entity)
                        }
                    }
                }
            }
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
        database.withTransaction {
            val existing = dao.getById(id) ?: return@withTransaction
            dao.updateRawPayload(id, rawPayloadJson)
            upsertRealtimeDerivedDataSafely(
                messageId = id,
                message = existing.asModel().copy(rawPayloadJson = rawPayloadJson),
                updateListPayload = true,
            )
        }
    }

    suspend fun deleteById(id: String) {
        database.withTransaction {
            dao.getById(id) ?: return@withTransaction
            dao.deleteById(id)
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
                .sumOf { dao.deleteByIds(it) }
        }
    }

    suspend fun deleteByChannel(channel: String): Int {
        return deleteByChannelRead(channel = channel, readState = null)
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
        val textTokens = mutableListOf<String>()
        val tags = linkedSetOf<String>()
        raw.trim()
            .split("\\s+".toRegex())
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { token ->
                val tag = parseTagToken(token)
                if (tag != null) {
                    tags += tag
                } else {
                    val textToken = sanitizeToken(token)
                    if (textToken.isNotEmpty()) {
                        textTokens += textToken
                    }
                }
            }
        return SearchQueryPlan(
            textTokens = textTokens.take(MAX_QUERY_TOKENS),
            tags = tags.toList().take(MAX_QUERY_TOKENS),
        )
    }

    private fun sanitizeToken(raw: String): String {
        val cleaned = raw.replace(Regex("[^\\p{L}\\p{Nd}_]"), "")
        return if (cleaned.length > MAX_TOKEN_LENGTH) cleaned.substring(0, MAX_TOKEN_LENGTH) else cleaned
    }

    private fun parseTagToken(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val value = when {
            trimmed.startsWith("#") -> trimmed.drop(1)
            trimmed.startsWith("tag:", ignoreCase = true) -> trimmed.drop(4)
            else -> return null
        }
        val normalized = value.trim().lowercase()
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
            if (updateListPayload) {
                dao.updateListPayload(messageId, MessageEntity.buildListPayloadJson(message.rawPayloadJson))
            }
            upsertMetadataIndex(messageId, message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val description = error.toString().take(1_000)
            database.openHelper.writableDatabase.execSQL(
                """
                UPDATE message_derived_state
                SET status = 'stale', updated_at_epoch_ms = ?, last_error = ?
                WHERE component IN ('message_metadata_index', 'message_summary_projection')
                """.trimIndent(),
                arrayOf<Any>(System.currentTimeMillis(), description),
            )
        }
    }

    private suspend fun upsertMetadataIndex(messageId: String, message: PushMessage) {
        val metadataRows = message.metadata
            .asSequence()
            .map { (key, value) ->
                MessageMetadataIndexEntity(
                    messageId = messageId,
                    keyName = "metadata_${key.trim().lowercase()}",
                    valueNorm = value.trim().lowercase(),
                    label = null,
                    receivedAt = message.receivedAt.toEpochMilli(),
                )
            }
            .filter { it.keyName.isNotEmpty() && it.valueNorm.isNotEmpty() }
            .toList()
        val tagRows = message.tags
            .asSequence()
            .map { it.trim().lowercase() }
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
        val rows = (metadataRows + tagRows)
            .distinctBy { "${it.keyName}\u001F${it.valueNorm}" }

        metadataIndexDao.deleteByMessageId(messageId)
        if (rows.isNotEmpty()) {
            metadataIndexDao.insertAll(rows)
        }
    }

    suspend fun backfillTagMetadataIndexIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(TAG_METADATA_BACKFILL_PREFS, Context.MODE_PRIVATE)
        val databaseState = database.openHelper.writableDatabase.query(
            "SELECT status FROM message_derived_state WHERE component = 'message_metadata_index'"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (databaseState == "ready") {
            return
        }
        var beforeReceivedAt = Long.MAX_VALUE
        var beforeId = "\uFFFF"
        while (true) {
            val messages = dao.getMetadataBackfillPage(
                beforeReceivedAt = beforeReceivedAt,
                beforeId = beforeId,
                limit = METADATA_BACKFILL_BATCH_SIZE,
            )
            if (messages.isEmpty()) {
                val revision = database.openHelper.writableDatabase.query(
                    "SELECT revision FROM message_store_revision WHERE id = 1"
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
                database.openHelper.writableDatabase.execSQL(
                    """
                    UPDATE message_derived_state
                    SET status = 'ready', source_revision = ?, cursor_local_message_id = NULL,
                        updated_at_epoch_ms = ?, last_error = NULL
                    WHERE component IN ('message_metadata_index', 'message_summary_projection')
                    """.trimIndent(),
                    arrayOf(revision, System.currentTimeMillis()),
                )
                break
            }
            database.withTransaction {
                for (entity in messages) {
                    upsertMetadataIndex(entity.id, entity.asModel())
                    dao.updateListPayload(
                        entity.id,
                        MessageEntity.buildListPayloadJson(entity.rawPayloadJson),
                    )
                }
                database.openHelper.writableDatabase.execSQL(
                    """
                    UPDATE message_derived_state
                    SET status = 'building', cursor_local_message_id = ?,
                        updated_at_epoch_ms = ?, last_error = NULL
                    WHERE component IN ('message_metadata_index', 'message_summary_projection')
                    """.trimIndent(),
                    arrayOf<Any>(messages.last().id, System.currentTimeMillis()),
                )
            }
            beforeReceivedAt = messages.last().receivedAt
            beforeId = messages.last().id
            kotlinx.coroutines.yield()
        }
        prefs.edit {
            putBoolean(TAG_METADATA_BACKFILL_KEY, true)
        }
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

    private suspend fun hasThingHead(thingId: String?): Boolean {
        val normalizedThingId = thingId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return thingHeadDao.existsByThingId(normalizedThingId)
    }

    suspend fun replayPendingForThing(thingId: String) {
        val normalizedThingId = thingId.trim().takeIf { it.isNotEmpty() } ?: return
        database.withTransaction {
            val pending = pendingThingMessageDao.loadByThingId(normalizedThingId)
            if (pending.isEmpty()) return@withTransaction
            val consumedIds = mutableListOf<String>()
            pending.forEach { row ->
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
                    consumedIds += row.id
                }
            }
            if (consumedIds.isNotEmpty()) {
                consumedIds
                    .chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
                    .forEach { pendingThingMessageDao.deleteByIds(it) }
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

    private suspend fun enqueuePendingThingMessage(message: PushMessage) {
        val thingScoped = ThingSubMessageEntity.fromModel(message)
        val thingId = thingScoped.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val stableMessageId = thingScoped.messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        pendingThingMessageDao.insert(
            PendingThingMessageEntity.fromThingScopedMessage(
                thingScoped.copy(
                    thingId = thingId,
                    messageId = stableMessageId,
                )
            )
        )
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
