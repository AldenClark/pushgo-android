package io.ethan.pushgo.testing

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.random.Random

enum class RuntimeFixtureKind {
    MESSAGE,
    EVENT,
    THING,
    TASK_MESSAGE,
    INVALID,
}

enum class RuntimeDeliveryChannel {
    FCM,
    PRIVATE,
}

enum class RuntimeActiveChannel {
    FCM,
    PRIVATE,
}

enum class RuntimeChannelEventType {
    INITIAL_DEFAULT_FCM,
    SWITCH_REQUESTED,
    SWITCH_SUCCEEDED,
    SWITCH_FAILED,
    FCM_TOKEN_MISSING,
    FCM_TOKEN_REFRESHED,
    FCM_TOKEN_INVALIDATED,
    PRIVATE_DISCONNECTED,
    PRIVATE_RECONNECTED,
    PRIVATE_SESSION_RESUMED,
    PRIVATE_SESSION_RESUME_FAILED,
    MESSAGE_ARRIVED,
    ACK_SUCCEEDED,
    ACK_FAILED,
    ACK_RETRIED,
}

data class RuntimeFixtureRecord(
    val index: Int,
    val kind: RuntimeFixtureKind,
    val canonicalId: String,
    val channelId: String,
    val sentAtEpochMillis: Long,
    val payload: Map<String, String>,
) {
    fun fcmPayload(): RuntimeInboundPayload {
        return RuntimeInboundPayload(
            deliveryChannel = RuntimeDeliveryChannel.FCM,
            transportMessageId = "fcm-$canonicalId",
            data = payload + mapOf("provider" to "fcm"),
        )
    }

    fun privatePayload(): RuntimeInboundPayload {
        return RuntimeInboundPayload(
            deliveryChannel = RuntimeDeliveryChannel.PRIVATE,
            transportMessageId = "private-$canonicalId",
            data = payload + mapOf("provider" to "private"),
        )
    }
}

data class RuntimeInboundPayload(
    val deliveryChannel: RuntimeDeliveryChannel,
    val transportMessageId: String,
    val data: Map<String, String>,
)

data class RuntimeFixtureDataset(
    val seed: Long,
    val size: Int,
    val records: List<RuntimeFixtureRecord>,
)

data class RuntimeChannelEvent(
    val type: RuntimeChannelEventType,
    val step: Int,
    val activeBefore: RuntimeActiveChannel,
    val activeAfter: RuntimeActiveChannel,
    val deliveryChannel: RuntimeDeliveryChannel? = null,
    val messageId: String? = null,
    val deliveryId: String? = null,
    val token: String? = null,
    val sessionId: String? = null,
    val resumeToken: String? = null,
    val ackId: String? = null,
    val accepted: Boolean? = null,
    val detail: String? = null,
)

data class RuntimeChannelScenario(
    val seed: Long,
    val events: List<RuntimeChannelEvent>,
) {
    val finalActiveChannel: RuntimeActiveChannel
        get() = events.lastOrNull()?.activeAfter ?: RuntimeActiveChannel.FCM

    fun acceptedCanonicalMessageIds(): Set<String> {
        return events
            .asSequence()
            .filter { it.type == RuntimeChannelEventType.MESSAGE_ARRIVED && it.accepted == true }
            .mapNotNull { it.messageId }
            .toSet()
    }
}

object RuntimeFixtureSizes {
    val supported = listOf(0, 1, 10, 100, 1_000, 10_000, 100_000)

    fun requireSupported(size: Int) {
        require(size in supported) {
            "Unsupported runtime fixture size: $size. Supported sizes: ${supported.joinToString()}"
        }
    }
}

class RuntimeFixtureGenerator(private val seed: Long) {
    fun generateDataset(size: Int): RuntimeFixtureDataset {
        return RuntimeFixtureDataset(
            seed = seed,
            size = size,
            records = generateRecords(size).toList(),
        )
    }

    fun generateRecords(size: Int): Sequence<RuntimeFixtureRecord> {
        RuntimeFixtureSizes.requireSupported(size)
        return (0 until size).asSequence().map { index ->
            buildRecord(index)
        }
    }

