package io.ethan.pushgo.notifications

import android.content.Context
import io.ethan.pushgo.R
import io.ethan.pushgo.data.IncomingEntityRecord
import io.ethan.pushgo.data.ParsedEntityProfile
import io.ethan.pushgo.data.parseEventProfile
import io.ethan.pushgo.data.parseThingProfile
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.markdown.MessagePreviewExtractor
import io.ethan.pushgo.util.normalizeExternalImageUrl
import io.ethan.pushgo.util.normalizeExternalOpenUrl
import io.ethan.pushgo.util.rewriteVisibleUrlsInText
import io.ethan.pushgo.util.JsonCompat
import java.time.Instant
import java.util.UUID

object NotificationIngressParser {
    private const val GATEWAY_PLACEHOLDER_TITLE = "PushGo"
    private const val GATEWAY_PLACEHOLDER_MESSAGE_BODY = "You received a new message."

    data class NotificationTextLocalizer(
        val eventTitleFallback: (String) -> String,
        val thingTitleFallback: (String) -> String,
        val eventBodyOpen: String,
        val eventBodyOngoing: String,
        val eventBodyClosed: String,
        val updatedBody: String,
        val thingAttributeUpdateTemplate: (String) -> String,
        val thingAttributePairTemplate: (String, String) -> String,
    ) {
        companion object {
            fun defaultEnglish(): NotificationTextLocalizer = NotificationTextLocalizer(
                eventTitleFallback = { id -> "Event $id" },
                thingTitleFallback = { id -> "Object $id" },
                eventBodyOpen = "Opened",
                eventBodyOngoing = "Ongoing",
                eventBodyClosed = "Closed",
                updatedBody = "Updated",
                thingAttributeUpdateTemplate = { details -> "Attribute update || $details" },
                thingAttributePairTemplate = { name, value -> "$name: $value" },
            )

            fun fromContext(context: Context): NotificationTextLocalizer = NotificationTextLocalizer(
                eventTitleFallback = { id -> context.getString(R.string.entity_title_event_fallback, id) },
                thingTitleFallback = { id -> context.getString(R.string.entity_title_thing_fallback, id) },
                eventBodyOpen = context.getString(R.string.entity_body_event_open),
                eventBodyOngoing = context.getString(R.string.entity_body_event_ongoing),
                eventBodyClosed = context.getString(R.string.entity_body_event_closed),
                updatedBody = context.getString(R.string.entity_body_updated),
                thingAttributeUpdateTemplate = { details ->
                    context.getString(R.string.entity_body_thing_attribute_update_template, details)
                },
                thingAttributePairTemplate = { name, value ->
                    context.getString(R.string.entity_body_thing_attribute_pair_template, name, value)
                },
            )
        }
    }

    data class EntityNotificationText(
        val title: String,
        val body: String,
    )

    private data class ThingAttributeSnapshot(
        val pairs: List<Pair<String, String>>,
        val thingName: String?,
    )

