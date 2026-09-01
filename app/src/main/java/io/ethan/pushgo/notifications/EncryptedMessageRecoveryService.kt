package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.model.DecryptionState
import org.json.JSONObject

data class EncryptedMessageRecoveryReport(
    val examinedCount: Int,
    val updatedCount: Int,
    val decryptedCount: Int,
)

class EncryptedMessageRecoveryService(
    private val messageRepository: MessageRepository,
) {
    suspend fun recover(keyBytes: ByteArray): EncryptedMessageRecoveryReport {
        val candidates = messageRepository.loadEncryptedRecoveryCandidates()
        var updatedCount = 0
        var decryptedCount = 0

        for (existing in candidates) {
            val payload = encryptedRecoveryPayloadStrings(existing.rawPayloadJson) ?: continue
            val reparsed = NotificationIngressParser.parse(
                data = payload,
                transportMessageId = existing.notificationId,
                keyBytes = keyBytes,
                now = existing.receivedAt,
            ) as? InboundPersistenceRequest.Message ?: continue
            if (reparsed.message.decryptionState == null) continue

            val updated = messageRepository.replaceEncryptedRecoveryCandidate(
                existingId = existing.id,
                reparsed = reparsed.message,
            )
            if (updated) {
                updatedCount += 1
                if (reparsed.message.decryptionState == DecryptionState.DECRYPT_OK) {
                    decryptedCount += 1
                }
            }
        }

        return EncryptedMessageRecoveryReport(
            examinedCount = candidates.size,
            updatedCount = updatedCount,
            decryptedCount = decryptedCount,
        )
    }

}

internal fun encryptedRecoveryPayloadStrings(rawPayloadJson: String): Map<String, String>? {
    val source = runCatching { JSONObject(rawPayloadJson) }.getOrNull() ?: return null
    return buildMap {
        source.keys().forEach { key ->
            if (
                key == NotificationIngressParser.RECOVERY_TITLE_CIPHERTEXT ||
                key == NotificationIngressParser.RECOVERY_BODY_CIPHERTEXT
            ) {
                return@forEach
            }
            val value = source.opt(key)
            if (value != null && value != JSONObject.NULL) {
                put(key, if (value is String) value else value.toString())
            }
        }
        source.optString(NotificationIngressParser.RECOVERY_TITLE_CIPHERTEXT)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { put("title", it) }
        source.optString(NotificationIngressParser.RECOVERY_BODY_CIPHERTEXT)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { put("body", it) }
    }
}
