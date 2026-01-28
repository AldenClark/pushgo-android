package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.model.DecryptionState
import org.json.JSONObject
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
        val image: String?,
        val icon: String?,
        val decryptedBody: String?,
        val decryptionState: DecryptionState?,
    )

    fun decryptIfNeeded(
        data: Map<String, String>,
        title: String,
        body: String,
        keyBase64: String?,
    ): Result {
        val ciphertext = data["ciphertext"]
        val likelyEncrypted = !ciphertext.isNullOrBlank()
            || InlineCipherEnvelope.looksLikeCiphertext(title)
            || InlineCipherEnvelope.looksLikeCiphertext(body)

        if (likelyEncrypted && keyBase64.isNullOrBlank()) {
            return Result(title, body, null, null, null, DecryptionState.NOT_CONFIGURED)
        }

        val keyBytes = keyBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (keyBytes != null && keyBytes.isNotEmpty() && keyBytes.size !in VALID_KEY_LENGTHS) {
            return Result(
                title,
                body,
                null,
                null,
                null,
                if (likelyEncrypted) DecryptionState.DECRYPT_FAILED else null,
            )
        }
        if (likelyEncrypted && (keyBytes == null || keyBytes.isEmpty())) {
            return Result(title, body, null, null, null, DecryptionState.DECRYPT_FAILED)
        }
        if (keyBytes == null || keyBytes.isEmpty()) {
            return Result(title, body, null, null, null, null)
        }

        var resolvedTitle = title
        var resolvedBody = body
        var image: String? = null
        var icon: String? = null
        var decryptedBody: String? = null
        var inlineStatus: DecryptStatus = DecryptStatus.NONE
        var cipherStatus: DecryptStatus = DecryptStatus.NONE

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
                    decryptedBody = it
                }
                image = cipherResult.image
                icon = cipherResult.icon
            }
        }

        val state = when {
            inlineStatus == DecryptStatus.FAILURE || cipherStatus == DecryptStatus.FAILURE -> DecryptionState.DECRYPT_FAILED
            inlineStatus == DecryptStatus.SUCCESS || cipherStatus == DecryptStatus.SUCCESS -> DecryptionState.DECRYPT_OK
            likelyEncrypted -> DecryptionState.DECRYPT_FAILED
            else -> null
        }

        return Result(resolvedTitle, resolvedBody, image, icon, decryptedBody, state)
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
            val json = JSONObject(plaintext)
            CipherDecryptResult(
                status = DecryptStatus.SUCCESS,
                title = json.optString("title", "").trim().takeUnless { it.isEmpty() },
                body = json.optString("body", "").trim().takeUnless { it.isEmpty() },
                image = json.optString("image", "").trim().takeUnless { it.isEmpty() },
                icon = json.optString("icon", "").trim().takeUnless { it.isEmpty() },
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
        val image: String? = null,
        val icon: String? = null,
    )

    private enum class DecryptStatus {
        NONE,
        SUCCESS,
        FAILURE,
    }
}