    fun parse(
        data: Map<String, String>,
        transportMessageId: String?,
        keyBytes: ByteArray?,
        now: Instant = Instant.now(),
        textLocalizer: NotificationTextLocalizer = NotificationTextLocalizer.defaultEnglish(),
    ): InboundPersistenceRequest? {
        val sanitized = data.toMutableMap().apply {
            for (key in listOf(
                "icon",
                "icon_url",
                "iconUrl",
                "ring_mode",
                "ringMode",
                "volume",
                "sound",
                "notification_count",
                "body_render_payload",
                "body_render_is_markdown",
                "body_render_source",
            )) {
                remove(key)
            }
        }
        sanitizeIngressPayload(sanitized)
        val rawTitle = sanitized["title"] ?: ""
        val rawBody = sanitized["body"] ?: ""
        val channel = sanitized["channel_id"]?.trim()?.takeIf { it.isNotEmpty() }
        val url = sanitized["url"]?.let(::normalizeExternalOpenUrl)
        val serverId = sanitized["server_id"]
        val deliveryId = sanitized["delivery_id"]?.trim()?.takeIf { it.isNotEmpty() }
        val messageId = extractMessageId(sanitized)
        val decryptResult = NotificationDecryptor.decryptIfNeeded(sanitized, rawTitle, rawBody, keyBytes)
        val normalizedDecryptResult = decryptResult.copy(
            body = rewriteVisibleUrlsInText(decryptResult.body),
            images = sanitizeImageCandidates(decryptResult.images),
        )
        val level = normalizeLevel(sanitized["severity"])
        val entityType = normalizeEntityType(sanitized["entity_type"]) ?: return null
        val sanitizedTitleBody = sanitizeGatewayPlaceholderText(
            entityType = entityType,
            title = normalizedDecryptResult.title,
            body = normalizedDecryptResult.body,
        )
        sanitized["title"] = sanitizedTitleBody.first
        sanitized["body"] = sanitizedTitleBody.second
        val sentAt = parseEpochSeconds(sanitized["sent_at"])
        val ttl = parseEpochSeconds(sanitized["ttl"])
        val receivedAt = sentAt?.let { Instant.ofEpochSecond(it) } ?: now
        val isExpired = ttl?.let { now.epochSecond > it } ?: false

        val imageUrls = resolveImageUrls(sanitized, normalizedDecryptResult)

        val rawPayload = linkedMapOf<String, Any?>().apply {
            sanitized.forEach { (key, value) -> put(key, value) }
            if (!sanitized.containsKey("title")) put("title", rawTitle)
            if (!sanitized.containsKey("body")) put("body", rawBody)
            level?.let { put("severity", it) }
            if (imageUrls.isNotEmpty()) {
                put("images", JsonCompat.stringify(imageUrls))
            }
            normalizedDecryptResult.decryptionState?.let { put("decryption_state", toWireDecryptionState(it)) }
            transportMessageId?.let { put("_fcm_message_id", it) }
        }.let(JsonCompat::stringify)

        if (entityType == "message") {
            if (messageId == null) {
                return null
            }
            if (!hasMessageContent(sanitized, sanitizedTitleBody.first, sanitizedTitleBody.second, imageUrls)) {
                return null
            }
            val pushMessage = PushMessage(
                id = UUID.randomUUID().toString(),
                messageId = messageId,
                title = sanitizedTitleBody.first,
                body = sanitizedTitleBody.second,
                channel = channel,
                url = url,
                isRead = false,
                receivedAt = receivedAt,
                rawPayloadJson = rawPayload,
                status = MessageStatus.NORMAL,
                decryptionState = normalizedDecryptResult.decryptionState,
                notificationId = transportMessageId,
                serverId = serverId,
                bodyPreview = MessagePreviewExtractor.listPreview(sanitizedTitleBody.second),
            )
            return InboundPersistenceRequest.Message(
                message = pushMessage,
                level = level,
                imageUrl = imageUrls.firstOrNull(),
                shouldNotify = shouldNotifyEntity(
                    entityType = entityType,
                    level = level,
                    data = sanitized,
                    isExpired = isExpired,
                ),
            )
        }

        val eventId = sanitized["event_id"]?.trim()?.takeIf { it.isNotEmpty() }
        val thingId = sanitized["thing_id"]?.trim()?.takeIf { it.isNotEmpty() }
        val entityId = sanitized["entity_id"]?.trim()?.takeIf { it.isNotEmpty() }

        if (entityId.isNullOrBlank()) {
            return null
        }
        if (entityType == "event" && eventId.isNullOrBlank()) {
            return null
        }
        if (entityType == "thing" && thingId.isNullOrBlank()) {
            return null
        }

        val profile = when (entityType) {
            "event" -> parseEventProfile(sanitized["event_profile_json"])
            "thing" -> parseThingProfile(sanitized["thing_profile_json"])
            else -> null
        }

        val explicitTitle = sanitizedTitleBody.first
        val explicitBody = sanitizedTitleBody.second
        val notificationText = resolveEntityNotificationText(
            entityType = entityType,
            entityId = entityId,
            eventId = eventId,
            thingId = thingId,
            payload = sanitized,
            profile = profile,
            explicitTitle = explicitTitle,
            explicitBody = explicitBody,
            textLocalizer = textLocalizer,
        )
        val title = notificationText.title
        val body = notificationText.body

        val record = IncomingEntityRecord(
            entityType = entityType,
            entityId = entityId,
            channel = channel,
            title = title,
            body = body,
            rawPayloadJson = rawPayload,
            receivedAt = receivedAt,
            opId = sanitized["op_id"]?.trim()?.takeIf { it.isNotEmpty() },
            deliveryId = deliveryId,
            serverId = serverId,
            eventId = eventId,
            thingId = thingId,
            eventState = sanitized["event_state"]?.trim()?.takeIf { it.isNotEmpty() },
            eventTimeEpoch = parseEpochMillis(sanitized["event_time"]),
            observedTimeEpoch = parseEpochMillis(sanitized["observed_at"]),
        )

        return InboundPersistenceRequest.Entity(
            record = record,
            level = level,
            notificationTitle = title,
            notificationBody = body,
            shouldNotify = shouldNotifyEntity(
                entityType = entityType,
                level = level,
                data = sanitized,
                isExpired = isExpired,
            ),
            hasExplicitTitle = explicitTitle.trim().isNotEmpty(),
        )
    }