    fun boundaryPayloads(): List<RuntimeFixtureRecord> {
        return listOf(
            messageRecord(
                index = 0,
                variant = 0,
                title = "",
                body = "",
                messageId = "boundary-empty-message",
                sentAtEpochMillis = BASE_TIME_MS,
            ),
            messageRecord(
                index = 1,
                variant = 1,
                title = "长标题 ".repeat(96).trim(),
                body = largeMarkdownBody(1),
                messageId = "boundary-long-markdown",
                sentAtEpochMillis = BASE_TIME_MS + 1,
            ),
            messageRecord(
                index = 2,
                variant = 2,
                title = "Unicode mixed 中文 日本語 English emoji 🚀 rtl",
                body = "中文正文 / 日本語本文 / English body / مرحبا / שלום / emoji ✅",
                messageId = "boundary-unicode-rtl",
                sentAtEpochMillis = BASE_TIME_MS + 2,
            ),
            invalidRecord("invalid-missing-message-id", mapOf("entity_type" to "message", "title" to "missing id")),
            invalidRecord("invalid-unknown-type", mapOf("entity_type" to "unknown", "message_id" to "unknown-1")),
            invalidRecord(
                "invalid-illegal-fields",
                mapOf(
                    "entity_type" to "event",
                    "event_id" to "",
                    "entity_id" to "",
                    "unknown_future_field" to JSONObject().put("nested", true).toString(),
                ),
            ),
        )
    }

    fun duplicateAndOutOfOrderPayloads(): List<RuntimeFixtureRecord> {
        val newer = messageRecord(
            index = 10,
            variant = 0,
            title = "newer duplicate",
            body = "new message body",
            messageId = "duplicate-message-1",
            sentAtEpochMillis = BASE_TIME_MS + 10_000,
        )
        val olderLate = messageRecord(
            index = 11,
            variant = 1,
            title = "older late duplicate",
            body = "older message body",
            messageId = "duplicate-message-1",
            sentAtEpochMillis = BASE_TIME_MS + 1_000,
        )
        return listOf(
            messageRecord(12, 2, "same ts a", "same timestamp", "same-ts-a", BASE_TIME_MS + 5_000),
            messageRecord(13, 3, "future", "future time", "future-message", BASE_TIME_MS + 365L * 24 * 60 * 60 * 1_000),
            messageRecord(14, 4, "ancient", "very old time", "ancient-message", BASE_TIME_MS - 20L * 365 * 24 * 60 * 60 * 1_000),
            newer,
            olderLate,
        )
    }

