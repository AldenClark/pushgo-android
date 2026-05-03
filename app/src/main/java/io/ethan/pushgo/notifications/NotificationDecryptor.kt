package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.util.JsonCompat
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object NotificationDecryptor {
    private val VALID_KEY_LENGTHS = setOf(16, 24, 32)
    private const val MAX_CIPHERTEXT_BYTES = 64 * 1024

    data class Result(
        val title: String,
        val body: String,
        val images: List<String>,
        val url: String?,
        val tagsJson: String?,
        val metadataJson: String?,
        val description: String?,
        val statusText: String?,
        val messageText: String?,
        val attrsJson: String?,
        val startedAt: String?,
        val endedAt: String?,
        val primaryImage: String?,
        val stateText: String?,
        val createdAt: String?,
        val deletedAt: String?,
        val externalIdsJson: String?,
        val locationType: String?,
        val locationValue: String?,
        val locationJson: String?,
        val decryptionState: DecryptionState?,
    ) {
        val image: String?
            get() = images.firstOrNull()
    }

    fun decryptIfNeeded(
        data: Map<String, String>,
        title: String,
        body: String,
        keyBytes: ByteArray?,
    ): Result {
        val ciphertext = data["ciphertext"]
        val likelyEncrypted = !ciphertext.isNullOrBlank()
            || InlineCipherEnvelope.looksLikeCiphertext(title)
            || InlineCipherEnvelope.looksLikeCiphertext(body)

        if (likelyEncrypted && (keyBytes == null || keyBytes.isEmpty())) {
            return Result(
                title = title,
                body = body,
                images = emptyList(),
                url = null,
                tagsJson = null,
                metadataJson = null,
                description = null,
                statusText = null,
                messageText = null,
                attrsJson = null,
                startedAt = null,
                endedAt = null,
                primaryImage = null,
                stateText = null,
                createdAt = null,
                deletedAt = null,
                externalIdsJson = null,
                locationType = null,
                locationValue = null,
                locationJson = null,
                decryptionState = DecryptionState.NOT_CONFIGURED,
            )
        }

        if (keyBytes != null && keyBytes.isNotEmpty() && keyBytes.size !in VALID_KEY_LENGTHS) {
            return Result(
                title = title,
                body = body,
                images = emptyList(),
                url = null,
                tagsJson = null,
                metadataJson = null,
                description = null,
                statusText = null,
                messageText = null,
                attrsJson = null,
                startedAt = null,
                endedAt = null,
                primaryImage = null,
                stateText = null,
                createdAt = null,
                deletedAt = null,
                externalIdsJson = null,
                locationType = null,
                locationValue = null,
                locationJson = null,
                decryptionState = if (likelyEncrypted) DecryptionState.DECRYPT_FAILED else null,
            )
        }
        if (keyBytes == null || keyBytes.isEmpty()) {
            return Result(
                title = title,
                body = body,
                images = emptyList(),
                url = null,
                tagsJson = null,
                metadataJson = null,
                description = null,
                statusText = null,
                messageText = null,
                attrsJson = null,
                startedAt = null,
                endedAt = null,
                primaryImage = null,
                stateText = null,
                createdAt = null,
                deletedAt = null,
                externalIdsJson = null,
                locationType = null,
                locationValue = null,
                locationJson = null,
                decryptionState = null,
            )
        }

        var resolvedTitle = title
        var resolvedBody = body
        var images: List<String> = emptyList()
        var resolvedUrl: String? = null
        var tagsJson: String? = null
        var metadataJson: String? = null
        var description: String? = null
        var statusText: String? = null
        var messageText: String? = null
        var attrsJson: String? = null
        var startedAt: String? = null
        var endedAt: String? = null
        var primaryImage: String? = null
        var stateText: String? = null
        var createdAt: String? = null
        var deletedAt: String? = null
        var externalIdsJson: String? = null
        var locationType: String? = null
        var locationValue: String? = null
        var locationJson: String? = null
        var inlineStatus: DecryptStatus = DecryptStatus.NONE
        var cipherStatus: DecryptStatus = DecryptStatus.NONE
        var payloadOverridesApplied = false

        val inlineTitle = decryptInlineField(title, keyBytes)
        when (inlineTitle.status) {
            DecryptStatus.SUCCESS -> {
                resolvedTitle = inlineTitle.text ?: resolvedTitle
                inlineStatus = DecryptStatus.SUCCESS
            }
            DecryptStatus.FAILURE -> inlineStatus = DecryptStatus.FAILURE
            DecryptStatus.NONE -> Unit
        }

        val inlineBody = decryptInlineField(body, keyBytes)
        when (inlineBody.status) {
            DecryptStatus.SUCCESS -> {
                resolvedBody = inlineBody.text ?: resolvedBody
                inlineStatus = if (inlineStatus == DecryptStatus.FAILURE) inlineStatus else DecryptStatus.SUCCESS
            }
            DecryptStatus.FAILURE -> inlineStatus = DecryptStatus.FAILURE
            DecryptStatus.NONE -> Unit
        }

        if (!ciphertext.isNullOrBlank()) {
            val cipherResult = decryptCiphertextPayload(ciphertext, keyBytes)
            cipherStatus = cipherResult.status
            if (cipherResult.status == DecryptStatus.SUCCESS) {
                cipherResult.title?.let { resolvedTitle = it }
                cipherResult.body?.let {
                    resolvedBody = it
                }
                images = cipherResult.images
                resolvedUrl = cipherResult.url
                tagsJson = cipherResult.tagsJson
                metadataJson = cipherResult.metadataJson
                description = cipherResult.description
                statusText = cipherResult.statusText
                messageText = cipherResult.messageText
                attrsJson = cipherResult.attrsJson
                startedAt = cipherResult.startedAt
                endedAt = cipherResult.endedAt
                primaryImage = cipherResult.primaryImage
                stateText = cipherResult.stateText
                createdAt = cipherResult.createdAt
                deletedAt = cipherResult.deletedAt
                externalIdsJson = cipherResult.externalIdsJson
                locationType = cipherResult.locationType
                locationValue = cipherResult.locationValue
                locationJson = cipherResult.locationJson
                payloadOverridesApplied = cipherResult.hasPayloadOverrides
            }
        }

        val state = when {
            inlineStatus == DecryptStatus.FAILURE || cipherStatus == DecryptStatus.FAILURE -> DecryptionState.DECRYPT_FAILED
            inlineStatus == DecryptStatus.SUCCESS || cipherStatus == DecryptStatus.SUCCESS || payloadOverridesApplied -> DecryptionState.DECRYPT_OK
            likelyEncrypted -> DecryptionState.DECRYPT_FAILED
            else -> null
        }

        return Result(
            title = resolvedTitle,
            body = resolvedBody,
            images = images,
            url = resolvedUrl,
            tagsJson = tagsJson,
            metadataJson = metadataJson,
            description = description,
            statusText = statusText,
            messageText = messageText,
            attrsJson = attrsJson,
            startedAt = startedAt,
            endedAt = endedAt,
            primaryImage = primaryImage,
            stateText = stateText,
            createdAt = createdAt,
            deletedAt = deletedAt,
            externalIdsJson = externalIdsJson,
            locationType = locationType,
            locationValue = locationValue,
            locationJson = locationJson,
            decryptionState = state,
        )
    }

    private fun decryptInlineField(value: String, key: ByteArray): InlineDecryptResult {
        val envelope = InlineCipherEnvelope.from(value) ?: return InlineDecryptResult(DecryptStatus.NONE, null)
        return try {
            val plaintext = aesGcmDecrypt(envelope.ciphertextAndTag, key, envelope.iv)
            InlineDecryptResult(DecryptStatus.SUCCESS, plaintext)
        } catch (ex: Exception) {
            InlineDecryptResult(DecryptStatus.FAILURE, null)
        }
    }

    private fun decryptCiphertextPayload(
        ciphertext: String,
        key: ByteArray,
    ): CipherDecryptResult {
        val envelope = InlineCipherEnvelope.from(ciphertext) ?: return CipherDecryptResult(DecryptStatus.NONE)
        return try {
            val plaintext = aesGcmDecrypt(envelope.ciphertextAndTag, key, envelope.iv)
            val json = JsonCompat.parseObject(plaintext) ?: return CipherDecryptResult(DecryptStatus.FAILURE)
            CipherDecryptResult(
                status = DecryptStatus.SUCCESS,
                title = json.stringValue("title"),
                body = json.stringValue("body"),
                images = decodeImages(json),
                url = json.stringValue("url"),
                tagsJson = decodeStringArrayJsonValue(json["tags"]),
                metadataJson = decodeObjectJsonValue(json["metadata"]),
                description = json.stringValue("description"),
                statusText = json.stringValue("status"),
                messageText = json.stringValue("message"),
                attrsJson = decodeObjectJsonValue(json["attrs"]),
                startedAt = numberAsLong(json["started_at"])?.toString(),
                endedAt = numberAsLong(json["ended_at"])?.toString(),
                primaryImage = json.stringValue("primary_image"),
                stateText = json.stringValue("state"),
                createdAt = numberAsLong(json["created_at"])?.toString(),
                deletedAt = numberAsLong(json["deleted_at"])?.toString(),
                externalIdsJson = decodeObjectJsonValue(json["external_ids"]),
                locationType = json.stringValue("location_type"),
                locationValue = json.stringValue("location_value"),
                locationJson = decodeObjectJsonValue(json["location"]),
            )
        } catch (ex: Exception) {
            CipherDecryptResult(DecryptStatus.FAILURE)
        }
    }

    private fun aesGcmDecrypt(ciphertextAndTag: ByteArray, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val params = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, params)
        val output = cipher.doFinal(ciphertextAndTag)
        return String(output, Charsets.UTF_8)
    }

    private data class InlineCipherEnvelope(
        val ciphertextAndTag: ByteArray,
        val iv: ByteArray,
    ) {
        companion object {
            private const val IV_LENGTH = 12
            private const val TAG_LENGTH = 16
            private const val MINIMUM_CIPHER_BYTES = IV_LENGTH + TAG_LENGTH + 1
            private const val MINIMUM_BASE64_LENGTH = ((MINIMUM_CIPHER_BYTES + 2) / 3) * 4
            private val INVALID_BASE64 = Regex("[^A-Za-z0-9+/=]")

            fun looksLikeCiphertext(value: String): Boolean {
                if (value.isBlank() || value.length % 4 != 0) return false
                if (value.length < MINIMUM_BASE64_LENGTH) return false
                return !INVALID_BASE64.containsMatchIn(value)
            }

            fun from(base64: String): InlineCipherEnvelope? {
                if (!looksLikeCiphertext(base64)) return null
                val decoded = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
                if (decoded.size < MINIMUM_CIPHER_BYTES || decoded.size > MAX_CIPHERTEXT_BYTES) return null
                val iv = decoded.copyOfRange(decoded.size - IV_LENGTH, decoded.size)
                val cipherAndTag = decoded.copyOfRange(0, decoded.size - IV_LENGTH)
                if (cipherAndTag.size <= TAG_LENGTH) return null
                return InlineCipherEnvelope(cipherAndTag, iv)
            }
        }
    }

    private data class InlineDecryptResult(val status: DecryptStatus, val text: String?)

    private data class CipherDecryptResult(
        val status: DecryptStatus,
        val title: String? = null,
        val body: String? = null,
        val images: List<String> = emptyList(),
        val url: String? = null,
        val tagsJson: String? = null,
        val metadataJson: String? = null,
        val description: String? = null,
        val statusText: String? = null,
        val messageText: String? = null,
        val attrsJson: String? = null,
        val startedAt: String? = null,
        val endedAt: String? = null,
        val primaryImage: String? = null,
        val stateText: String? = null,
        val createdAt: String? = null,
        val deletedAt: String? = null,
        val externalIdsJson: String? = null,
        val locationType: String? = null,
        val locationValue: String? = null,
        val locationJson: String? = null,
    )

    private val CipherDecryptResult.hasPayloadOverrides: Boolean
        get() {
            return !url.isNullOrBlank()
                || !tagsJson.isNullOrBlank()
                || !metadataJson.isNullOrBlank()
                || !description.isNullOrBlank()
                || !statusText.isNullOrBlank()
                || !messageText.isNullOrBlank()
                || !attrsJson.isNullOrBlank()
                || !startedAt.isNullOrBlank()
                || !endedAt.isNullOrBlank()
                || !primaryImage.isNullOrBlank()
                || !stateText.isNullOrBlank()
                || !createdAt.isNullOrBlank()
                || !deletedAt.isNullOrBlank()
                || !externalIdsJson.isNullOrBlank()
                || !locationType.isNullOrBlank()
                || !locationValue.isNullOrBlank()
                || !locationJson.isNullOrBlank()
                || images.isNotEmpty()
                || !title.isNullOrBlank()
                || !body.isNullOrBlank()
        }

    private enum class DecryptStatus {
        NONE,
        SUCCESS,
        FAILURE,
    }

    private fun decodeImages(json: Map<String, Any?>): List<String> {
        val results = linkedSetOf<String>()
        val image = json.stringValue("image").orEmpty()
        if (image.isNotEmpty()) {
            results += image
        }
        val rawImages = json["images"]
        when (rawImages) {
            is List<*> -> {
                for (entry in rawImages) {
                    val value = entry?.toString()?.trim().orEmpty()
                    if (value.isNotEmpty()) {
                        results += value
                    }
                }
            }
            is String -> {
                val trimmed = rawImages.trim()
                if (trimmed.isNotEmpty()) {
                    val parsed = runCatching { JsonCompat.parseArray(trimmed) }.getOrNull()
                    if (parsed != null) {
                        for (entry in parsed) {
                            val value = entry?.toString()?.trim().orEmpty()
                            if (value.isNotEmpty()) {
                                results += value
                            }
                        }
                    } else {
                        results += trimmed
                    }
                }
            }
        }
        return results.toList()
    }

    private fun decodeObjectJsonValue(raw: Any?): String? {
        return when (raw) {
            null -> null
            is String -> {
                val text = raw.trim()
                if (text.isEmpty()) {
                    null
                } else {
                    val parsed = JsonCompat.parseObject(text) ?: return null
                    JsonCompat.stringify(parsed)
                }
            }
            is Map<*, *> -> JsonCompat.stringify(raw)
            else -> null
        }
    }

    private fun decodeStringArrayJsonValue(raw: Any?): String? {
        val values = linkedSetOf<String>()
        when (raw) {
            null -> return null
            is List<*> -> {
                for (entry in raw) {
                    val value = entry?.toString()?.trim().orEmpty()
                    if (value.isNotEmpty()) {
                        values += value
                    }
                }
            }
            is String -> {
                val text = raw.trim()
                if (text.isEmpty()) return null
                val parsed = JsonCompat.parseArray(text)
                if (parsed != null) {
                    for (entry in parsed) {
                        val value = entry?.toString()?.trim().orEmpty()
                        if (value.isNotEmpty()) {
                            values += value
                        }
                    }
                } else {
                    values += text
                }
            }
            else -> return null
        }
        if (values.isEmpty()) return null
        return JsonCompat.stringify(values.toList())
    }

    private fun numberAsLong(raw: Any?): Long? {
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
    }

    private fun Map<String, Any?>.stringValue(key: String): String? {
        return this[key]?.toString()?.trim()?.ifEmpty { null }
    }
}
