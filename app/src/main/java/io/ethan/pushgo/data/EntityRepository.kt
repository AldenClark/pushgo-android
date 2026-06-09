package io.ethan.pushgo.data

import androidx.room.withTransaction
import io.ethan.pushgo.data.db.EventChangeLogDao
import io.ethan.pushgo.data.db.EventChangeLogEntity
import io.ethan.pushgo.data.db.InboundDeliveryLedgerDao
import io.ethan.pushgo.data.db.OperationLedgerDao
import io.ethan.pushgo.data.db.PendingThingEventDao
import io.ethan.pushgo.data.db.PendingThingEventEntity
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.db.ThingChangeLogDao
import io.ethan.pushgo.data.db.ThingChangeLogEntity
import io.ethan.pushgo.data.db.ThingHeadDao
import io.ethan.pushgo.data.db.ThingHeadEntity
import io.ethan.pushgo.data.db.ThingSubEventDao
import io.ethan.pushgo.data.db.ThingSubEventEntity
import io.ethan.pushgo.data.db.ThingSubMessageDao
import io.ethan.pushgo.data.db.ThingSubMessageEntity
import io.ethan.pushgo.data.db.TopLevelEventHeadDao
import io.ethan.pushgo.data.db.TopLevelEventHeadEntity
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant

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
    private val pendingThingEventDao: PendingThingEventDao,
) {
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

    suspend fun insertIncoming(entity: IncomingEntityRecord): Boolean {
        val entityType = entity.entityType.trim().lowercase()
        return database.withTransaction {
            when (entityType) {
                "event", "thing" -> {
                    val deliveryClaimed = io.ethan.pushgo.data.claimInboundDelivery(
                        inboundDeliveryLedgerDao = inboundDeliveryLedgerDao,
                        channelId = entity.channel,
                        entityType = entityType,
                        entityId = entity.entityId,
                        deliveryId = entity.deliveryId,
                        opId = entity.opId,
                        appliedAt = entity.receivedAt.toEpochMilli(),
                    )
                    if (!deliveryClaimed) {
                        return@withTransaction false
                    }
                    val claimed = io.ethan.pushgo.data.claimOperationScope(
                        operationLedgerDao = operationLedgerDao,
                        channelId = entity.channel,
                        entityType = entityType,
                        entityId = entity.entityId,
                        opId = entity.opId,
                        deliveryId = entity.deliveryId,
                        appliedAt = entity.receivedAt.toEpochMilli(),
                    )
                    if (!claimed) {
                        false
                    } else if (entityType == "event") {
                        insertEventIncoming(entity)
                    } else {
                        insertThingIncoming(entity)
                    }
                }
                else -> false
            }
        }
    }

    suspend fun deleteAll() {
        database.withTransaction {
            eventChangeLogDao.deleteAll()
            topLevelEventHeadDao.deleteAll()
            thingChangeLogDao.deleteAll()
            thingHeadDao.deleteAll()
            thingSubEventDao.deleteAll()
            thingSubMessageDao.deleteAll()
        }
    }

    suspend fun deleteEvent(eventId: String): Int {
        val normalized = eventId.trim()
        if (normalized.isEmpty()) return 0
        return database.withTransaction {
            var deleted = 0
            deleted += eventChangeLogDao.deleteByEventId(normalized)
            deleted += topLevelEventHeadDao.deleteByEventId(normalized)
            deleted += thingSubEventDao.deleteByEventId(normalized)
            deleted
        }
    }

    suspend fun deleteEvents(eventIds: Collection<String>): Int {
        val normalizedIds = normalizeIds(eventIds)
        if (normalizedIds.isEmpty()) return 0
        return database.withTransaction {
            var deleted = 0
            deleted += eventChangeLogDao.deleteByEventIds(normalizedIds)
            deleted += topLevelEventHeadDao.deleteByEventIds(normalizedIds)
            deleted += thingSubEventDao.deleteByEventIds(normalizedIds)
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
                deleted += thingSubEventDao.countAll()
                eventChangeLogDao.deleteAll()
                topLevelEventHeadDao.deleteAll()
                thingSubEventDao.deleteAll()
            } else {
                deleted += eventChangeLogDao.deleteByChannel(normalizedChannel)
                deleted += topLevelEventHeadDao.deleteByChannel(normalizedChannel)
                deleted += thingSubEventDao.deleteByChannel(normalizedChannel)
            }
            deleted
        }
    }

    suspend fun deleteThing(thingId: String): Int {
        val normalized = thingId.trim()
        if (normalized.isEmpty()) return 0
        return database.withTransaction {
            var deleted = 0
            deleted += thingChangeLogDao.deleteByThingId(normalized)
            deleted += thingHeadDao.deleteByThingId(normalized)
            deleted += thingSubEventDao.deleteByThingId(normalized)
            deleted += thingSubMessageDao.deleteByThingId(normalized)
            deleted += pendingThingEventDao.deleteByThingId(normalized)
            deleted
        }
    }

    suspend fun deleteThings(thingIds: Collection<String>): Int {
        val normalizedIds = normalizeIds(thingIds)
        if (normalizedIds.isEmpty()) return 0
        return database.withTransaction {
            var deleted = 0
            deleted += thingChangeLogDao.deleteByThingIds(normalizedIds)
            deleted += thingHeadDao.deleteByThingIds(normalizedIds)
            deleted += thingSubEventDao.deleteByThingIds(normalizedIds)
            deleted += thingSubMessageDao.deleteByThingIds(normalizedIds)
            deleted += pendingThingEventDao.deleteByThingIds(normalizedIds)
            deleted
        }
    }

    suspend fun deleteThings(channelId: String?): Int {
        val normalizedChannel = channelId?.trim()?.takeIf { it.isNotEmpty() }
        return database.withTransaction {
            var deleted = 0
            if (normalizedChannel == null) {
                deleted += thingChangeLogDao.countAll()
                deleted += thingHeadDao.countAll()
                deleted += thingSubEventDao.countAll()
                deleted += thingSubMessageDao.countAll()
                thingChangeLogDao.deleteAll()
                thingHeadDao.deleteAll()
                thingSubEventDao.deleteAll()
                thingSubMessageDao.deleteAll()
                deleted += pendingThingEventDao.deleteAll()
            } else {
                deleted += thingChangeLogDao.deleteByChannel(normalizedChannel)
                deleted += thingHeadDao.deleteByChannel(normalizedChannel)
                deleted += thingSubEventDao.deleteByChannel(normalizedChannel)
                deleted += thingSubMessageDao.deleteByChannel(normalizedChannel)
                deleted += pendingThingEventDao.deleteByChannel(normalizedChannel)
            }
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

    private suspend fun insertEventIncoming(entity: IncomingEntityRecord): Boolean {
        val deliveryId = entity.deliveryId?.trim()?.takeIf { it.isNotEmpty() }
        val thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() }
        return if (thingId == null) {
            if (deliveryId != null && eventChangeLogDao.getByDeliveryId(deliveryId) != null) {
                false
            } else {
                eventChangeLogDao.insert(EventChangeLogEntity.fromIncoming(entity))
                val eventId = entity.eventId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
                topLevelEventHeadDao.upsert(
                    TopLevelEventHeadEntity.fromMerged(
                        existing = topLevelEventHeadDao.getByEventId(eventId),
                        entity = entity,
                    )
                )
                true
            }
        } else {
            if (!thingHeadDao.existsByThingId(thingId)) {
                pendingThingEventDao.insert(PendingThingEventEntity.fromIncoming(entity))
                false
            } else if (deliveryId != null && thingSubEventDao.getByDeliveryId(deliveryId) != null) {
                false
            } else {
                thingSubEventDao.insert(ThingSubEventEntity.fromIncoming(entity))
                true
            }
        }
    }

    private suspend fun insertThingIncoming(entity: IncomingEntityRecord): Boolean {
        val deliveryId = entity.deliveryId?.trim()?.takeIf { it.isNotEmpty() }
        if (deliveryId != null && thingChangeLogDao.getByDeliveryId(deliveryId) != null) {
            return false
        }
        thingChangeLogDao.insert(ThingChangeLogEntity.fromIncoming(entity))
        val thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
        thingHeadDao.upsert(
            ThingHeadEntity.fromMerged(
                existing = thingHeadDao.getByThingId(thingId),
                entity = entity,
            )
        )
        replayPendingForThing(thingId)
        return true
    }

    suspend fun replayPendingForThing(thingId: String) {
        val normalizedThingId = thingId.trim().takeIf { it.isNotEmpty() } ?: return
        database.withTransaction {
            val pending = pendingThingEventDao.loadByThingId(normalizedThingId)
            if (pending.isEmpty()) return@withTransaction
            val consumedIds = mutableListOf<String>()
            pending.forEach { row ->
                val incoming = row.toIncomingEntityRecord()
                val deliveryId = incoming.deliveryId?.trim()?.takeIf { it.isNotEmpty() }
                if (deliveryId != null && thingSubEventDao.getByDeliveryId(deliveryId) != null) {
                    consumedIds += row.id
                    return@forEach
                }
                thingSubEventDao.insert(ThingSubEventEntity.fromIncoming(incoming))
                consumedIds += row.id
            }
            if (consumedIds.isNotEmpty()) {
                pendingThingEventDao.deleteByIds(consumedIds)
            }
        }
    }

    private companion object {
        val entityProjectionMessageSortComparator = compareByDescending<PushMessage> { it.receivedAt.toEpochMilli() }
            .thenByDescending { it.messageId ?: it.id }
    }
}
