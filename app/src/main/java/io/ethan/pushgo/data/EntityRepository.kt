package io.ethan.pushgo.data

import androidx.room.withTransaction
import io.ethan.pushgo.data.db.EventChangeLogDao
import io.ethan.pushgo.data.db.EventChangeLogEntity
import io.ethan.pushgo.data.db.InboundDeliveryLedgerDao
import io.ethan.pushgo.data.db.OperationLedgerDao
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.db.ThingChangeLogDao
import io.ethan.pushgo.data.db.ThingChangeLogEntity
import io.ethan.pushgo.data.db.ThingHeadDao
import io.ethan.pushgo.data.db.ThingHeadEntity
import io.ethan.pushgo.data.db.ThingSubEventDao
import io.ethan.pushgo.data.db.ThingSubEventEntity
import io.ethan.pushgo.data.db.ThingSubMessageDao
import io.ethan.pushgo.data.db.ThingSubMessageEntity
import io.ethan.pushgo.data.db.ThingSubMessageSearchCandidate
import io.ethan.pushgo.data.db.TopLevelEventHeadDao
import io.ethan.pushgo.data.db.TopLevelEventHeadEntity
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.util.SearchTextNormalizer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Hard limits for the Thing list; detail views intentionally use the complete history path. */
const val THING_LIST_RELATED_MESSAGES_PER_THING_LIMIT = 8
const val THING_LIST_RELATED_MESSAGES_TOTAL_LIMIT = 240
private const val THING_RELATED_MESSAGE_SEARCH_RESULT_LIMIT = 400

data class IncomingEntityRecord(
    val entityType: String,
    val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    val rawPayloadJson: String,
    val receivedAt: Instant,
    val opId: String?,
    val deliveryId: String?,
    val serverId: String?,
    val eventId: String?,
    val thingId: String?,
    val eventState: String?,
    val eventTimeEpoch: Long?,
    val observedTimeEpoch: Long?,
    val localDeliveryKey: String? = null,
)

data class EntityProjectionCursor(
    val receivedAt: Long,
    val id: String,
)

data class EntityProjectionDetail(
    val head: PushMessage?,
    val history: List<PushMessage>,
) {
    fun asMessages(): List<PushMessage> {
        val headMessage = head ?: return history
        val seen = linkedSetOf(headMessage.id)
        headMessage.messageId?.trim()?.takeIf { it.isNotEmpty() }?.let(seen::add)
        return buildList {
            add(headMessage)
            history.forEach { message ->
                val keys = listOfNotNull(
                    message.id.trim().takeIf { it.isNotEmpty() },
                    message.messageId?.trim()?.takeIf { it.isNotEmpty() },
                )
                if (keys.any(seen::contains)) return@forEach
                add(message)
                seen.addAll(keys)
            }
        }.sortedWith(
            compareByDescending<PushMessage> { it.receivedAt.toEpochMilli() }
                .thenByDescending { it.messageId ?: it.id }
        )
    }
}

data class ThingProjectionPage(
    val headMessages: List<PushMessage>,
    val relatedMessages: List<PushMessage>,
    /** True when child history was capped and the page is not a complete relation snapshot. */
    val hasMoreRelatedMessages: Boolean = false,
) {
    fun asMessages(): List<PushMessage> = headMessages + relatedMessages
}

