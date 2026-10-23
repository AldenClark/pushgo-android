package io.ethan.pushgo.data

import androidx.room.withTransaction
import io.ethan.pushgo.data.db.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

class OrdinaryDeletionTargetChangedException : IllegalStateException(
    "Deletion target changed; confirm the current content again",
)

/** A foreground-only digest of the exact rows ordinary deletion will remove. */
class OrdinaryDeletionConfirmation(private val database: PushGoDatabase) {
    class Identity internal constructor(internal val fingerprint: String, val displayTitle: String?)

    suspend fun capture(operation: PendingLocalDeletionOperation): Identity = database.withTransaction {
        require(operation.targetIds.size == 1)
        val target = operation.targetIds.single().trim()
        require(target.isNotEmpty())
        val digest = FootprintDigest()
        digest.appendRow("operation", operation.kind.name, target)
        val settings = database.appSettingsDao().get()
        digest.appendRow(
            "configuration", settings?.serverAddress, settings?.useFcmChannel ?: true,
            settings?.notificationKeyUpdatedAt,
            settings?.keyEncoding ?: io.ethan.pushgo.data.model.KeyEncoding.BASE64.name,
        )
        val displayTitle = when (operation.kind) {
            PendingLocalDeletionKind.MESSAGES -> {
                val message = database.messageDao().getById(target)
                val subMessage = database.thingSubMessageDao().getById(target)
                if (message == null && subMessage == null) throw OrdinaryDeletionTargetChangedException()
                message?.let(digest::message)
                subMessage?.let(digest::subMessage)
                message?.title ?: subMessage?.title
            }
            PendingLocalDeletionKind.EVENTS -> {
                val logs = database.eventChangeLogDao().getByEventId(target)
                val head = database.topLevelEventHeadDao().getByEventId(target)
                val children = database.thingSubEventDao().getByEventId(target)
                if (head == null && logs.isEmpty() && children.isEmpty()) {
                    throw OrdinaryDeletionTargetChangedException()
                }
                logs.sortedBy { it.id }.forEach(digest::eventLog)
                head?.let(digest::eventHead)
                children.sortedBy { it.id }.forEach(digest::subEvent)
                head?.title ?: (logs.map { TitleCandidate(it.receivedAt, it.id, it.title) } +
                    children.map { TitleCandidate(it.receivedAt, it.id, it.title) })
                    .maxWithOrNull(compareBy<TitleCandidate> { it.receivedAt }.thenBy { it.id })?.title
            }
            PendingLocalDeletionKind.THINGS -> {
                val logs = database.thingChangeLogDao().getByThingId(target)
                val head = database.thingHeadDao().getByThingId(target)
                val children = database.thingSubEventDao().getByThingIdIncludingWaiting(target)
                val messages = database.thingSubMessageDao().getByThingId(target)
                if (head == null && logs.isEmpty() && children.isEmpty() && messages.isEmpty()) {
                    throw OrdinaryDeletionTargetChangedException()
                }
                logs.sortedBy { it.id }.forEach(digest::thingLog)
                head?.let(digest::thingHead)
                children
                    .sortedWith(compareBy<ThingSubEventEntity> { it.id }.thenBy { it.activated })
                    .forEach(digest::subEvent)
                messages.sortedBy { it.id }.forEach(digest::subMessage)
                head?.title ?: logs.maxWithOrNull(
                    compareBy<ThingChangeLogEntity> { it.receivedAt }.thenBy { it.id },
                )?.title
            }
            else -> error("Ordinary confirmation requires a Message, Event, or Thing")
        }
        Identity(digest.finish(), displayTitle?.takeIf { it.isNotBlank() })
    }

    /** Caller must hold the same Room transaction through validation and deletion. */
    suspend fun validate(identity: Identity, operation: PendingLocalDeletionOperation) {
        check(database.inTransaction()) { "Confirmation validation requires the deletion transaction" }
        if (capture(operation).fingerprint != identity.fingerprint) {
            throw OrdinaryDeletionTargetChangedException()
        }
    }

    private data class TitleCandidate(val receivedAt: Long, val id: String, val title: String)
}