    internal fun resolveEntityNotificationText(
        entityType: String,
        entityId: String,
        eventId: String?,
        thingId: String?,
        payload: Map<String, String>,
        profile: ParsedEntityProfile?,
        explicitTitle: String,
        explicitBody: String,
        textLocalizer: NotificationTextLocalizer,
    ): EntityNotificationText {
        val messageText = profile?.message?.trim()?.takeIf { it.isNotEmpty() }
        val attrsSnapshot = if (entityType == "thing") {
            parseThingAttributeSnapshot(payload["thing_attrs_json"])
        } else {
            null
        }

        val fallbackTitleId = when (entityType) {
            "event" -> eventId ?: entityId
            "thing" -> thingId ?: entityId
            else -> entityId
        }
        val fallbackTitle = when (entityType) {
            "event" -> textLocalizer.eventTitleFallback(fallbackTitleId)
            "thing" -> textLocalizer.thingTitleFallback(fallbackTitleId)
            else -> fallbackTitleId
        }

        val title = explicitTitle.trim().ifEmpty {
            when (entityType) {
                "thing" -> {
                    attrsSnapshot?.thingName
                        ?: profile?.title?.trim()?.takeIf { it.isNotEmpty() }
                        ?: fallbackTitle
                }
                else -> profile?.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
            }
        }

        val body = explicitBody.trim().ifEmpty {
            messageText
                ?: if (entityType == "thing") {
                    buildThingAttributeUpdateBody(attrsSnapshot, textLocalizer)
                        ?: profile?.description?.trim()?.takeIf { it.isNotEmpty() }
                        ?: textLocalizer.updatedBody
                } else {
                    profile?.description?.trim()?.takeIf { it.isNotEmpty() }
                        ?: defaultEventBody(payload["event_state"], textLocalizer)
                }
        }

        return EntityNotificationText(title = title, body = body)
    }

    fun isPrivateWakeupPayload(data: Map<String, String>): Boolean {
        val mode = data["private_mode"]?.trim()?.lowercase()
        if (mode == "wakeup") return true
        val wakeup = data["private_wakeup"]?.trim()?.lowercase()
        return wakeup == "1" || wakeup == "true"
    }

    private fun hasMessageContent(
        data: Map<String, String>,
        title: String,
        body: String,
        imageUrls: List<String>,
    ): Boolean {
        if (title.isNotBlank() || body.isNotBlank()) return true
        if (imageUrls.isNotEmpty()) return true
        return listOf(
            data["ciphertext"],
            data["url"],
            data["images"],
        ).any { !it.isNullOrBlank() }
    }

    private fun sanitizeGatewayPlaceholderText(
        entityType: String,
        title: String,
        body: String,
    ): Pair<String, String> {
        val trimmedTitle = title.trim()
        val trimmedBody = body.trim()
        if (entityType == "message" && isGatewayPlaceholderMessage(trimmedTitle, trimmedBody)) {
            return "" to ""
        }
        if (trimmedBody == gatewayPlaceholderBody(entityType)) {
            return title to ""
        }
        return title to body
    }

    private fun isGatewayPlaceholderMessage(title: String, body: String): Boolean {
        return body == GATEWAY_PLACEHOLDER_MESSAGE_BODY &&
            (title.isEmpty() || title == GATEWAY_PLACEHOLDER_TITLE)
    }

    private fun gatewayPlaceholderBody(entityType: String): String? {
        return when (entityType) {
            "event" -> "Event updated."
            "thing" -> "Object updated."
            "message" -> GATEWAY_PLACEHOLDER_MESSAGE_BODY
            else -> null
        }
    }

