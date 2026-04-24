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
        val eventProfileJson: String?,
        val eventAttrsJson: String?,
        val thingProfileJson: String?,
        val thingAttrsJson: String?,
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
                eventProfileJson = null,
                eventAttrsJson = null,
                thingProfileJson = null,
                thingAttrsJson = null,
                decryptionState = DecryptionState.NOT_CONFIGURED,
            )
        }

        if (keyBytes != null && keyBytes.isNotEmpty() && keyBytes.size !in VALID_KEY_LENGTHS) {
            return Result(
                title = title,
                body = body,
                images = emptyList(),
                url = null,
                eventProfileJson = null,
                eventAttrsJson = null,
                thingProfileJson = null,
                thingAttrsJson = null,
                decryptionState = if (likelyEncrypted) DecryptionState.DECRYPT_FAILED else null,
            )
        }
        if (keyBytes == null || keyBytes.isEmpty()) {
            return Result(
                title = title,
                body = body,
                images = emptyList(),
                url = null,
                eventProfileJson = null,
                eventAttrsJson = null,
                thingProfileJson = null,
                thingAttrsJson = null,
                decryptionState = null,
            )
        }

        var resolvedTitle = title
        var resolvedBody = body
        var images: List<String> = emptyList()
        var resolvedUrl: String? = null
        var eventProfileJson: String? = null
        var eventAttrsJson: String? = null
        var thingProfileJson: String? = null
        var thingAttrsJson: String? = null
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
                eventProfileJson = cipherResult.eventProfileJson
                eventAttrsJson = cipherResult.eventAttrsJson
                thingProfileJson = cipherResult.thingProfileJson
                thingAttrsJson = cipherResult.thingAttrsJson
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
            eventProfileJson = eventProfileJson,
            eventAttrsJson = eventAttrsJson,
            thingProfileJson = thingProfileJson,
            thingAttrsJson = thingAttrsJson,
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

    private fun decryptCiphertextPayload(ciphertext: String, key: ByteArray): CipherDecryptResult {
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
                eventProfileJson = decodeObjectJsonValue(json["event_profile_json"]),
                eventAttrsJson = decodeObjectJsonValue(json["event_attrs_json"]),
                thingProfileJson = decodeObjectJsonValue(json["thing_profile_json"]),
                thingAttrsJson = decodeObjectJsonValue(json["thing_attrs_json"]),
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
        val eventProfileJson: String? = null,
        val eventAttrsJson: String? = null,
        val thingProfileJson: String? = null,
        val thingAttrsJson: String? = null,
    )

    private val CipherDecryptResult.hasPayloadOverrides: Boolean
        get() {
            return !url.isNullOrBlank()
                || !eventProfileJson.isNullOrBlank()
                || !eventAttrsJson.isNullOrBlank()
                || !thingProfileJson.isNullOrBlank()
                || !thingAttrsJson.isNullOrBlank()
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

    private fun Map<String, Any?>.stringValue(key: String): String? {
        return this[key]?.toString()?.trim()?.ifEmpty { null }
    }
}