    fun generateChannelSwitchScenario(): RuntimeChannelScenario {
        val events = mutableListOf<RuntimeChannelEvent>()
        var active = RuntimeActiveChannel.FCM

        fun add(
            type: RuntimeChannelEventType,
            after: RuntimeActiveChannel = active,
            deliveryChannel: RuntimeDeliveryChannel? = null,
            messageId: String? = null,
            deliveryId: String? = null,
            token: String? = null,
            sessionId: String? = null,
            resumeToken: String? = null,
            ackId: String? = null,
            accepted: Boolean? = null,
            detail: String? = null,
        ) {
            events += RuntimeChannelEvent(
                type = type,
                step = events.size,
                activeBefore = active,
                activeAfter = after,
                deliveryChannel = deliveryChannel,
                messageId = messageId,
                deliveryId = deliveryId,
                token = token,
                sessionId = sessionId,
                resumeToken = resumeToken,
                ackId = ackId,
                accepted = accepted,
                detail = detail,
            )
            active = after
        }

        add(RuntimeChannelEventType.INITIAL_DEFAULT_FCM)
        add(RuntimeChannelEventType.FCM_TOKEN_REFRESHED, token = "fcm-token-$seed")
        add(
            RuntimeChannelEventType.MESSAGE_ARRIVED,
            deliveryChannel = RuntimeDeliveryChannel.FCM,
            messageId = "startup-fcm-message",
            deliveryId = "delivery-fcm-startup",
            accepted = true,
        )
        add(RuntimeChannelEventType.SWITCH_REQUESTED, detail = "FCM -> private")
        add(
            RuntimeChannelEventType.MESSAGE_ARRIVED,
            deliveryChannel = RuntimeDeliveryChannel.FCM,
            messageId = "switch-inflight-fcm",
            deliveryId = "delivery-fcm-inflight",
            accepted = true,
        )
        add(RuntimeChannelEventType.PRIVATE_RECONNECTED, sessionId = "private-session-$seed")
        add(
            RuntimeChannelEventType.MESSAGE_ARRIVED,
            deliveryChannel = RuntimeDeliveryChannel.PRIVATE,
            messageId = "switch-inflight-private",
            deliveryId = "delivery-private-inflight",
            accepted = true,
        )
        add(RuntimeChannelEventType.SWITCH_SUCCEEDED, after = RuntimeActiveChannel.PRIVATE, detail = "private active")
        add(
            RuntimeChannelEventType.MESSAGE_ARRIVED,
            deliveryChannel = RuntimeDeliveryChannel.FCM,
            messageId = "old-channel-late",
            deliveryId = "delivery-old-channel-late",
            accepted = false,
            detail = "old FCM route after private activation",
        )
        add(
            RuntimeChannelEventType.MESSAGE_ARRIVED,
            deliveryChannel = RuntimeDeliveryChannel.FCM,
            messageId = "dual-delivery-1",
            deliveryId = "delivery-dual-fcm",
            accepted = true,
        )
        add(
            RuntimeChannelEventType.MESSAGE_ARRIVED,
            deliveryChannel = RuntimeDeliveryChannel.PRIVATE,
            messageId = "dual-delivery-1",
            deliveryId = "delivery-dual-private",
            accepted = false,
            detail = "same canonical message id already accepted",
        )
        add(RuntimeChannelEventType.ACK_FAILED, ackId = "delivery-private-inflight")
        add(RuntimeChannelEventType.ACK_RETRIED, ackId = "delivery-private-inflight")
        add(RuntimeChannelEventType.ACK_SUCCEEDED, ackId = "delivery-private-inflight")
        add(RuntimeChannelEventType.PRIVATE_DISCONNECTED, sessionId = "private-session-$seed")
        add(
            RuntimeChannelEventType.PRIVATE_SESSION_RESUME_FAILED,
            sessionId = "private-session-$seed",
            resumeToken = "resume-$seed-stale",
            detail = "resume token rejected by gateway",
        )
        add(RuntimeChannelEventType.PRIVATE_RECONNECTED, sessionId = "private-session-$seed")
        add(
            RuntimeChannelEventType.PRIVATE_SESSION_RESUMED,
            sessionId = "private-session-$seed",
            resumeToken = "resume-$seed-1",
        )
        add(RuntimeChannelEventType.SWITCH_REQUESTED, detail = "private -> FCM")
        add(RuntimeChannelEventType.FCM_TOKEN_MISSING)
        add(RuntimeChannelEventType.FCM_TOKEN_REFRESHED, token = "fcm-token-$seed-refreshed")
        add(RuntimeChannelEventType.SWITCH_SUCCEEDED, after = RuntimeActiveChannel.FCM, detail = "FCM active")
        add(
            RuntimeChannelEventType.MESSAGE_ARRIVED,
            deliveryChannel = RuntimeDeliveryChannel.PRIVATE,
            messageId = "private-late-after-fcm",
            deliveryId = "delivery-private-late-after-fcm",
            accepted = false,
        )
        add(RuntimeChannelEventType.FCM_TOKEN_INVALIDATED)
        add(RuntimeChannelEventType.SWITCH_REQUESTED, detail = "FCM -> private failure")
        add(RuntimeChannelEventType.SWITCH_FAILED, after = RuntimeActiveChannel.FCM, detail = "private auth failed")

        return RuntimeChannelScenario(seed = seed, events = events)
    }

    fun scenarioMessagePayload(
        messageId: String,
        deliveryId: String,
        deliveryChannel: RuntimeDeliveryChannel,
        channelId: String = "channel-switch",
        sentAtEpochMillis: Long = BASE_TIME_MS,
    ): RuntimeInboundPayload {
        val payload = linkedMapOf(
            "entity_type" to "message",
            "entity_id" to messageId,
            "message_id" to messageId,
            "channel_id" to channelId,
            "delivery_id" to deliveryId,
            "op_id" to "op-$deliveryId",
            "title" to "Scenario $messageId",
            "body" to "Scenario body for $messageId",
            "sent_at" to sentAtEpochMillis.toString(),
            "occurred_at" to sentAtEpochMillis.toString(),
            "severity" to "normal",
            "tags" to JSONArray().put("scenario").put(channelId).toString(),
            "metadata" to JSONObject().put("seed", seed).put("source", "channel-switch").toString(),
            "provider" to if (deliveryChannel == RuntimeDeliveryChannel.FCM) "fcm" else "private",
        )
        return RuntimeInboundPayload(
            deliveryChannel = deliveryChannel,
            transportMessageId = deliveryId,
            data = payload,
        )
    }

    fun canonicalizeFirstArrival(records: Iterable<RuntimeFixtureRecord>): List<RuntimeFixtureRecord> {
        val byCanonicalId = LinkedHashMap<String, RuntimeFixtureRecord>()
        records.forEach { record ->
            byCanonicalId.putIfAbsent(record.canonicalId, record)
        }
        return byCanonicalId.values.sortedWith(
            compareByDescending<RuntimeFixtureRecord> { it.sentAtEpochMillis }
                .thenByDescending { it.canonicalId },
        )
    }

