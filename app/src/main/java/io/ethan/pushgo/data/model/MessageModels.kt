package io.ethan.pushgo.data.model

import java.time.Instant

enum class MessageStatus {
    NORMAL,
    MISSING,
    PARTIALLY_DECRYPTED,
    DECRYPTED,
}

enum class DecryptionState {
    NOT_CONFIGURED,
    ALG_MISMATCH,
    DECRYPT_OK,
    DECRYPT_FAILED,
}

data class PushMessage(
    val id: String,
    val messageId: String?,
    val title: String,
    val body: String,
    val channel: String?,
    val url: String?,
    val isRead: Boolean,
    val receivedAt: Instant,
    val rawPayloadJson: String,
    val status: MessageStatus,
    val decryptionState: DecryptionState?,
    val notificationId: String?,
    val serverId: String?,
)
