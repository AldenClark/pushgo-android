package io.ethan.pushgo.data.model

import io.ethan.pushgo.util.JsonCompat
import io.ethan.pushgo.util.PayloadTimeNormalizer
import io.ethan.pushgo.util.UrlValidators
import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable
enum class MessageStatus {
    NORMAL,
    MISSING,
    PARTIALLY_DECRYPTED,
    DECRYPTED,
}

@Serializable
enum class DecryptionState {
    NOT_CONFIGURED,
    ALG_MISMATCH,
    DECRYPT_OK,
    DECRYPT_FAILED,
}

@Serializable
enum class MessageSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
    ;

    companion object {
        fun fromRaw(raw: String?): MessageSeverity? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return when (normalized) {
                "low" -> LOW
                "medium", "normal" -> MEDIUM
                "high" -> HIGH
                "critical" -> CRITICAL
                else -> null
            }
        }
    }
}

@Serializable
data class PushMessage(
    val id: String,
    val messageId: String?,
    val title: String,
    val body: String,
    val channel: String?,
    val url: String?,
    val isRead: Boolean,
    @Serializable(with = InstantSerializer::class)
    val receivedAt: Instant,
    val rawPayloadJson: String,
    val status: MessageStatus,
    val decryptionState: DecryptionState?,
    val notificationId: String?,
    val serverId: String?,
    val bodyPreview: String? = null,
) {
    private val payloadObject: Map<String, Any?>? by lazy(LazyThreadSafetyMode.NONE) {
        JsonCompat.parseObject(rawPayloadJson)
    }

    private val metadataCache: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) {
        decodeMetadata(payloadObject)
    }

    private val tagsCache: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        decodeTags(payloadObject)
    }

    val opId: String?
        get() = rawPayloadString("op_id")

    val deliveryId: String?
        get() = rawPayloadString("delivery_id")

    val entityType: String
        get() {
            return normalizedEntityType(rawPayloadString("entity_type")).orEmpty()
        }

    val entityId: String?
        get() = rawPayloadString("entity_id")

    val eventId: String?
        get() = rawPayloadString("event_id")

    val thingId: String?
        get() = rawPayloadString("thing_id")

    val eventState: String?
        get() = rawPayloadString("event_state")

    val occurredAtEpoch: Long?
        get() {
            val raw = rawPayloadString("occurred_at") ?: return null
            return PayloadTimeNormalizer.epochMillis(raw)?.takeIf { it > 0L }
        }

    val severity: MessageSeverity?
        get() = MessageSeverity.fromRaw(rawPayloadString("severity"))

    val metadata: Map<String, String>
        get() = metadataCache

    val tags: List<String>
        get() = tagsCache

    val imageUrls: List<String>
        get() = decodeImageUrls(payloadObject)

    val imageUrl: String?
        get() = imageUrls.firstOrNull()

    private fun decodeMetadata(payload: Map<String, Any?>?): Map<String, String> {
        val raw = (payload?.get("metadata") as? String)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        val objectValue = JsonCompat.parseObject(raw) ?: return emptyMap()
        val map = linkedMapOf<String, String>()
        for (rawKey in objectValue.keys) {
            val key = rawKey.trim()
            if (key.isEmpty()) continue
            val value = metadataScalarText(objectValue[rawKey]) ?: continue
            map[key] = value
        }
        return map
    }

    private fun decodeTags(payload: Map<String, Any?>?): List<String> {
        val raw = (payload?.get("tags") as? String)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val arrayValue = JsonCompat.parseArray(raw) ?: return emptyList()
        val tags = linkedSetOf<String>()
        for (entry in arrayValue) {
            val value = entry?.toString()?.trim().orEmpty()
            if (value.isNotEmpty()) {
                tags += value
            }
        }
        return tags.toList()
    }

    private fun rawPayloadString(key: String): String? {
        val value = (payloadObject?.get(key) as? String)?.trim().orEmpty()
        return value.takeIf { it.isNotEmpty() }
    }

    private fun decodeImageUrls(payload: Map<String, Any?>?): List<String> {
        if (payload == null) return emptyList()
        val urls = linkedSetOf<String>()
        val rawImages = payload["images"]
        when (rawImages) {
            is String -> {
                val trimmed = rawImages.trim()
                if (trimmed.isNotEmpty()) {
                    val parsed = JsonCompat.parseArray(trimmed)
                    if (parsed != null) {
                        for (entry in parsed) {
                            UrlValidators.normalizeHttpsUrl(entry?.toString())?.let { urls += it }
                        }
                    } else {
                        UrlValidators.normalizeHttpsUrl(trimmed)?.let { urls += it }
                    }
                }
            }
        }
        return urls.toList()
    }

    private fun metadataScalarText(raw: Any?): String? {
        return when (raw) {
            is String -> raw.trim().takeIf { it.isNotEmpty() }
            is Number -> raw.toString()
            is Boolean -> if (raw) "true" else "false"
            else -> null
        }
    }

    private fun normalizedEntityType(raw: String?): String? {
        return when (raw?.trim()?.lowercase()) {
            "message" -> "message"
            "event" -> "event"
            "thing" -> "thing"
            else -> null
        }
    }
}