    fun sortNewestFirst(records: Iterable<RuntimeFixtureRecord>): List<RuntimeFixtureRecord> {
        return records.sortedWith(
            compareByDescending<RuntimeFixtureRecord> { it.sentAtEpochMillis }
                .thenByDescending { it.canonicalId },
        )
    }

    private fun buildRecord(index: Int): RuntimeFixtureRecord {
        val random = Random(seed xor (index.toLong() * 0x9E3779B97F4A7C15UL.toLong()))
        val variant = random.nextInt(0, 32)
        return when (index % 4) {
            0 -> messageRecord(
                index = index,
                variant = variant,
                title = titleFor(index, variant),
                body = bodyFor(index, variant),
                messageId = "msg-${seedText()}-$index",
                sentAtEpochMillis = timestampFor(index, variant),
            )
            1 -> eventRecord(index, variant)
            2 -> thingRecord(index, variant)
            else -> taskMessageRecord(index, variant)
        }
    }

    private fun messageRecord(
        index: Int,
        variant: Int,
        title: String,
        body: String,
        messageId: String,
        sentAtEpochMillis: Long,
    ): RuntimeFixtureRecord {
        val channelId = channelFor(index)
        val payload = linkedMapOf(
            "entity_type" to "message",
            "entity_id" to messageId,
            "message_id" to messageId,
            "channel_id" to channelId,
            "delivery_id" to "delivery-$messageId",
            "op_id" to "op-$messageId",
            "title" to title,
            "body" to body,
            "sent_at" to sentAtEpochMillis.toString(),
            "occurred_at" to (sentAtEpochMillis - (variant % 7) * 1_000L).toString(),
            "severity" to severityFor(variant),
            "tags" to tagsJson(index, variant),
            "metadata" to metadataJson(index, variant),
        )
        if (variant % 9 == 0) {
            payload["images"] = JSONArray()
                .put("https://cdn.example.com/runtime/$messageId.png")
                .put("https://cdn.example.com/runtime/$messageId.webp")
                .toString()
        }
        if (variant % 11 == 0) {
            payload["unknown_future_field"] = "future-$index"
        }
        return RuntimeFixtureRecord(index, RuntimeFixtureKind.MESSAGE, messageId, channelId, sentAtEpochMillis, payload)
    }

    private fun eventRecord(index: Int, variant: Int): RuntimeFixtureRecord {
        val eventId = "evt-${seedText()}-$index"
        val channelId = channelFor(index)
        val thingId = if (variant % 3 == 0) "thing-${seedText()}-${index / 4}" else null
        val sentAt = timestampFor(index, variant)
        val payload = linkedMapOf(
            "entity_type" to "event",
            "entity_id" to eventId,
            "event_id" to eventId,
            "channel_id" to channelId,
            "delivery_id" to "delivery-$eventId",
            "op_id" to "op-$eventId",
            "title" to titleFor(index, variant),
            "body" to bodyFor(index, variant),
            "event_state" to eventStateFor(variant),
            "event_time" to sentAt.toString(),
            "sent_at" to sentAt.toString(),
            "severity" to severityFor(variant),
            "tags" to tagsJson(index, variant),
            "metadata" to metadataJson(index, variant),
        )
        if (thingId != null) {
            payload["thing_id"] = thingId
        }
        return RuntimeFixtureRecord(index, RuntimeFixtureKind.EVENT, eventId, channelId, sentAt, payload)
    }

    private fun thingRecord(index: Int, variant: Int): RuntimeFixtureRecord {
        val thingId = "thing-${seedText()}-$index"
        val channelId = channelFor(index)
        val sentAt = timestampFor(index, variant)
        val payload = linkedMapOf(
            "entity_type" to "thing",
            "entity_id" to thingId,
            "thing_id" to thingId,
            "channel_id" to channelId,
            "delivery_id" to "delivery-$thingId",
            "op_id" to "op-$thingId",
            "title" to titleFor(index, variant),
            "body" to bodyFor(index, variant),
            "observed_at" to sentAt.toString(),
            "sent_at" to sentAt.toString(),
            "attrs" to JSONObject()
                .put("name", "Object $index")
                .put("state", if (variant % 5 == 0) "degraded" else "active")
                .put("temperature", 20 + (variant % 10))
                .toString(),
            "tags" to tagsJson(index, variant),
            "metadata" to metadataJson(index, variant),
        )
        return RuntimeFixtureRecord(index, RuntimeFixtureKind.THING, thingId, channelId, sentAt, payload)
    }