    private fun extractMessageId(data: Map<String, String>): String? {
        val value = data["message_id"]?.trim()
        return value?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeLevel(level: String?): String? {
        val normalized = level?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "critical", "high", "normal", "low" -> normalized
            else -> null
        }
    }

    private fun resolveImageUrls(
        data: Map<String, String>,
        decryptResult: NotificationDecryptor.Result,
    ): List<String> {
        val urls = linkedSetOf<String>()
        decryptResult.images.forEach { value ->
            normalizeExternalImageUrl(value)?.let { urls += it }
        }
        val rawImages = data["images"]?.trim().orEmpty()
        if (rawImages.isNotEmpty()) {
            val parsed = runCatching { JsonCompat.parseArray(rawImages) }.getOrNull()
            if (parsed != null) {
                for (entry in parsed) {
                    normalizeExternalImageUrl(entry?.toString().orEmpty())?.let { urls += it }
                }
            } else {
                normalizeExternalImageUrl(rawImages)?.let { urls += it }
            }
        }
        return urls.toList()
    }

    private fun sanitizeIngressPayload(payload: MutableMap<String, String>) {
        sanitizeTextField(payload, "body")
        sanitizeTextField(payload, "message")
        sanitizeTextField(payload, "description")
        sanitizeOpenUrlField(payload, "url")
        sanitizeImageField(payload, "images")
        sanitizeProfileField(payload, key = "event_profile_json", includePrimaryImage = false)
        sanitizeProfileField(payload, key = "thing_profile_json", includePrimaryImage = true)
    }

    private fun sanitizeTextField(payload: MutableMap<String, String>, key: String) {
        val value = payload[key] ?: return
        val rewritten = rewriteVisibleUrlsInText(value)
        payload[key] = rewritten
    }

    private fun sanitizeOpenUrlField(payload: MutableMap<String, String>, key: String) {
        val raw = payload[key]?.trim().orEmpty()
        if (raw.isEmpty()) {
            payload.remove(key)
            return
        }
        val safe = normalizeExternalOpenUrl(raw)
        if (safe == null) {
            payload.remove(key)
        } else {
            payload[key] = safe
        }
    }

    private fun sanitizeImageField(payload: MutableMap<String, String>, key: String) {
        val raw = payload[key]?.trim().orEmpty()
        if (raw.isEmpty()) {
            payload.remove(key)
            return
        }
        val safe = sanitizeImageValue(raw)
        if (safe.isEmpty()) {
            payload.remove(key)
        } else {
            payload[key] = JsonCompat.stringify(safe)
        }
    }

    private fun sanitizeProfileField(
        payload: MutableMap<String, String>,
        key: String,
        includePrimaryImage: Boolean,
    ) {
        val raw = payload[key]?.trim().orEmpty()
        if (raw.isEmpty()) return
        val parsed = JsonCompat.parseObject(raw) ?: return
        val normalized = parsed.toMutableMap()
        listOf("description", "message").forEach { field ->
            val current = normalized[field]?.toString()?.trim().orEmpty()
            if (current.isEmpty()) return@forEach
            normalized[field] = rewriteVisibleUrlsInText(current)
        }
        if (includePrimaryImage) {
            val primary = normalized["primary_image"]?.toString()?.trim().orEmpty()
            if (primary.isEmpty()) {
                normalized.remove("primary_image")
            } else {
                val safe = normalizeExternalImageUrl(primary)
                if (safe == null) {
                    normalized.remove("primary_image")
                } else {
                    normalized["primary_image"] = safe
                }
            }
        }
        val safeImages = sanitizeImageCandidates(
            (normalized["images"] as? List<*>)?.map { it?.toString().orEmpty() } ?: emptyList(),
        )
        if (safeImages.isEmpty()) {
            normalized.remove("images")
        } else {
            normalized["images"] = safeImages
        }
        payload[key] = JsonCompat.stringify(normalized)
    }

    private fun sanitizeImageValue(raw: String): List<String> {
        val parsed = runCatching { JsonCompat.parseArray(raw) }.getOrNull()
        if (parsed != null) {
            return sanitizeImageCandidates(parsed.map { it?.toString().orEmpty() })
        }
        return sanitizeImageCandidates(listOf(raw))
    }

    private fun sanitizeImageCandidates(values: List<String>): List<String> {
        val urls = linkedSetOf<String>()
        values.forEach { raw ->
            normalizeExternalImageUrl(raw)?.let { urls += it }
        }
        return urls.toList()
    }