data class ThingRelatedMessageSearchPage(
    val thingIds: List<String>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

class EntityRepository(
    private val database: PushGoDatabase,
    private val inboundDeliveryLedgerDao: InboundDeliveryLedgerDao,
    private val operationLedgerDao: OperationLedgerDao,
    private val eventChangeLogDao: EventChangeLogDao,
    private val thingChangeLogDao: ThingChangeLogDao,
    private val thingSubEventDao: ThingSubEventDao,
    private val topLevelEventHeadDao: TopLevelEventHeadDao,
    private val thingHeadDao: ThingHeadDao,
    private val thingSubMessageDao: ThingSubMessageDao,
) {
    /** Registration is a short transaction, never held over network, parsing or notification work. */
    suspend fun beginThingWrite(thingId: String?, channel: String?): ThingWriteTicket? {
        val normalized = thingId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var registered: ThingWriteTicket? = null
        return try {
            database.withTransaction {
                database.thingWriteTickets.register(normalized, channel).also { registered = it }
            }
        } catch (error: Throwable) {
            registered?.close()
            throw error
        }
    }

    suspend fun wouldPersistAsPending(entity: IncomingEntityRecord): Boolean {
        val entityType = entity.entityType.trim().lowercase()
        if (entityType != "event") return false
        val thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return !thingHeadDao.existsByThingId(thingId)
    }

    fun observeEventCount(): Flow<Int> = topLevelEventHeadDao.observeCount().distinctUntilChanged()

    fun observeEventRefreshToken(): Flow<Long> {
        val headCountFlow = topLevelEventHeadDao.observeCount().map(Int::toLong)
        val changeCountFlow = eventChangeLogDao.observeCount().map(Int::toLong)
        val subEventCountFlow = thingSubEventDao.observeCount().map(Int::toLong)
        return combine(
            headCountFlow,
            topLevelEventHeadDao.observeLatestReceivedAt(),
            changeCountFlow,
            eventChangeLogDao.observeLatestReceivedAt(),
            subEventCountFlow,
            thingSubEventDao.observeLatestReceivedAt(),
        ) { values ->
            val headCount = values[0]
            val headLatest = values[1]
            val changeCount = values[2]
            val changeLatest = values[3]
            val subEventCount = values[4]
            val subEventLatest = values[5]
            var token = 23L
            token = (token * 31) xor (headLatest shl 1)
            token = (token * 31) xor headCount
            token = (token * 31) xor (changeLatest shl 2)
            token = (token * 31) xor changeCount
            token = (token * 31) xor (subEventLatest shl 3)
            token = (token * 31) xor subEventCount
            token
        }.distinctUntilChanged()
    }

    fun observeThingCount(): Flow<Int> = thingHeadDao.observeCount().distinctUntilChanged()

    fun observeThingRefreshToken(): Flow<Long> {
        val headCountFlow = thingHeadDao.observeCount().map(Int::toLong)
        val changeCountFlow = thingChangeLogDao.observeCount().map(Int::toLong)
        val subEventCountFlow = thingSubEventDao.observeCount().map(Int::toLong)
        val subMessageCountFlow = thingSubMessageDao.observeCount().map(Int::toLong)
        return combine(
            headCountFlow,
            thingHeadDao.observeLatestReceivedAt(),
            changeCountFlow,
            thingChangeLogDao.observeLatestReceivedAt(),
            subEventCountFlow,
            thingSubEventDao.observeLatestReceivedAt(),
            subMessageCountFlow,
            thingSubMessageDao.observeLatestReceivedAt(),
        ) { values ->
            val headCount = values[0]
            val headLatest = values[1]
            val changeCount = values[2]
            val changeLatest = values[3]
            val subEventCount = values[4]
            val subEventLatest = values[5]
            val subMessageCount = values[6]
            val subMessageLatest = values[7]
            var token = 17L
            token = (token * 31) xor (headLatest shl 1)
            token = (token * 31) xor headCount
            token = (token * 31) xor (changeLatest shl 2)
            token = (token * 31) xor changeCount
            token = (token * 31) xor (subEventLatest shl 3)
            token = (token * 31) xor subEventCount
            token = (token * 31) xor (subMessageLatest shl 4)
            token = (token * 31) xor subMessageCount
            token
        }.distinctUntilChanged()
    }

    suspend fun eventCount(): Int = topLevelEventHeadDao.countAll()

    suspend fun thingCount(): Int = thingHeadDao.countAll()

    suspend fun resolveStoredEventTitle(eventId: String): String? {
        val normalized = eventId.trim()
        if (normalized.isEmpty()) return null
        return topLevelEventHeadDao.findTitleByEventId(normalized)?.trim()?.takeIf { it.isNotEmpty() }
            ?: thingSubEventDao.findLatestTitleByEventId(normalized)?.trim()?.takeIf { it.isNotEmpty() }
            ?: eventChangeLogDao.findLatestTitleByEventId(normalized)?.trim()?.takeIf { it.isNotEmpty() }
    }

    suspend fun resolveStoredThingTitle(thingId: String): String? {
        val normalized = thingId.trim()
        if (normalized.isEmpty()) return null
        return thingHeadDao.findTitleByThingId(normalized)?.trim()?.takeIf { it.isNotEmpty() }
            ?: thingChangeLogDao.findLatestTitleByThingId(normalized)?.trim()?.takeIf { it.isNotEmpty() }
    }

    suspend fun getEventProjectionMessages(): List<PushMessage> {
        return topLevelEventHeadDao.getAllProjection().map(TopLevelEventHeadEntity::asModel)
    }

    suspend fun getEventProjectionMessagesPage(
        before: EntityProjectionCursor?,
        limit: Int,
    ): List<PushMessage> {
        val pageSize = limit.coerceIn(1, 500)
        val beforeReceivedAt = before?.receivedAt
        val beforeId = before?.id
        return topLevelEventHeadDao.getProjectionPage(
            beforeReceivedAt = beforeReceivedAt,
            beforeId = beforeId,
            limit = pageSize,
        ).map(TopLevelEventHeadEntity::asModel)
    }

    suspend fun getEventProjectionDetail(eventId: String): EntityProjectionDetail? {
        val normalized = eventId.trim()
        if (normalized.isEmpty()) return null
        val head = topLevelEventHeadDao.getByEventId(normalized)?.asModel()
        val history = (
            eventChangeLogDao.getByEventId(normalized).map(EventChangeLogEntity::asModel) +
                thingSubEventDao.getByEventId(normalized).map(ThingSubEventEntity::asModel)
            )
            .sortedWith(entityProjectionMessageSortComparator)
        if (head == null && history.isEmpty()) return null
        return EntityProjectionDetail(head = head, history = history)
    }

    suspend fun getThingProjectionMessages(): List<PushMessage> {
        return thingHeadDao.getAllProjection().map(ThingHeadEntity::asModel)
    }

    suspend fun getThingProjectionMessagesPage(
        before: EntityProjectionCursor?,
        limit: Int,
    ): List<PushMessage> {
        val pageSize = limit.coerceIn(1, 500)
        val beforeReceivedAt = before?.receivedAt
        val beforeId = before?.id
        return thingHeadDao.getProjectionPage(
            beforeReceivedAt = beforeReceivedAt,
            beforeId = beforeId,
            limit = pageSize,
        ).map(ThingHeadEntity::asModel)
    }

    suspend fun getThingProjectionPage(
        before: EntityProjectionCursor?,
        limit: Int,
    ): ThingProjectionPage = database.withTransaction {
        val headMessages = getThingProjectionMessagesPage(before = before, limit = limit)
        val thingIds = headMessages
            .asSequence()
            .mapNotNull { it.thingId?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .toList()
        val relatedMessages = if (thingIds.isEmpty()) {
            emptyList()
        } else {
            thingSubMessageDao.getByThingIdsBounded(
                thingIds = thingIds,
                perThingLimit = THING_LIST_RELATED_MESSAGES_PER_THING_LIMIT,
                totalLimit = THING_LIST_RELATED_MESSAGES_TOTAL_LIMIT,
            ).map(ThingSubMessageEntity::asModel)
        }
        val relatedMessageCount = if (thingIds.isEmpty()) 0 else thingSubMessageDao.countByThingIds(thingIds)
        ThingProjectionPage(
            headMessages = headMessages,
            relatedMessages = relatedMessages,
            hasMoreRelatedMessages = relatedMessageCount > relatedMessages.size,
        )
    }

    suspend fun searchThingIdsByRelatedMessageText(
        rawQuery: String,
        afterId: String? = null,
        limit: Int = THING_RELATED_MESSAGE_SEARCH_RESULT_LIMIT,
    ): ThingRelatedMessageSearchPage {
        val query = SearchTextNormalizer.normalizedQuery(rawQuery).take(512)
        if (query.isEmpty()) return ThingRelatedMessageSearchPage(emptyList(), null, hasMore = false)
        val pageSize = limit.coerceIn(1, THING_RELATED_MESSAGE_SEARCH_RESULT_LIMIT)
        val candidates = thingSubMessageDao.getSearchCandidates(
            afterId = afterId,
            limit = pageSize + 1,
        )
        val ids = candidates
            .asSequence()
            .filter { candidate -> candidate.matches(query) }
            .mapNotNull { it.thingId?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .toList()
        return ThingRelatedMessageSearchPage(
            thingIds = ids,
            nextCursor = candidates.lastOrNull()?.id,
            hasMore = candidates.size > pageSize,
        )
    }

    private fun ThingSubMessageSearchCandidate.matches(query: String): Boolean =
        SearchTextNormalizer.joinedCandidates(listOf(title, body, bodyPreview, id, messageId))
            .contains(query)

    suspend fun getThingProjectionDetail(thingId: String): EntityProjectionDetail? {
        val normalized = thingId.trim()
        if (normalized.isEmpty()) return null
        val head = thingHeadDao.getByThingId(normalized)?.asModel()
        val history = (
            thingChangeLogDao.getByThingId(normalized).map(ThingChangeLogEntity::asModel) +
                thingSubEventDao.getByThingId(normalized).map(ThingSubEventEntity::asModel) +
                thingSubMessageDao.getByThingId(normalized).map(ThingSubMessageEntity::asModel)
            )
            .sortedWith(entityProjectionMessageSortComparator)
        if (head == null && history.isEmpty()) return null
        return EntityProjectionDetail(head = head, history = history)
    }

    suspend fun getNotificationEntityKeysByChannel(channelId: String): List<Pair<String, String>> {
        val normalizedChannel = channelId.trim()
        if (normalizedChannel.isEmpty()) return emptyList()
        return database.withTransaction {
            buildList {
                eventChangeLogDao.getEntityIdsByChannel(normalizedChannel).forEach {
                    add("event" to it)
                }
                thingSubEventDao.getEntityIdsByChannel(normalizedChannel).forEach {
                    add("event" to it)
                }
                topLevelEventHeadDao.getEntityIdsByChannel(normalizedChannel).forEach {
                    add("event" to it)
                }
                thingChangeLogDao.getEntityIdsByChannel(normalizedChannel).forEach {
                    add("thing" to it)
                }
                thingHeadDao.getEntityIdsByChannel(normalizedChannel).forEach {
                    add("thing" to it)
                }
            }.filter { (_, entityId) -> entityId.isNotBlank() }.distinct()
        }
    }

    suspend fun insertIncoming(
        entity: IncomingEntityRecord,
        providerAckIdentity: ProviderAckIdentity? = null,
        deliveryScope: InboundDeliveryScope? = providerAckIdentity.inboundDeliveryScope(),
        thingWriteTicket: ThingWriteTicket? = null,
        postProcessFlags: Int = 0,
        beforeAcceptanceCommit: (suspend () -> Unit)? = null,
        onPreviouslyAcceptedReplay: (() -> Unit)? = null,
    ): Boolean {
        val scopedEntity = entity.copy(
            localDeliveryKey = entity.deliveryId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { deliveryScope.scopedDeliveryStorageKey(it) },
        )
        val entityType = scopedEntity.entityType.trim().lowercase()
        if (entityType !in setOf("event", "thing")) return false
        return database.withTransaction {
            thingWriteTicket?.requireCurrent()
            val inserted = when (entityType) {
                "event", "thing" -> {
                    val deliveryClaimed = io.ethan.pushgo.data.claimInboundDelivery(
                        inboundDeliveryLedgerDao = inboundDeliveryLedgerDao,
                        channelId = scopedEntity.channel,
                        entityType = entityType,
                        entityId = scopedEntity.entityId,
                        deliveryId = scopedEntity.deliveryId,
                        opId = scopedEntity.opId,
                        appliedAt = scopedEntity.receivedAt.toEpochMilli(),
                        providerAckIdentity = providerAckIdentity,
                        deliveryScope = deliveryScope,
                        acceptanceProven = true,
                    )
                    val previouslyProven = !deliveryClaimed && hasProvenInboundAcceptance(database, deliveryScope,
                        scopedEntity.deliveryId, scopedEntity.channel, entityType, scopedEntity.entityId, scopedEntity.opId)
                    val recoveringLegacyReceipt = !deliveryClaimed && !previouslyProven &&
                        isLegacyStagingRecovery(database, deliveryScope, scopedEntity.deliveryId, providerAckIdentity)
                    if (!deliveryClaimed && !recoveringLegacyReceipt) {
                        if (previouslyProven) {
                            if (retainPreviouslyAcceptedEntity(scopedEntity, deliveryScope, onPreviouslyAcceptedReplay)) beforeAcceptanceCommit?.invoke()
                        } else {
                            requireStoredEntityAcceptance(scopedEntity, deliveryScope)
                            proveInboundAcceptance(database, deliveryScope, scopedEntity.deliveryId)
                            beforeAcceptanceCommit?.invoke()
                        }
                        return@withTransaction false
                    }
                    val claimed = io.ethan.pushgo.data.claimOperationScope(
                        operationLedgerDao = operationLedgerDao,
                        channelId = scopedEntity.channel,
                        entityType = entityType,
                        entityId = scopedEntity.entityId,
                        opId = scopedEntity.opId,
                        deliveryId = scopedEntity.deliveryId,
                        appliedAt = scopedEntity.receivedAt.toEpochMilli(),
                        providerAckIdentity = providerAckIdentity,
                        deliveryScope = deliveryScope,
                    )
                    if (!claimed && !recoveringLegacyReceipt) {
                        if (scopedEntity.deliveryId?.trim().isNullOrEmpty()) return@withTransaction false
                        if (hasProvenOperationAcceptance(database, deliveryScope, scopedEntity.deliveryId,
                                scopedEntity.channel, entityType, scopedEntity.entityId, scopedEntity.opId)) {
                            if (retainPreviouslyAcceptedEntity(scopedEntity, deliveryScope, onPreviouslyAcceptedReplay)) beforeAcceptanceCommit?.invoke()
                        } else {
                            val key = deliveryScope.scopedDeliveryStorageKey(composeOperationScopeKey(
                                scopedEntity.channel?.trim()?.takeIf(String::isNotEmpty), entityType,
                                scopedEntity.entityId.trim().takeIf(String::isNotEmpty), scopedEntity.opId?.trim()?.takeIf(String::isNotEmpty)))
                            val originalDelivery = database.openHelper.readableDatabase.query(
                                "SELECT delivery_id FROM operation_ledger WHERE scope_key=?", arrayOf(key),
                            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                                ?: throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
                            requireStoredEntityAcceptance(scopedEntity, deliveryScope, originalDelivery)
                            beforeAcceptanceCommit?.invoke()
                        }
                        false
                    } else {
                        // Every tentative true claim rolls back if its business acceptance
                        // or independently authenticated duplicate proof fails.
                        requireInboundProjectionHeads(scopedEntity, deliveryScope)
                        beforeAcceptanceCommit?.invoke()
                        val accepted = if (entityType == "event") insertEventIncoming(scopedEntity, deliveryScope)
                            else insertThingIncoming(scopedEntity, deliveryScope)
                        if (!accepted && !scopedEntity.deliveryId?.trim().isNullOrEmpty()) {
                            requireStoredEntityAcceptance(scopedEntity, deliveryScope)
                        }
                        if (recoveringLegacyReceipt) proveInboundAcceptance(database, deliveryScope, scopedEntity.deliveryId)
                        accepted
                    }
                }
                else -> false
            }
            val ownerId = scopedEntity.localDeliveryKey ?: scopedEntity.deliveryId ?: when {
                entityType == "thing" -> ThingChangeLogEntity.fromIncoming(scopedEntity).id
                scopedEntity.thingId.isNullOrBlank() -> EventChangeLogEntity.fromIncoming(scopedEntity).id
                else -> ThingSubEventEntity.fromIncoming(scopedEntity).id
            }
            if (inserted) {
                // Things publish their origin before activating their stored children.
                if (entityType != "thing") database.recordResourceOrigin(ownerId, scopedEntity.receivedAt.toEpochMilli(), deliveryScope)
                val table = when {
                    entityType == "thing" -> "thing_change_logs"
                    scopedEntity.thingId.isNullOrBlank() -> "event_change_logs"
                    else -> "thing_sub_events"
                }
                database.markPostProcessPending(table, ownerId, postProcessFlags)
            }
            inserted
        }
    }

    /** A committed processed fact need not adopt a later head or recreate deleted content. */
    private suspend fun retainPreviouslyAcceptedEntity(
        entity: IncomingEntityRecord,
        scope: InboundDeliveryScope?,
        onPreviouslyAcceptedReplay: (() -> Unit)?,
    ): Boolean {
        val hasCurrentContext = try {
            if (entity.entityType.trim().lowercase() == "thing") {
                val thingId = entity.thingId?.trim()?.takeIf(String::isNotEmpty) ?: entity.entityId
                provenThingLifecycleGateway(thingId, scope) != null
            } else {
                requireStoredEntityAcceptance(entity, scope)
                true
            }
        } catch (_: InboundIdentityConflictException) {
            false
        }
        if (!hasCurrentContext) onPreviouslyAcceptedReplay?.invoke()
        return hasCurrentContext
    }

    /** Only duplicate branches read the exact canonical source, including its waiting slot. */
    private fun requireStoredEntityAcceptance(
        incoming: IncomingEntityRecord,
        scope: InboundDeliveryScope?,
        sourceDeliveryId: String? = incoming.deliveryId,
    ) {
        check(database.inTransaction())
        val type = incoming.entityType.trim().lowercase()
        val thingId = incoming.thingId?.trim()?.takeIf(String::isNotEmpty)
        val expectedThingId = if (type == "thing") thingId ?: incoming.entityId else thingId
        val eventId = incoming.eventId?.trim()?.takeIf(String::isNotEmpty) ?: incoming.entityId
        val table = if (type == "thing") "thing_change_logs" else if (thingId == null) "event_change_logs" else "thing_sub_events"
        val sourceId = sourceDeliveryId?.trim()?.takeIf(String::isNotEmpty)?.let { scope.scopedDeliveryStorageKey(it) }
            ?: when (table) {
                "thing_change_logs" -> ThingChangeLogEntity.fromIncoming(incoming).id
                "event_change_logs" -> EventChangeLogEntity.fromIncoming(incoming).id
                else -> ThingSubEventEntity.fromIncoming(incoming).id
            }
        val eventColumns = if (type == "thing") "NULL,NULL" else "event_id,event_state"
        val observed = if (type == "thing") "observed_time_epoch" else "NULL"
        val slot = if (table == "thing_sub_events") "activated" else "0"
        var sawSource = false
        var conflict = InboundIdentityConflictReason.UNVERIFIABLE_HISTORY
        val proven = database.openHelper.readableDatabase.query(
            "SELECT rowid,id,$slot,entity_id,channel,title,body,raw_payload_json,op_id,delivery_id,thing_id,$eventColumns,event_time_epoch,$observed FROM $table WHERE id=? LIMIT 2",
            arrayOf(sourceId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sawSource = true
                if (scope != null) {
                    val oldGateway = sourceRowGateway(table, cursor.getLong(0), cursor.getString(1), cursor.getInt(2))
                    if (oldGateway == null) continue
                    if (oldGateway != scope.gatewayUrl) { conflict = InboundIdentityConflictReason.FOREIGN_GATEWAY; continue }
                }
                fun text(index: Int) = cursor.getString(index)?.trim()?.takeIf(String::isNotEmpty)
                fun time(index: Int) = if (cursor.isNull(index)) null else cursor.getLong(index)
                if (text(3) != incoming.entityId.trim().takeIf(String::isNotEmpty) ||
                    text(4) != incoming.channel?.trim()?.takeIf(String::isNotEmpty) ||
                    cursor.getString(5) != incoming.title || cursor.getString(6) != incoming.body ||
                    text(8) != incoming.opId?.trim()?.takeIf(String::isNotEmpty) ||
                    text(9) != sourceDeliveryId?.trim()?.takeIf(String::isNotEmpty) ||
                    text(10) != expectedThingId || (type == "event" && (text(11) != eventId || text(12) != incoming.eventState?.trim()?.takeIf(String::isNotEmpty))) ||
                    time(13) != incoming.eventTimeEpoch || (type == "thing" && time(14) != incoming.observedTimeEpoch) ||
                    !sameEntityBusinessPayload(cursor.getString(7), incoming.rawPayloadJson)) {
                    conflict = InboundIdentityConflictReason.DIFFERENT_CONTENT
                    continue
                }
                return@use true
            }
            false
        }
        if (!proven) throw InboundIdentityConflictException(if (sawSource) conflict else InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
    }

    private fun sameEntityBusinessPayload(previous: String, incoming: String): Boolean {
        fun business(raw: String) = runCatching { org.json.JSONObject(raw) }.getOrNull()?.apply {
            listOf("base_url", "gateway_url", "provider_device_key", "delivery_id", "ack_contract", "_fcm_message_id").forEach(::remove)
        }
        fun equal(left: Any?, right: Any?): Boolean = when {
            left is org.json.JSONObject && right is org.json.JSONObject -> {
                val keys = left.keys().asSequence().toSet()
                keys == right.keys().asSequence().toSet() && keys.all { equal(left.get(it), right.get(it)) }
            }
            left is org.json.JSONArray && right is org.json.JSONArray ->
                left.length() == right.length() && (0 until left.length()).all { equal(left.get(it), right.get(it)) }
            else -> left == right
        }
        val old = business(previous) ?: return false
        val new = business(incoming) ?: return false
        return equal(old, new)
    }

    private suspend fun requireInboundProjectionHeads(entity: IncomingEntityRecord, scope: InboundDeliveryScope?) {
        if (scope == null) return // Existing internal/legacy calls do not acquire an invented origin.
        if (entity.entityType.trim().lowercase() == "thing") {
            val id = entity.thingId?.trim()?.takeIf(String::isNotEmpty) ?: entity.entityId
            provenThingLifecycleGateway(id, scope)
        } else if (entity.entityType.trim().lowercase() == "event") {
            val id = entity.eventId?.trim()?.takeIf(String::isNotEmpty) ?: entity.entityId
            topLevelEventHeadDao.getByEventId(id)?.let { database.requireInboundHeadGateway(scope, it.sourceId, it.messageId) }
            entity.thingId?.trim()?.takeIf(String::isNotEmpty)?.let { thingId ->
                provenThingLifecycleGateway(thingId, scope)
            }
        }
    }

    suspend fun deleteAll() {
        database.withTransaction {
            eventChangeLogDao.deleteAll()
            topLevelEventHeadDao.deleteAll()
            thingChangeLogDao.deleteAll()
            thingHeadDao.deleteAll()
            thingSubEventDao.deleteAllActivated()
            thingSubMessageDao.deleteAll()
            database.pendingThingMessageDao().deleteAll()
            database.thingWriteTickets.invalidateChannel(null)
        }
    }

    /** Verify the concrete delivery record, never just its mutable semantic head. */
    suspend fun publishNotificationIfCurrent(
        entity: IncomingEntityRecord,
        deliveryScope: InboundDeliveryScope?,
        replay: Boolean,
        publish: () -> Boolean,
    ): Boolean? = database.withTransaction {
        val scoped = entity.copy(
            localDeliveryKey = entity.deliveryId?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { deliveryScope.scopedDeliveryStorageKey(it) },
        )
        when (scoped.entityType.trim().lowercase()) {
            "event" -> if (scoped.thingId.isNullOrBlank()) {
                val expected = EventChangeLogEntity.fromIncoming(scoped)
                val current = eventChangeLogDao.getById(expected.id) ?: return@withTransaction null
                if (current != expected.copy(receivedAt = if (replay) current.receivedAt else expected.receivedAt, postProcessPending = current.postProcessPending)) {
                    return@withTransaction null
                }
            } else {
                val expected = ThingSubEventEntity.fromIncoming(scoped)
                val current = thingSubEventDao.getById(expected.id) ?: return@withTransaction null
                if (current != expected.copy(receivedAt = if (replay) current.receivedAt else expected.receivedAt, postProcessPending = current.postProcessPending)) {
                    return@withTransaction null
                }
            }
            "thing" -> {
                val expected = ThingChangeLogEntity.fromIncoming(scoped)
                val current = thingChangeLogDao.getById(expected.id) ?: return@withTransaction null
                if (current != expected.copy(receivedAt = if (replay) current.receivedAt else expected.receivedAt, postProcessPending = current.postProcessPending)) {
                    return@withTransaction null
                }
            }
            else -> return@withTransaction null
        }
        publish()
    }

    suspend fun deleteEvent(eventId: String): Int {
        val normalized = eventId.trim()
        if (normalized.isEmpty()) return 0
        return deleteEvents(listOf(normalized))
    }

    suspend fun deleteEvents(eventIds: Collection<String>): Int {
        val normalizedIds = normalizeIds(eventIds)
        if (normalizedIds.isEmpty()) return 0
        return database.withTransaction {
            var deleted = 0
            for (id in normalizedIds) {
                val head = topLevelEventHeadDao.getByEventId(id)
                if (head == null) {
                    val logGateway = provenGroupGateway("event_change_logs", "t.event_id=?", arrayOf(id))
                    val childGateway = provenGroupGateway("thing_sub_events", "t.event_id=? AND t.activated=1", arrayOf(id))
                    val gateway = soleGateway(logGateway, childGateway) ?: continue
                    deleted += deleteEntityRows("event_change_logs", "t.event_id=?", arrayOf(id), gateway)
                    deleted += deleteEntityRows("thing_sub_events", "t.event_id=? AND t.activated=1", arrayOf(id), gateway)
                    continue
                }
                val gateway = headGateway(head.sourceId, head.messageId)
                if (gateway != null) {
                    deleted += deleteEntityRows("event_change_logs", "t.event_id=?", arrayOf(id), gateway)
                    deleted += deleteEntityRows("thing_sub_events", "t.event_id=? AND t.activated=1", arrayOf(id), gateway)
                } else {
                    // A head can have come from either concrete event source table.
                    // If both match its old source ID, only an exact payload match can
                    // identify one of them; never broaden this to the bare event ID.
                    val log = eventChangeLogDao.getById(head.sourceId)?.takeIf { it.eventId == id }
                    val child = thingSubEventDao.getById(head.sourceId)?.takeIf { it.eventId == id }
                    if (log != null && (child == null || (log.rawPayloadJson == head.rawPayloadJson && child.rawPayloadJson != head.rawPayloadJson))) {
                        deleted += deleteSelectedSource("event_change_logs", head.sourceId)
                    } else if (child != null && (log == null || (child.rawPayloadJson == head.rawPayloadJson && log.rawPayloadJson != head.rawPayloadJson))) {
                        deleted += deleteSelectedSource("thing_sub_events", head.sourceId, activeOnly = true)
                    }
                }
                deleted += topLevelEventHeadDao.deleteByEventId(id)
                // Event deletion has never consumed waiting child lifecycle slots.
            }
            deleted
        }
    }

    suspend fun deleteEvents(channelId: String?): Int {
        val normalizedChannel = channelId?.trim()?.takeIf { it.isNotEmpty() }
        return database.withTransaction {
            var deleted = 0
            if (normalizedChannel == null) {
                deleted += eventChangeLogDao.countAll()
                deleted += topLevelEventHeadDao.countAll()
                eventChangeLogDao.deleteAll()
                topLevelEventHeadDao.deleteAll()
                deleted += thingSubEventDao.deleteAllActivated()
            } else {
                deleted += eventChangeLogDao.deleteByChannel(normalizedChannel)
                deleted += topLevelEventHeadDao.deleteByChannel(normalizedChannel)
                deleted += thingSubEventDao.deleteActivatedByChannel(normalizedChannel)
            }
            deleted
        }
    }

    suspend fun deleteThing(thingId: String): Int {
        val normalized = thingId.trim()
        if (normalized.isEmpty()) return 0
        return deleteThings(listOf(normalized))
    }

    suspend fun deleteThings(thingIds: Collection<String>): Int {
        val normalizedIds = normalizeIds(thingIds)
        if (normalizedIds.isEmpty()) return 0
        return database.withTransaction {
            var deleted = 0
            for (id in normalizedIds) {
                val head = thingHeadDao.getByThingId(id)
                if (head == null) {
                    val logGateway = provenGroupGateway("thing_change_logs", "t.thing_id=?", arrayOf(id))
                    val eventGateway = provenGroupGateway("thing_sub_events", "t.thing_id=? AND t.activated=1", arrayOf(id))
                    val messageGateway = provenGroupGateway("thing_sub_messages", "t.thing_id=?", arrayOf(id))
                    val gateway = soleGateway(logGateway, eventGateway, messageGateway) ?: continue
                    deleted += deleteEntityRows("thing_change_logs", "t.thing_id=?", arrayOf(id), gateway)
                    deleted += deleteEntityRows("thing_sub_events", "t.thing_id=?", arrayOf(id), gateway)
                    deleted += deleteEntityRows("thing_sub_messages", "t.thing_id=?", arrayOf(id), gateway)
                    deleted += deleteEntityRows("pending_thing_messages", "t.thing_id=?", arrayOf(id), gateway)
                    continue
                }
                val gateway = headGateway(head.sourceId, head.messageId)
                if (gateway != null) {
                    deleted += deleteEntityRows("thing_change_logs", "t.thing_id=?", arrayOf(id), gateway)
                    deleted += deleteEntityRows("thing_sub_events", "t.thing_id=? AND t.activated=1", arrayOf(id), gateway)
                    deleted += deleteEntityRows("thing_sub_messages", "t.thing_id=?", arrayOf(id), gateway)
                    deleted += deleteEntityRows("thing_sub_events", "t.thing_id=? AND t.activated=0", arrayOf(id), gateway)
                    deleted += deleteEntityRows("pending_thing_messages", "t.thing_id=?", arrayOf(id), gateway)
                } else {
                    if (thingChangeLogDao.getById(head.sourceId)?.thingId == id) {
                        deleted += deleteSelectedSource("thing_change_logs", head.sourceId)
                    }
                }
                deleted += thingHeadDao.deleteByThingId(id)
            }
            database.thingWriteTickets.invalidateThings(normalizedIds)
            deleted
        }
    }

    private fun headGateway(sourceId: String, deliveryId: String?): String? =
        database.authenticatedSourceGateway(sourceId)
            ?: deliveryId?.trim()?.takeIf(String::isNotEmpty)?.let {
                database.provenInboundGateway(it, scopedSourceId = sourceId)
            }

    private fun soleGateway(vararg gateways: String?): String? {
        val known = gateways.filterNotNull().distinct()
        if (known.size > 1) throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
        return known.singleOrNull()
    }

    /** A headless visible group remains deletable only when every concrete source proves one origin. */
    private fun provenGroupGateway(table: String, predicate: String, args: Array<Any>): String? {
        var after = Long.MIN_VALUE
        var gateway: String? = null
        while (true) {
            val page = sourceRowPage(table, predicate, args, after)
            if (page.isEmpty()) return gateway
            for ((rowId, id, ownerSlot) in page) {
                val sourceGateway = sourceRowGateway(table, rowId, id, ownerSlot)
                    ?: throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
                if (gateway != null && gateway != sourceGateway) throw InboundIdentityConflictException(InboundIdentityConflictReason.UNVERIFIABLE_HISTORY)
                gateway = sourceGateway
            }
            after = page.last().first
        }
    }

    /** Only the concrete confirmation paths above call this, within their original transaction. */
    private fun deleteEntityRows(table: String, predicate: String, args: Array<Any>, gateway: String): Int {
        check(database.inTransaction())
        var after = Long.MIN_VALUE
        var removed = 0
        while (true) {
            val page = sourceRowPage(table, predicate, args, after)
            if (page.isEmpty()) break
            val selected = page.filter { (rowId, id, ownerSlot) ->
                sourceRowGateway(table, rowId, id, ownerSlot) == gateway
            }
            if (selected.isNotEmpty()) {
                database.openHelper.writableDatabase.compileStatement(
                    "DELETE FROM $table WHERE rowid IN (${selected.joinToString(",") { "?" }})",
                ).use { statement ->
                    selected.forEachIndexed { index, row -> statement.bindLong(index + 1, row.first) }
                    removed += statement.executeUpdateDelete()
                }
            }
            after = page.last().first
        }
        return removed
    }

    private fun sourceRowPage(table: String, predicate: String, args: Array<Any>, after: Long): List<Triple<Long, String, Int>> {
        require(table in setOf("event_change_logs", "thing_change_logs", "thing_sub_events", "thing_sub_messages", "pending_thing_messages"))
        val slot = if (table == "thing_sub_events") "t.activated" else "0"
        // A forward rowid walk avoids sorting the matching history anew on each page.
        // A sparse target can still scan unrelated rows; this is not constant physical work.
        return database.openHelper.readableDatabase.query(
            "SELECT t.rowid,t.id,$slot FROM $table t NOT INDEXED WHERE $predicate AND t.rowid>? ORDER BY t.rowid LIMIT 64",
            arrayOf(*args, after),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getInt(2)))
        } }
    }

    private fun deleteSelectedSource(table: String, sourceId: String, activeOnly: Boolean = false): Int =
        database.openHelper.writableDatabase.compileStatement(
            "DELETE FROM $table WHERE id=?" + if (activeOnly) " AND activated=1" else "",
        ).use { statement -> statement.bindString(1, sourceId); statement.executeUpdateDelete() }

    private fun sourceRowGateway(table: String, rowId: Long, id: String, slot: Int): String? {
        if (table == "pending_thing_messages") return database.openHelper.readableDatabase.query(
            "SELECT authenticated_gateway_url,authenticated_device_key FROM pending_thing_messages WHERE rowid=?", arrayOf(rowId),
        ).use { cursor -> if (cursor.moveToFirst()) InboundDeliveryScope.create(cursor.getString(0), cursor.getString(1))?.gatewayUrl else null }
        val owner = database.openHelper.readableDatabase.query(
            "SELECT authenticated_gateway_url,authenticated_device_key,origin_scope FROM message_resource_owners WHERE source_table=? AND owner_id=? AND owner_slot=? LIMIT 1",
            arrayOf<Any>(table, id, slot),
        ).use { cursor -> if (cursor.moveToFirst()) Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)) else null }
        val literal = owner?.let { InboundDeliveryScope.create(it.first, it.second) }
        if (literal != null && MessageImageStore.originScope(literal) == owner?.third) return literal.gatewayUrl
        if (owner != null && (owner.first != null || owner.second != null)) return null
        // Historical waiting facts without the new atomic authenticated scope
        // remain isolated; a later matching parent does not adopt or expire them.
        if (table == "thing_sub_events" && slot == 0) return null
        val delivery = database.openHelper.readableDatabase.query(
            if (table == "thing_sub_messages") "SELECT raw_payload_json FROM thing_sub_messages WHERE rowid=?"
            else "SELECT delivery_id FROM $table WHERE rowid=?", arrayOf(rowId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else if (table == "thing_sub_messages") {
                runCatching { org.json.JSONObject(cursor.getString(0)).optString("delivery_id") }.getOrNull()
            } else cursor.getString(0)
        }?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return database.provenInboundGateway(delivery, originHash = owner?.third, scopedSourceId = id)
    }

    suspend fun deleteThings(channelId: String?): Int {
        val normalizedChannel = channelId?.trim()?.takeIf { it.isNotEmpty() }
        return database.withTransaction {
            var deleted = 0
            if (normalizedChannel == null) {
                deleted += thingChangeLogDao.countAll()
                deleted += thingHeadDao.countAll()
                deleted += thingSubMessageDao.countAll()
                thingChangeLogDao.deleteAll()
                thingHeadDao.deleteAll()
                deleted += thingSubEventDao.deleteAll()
                thingSubMessageDao.deleteAll()
                deleted += database.pendingThingMessageDao().deleteAll()
            } else {
                deleted += thingChangeLogDao.deleteByChannel(normalizedChannel)
                deleted += thingHeadDao.deleteByChannel(normalizedChannel)
                deleted += thingSubEventDao.deleteByChannel(normalizedChannel)
                deleted += thingSubMessageDao.deleteByChannel(normalizedChannel)
                deleted += database.pendingThingMessageDao().deleteByChannel(normalizedChannel)
            }
            database.thingWriteTickets.invalidateChannel(normalizedChannel)
            deleted
        }
    }

    private fun normalizeIds(ids: Collection<String>): List<String> {
        return ids
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private suspend fun insertEventIncoming(entity: IncomingEntityRecord, deliveryScope: InboundDeliveryScope?): Boolean {
        val localDeliveryKey = entity.localDeliveryKey?.trim()?.takeIf { it.isNotEmpty() }
        val thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() }
        return if (thingId == null) {
            if (localDeliveryKey != null && eventChangeLogDao.getById(localDeliveryKey) != null) {
                false
            } else {
                eventChangeLogDao.insert(EventChangeLogEntity.fromIncoming(entity))
                val eventId = entity.eventId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
                val existing = topLevelEventHeadDao.getByEventId(eventId)
                val incoming = TopLevelEventHeadEntity.fromIncoming(entity)
                if (existing == null || incoming.isNewerThan(existing)) {
                    topLevelEventHeadDao.upsert(
                        TopLevelEventHeadEntity.fromMerged(
                            existing = existing,
                            entity = entity,
                        )
                    )
                }
                synchronizeThingEventProjections(entity, eventId, deliveryScope)
                true
            }
        } else {
            if (!thingHeadDao.existsByThingId(thingId)) {
                val waiting = ThingSubEventEntity.fromIncoming(entity, activated = false)
                val existed = database.openHelper.readableDatabase.query(
                    "SELECT 1 FROM thing_sub_events WHERE id=? AND activated=0 LIMIT 1", arrayOf(waiting.id),
                ).use { it.moveToFirst() }
                thingSubEventDao.insertWaiting(waiting)
                // IGNORE of an old NULL-owner row must not adopt it on replay.
                if (!existed) database.recordResourceOrigin(waiting.id, waiting.receivedAt, deliveryScope)
                false
            } else if (localDeliveryKey != null && thingSubEventDao.getById(localDeliveryKey) != null) {
                false
            } else {
                thingSubEventDao.insert(ThingSubEventEntity.fromIncoming(entity))
                synchronizeTopLevelEventProjection(entity, deliveryScope)
                true
            }
        }
    }

    private suspend fun synchronizeThingEventProjections(
        entity: IncomingEntityRecord,
        eventId: String,
        deliveryScope: InboundDeliveryScope?,
    ) {
        thingSubEventDao.getByEventId(eventId)
            .groupBy(ThingSubEventEntity::thingId)
            .values
            .mapNotNull { versions ->
                versions.maxWithOrNull(
                    compareBy<ThingSubEventEntity> { it.receivedAt }.thenBy { it.id }
                )
            }
            .forEach { current ->
                database.requireInboundHeadGateway(deliveryScope, current.id, current.deliveryId)
                val merged = ThingSubEventEntity.fromMerged(current, entity)
                if (merged.isNewerThan(current)) {
                    thingSubEventDao.insert(merged)
                    database.recordResourceOrigin(merged.id, merged.receivedAt, deliveryScope)
                }
            }
    }

    private suspend fun synchronizeTopLevelEventProjection(entity: IncomingEntityRecord, deliveryScope: InboundDeliveryScope?) {
        val eventId = entity.eventId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
        val existing = topLevelEventHeadDao.getByEventId(eventId) ?: return
        database.requireInboundHeadGateway(deliveryScope, existing.sourceId, existing.messageId)
        val incoming = TopLevelEventHeadEntity.fromIncoming(entity)
        if (incoming.isNewerThan(existing)) {
            topLevelEventHeadDao.upsert(
                TopLevelEventHeadEntity.fromMerged(existing = existing, entity = entity)
            )
        }
    }

    private suspend fun insertThingIncoming(entity: IncomingEntityRecord, deliveryScope: InboundDeliveryScope?): Boolean {
        val localDeliveryKey = entity.localDeliveryKey?.trim()?.takeIf { it.isNotEmpty() }
        if (localDeliveryKey != null && thingChangeLogDao.getById(localDeliveryKey) != null) {
            return false
        }
        val source = ThingChangeLogEntity.fromIncoming(entity)
        thingChangeLogDao.insert(source)
        database.recordResourceOrigin(source.id, source.receivedAt, deliveryScope)
        val thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
        val existing = thingHeadDao.getByThingId(thingId)
        val incoming = ThingHeadEntity.fromIncoming(entity)
        if (existing == null || incoming.isNewerThan(existing)) {
            thingHeadDao.upsert(
                ThingHeadEntity.fromMerged(
                    existing = existing,
                    entity = entity,
                )
            )
        }
        activateStoredEventsForThing(thingId, deliveryScope = deliveryScope)
        return true
    }

    /** The caller retains its Room transaction through the dependent acceptance/replay. */
    internal suspend fun provenThingLifecycleGateway(
        thingId: String,
        deliveryScope: InboundDeliveryScope? = null,
    ): String? {
        check(database.inTransaction()) { "Thing lifecycle origin requires the caller's Room transaction" }
        val normalizedThingId = thingId.trim().takeIf(String::isNotEmpty) ?: return null
        val head = thingHeadDao.getByThingId(normalizedThingId)
        if (head != null) {
            val rawHeadDelivery = head.messageId?.trim()?.takeIf(String::isNotEmpty)
            return if (deliveryScope != null) {
                database.requireInboundHeadGateway(deliveryScope, head.sourceId, rawHeadDelivery)
                deliveryScope.gatewayUrl
            } else headGateway(head.sourceId, rawHeadDelivery)
        }
        // Cross-channel parent removal can leave an authenticated visible
        // group. It still proves its origin without inventing a new head.
        val provenGateway = soleGateway(
            provenGroupGateway("thing_change_logs", "t.thing_id=?", arrayOf(normalizedThingId)),
            provenGroupGateway("thing_sub_events", "t.thing_id=? AND t.activated=1", arrayOf(normalizedThingId)),
            provenGroupGateway("thing_sub_messages", "t.thing_id=?", arrayOf(normalizedThingId)),
        ) ?: return null
        if (deliveryScope != null && provenGateway != deliveryScope.gatewayUrl) {
            throw InboundIdentityConflictException(InboundIdentityConflictReason.FOREIGN_GATEWAY)
        }
        return provenGateway
    }

    suspend fun activateStoredEventsForThing(thingId: String, thingWriteTicket: ThingWriteTicket? = null) {
        activateStoredEventsForThing(thingId, thingWriteTicket, null)
    }

    suspend fun activateStoredEventsForThing(
        thingId: String,
        thingWriteTicket: ThingWriteTicket? = null,
        deliveryScope: InboundDeliveryScope?,
    ) {
        val normalizedThingId = thingId.trim().takeIf { it.isNotEmpty() } ?: return
        database.withTransaction {
            thingWriteTicket?.requireCurrent()
            val targetGateway = provenThingLifecycleGateway(normalizedThingId, deliveryScope) ?: return@withTransaction
            // Retain the existing single scalar lookup and its ordering. No body
            // list, second paging/index scheme or repeated COALESCE sort is added.
            for (row in thingSubEventDao.getWaitingActivationFields(normalizedThingId)) {
                val gateway = database.authenticatedSourceGateway(row.id) ?: continue
                if (gateway != targetGateway) continue
                val localDeliveryKey = row.id.trim().takeIf(String::isNotEmpty)
                val existing = localDeliveryKey?.let { thingSubEventDao.getById(it) }
                if (existing != null) {
                    val existingGateway = database.authenticatedSourceGateway(existing.id)
                    if (existingGateway == targetGateway) thingSubEventDao.deleteWaitingById(row.id)
                    continue
                }
                thingSubEventDao.activateWaiting(
                    id = row.id,
                    thingId = row.thingId.trim().takeIf { it.isNotEmpty() } ?: row.entityId,
                    eventId = row.eventId.trim().takeIf { it.isNotEmpty() } ?: row.entityId,
                    opId = row.opId?.trim()?.takeIf { it.isNotEmpty() },
                    deliveryId = row.deliveryId?.trim()?.takeIf { it.isNotEmpty() },
                    eventState = row.eventState?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    private companion object {
        val entityProjectionMessageSortComparator = compareByDescending<PushMessage> { it.receivedAt.toEpochMilli() }
            .thenByDescending { it.messageId ?: it.id }
    }
}