    private fun taskMessageRecord(index: Int, variant: Int): RuntimeFixtureRecord {
        val messageId = "task-msg-${seedText()}-$index"
        val taskId = "task-${seedText()}-${index / 4}"
        val sentAt = timestampFor(index, variant)
        val record = messageRecord(
            index = index,
            variant = variant,
            title = "Task $taskId ${taskStateFor(variant)}",
            body = "Task-like message for $taskId\n\n${bodyFor(index, variant)}",
            messageId = messageId,
            sentAtEpochMillis = sentAt,
        )
        val metadata = JSONObject(record.payload["metadata"].orEmpty())
            .put("task_id", taskId)
            .put("task_state", taskStateFor(variant))
            .put("assignee", "owner-${variant % 13}")
        val payload = record.payload + mapOf(
            "tags" to JSONArray(record.payload["tags"]).put("task").toString(),
            "metadata" to metadata.toString(),
            "task_id" to taskId,
            "task_state" to taskStateFor(variant),
        )
        return record.copy(kind = RuntimeFixtureKind.TASK_MESSAGE, payload = payload)
    }

    private fun invalidRecord(canonicalId: String, payload: Map<String, String>): RuntimeFixtureRecord {
        return RuntimeFixtureRecord(
            index = -1,
            kind = RuntimeFixtureKind.INVALID,
            canonicalId = canonicalId,
            channelId = "invalid-channel",
            sentAtEpochMillis = BASE_TIME_MS,
            payload = payload,
        )
    }

    private fun titleFor(index: Int, variant: Int): String {
        return when {
            variant % 17 == 0 -> ""
            variant % 13 == 0 -> "长标题 ".repeat(24).trim()
            variant % 7 == 0 -> "RTL مرحبا שלום item $index"
            variant % 5 == 0 -> "Unicode 中文 日本語 emoji 🚀 item $index"
            else -> "Runtime item $index"
        }
    }

    private fun bodyFor(index: Int, variant: Int): String {
        return when {
            variant % 19 == 0 -> ""
            variant % 11 == 0 -> largeMarkdownBody(index)
            variant % 7 == 0 -> "中文正文 / 日本語本文 / English body / مرحبا / שלום / emoji ✅ $index"
            else -> "Runtime body $index state=$variant"
        }
    }

    private fun largeMarkdownBody(index: Int): String {
        return buildString {
            append("# Runtime Markdown $index\n\n")
            repeat(64) { line ->
                append("- item ")
                append(line)
                append(" [link](https://example.com/runtime/")
                append(index)
                append('/')
                append(line)
                append(") `code` 中文 日本語\n")
            }
        }
    }

    private fun timestampFor(index: Int, variant: Int): Long {
        val base = BASE_TIME_MS + index * 1_000L
        return when {
            variant % 23 == 0 -> base + 365L * 24 * 60 * 60 * 1_000
            variant % 29 == 0 -> base - 20L * 365 * 24 * 60 * 60 * 1_000
            variant % 5 == 0 -> BASE_TIME_MS
            variant % 3 == 0 -> base - 60_000L
            else -> base
        }
    }

    private fun channelFor(index: Int): String = "channel-${index % 8}"

    private fun severityFor(variant: Int): String {
        return when (variant % 4) {
            0 -> "low"
            1 -> "normal"
            2 -> "high"
            else -> "critical"
        }
    }

    private fun eventStateFor(variant: Int): String {
        return when (variant % 3) {
            0 -> "OPEN"
            1 -> "ONGOING"
            else -> "CLOSED"
        }
    }

    private fun taskStateFor(variant: Int): String {
        return when (variant % 4) {
            0 -> "todo"
            1 -> "doing"
            2 -> "blocked"
            else -> "done"
        }
    }

    private fun tagsJson(index: Int, variant: Int): String {
        return JSONArray()
            .put("tag-${index % 5}")
            .put("channel-${index % 8}")
            .put(if (variant % 2 == 0) "ops" else "product")
            .toString()
    }

    private fun metadataJson(index: Int, variant: Int): String {
        return JSONObject()
            .put("seed", seed)
            .put("index", index)
            .put("variant", variant)
            .put("locale", if (variant % 3 == 0) "zh-CN" else "en-US")
            .toString()
    }

    private fun seedText(): String = seed.toULong().toString(16)

    companion object {
        val BASE_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val BASE_TIME_MS: Long = BASE_TIME.toEpochMilli()
    }
}
