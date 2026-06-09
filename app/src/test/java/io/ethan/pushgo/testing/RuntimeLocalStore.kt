package io.ethan.pushgo.testing

import io.ethan.pushgo.data.db.EventChangeLogEntity
import io.ethan.pushgo.data.db.MessageEntity
import io.ethan.pushgo.data.db.ThingChangeLogEntity
import io.ethan.pushgo.data.db.ThingHeadEntity
import io.ethan.pushgo.data.db.ThingSubMessageEntity
import io.ethan.pushgo.data.db.TopLevelEventHeadEntity
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import io.ethan.pushgo.notifications.NotificationIngressParser
import org.json.JSONObject

/**
 * JVM-only mirror of the local canonical data path.
 *
 * The store intentionally reuses the production ingress parser and Room entity
 * projection converters, but does not execute Room SQL. Real DAO/Room behavior
 * still belongs in instrumented or Robolectric coverage.
 */
class RuntimeLocalStore private constructor(
    private val messageEntitiesByStableId: LinkedHashMap<String, MessageEntity>,
    private val thingSubMessageEntitiesByStableId: LinkedHashMap<String, ThingSubMessageEntity>,
    private val eventChangeLogsByDelivery: LinkedHashMap<String, EventChangeLogEntity>,
    private val thingChangeLogsByDelivery: LinkedHashMap<String, ThingChangeLogEntity>,
    private val eventHeadsByEventId: LinkedHashMap<String, TopLevelEventHeadEntity>,
    private val thingHeadsByThingId: LinkedHashMap<String, ThingHeadEntity>,
    private val deliveryLedger: LinkedHashSet<String>,
    private val operationLedger: LinkedHashSet<String>,
) {
    constructor() : this(
        messageEntitiesByStableId = linkedMapOf(),
        thingSubMessageEntitiesByStableId = linkedMapOf(),
        eventChangeLogsByDelivery = linkedMapOf(),
        thingChangeLogsByDelivery = linkedMapOf(),
        eventHeadsByEventId = linkedMapOf(),
        thingHeadsByThingId = linkedMapOf(),
        deliveryLedger = linkedSetOf(),
        operationLedger = linkedSetOf(),
    )

    data class InsertResult(
        val accepted: Boolean,
        val kind: RuntimeFixtureKind?,
        val canonicalId: String?,
        val reason: String,
    )

    data class Snapshot(
        val messages: List<MessageEntity>,
        val thingSubMessages: List<ThingSubMessageEntity>,
        val eventChangeLogs: List<EventChangeLogEntity>,
        val thingChangeLogs: List<ThingChangeLogEntity>,
        val eventHeads: List<TopLevelEventHeadEntity>,
        val thingHeads: List<ThingHeadEntity>,
        val deliveryLedger: Set<String>,
        val operationLedger: Set<String>,
    )

    fun ingest(payload: RuntimeInboundPayload): InsertResult {
        val parsed = NotificationIngressParser.parse(
            data = payload.data,
            transportMessageId = payload.transportMessageId,
            keyBytes = null,
        ) ?: return InsertResult(false, null, null, "parse_rejected")

        return when (parsed) {
            is InboundPersistenceRequest.Message -> ingestMessage(parsed.message)
            is InboundPersistenceRequest.Entity -> ingestEntity(parsed.record)
        }
    }

    fun ingestAll(payloads: Sequence<RuntimeInboundPayload>): List<InsertResult> {
        return payloads.map(::ingest).toList()
    }

    fun allMessagesNewestFirst(): List<PushMessage> {
        return allMessageModels().sortedWith(messageSortComparator)
    }

    fun firstMessagePage(limit: Int = 50): List<PushMessage> {
        return messagePage(offset = 0, limit = limit)
    }

    fun messagePage(offset: Int, limit: Int): List<PushMessage> {
        return allMessagesNewestFirst().drop(offset).take(limit)
    }

    fun continuousMessagePages(pageSize: Int, pageCount: Int): List<List<PushMessage>> {
        return (0 until pageCount).map { page ->
            messagePage(offset = page * pageSize, limit = pageSize)
        }
    }

    fun searchMessages(query: String, limit: Int = 50): List<PushMessage> {
        val tokens = query.trim()
            .split("\\s+".toRegex())
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        return allMessagesNewestFirst()
            .filter { message ->
                val haystack = buildString {
                    append(message.title.lowercase())
                    append('\n')
                    append(message.body.lowercase())
                    append('\n')
                    append(message.tags.joinToString(" ") { it.lowercase() })
                    append('\n')
                    append(message.metadata.entries.joinToString(" ") { "${it.key.lowercase()}=${it.value.lowercase()}" })
                }
                tokens.all { token -> haystack.contains(token.removePrefix("#").removePrefix("tag:")) }
            }
            .take(limit)
    }

    fun filterMessages(
        channelId: String? = null,
        tag: String? = null,
        unreadOnly: Boolean? = null,
    ): List<PushMessage> {
        val normalizedTag = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return allMessagesNewestFirst().filter { message ->
            val channelMatches = channelId == null || message.channel == channelId
            val tagMatches = normalizedTag == null || message.tags.any { it.lowercase() == normalizedTag }
            val readMatches = unreadOnly == null || if (unreadOnly) !message.isRead else message.isRead
            channelMatches && tagMatches && readMatches
        }
    }

    fun eventProjectionsNewestFirst(): List<PushMessage> {
        return eventHeadsByEventId.values
            .map(TopLevelEventHeadEntity::asModel)
            .sortedWith(messageSortComparator)
    }

    fun thingProjectionsNewestFirst(): List<PushMessage> {
        return thingHeadsByThingId.values
            .map(ThingHeadEntity::asModel)
            .sortedWith(messageSortComparator)
    }

    fun eventProjectionDetailMessages(eventId: String): List<PushMessage> {
        val normalized = eventId.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val head = eventHeadsByEventId[normalized]?.asModel()
        val history = eventChangeLogsByDelivery.values
            .asSequence()
            .filter { it.eventId == normalized }
            .map(EventChangeLogEntity::asModel)
            .sortedWith(messageSortComparator)
            .toList()
        return combineHeadAndHistory(head, history)
    }

    fun thingProjectionDetailMessages(thingId: String): List<PushMessage> {
        val normalized = thingId.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val head = thingHeadsByThingId[normalized]?.asModel()
        val history = (
            thingChangeLogsByDelivery.values
                .asSequence()
                .filter { it.thingId == normalized }
                .map(ThingChangeLogEntity::asModel)
                .toList() +
                thingSubMessageEntitiesByStableId.values
                    .asSequence()
                    .filter { it.thingId == normalized }
                    .map(ThingSubMessageEntity::asModel)
                    .toList()
            )
            .sortedWith(messageSortComparator)
        return combineHeadAndHistory(head, history)
    }

    fun taskMessages(): List<PushMessage> {
        return allMessagesNewestFirst().filter { message ->
            message.tags.any { it.equals("task", ignoreCase = true) } ||
                message.metadata.containsKey("task_id") ||
                message.metadata.containsKey("task_state")
        }
    }

    fun messageCount(): Int = messageEntitiesByStableId.size + thingSubMessageEntitiesByStableId.size

    fun eventHeadCount(): Int = eventHeadsByEventId.size

    fun thingHeadCount(): Int = thingHeadsByThingId.size

    fun snapshot(): Snapshot {
        return Snapshot(
            messages = messageEntitiesByStableId.values.toList(),
            thingSubMessages = thingSubMessageEntitiesByStableId.values.toList(),
            eventChangeLogs = eventChangeLogsByDelivery.values.toList(),
            thingChangeLogs = thingChangeLogsByDelivery.values.toList(),
            eventHeads = eventHeadsByEventId.values.toList(),
            thingHeads = thingHeadsByThingId.values.toList(),
            deliveryLedger = deliveryLedger.toSet(),
            operationLedger = operationLedger.toSet(),
        )
    }

    private fun ingestMessage(message: PushMessage): InsertResult {
        val entityType = payloadText(message.rawPayloadJson, "entity_type").lowercase()
        if (entityType != "message") {
            return InsertResult(false, null, message.messageId, "unsupported_message_entity_type")
        }

        val canonical = canonicalizeMessage(message)
        val topLevelEntity = MessageEntity.fromModel(canonical)
        val stableId = topLevelEntity.messageId ?: topLevelEntity.id
        val operationKey = operationKey(
            channel = topLevelEntity.channel,
            entityType = "message",
            entityId = operationScopeEntityId(topLevelEntity.rawPayloadJson) ?: stableId,
            opId = payloadText(topLevelEntity.rawPayloadJson, "op_id").ifBlank { null },
        )
        if (operationKey != null && operationKey in operationLedger) {
            return InsertResult(false, RuntimeFixtureKind.MESSAGE, stableId, "duplicate_operation")
        }
        if (stableId in messageEntitiesByStableId || stableId in thingSubMessageEntitiesByStableId) {
            return InsertResult(false, RuntimeFixtureKind.MESSAGE, stableId, "duplicate_message_id")
        }
        val deliveryKey = deliveryKey(topLevelEntity.rawPayloadJson)
        if (deliveryKey != null && deliveryKey in deliveryLedger) {
            return InsertResult(false, RuntimeFixtureKind.MESSAGE, stableId, "duplicate_delivery")
        }

        deliveryKey?.let(deliveryLedger::add)
        operationKey?.let(operationLedger::add)

        if (!topLevelEntity.thingId.isNullOrBlank()) {
            thingSubMessageEntitiesByStableId[stableId] = ThingSubMessageEntity.fromModel(canonical)
            return InsertResult(true, RuntimeFixtureKind.TASK_MESSAGE, stableId, "inserted_thing_sub_message")
        }

        messageEntitiesByStableId[stableId] = topLevelEntity
        return InsertResult(true, RuntimeFixtureKind.MESSAGE, stableId, "inserted_message")
    }

    private fun ingestEntity(record: io.ethan.pushgo.data.IncomingEntityRecord): InsertResult {
        val entityType = record.entityType.trim().lowercase()
        val entityId = when (entityType) {
            "event" -> record.eventId?.trim()?.takeIf { it.isNotEmpty() } ?: record.entityId
            "thing" -> record.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: record.entityId
            else -> return InsertResult(false, null, record.entityId, "unsupported_entity_type")
        }
        val operationKey = operationKey(
            channel = record.channel,
            entityType = entityType,
            entityId = record.entityId,
            opId = record.opId,
        )
        if (operationKey != null && operationKey in operationLedger) {
            return InsertResult(false, kindForEntity(entityType), entityId, "duplicate_operation")
        }
        val deliveryKey = record.deliveryId?.trim()?.takeIf { it.isNotEmpty() }
        if (deliveryKey != null && deliveryKey in deliveryLedger) {
            return InsertResult(false, kindForEntity(entityType), entityId, "duplicate_delivery")
        }

        deliveryKey?.let(deliveryLedger::add)
        operationKey?.let(operationLedger::add)

        return when (entityType) {
            "event" -> {
                val log = EventChangeLogEntity.fromIncoming(record)
                eventChangeLogsByDelivery[log.id] = log
                if (record.thingId.isNullOrBlank()) {
                    val eventId = record.eventId?.trim()?.takeIf { it.isNotEmpty() } ?: record.entityId
                    val current = eventHeadsByEventId[eventId]
                    val head = TopLevelEventHeadEntity.fromMerged(
                        existing = current,
                        entity = record,
                    )
                    if (current == null || head.isNewerThan(current)) {
                        eventHeadsByEventId[head.eventId] = head
                    }
                }
                InsertResult(true, RuntimeFixtureKind.EVENT, entityId, "inserted_event")
            }
            "thing" -> {
                val log = ThingChangeLogEntity.fromIncoming(record)
                thingChangeLogsByDelivery[log.id] = log
                val thingId = record.thingId?.trim()?.takeIf { it.isNotEmpty() } ?: record.entityId
                val current = thingHeadsByThingId[thingId]
                val head = ThingHeadEntity.fromMerged(
                    existing = current,
                    entity = record,
                )
                if (current == null || head.isNewerThan(current)) {
                    thingHeadsByThingId[head.thingId] = head
                }
                InsertResult(true, RuntimeFixtureKind.THING, entityId, "inserted_thing")
            }
            else -> InsertResult(false, null, entityId, "unsupported_entity_type")
        }
    }

    private fun allMessageModels(): List<PushMessage> {
        return messageEntitiesByStableId.values.map(MessageEntity::asModel) +
            thingSubMessageEntitiesByStableId.values.map(ThingSubMessageEntity::asModel)
    }

    private fun combineHeadAndHistory(head: PushMessage?, history: List<PushMessage>): List<PushMessage> {
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
        }
    }

    private fun canonicalizeMessage(message: PushMessage): PushMessage {
        val stableMessageId = message.messageId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: message.deliveryId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: message.id.trim().takeIf { it.isNotEmpty() }
        return if (stableMessageId == null || message.messageId == stableMessageId) {
            message
        } else {
            message.copy(messageId = stableMessageId)
        }
    }

    private fun deliveryKey(rawPayloadJson: String): String? {
        return payloadText(rawPayloadJson, "delivery_id").ifBlank { null }
    }

    private fun operationScopeEntityId(rawPayloadJson: String): String? {
        return payloadText(rawPayloadJson, "entity_id").ifBlank {
            payloadText(rawPayloadJson, "event_id").ifBlank {
                payloadText(rawPayloadJson, "thing_id").ifBlank { null }
            }
        }
    }

    private fun operationKey(channel: String?, entityType: String, entityId: String?, opId: String?): String? {
        val normalizedOpId = opId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedEntityId = entityId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedChannel = channel?.trim()?.takeIf { it.isNotEmpty() } ?: "_"
        return "$normalizedChannel:$entityType:$normalizedEntityId:$normalizedOpId"
    }

    private fun kindForEntity(entityType: String): RuntimeFixtureKind? {
        return when (entityType) {
            "event" -> RuntimeFixtureKind.EVENT
            "thing" -> RuntimeFixtureKind.THING
            else -> null
        }
    }

    private fun payloadText(rawPayloadJson: String, key: String): String {
        return runCatching { JSONObject(rawPayloadJson).optString(key, "") }.getOrDefault("").trim()
    }

    private fun TopLevelEventHeadEntity.isNewerThan(current: TopLevelEventHeadEntity): Boolean {
        if (receivedAt != current.receivedAt) return receivedAt > current.receivedAt
        return sourceId > current.sourceId
    }

    private fun ThingHeadEntity.isNewerThan(current: ThingHeadEntity): Boolean {
        if (receivedAt != current.receivedAt) return receivedAt > current.receivedAt
        return sourceId > current.sourceId
    }

    companion object {
        private val messageSortComparator = compareByDescending<PushMessage> { it.receivedAt.toEpochMilli() }
            .thenByDescending { it.messageId ?: it.id }

        fun fromSnapshot(snapshot: Snapshot): RuntimeLocalStore {
            return RuntimeLocalStore(
                messageEntitiesByStableId = snapshot.messages.associateByTo(linkedMapOf()) { it.messageId ?: it.id },
                thingSubMessageEntitiesByStableId = snapshot.thingSubMessages.associateByTo(linkedMapOf()) {
                    it.messageId ?: it.id
                },
                eventChangeLogsByDelivery = snapshot.eventChangeLogs.associateByTo(linkedMapOf()) { it.id },
                thingChangeLogsByDelivery = snapshot.thingChangeLogs.associateByTo(linkedMapOf()) { it.id },
                eventHeadsByEventId = snapshot.eventHeads.associateByTo(linkedMapOf()) { it.eventId },
                thingHeadsByThingId = snapshot.thingHeads.associateByTo(linkedMapOf()) { it.thingId },
                deliveryLedger = LinkedHashSet(snapshot.deliveryLedger),
                operationLedger = LinkedHashSet(snapshot.operationLedger),
            )
        }
    }
}
