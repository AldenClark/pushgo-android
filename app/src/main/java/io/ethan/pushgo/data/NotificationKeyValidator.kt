package io.ethan.pushgo.data

import io.ethan.pushgo.data.model.KeyEncoding

sealed class NotificationKeyValidationException(message: String) : IllegalArgumentException(message) {
    object InvalidBase64 : NotificationKeyValidationException("Invalid base64 notification key")
    object InvalidHex : NotificationKeyValidationException("Invalid hex notification key")
    object InvalidLength : NotificationKeyValidationException("Notification key must be 16, 24, or 32 bytes")
}

object NotificationKeyValidator {
    private val allowedKeyByteCounts = setOf(16, 24, 32)

    fun parseEncoding(raw: String?): KeyEncoding {
        return when (raw?.trim()?.uppercase()) {
            null, "", "BASE64" -> KeyEncoding.BASE64
            "HEX" -> KeyEncoding.HEX
            "PLAINTEXT", "PLAIN", "TEXT" -> KeyEncoding.PLAINTEXT
            else -> throw IllegalArgumentException("Unsupported key encoding: $raw")
        }
    }

    fun normalizedKeyBytes(
        input: String,
        encoding: KeyEncoding,
    ): ByteArray {
        val bytes = when (encoding) {
            KeyEncoding.PLAINTEXT -> input.toByteArray()
            KeyEncoding.BASE64 -> runCatching { java.util.Base64.getDecoder().decode(input) }
                .getOrElse { throw NotificationKeyValidationException.InvalidBase64 }
            KeyEncoding.HEX -> {
                val clean = input.filterNot(Char::isWhitespace)
                if (clean.length % 2 != 0) throw NotificationKeyValidationException.InvalidHex
                ByteArray(clean.length / 2) { index ->
                    val segment = clean.substring(index * 2, index * 2 + 2)
                    segment.toIntOrNull(16)?.toByte()
                        ?: throw NotificationKeyValidationException.InvalidHex
                }
            }
        }
        if (bytes.size !in allowedKeyByteCounts) {
            throw NotificationKeyValidationException.InvalidLength
        }
        return bytes
    }
}