private class FootprintDigest {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun appendRow(table: String, vararg values: Any?) {
        value(table)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(values.size).array())
        values.forEach(::value)
    }

    private fun value(value: Any?) {
        when (value) {
            null -> digest.update(0.toByte())
            is String -> {
                digest.update(1.toByte())
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            is Long -> {
                digest.update(2.toByte())
                digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
            }
            is Boolean -> {
                digest.update(3.toByte())
                digest.update(if (value) 1.toByte() else 0.toByte())
            }
            else -> error("Unsupported confirmation field type")
        }
    }

    fun finish(): String = digest.digest().joinToString("") { "%02x".format(it) }

    // Read state and derived preview/list projections do not change deletion identity.
    // Only the two known image-cache paths are excluded; unknown payload data is retained.
    fun message(row: MessageEntity) = appendRow(
        "messages", row.id, row.messageId, row.title, row.body, row.channel, row.url,
        row.receivedAt, materialPayload(row.rawPayloadJson), row.status, row.decryptionState,
        row.notificationId, row.serverId, row.entityType, row.entityId, row.eventId,
        row.thingId, row.eventState, row.eventTimeEpoch, row.occurredAtEpoch,
    )

    fun subMessage(row: ThingSubMessageEntity) = appendRow(
        "thing_sub_messages", row.id, row.messageId, row.title, row.body, row.channel, row.url,
        row.receivedAt, materialPayload(row.rawPayloadJson), row.status, row.decryptionState,
        row.notificationId, row.serverId, row.entityType, row.entityId, row.eventId,
        row.thingId, row.eventState, row.eventTimeEpoch, row.occurredAtEpoch,
    )

    fun eventLog(row: EventChangeLogEntity) = appendRow(
        "event_change_logs", row.id, row.entityId, row.channel, row.title, row.body,
        materialPayload(row.rawPayloadJson), row.receivedAt, row.opId, row.deliveryId, row.serverId,
        row.eventId, row.thingId, row.eventState, row.eventTimeEpoch,
    )

    fun thingLog(row: ThingChangeLogEntity) = appendRow(
        "thing_change_logs", row.id, row.entityId, row.channel, row.title, row.body,
        materialPayload(row.rawPayloadJson), row.receivedAt, row.opId, row.deliveryId, row.serverId,
        row.eventId, row.thingId, row.eventState, row.eventTimeEpoch, row.observedTimeEpoch,
    )

    fun subEvent(row: ThingSubEventEntity) = appendRow(
        "thing_sub_events", row.id, row.activated, row.entityId, row.channel, row.title,
        row.body, materialPayload(row.rawPayloadJson), row.receivedAt, row.opId, row.deliveryId,
        row.serverId, row.eventId, row.thingId, row.eventState, row.eventTimeEpoch,
    )

    fun eventHead(row: TopLevelEventHeadEntity) = appendRow(
        "top_level_event_heads", row.eventId, row.sourceId, row.messageId, row.entityId,
        row.channel, row.title, row.body, materialPayload(row.rawPayloadJson), row.receivedAt, row.updatedAt,
        row.serverId, row.thingId, row.eventState, row.eventTimeEpoch,
    )

    fun thingHead(row: ThingHeadEntity) = appendRow(
        "thing_heads", row.thingId, row.sourceId, row.messageId, row.entityId,
        row.channel, row.title, row.body, materialPayload(row.rawPayloadJson), row.receivedAt, row.updatedAt,
        row.serverId, row.eventId, row.eventState, row.eventTimeEpoch, row.observedTimeEpoch,
    )

    private fun materialPayload(raw: String): String {
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return "raw:$raw"
        payload.remove(MessageImageStore.KEY_IMAGE_LOCAL_PATH)
        payload.remove(MessageImageStore.KEY_IMAGE_THUMBNAIL_LOCAL_PATH)
        return "json:${canonicalJson(payload)}"
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            "${JSONObject.quote(it)}:${canonicalJson(value.get(it))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") {
            canonicalJson(value.get(it))
        }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> error("Unsupported JSON confirmation value")
    }
}
