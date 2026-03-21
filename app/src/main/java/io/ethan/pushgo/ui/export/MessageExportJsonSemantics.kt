package io.ethan.pushgo.ui.export

import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.util.JsonCompat
import java.time.format.DateTimeFormatter

internal object MessageExportJsonSemantics {
    fun buildMessagesJson(
        messages: List<PushMessage>,
        formatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT,
    ): String {
        return JsonCompat.stringify(messages.map { buildMessageMap(it, formatter) })
    }

    fun buildMessageMap(
        message: PushMessage,
        formatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT,
    ): Map<String, Any?> {
        return linkedMapOf(
            "id" to message.id,
            "message_id" to message.messageId,
            "title" to message.title,
            "body" to message.body,
            "channel_id" to message.channel,
            "url" to message.url,
            "is_read" to message.isRead,
            "received_at" to formatter.format(message.receivedAt),
            "status" to message.status.name,
            "decryption_state" to message.decryptionState?.name,
            "notification_id" to message.notificationId,
            "server_id" to message.serverId,
            "raw_payload" to parseRawPayload(message.rawPayloadJson),
        )
    }

    fun parseRawPayload(raw: String): Any {
        return JsonCompat.parseObject(raw) ?: raw
    }
}
