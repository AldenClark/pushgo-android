package io.ethan.pushgo.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import io.ethan.pushgo.data.db.MessageChannelStatsAggregate
import io.ethan.pushgo.data.db.MessageChannelStatsDao
import io.ethan.pushgo.data.db.MessageDao
import io.ethan.pushgo.data.db.MessageEntity
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
import io.ethan.pushgo.data.model.MessageFilter
import io.ethan.pushgo.data.model.MessageListSortMode
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject

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
    suspend fun wouldPersistAsPending(message: PushMessage): Boolean {
        val canonical = canonicalMessage(message)
        return isThingScopedMessage(canonical) && !hasThingHead(canonical.thingId)
    }

    private companion object {
        const val MAX_QUERY_TOKENS = 6
        const val MAX_TOKEN_LENGTH = 32
    }

    fun observeMessages(filter: MessageFilter): Flow<PagingData<PushMessage>> {
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 50
            ),
            pagingSourceFactory = {
                val prioritizeUnread = if (filter.sortMode == MessageListSortMode.UNREAD_FIRST) 1 else 0
                dao.observeMessages(
                    readState = null,
                    withUrl = if (filter.withUrlOnly) 1 else 0,
                    channel = filter.channel,
                    serverId = filter.serverId,
                    prioritizeUnread = prioritizeUnread,
                )
            }
        ).flow.map { pagingData ->
            pagingData.map(MessageEntity::asModel)
        }
    }

    fun searchMessages(
        rawQuery: String,
        sortMode: MessageListSortMode,
        limit: Int = 200
    ): Flow<List<PushMessage>> {
        val query = buildFtsQuery(rawQuery)
        if (query.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val prioritizeUnread = if (sortMode == MessageListSortMode.UNREAD_FIRST) 1 else 0
        return dao.searchMessages(query = query, prioritizeUnread = prioritizeUnread, limit = limit)
            .map { list -> list.map(MessageEntity::asModel) }
    }

    fun observeChannelCounts(): Flow<List<MessageChannelCount>> = channelStatsDao.observeChannelCounts()

    fun observeUnreadCount(): Flow<Int> = channelStatsDao.observeUnreadCount().distinctUntilChanged()

    fun observeEventCount(): Flow<Int> = kotlinx.coroutines.flow.flowOf(0)

    fun observeThingCount(): Flow<Int> = kotlinx.coroutines.flow.flowOf(0)

    suspend fun getById(id: String): PushMessage? = dao.getById(id)?.asModel()

    suspend fun getByMessageId(messageId: String): PushMessage? {
        return dao.getByMessageId(messageId)?.asModel()
            ?: thingSubMessageDao.getByMessageId(messageId)?.asModel()
    }

    suspend fun getByNotificationId(notificationId: String): PushMessage? {
        return dao.getByNotificationId(notificationId)?.asModel()
    }

    suspend fun getAll(): List<PushMessage> = dao.getAll().map(MessageEntity::asModel)

    suspend fun getEventProjectionMessages(): List<PushMessage> = emptyList()

    suspend fun getThingProjectionMessages(): List<PushMessage> {
        return thingSubMessageDao.getAllProjection().map(ThingSubMessageEntity::asModel)
    }

    suspend fun getIdsBefore(readState: Boolean?, cutoff: Long): List<String> {
        return dao.getIdsBefore(readState, cutoff)
    }

    suspend fun getIdsByChannelRead(channel: String?, readState: Boolean?): List<String> {
        return dao.getIdsByChannelRead(channel, readState)
    }

    suspend fun insertIncoming(message: PushMessage): Boolean {
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
            upsertMetadataIndex(entity.id, canonicalMessage)
            applyUpsertStats(existing = existing, inserted = entity)
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
            upsertMetadataIndex(entity.id, canonicalMessage)
            applyUpsertStats(existing = existing, inserted = entity)
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
                    dao.getByMessageIds(stableMessageIds.toList())
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
                    dao.getByIds(entities.map { it.id }).associateBy { it.id }
                }
                val persistedEntities = mutableListOf<MessageEntity>()
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
                    persistedEntities += entity
                    upsertMetadataIndex(entity.id, message)
                }
                applyBulkUpsertStats(
                    existingById = existingById,
                    inserted = persistedEntities,
                )
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
                    thingSubMessageDao.getByMessageIds(stableMessageIds.toList())
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
                    val existingById = thingSubMessageDao.getByIds(entities.map { it.id }).associateBy { it.id }
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
            channelStatsDao.applyNegativeDelta(
                channel = channelKey(existing.channel),
                totalCount = 0,
                unreadCount = 1,
            )
            channelStatsDao.deleteEmptyRows()
        }
    }

    suspend fun markAllRead() {
        database.withTransaction {
            val unreadAggregates = dao.getUnreadAggregates()
            if (unreadAggregates.isEmpty()) {
                return@withTransaction
            }
            dao.markAllRead()
            unreadAggregates.forEach { aggregate ->
                channelStatsDao.applyNegativeDelta(
                    channel = aggregate.channel,
                    totalCount = 0,
                    unreadCount = aggregate.unreadCount,
                )
            }
            channelStatsDao.deleteEmptyRows()
        }
    }

    suspend fun updateRawPayload(id: String, rawPayloadJson: String) {
        dao.updateRawPayload(id, rawPayloadJson)
    }

    suspend fun deleteById(id: String) {
        database.withTransaction {
            val existing = dao.getById(id) ?: return@withTransaction
            dao.deleteById(id)
            applyRemovalAggregates(
                listOf(
                    MessageChannelStatsAggregate(
                        channel = channelKey(existing.channel),
                        totalCount = 1,
                        unreadCount = if (existing.isRead) 0 else 1,
                        latestReceivedAt = existing.receivedAt,
                    )
                )
            )
        }
    }

    suspend fun deleteByChannel(channel: String): Int {
        return deleteByChannelRead(channel = channel, readState = null)
    }

    suspend fun deleteByChannelRead(channel: String?, readState: Boolean?): Int {
        return database.withTransaction {
            val normalizedChannel = channel?.trim()
            val aggregates = dao.getChannelAggregates(
                channel = normalizedChannel,
                readState = readState,
            )
            if (aggregates.isEmpty()) {
                return@withTransaction 0
            }
            val deleted = dao.deleteByChannelRead(normalizedChannel, readState)
            if (deleted > 0) {
                applyRemovalAggregates(aggregates)
            }
            deleted
        }
    }

    suspend fun deleteOldestReadMessages(limit: Int): Int {
        return database.withTransaction {
            val aggregates = dao.getOldestReadAggregates(limit)
            if (aggregates.isEmpty()) {
                return@withTransaction 0
            }
            val deleted = dao.deleteOldestRead(limit)
            if (deleted > 0) {
                applyRemovalAggregates(aggregates)
            }
            deleted
        }
    }

    suspend fun deleteOldestReadMessages(limit: Int, excludedChannels: List<String>): Int {
        if (excludedChannels.isEmpty()) {
            return deleteOldestReadMessages(limit)
        }
        return database.withTransaction {
            val aggregates = dao.getOldestReadAggregatesExcludingChannels(
                limit = limit,
                excludedChannels = excludedChannels,
                excludedSize = excludedChannels.size,
            )
            if (aggregates.isEmpty()) {
                return@withTransaction 0
            }
            val deleted = dao.deleteOldestReadExcludingChannels(
                limit = limit,
                excludedChannels = excludedChannels,
                excludedSize = excludedChannels.size,
            )
            if (deleted > 0) {
                applyRemovalAggregates(aggregates)
            }
            deleted
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
            channelStatsDao.deleteAll()
        }
    }

    suspend fun deleteAllRead() {
        database.withTransaction {
            val aggregates = dao.getChannelAggregates(channel = null, readState = true)
            if (aggregates.isEmpty()) {
                return@withTransaction
            }
            dao.deleteAllRead()
            applyRemovalAggregates(aggregates)
        }
    }

    suspend fun deleteBefore(readState: Boolean?, cutoff: Long) {
        database.withTransaction {
            val aggregates = dao.getChannelAggregatesBefore(readState = readState, cutoff = cutoff)
            if (aggregates.isEmpty()) {
                return@withTransaction
            }
            dao.deleteBefore(readState, cutoff)
            applyRemovalAggregates(aggregates)
        }
    }

    suspend fun totalCount(): Int = dao.totalCount()

    suspend fun unreadCount(): Int = channelStatsDao.unreadCount()

    suspend fun countMessages(readState: Boolean?, cutoff: Long?): Int {
        return dao.countMessages(readState, cutoff)
    }

    private fun buildFtsQuery(raw: String): String {
        val tokens = raw.trim()
            .split("\\s+".toRegex())
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::sanitizeToken)
            .filter { it.isNotEmpty() }
            .take(MAX_QUERY_TOKENS)
            .toList()
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" AND ") { "${it}*" }
    }

    private fun sanitizeToken(raw: String): String {
        val cleaned = raw.replace(Regex("[^\\p{L}\\p{Nd}_]"), "")
        return if (cleaned.length > MAX_TOKEN_LENGTH) cleaned.substring(0, MAX_TOKEN_LENGTH) else cleaned
    }

    private suspend fun upsertMetadataIndex(message: PushMessage) {
        upsertMetadataIndex(message.id, message)
    }

    private suspend fun upsertMetadataIndex(messageId: String, message: PushMessage) {
        val rows = message.metadata
            .asSequence()
            .map { (key, value) ->
                MessageMetadataIndexEntity(
                    messageId = messageId,
                    keyName = key.trim().lowercase(),
                    valueNorm = value.trim().lowercase(),
                    label = null,
                    receivedAt = message.receivedAt.toEpochMilli(),
                )
            }
            .filter { it.keyName.isNotEmpty() && it.valueNorm.isNotEmpty() }
            .toList()

        metadataIndexDao.deleteByMessageId(messageId)
        if (rows.isNotEmpty()) {
            metadataIndexDao.insertAll(rows)
        }
    }

    private suspend fun pruneTopLevelMessageDuplicates(stableMessageIds: Set<String>) {
        if (stableMessageIds.isEmpty()) {
            return
        }
        val duplicates = mutableListOf<MessageEntity>()
        val existing = dao.getByMessageIds(stableMessageIds.toList())
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
        dao.deleteByIds(duplicates.map { it.id })
        val aggregates = duplicates.map { message ->
            MessageChannelStatsAggregate(
                channel = channelKey(message.channel),
                totalCount = 1,
                unreadCount = if (message.isRead) 0 else 1,
                latestReceivedAt = message.receivedAt,
            )
        }
        applyRemovalAggregates(aggregates)
    }

    private suspend fun pruneThingSubMessageDuplicates(stableMessageIds: Set<String>) {
        if (stableMessageIds.isEmpty()) {
            return
        }
        val duplicates = mutableListOf<ThingSubMessageEntity>()
        val existing = thingSubMessageDao.getByMessageIds(stableMessageIds.toList())
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
        thingSubMessageDao.deleteByIds(duplicates.map { it.id })
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

    private data class ChannelDeltaAccumulator(
        var totalCount: Int = 0,
        var unreadCount: Int = 0,
        var latestReceivedAt: Long = 0L,
    )

    private suspend fun applyUpsertStats(
        existing: MessageEntity?,
        inserted: MessageEntity,
    ) {
        val positive = linkedMapOf<String, ChannelDeltaAccumulator>()
        val negative = linkedMapOf<String, ChannelDeltaAccumulator>()
        val refreshChannels = linkedSetOf<String>()
        collectUpsertDeltas(
            existing = existing,
            inserted = inserted,
            positive = positive,
            negative = negative,
            refreshChannels = refreshChannels,
        )
        applyChannelDeltas(
            positive = positive,
            negative = negative,
            refreshChannels = refreshChannels,
        )
    }

    private suspend fun applyBulkUpsertStats(
        existingById: Map<String, MessageEntity>,
        inserted: List<MessageEntity>,
    ) {
        if (inserted.isEmpty()) return
        val positive = linkedMapOf<String, ChannelDeltaAccumulator>()
        val negative = linkedMapOf<String, ChannelDeltaAccumulator>()
        val refreshChannels = linkedSetOf<String>()
        inserted.forEach { entity ->
            collectUpsertDeltas(
                existing = existingById[entity.id],
                inserted = entity,
                positive = positive,
                negative = negative,
                refreshChannels = refreshChannels,
            )
        }
        applyChannelDeltas(
            positive = positive,
            negative = negative,
            refreshChannels = refreshChannels,
        )
    }

    private fun collectUpsertDeltas(
        existing: MessageEntity?,
        inserted: MessageEntity,
        positive: MutableMap<String, ChannelDeltaAccumulator>,
        negative: MutableMap<String, ChannelDeltaAccumulator>,
        refreshChannels: MutableSet<String>,
    ) {
        val insertedChannel = channelKey(inserted.channel)
        val insertedUnread = if (inserted.isRead) 0 else 1
        if (existing == null) {
            addChannelDelta(
                target = positive,
                channel = insertedChannel,
                totalCount = 1,
                unreadCount = insertedUnread,
                latestReceivedAt = inserted.receivedAt,
            )
            return
        }

        val existingChannel = channelKey(existing.channel)
        val existingUnread = if (existing.isRead) 0 else 1
        refreshChannels += existingChannel

        if (existingChannel == insertedChannel) {
            val unreadDelta = insertedUnread - existingUnread
            if (unreadDelta > 0) {
                addChannelDelta(
                    target = positive,
                    channel = insertedChannel,
                    totalCount = 0,
                    unreadCount = unreadDelta,
                    latestReceivedAt = inserted.receivedAt,
                )
            } else if (unreadDelta < 0) {
                addChannelDelta(
                    target = negative,
                    channel = insertedChannel,
                    totalCount = 0,
                    unreadCount = -unreadDelta,
                    latestReceivedAt = 0L,
                )
            }
            return
        }

        addChannelDelta(
            target = negative,
            channel = existingChannel,
            totalCount = 1,
            unreadCount = existingUnread,
            latestReceivedAt = 0L,
        )
        addChannelDelta(
            target = positive,
            channel = insertedChannel,
            totalCount = 1,
            unreadCount = insertedUnread,
            latestReceivedAt = inserted.receivedAt,
        )
    }

    private suspend fun applyChannelDeltas(
        positive: Map<String, ChannelDeltaAccumulator>,
        negative: Map<String, ChannelDeltaAccumulator>,
        refreshChannels: Set<String>,
    ) {
        positive.forEach { (channel, delta) ->
            if (delta.totalCount <= 0 && delta.unreadCount <= 0) {
                return@forEach
            }
            channelStatsDao.applyPositiveDelta(
                channel = channel,
                totalCount = delta.totalCount,
                unreadCount = delta.unreadCount,
                latestReceivedAt = delta.latestReceivedAt,
            )
        }
        negative.forEach { (channel, delta) ->
            if (delta.totalCount <= 0 && delta.unreadCount <= 0) {
                return@forEach
            }
            channelStatsDao.applyNegativeDelta(
                channel = channel,
                totalCount = delta.totalCount,
                unreadCount = delta.unreadCount,
            )
        }
        channelStatsDao.deleteEmptyRows()
        val channelsToRefresh = linkedSetOf<String>()
        channelsToRefresh.addAll(refreshChannels)
        channelsToRefresh.addAll(negative.keys)
        refreshLatestForChannels(channelsToRefresh)
    }

    private suspend fun applyRemovalAggregates(aggregates: List<MessageChannelStatsAggregate>) {
        if (aggregates.isEmpty()) {
            return
        }
        val refreshChannels = linkedSetOf<String>()
        aggregates.forEach { aggregate ->
            if (aggregate.totalCount <= 0 && aggregate.unreadCount <= 0) {
                return@forEach
            }
            channelStatsDao.applyNegativeDelta(
                channel = aggregate.channel,
                totalCount = aggregate.totalCount,
                unreadCount = aggregate.unreadCount,
            )
            refreshChannels += aggregate.channel
        }
        channelStatsDao.deleteEmptyRows()
        refreshLatestForChannels(refreshChannels)
    }

    private suspend fun refreshLatestForChannels(channels: Set<String>) {
        channels.forEach { channel ->
            val latestReceivedAt = dao.latestReceivedAtByNormalizedChannel(channel)
            if (latestReceivedAt == null) {
                channelStatsDao.deleteChannel(channel)
            } else {
                channelStatsDao.setLatestReceivedAt(channel, latestReceivedAt)
            }
        }
    }

    private fun addChannelDelta(
        target: MutableMap<String, ChannelDeltaAccumulator>,
        channel: String,
        totalCount: Int,
        unreadCount: Int,
        latestReceivedAt: Long,
    ) {
        val accumulator = target.getOrPut(channel) { ChannelDeltaAccumulator() }
        accumulator.totalCount += totalCount
        accumulator.unreadCount += unreadCount
        accumulator.latestReceivedAt = maxOf(accumulator.latestReceivedAt, latestReceivedAt)
    }

    private fun channelKey(channel: String?): String = channel?.trim().orEmpty()

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
                pendingThingMessageDao.deleteByIds(consumedIds)
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
