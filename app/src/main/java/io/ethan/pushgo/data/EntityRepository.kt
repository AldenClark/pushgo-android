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
import io.ethan.pushgo.util.PayloadTimeNormalizer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject
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
        return combine(
            topLevelEventHeadDao.observeCount(),
            topLevelEventHeadDao.observeLatestReceivedAt(),
        ) { count, latestReceivedAt ->
            // Encode both row count and latest event timestamp so in-place event updates trigger UI refresh.
            (latestReceivedAt shl 16) xor count.toLong()
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

    suspend fun getEventProjectionMessages(): List<PushMessage> {
        val topLevelHistory = eventChangeLogDao.getAllProjection().map(EventChangeLogEntity::asModel)
        val topLevelHeads = topLevelEventHeadDao.getAllProjection().map(TopLevelEventHeadEntity::asModel)
        return mergeAndSort(
            messages = topLevelHistory + topLevelHeads,
            keySelector = { eventProjectionTimeEpochMillis(it) }
        )
    }

    suspend fun getEventProjectionMessagesPage(
        before: EntityProjectionCursor?,
        limit: Int,
    ): List<PushMessage> {
        val pageSize = limit.coerceIn(1, 500)
        val beforeReceivedAt = before?.receivedAt
        val beforeId = before?.id
        val topLevelHistory = eventChangeLogDao.getProjectionPage(
            beforeReceivedAt = beforeReceivedAt,
            beforeId = beforeId,
            limit = pageSize,
        ).map(EventChangeLogEntity::asModel)
        val topLevelHeads = topLevelEventHeadDao.getProjectionPage(
            beforeReceivedAt = beforeReceivedAt,
            beforeId = beforeId,
            limit = pageSize,
        ).map(TopLevelEventHeadEntity::asModel)
        return mergeProjectionPage(
            messages = topLevelHistory + topLevelHeads,
            limit = pageSize,
        )
    }

    suspend fun getThingProjectionMessages(): List<PushMessage> {
        val heads = thingHeadDao.getAllProjection().map(ThingHeadEntity::asModel)
        val thingHistory = thingChangeLogDao.getAllProjection().map(ThingChangeLogEntity::asModel)
        val thingSubEvents = thingSubEventDao.getAllProjection().map(ThingSubEventEntity::asModel)
        val thingSubMessages = thingSubMessageDao.getAllProjection().map { it.asModel() }
        return mergeAndSort(
            messages = heads + thingHistory + thingSubEvents + thingSubMessages,
            keySelector = { thingProjectionTimeEpochMillis(it) }
        )
    }

    suspend fun getThingProjectionMessagesPage(
        before: EntityProjectionCursor?,
        limit: Int,
    ): List<PushMessage> {
        val pageSize = limit.coerceIn(1, 500)
        val beforeReceivedAt = before?.receivedAt
        val beforeId = before?.id
        val heads = thingHeadDao.getProjectionPage(
            beforeReceivedAt = beforeReceivedAt,
            beforeId = beforeId,
            limit = pageSize,
        ).map(ThingHeadEntity::asModel)
        val thingHistory = thingChangeLogDao.getProjectionPage(
            beforeReceivedAt = beforeReceivedAt,
            beforeId = beforeId,
            limit = pageSize,
        ).map(ThingChangeLogEntity::asModel)
        val thingSubEvents = thingSubEventDao.getProjectionPage(
            beforeReceivedAt = beforeReceivedAt,
            beforeId = beforeId,
            limit = pageSize,
        ).map(ThingSubEventEntity::asModel)
        val thingSubMessages = thingSubMessageDao.getProjectionPage(
            beforeReceivedAt = beforeReceivedAt,
            beforeId = beforeId,
            limit = pageSize,
        ).map(ThingSubMessageEntity::asModel)
        return mergeProjectionPage(
            messages = heads + thingHistory + thingSubEvents + thingSubMessages,
            limit = pageSize,
        )
    }

    suspend fun insertIncoming(entity: IncomingEntityRecord): Boolean {
        val entityType = entity.entityType.trim().lowercase()
        return database.withTransaction {
            when (entityType) {
                "event", "thing" -> {
                    val deliveryClaimed = claimInboundDelivery(
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
                    val claimed = claimOperationScope(
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

    suspend fun deleteEvents(channelId: String?): Int {
        val normalizedChannel = channelId?.trim()?.takeIf { it.isNotEmpty() }
        return database.withTransaction {
            var deleted = 0
            if (normalizedChannel == null) {
                deleted += eventChangeLogDao.countAll()
                deleted += topLevelEventHeadDao.countAll()
                eventChangeLogDao.deleteAll()
                topLevelEventHeadDao.deleteAll()
            } else {
                deleted += eventChangeLogDao.deleteByChannel(normalizedChannel)
                deleted += topLevelEventHeadDao.deleteByChannel(normalizedChannel)
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
            } else {
                deleted += thingChangeLogDao.deleteByChannel(normalizedChannel)
                deleted += thingHeadDao.deleteByChannel(normalizedChannel)
                deleted += thingSubEventDao.deleteByChannel(normalizedChannel)
                deleted += thingSubMessageDao.deleteByChannel(normalizedChannel)
            }
            deleted
        }
    }

    private suspend fun insertEventIncoming(entity: IncomingEntityRecord): Boolean {
        val deliveryId = entity.deliveryId?.trim()?.takeIf { it.isNotEmpty() }
        val thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() }
        return if (thingId == null) {
            if (deliveryId != null && eventChangeLogDao.getByDeliveryId(deliveryId) != null) {
                false
            } else {
                eventChangeLogDao.insert(EventChangeLogEntity.fromIncoming(entity))
                topLevelEventHeadDao.upsert(TopLevelEventHeadEntity.fromIncoming(entity))
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
        thingHeadDao.upsert(ThingHeadEntity.fromIncoming(entity))
        val thingId = entity.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: entity.entityId
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

    private fun mergeAndSort(
        messages: List<PushMessage>,
        keySelector: (PushMessage) -> Long,
    ): List<PushMessage> {
        if (messages.isEmpty()) return emptyList()
        val byId = LinkedHashMap<String, PushMessage>(messages.size)
        for (message in messages) {
            val current = byId[message.id]
            if (current == null) {
                byId[message.id] = message
                continue
            }
            val nextKey = keySelector(message)
            val currentKey = keySelector(current)
            if (nextKey > currentKey || (nextKey == currentKey && message.receivedAt > current.receivedAt)) {
                byId[message.id] = message
            }
        }
        return byId.values.sortedWith(
            compareByDescending<PushMessage> { keySelector(it) }
                .thenByDescending { it.receivedAt.toEpochMilli() }
                .thenByDescending { it.id }
        )
    }

    private fun mergeProjectionPage(
        messages: List<PushMessage>,
        limit: Int,
    ): List<PushMessage> {
        if (messages.isEmpty()) return emptyList()
        val byId = LinkedHashMap<String, PushMessage>(messages.size)
        for (message in messages) {
            val current = byId[message.id]
            if (current == null) {
                byId[message.id] = message
                continue
            }
            if (message.receivedAt > current.receivedAt || (message.receivedAt == current.receivedAt && message.id > current.id)) {
                byId[message.id] = message
            }
        }
        return byId.values
            .sortedWith(
                compareByDescending<PushMessage> { it.receivedAt.toEpochMilli() }
                    .thenByDescending { it.id }
            )
            .take(limit)
    }

    private fun eventProjectionTimeEpochMillis(message: PushMessage): Long {
        val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
        val eventTimeEpoch = PayloadTimeNormalizer.epochMillisFromJson(payload, "event_time")
        return eventTimeEpoch ?: message.receivedAt.toEpochMilli()
    }

    private fun thingProjectionTimeEpochMillis(message: PushMessage): Long {
        val payload = runCatching { JSONObject(message.rawPayloadJson) }.getOrNull()
        val observedEpoch = PayloadTimeNormalizer.epochMillisFromJson(payload, "observed_at")
        if (observedEpoch != null) {
            return observedEpoch
        }
        val eventEpoch = PayloadTimeNormalizer.epochMillisFromJson(payload, "event_time")
        return eventEpoch ?: message.receivedAt.toEpochMilli()
    }
}