    private fun normalizeEntityType(raw: String?): String? {
        return when (raw?.trim()?.lowercase()) {
            "message" -> "message"
            "event" -> "event"
            "thing" -> "thing"
            else -> null
        }
    }

    private fun toWireDecryptionState(state: io.ethan.pushgo.data.model.DecryptionState): String {
        return when (state) {
            io.ethan.pushgo.data.model.DecryptionState.NOT_CONFIGURED -> "notConfigured"
            io.ethan.pushgo.data.model.DecryptionState.ALG_MISMATCH -> "algMismatch"
            io.ethan.pushgo.data.model.DecryptionState.DECRYPT_OK -> "decryptOk"
            io.ethan.pushgo.data.model.DecryptionState.DECRYPT_FAILED -> "decryptFailed"
        }
    }

    private fun shouldNotifyEntity(
        entityType: String,
        level: String?,
        data: Map<String, String>,
        isExpired: Boolean,
    ): Boolean {
        if (isExpired) return false
        if (entityType == "event" || entityType == "thing") {
            return level == "critical" || level == "high"
        }
        return true
    }

    private fun parseEpochSeconds(value: String?): Long? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.toLongOrNull()
    }

    private fun parseEpochMillis(value: String?): Long? {
        return parseEpochSeconds(value)?.times(1000)
    }

    private fun parseThingAttributeSnapshot(raw: String?): ThingAttributeSnapshot? {
        val objectValue = JsonCompat.parseObject(raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null)
            ?: return null
        if (objectValue.isEmpty()) return null

        val pairs = mutableListOf<Pair<String, String>>()
        var thingName: String? = null

        val orderedKeys = objectValue.keys.sortedWith(compareBy<String>(
            { attributeSortPriority(it) },
            { it.lowercase() },
        ))
        for (key in orderedKeys) {
            val value = objectValue[key]
            val pair = parseAttributePair(key, value) ?: continue
            val normalizedKey = key.trim().lowercase()
            if (thingName == null &&
                (normalizedKey == "name" || normalizedKey == "thing_name" || key.trim() == "名称")
            ) {
                thingName = pair.second
            }
            pairs += pair
        }

        if (pairs.isEmpty()) return null
        return ThingAttributeSnapshot(
            pairs = pairs.take(6),
            thingName = thingName,
        )
    }

    private fun attributeSortPriority(key: String): Int {
        val normalized = key.trim().lowercase()
        return if (normalized == "name" || normalized == "thing_name" || key.trim() == "名称") {
            0
        } else {
            1
        }
    }

    private fun parseAttributePair(key: String, value: Any?): Pair<String, String>? {
        val fallbackName = key.trim().takeIf { it.isNotEmpty() } ?: return null
        return when (value) {
            null -> null
            is Map<*, *> -> {
                val rawLabel = value["label"]?.toString()?.trim()
                val label = rawLabel?.takeIf { it.isNotEmpty() } ?: fallbackName
                val rawValue = value["value"] ?: return null
                val normalizedValue = normalizeAttributeValue(rawValue) ?: return null
                label to normalizedValue
            }
            else -> {
                val normalizedValue = normalizeAttributeValue(value) ?: return null
                fallbackName to normalizedValue
            }
        }
    }

    private fun normalizeAttributeValue(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value.trim().takeIf { it.isNotEmpty() }
            is Number, is Boolean -> value.toString()
            else -> null
        }
    }

    private fun buildThingAttributeUpdateBody(
        snapshot: ThingAttributeSnapshot?,
        textLocalizer: NotificationTextLocalizer,
    ): String? {
        val details = snapshot?.pairs
            ?.map { (name, value) -> textLocalizer.thingAttributePairTemplate(name, value) }
            ?.filter { it.isNotBlank() }
            ?.joinToString(separator = ", ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return textLocalizer.thingAttributeUpdateTemplate(details)
    }

    private fun defaultEventBody(
        rawState: String?,
        textLocalizer: NotificationTextLocalizer,
    ): String {
        return when (rawState?.trim()?.uppercase()) {
            "OPEN" -> textLocalizer.eventBodyOpen
            "ONGOING" -> textLocalizer.eventBodyOngoing
            "CLOSED" -> textLocalizer.eventBodyClosed
            else -> textLocalizer.updatedBody
        }
    }
}
