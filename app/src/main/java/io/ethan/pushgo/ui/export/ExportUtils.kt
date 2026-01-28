package io.ethan.pushgo.ui.export

import android.content.Context
import android.net.Uri
import io.ethan.pushgo.data.model.PushMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.time.format.DateTimeFormatter

object ExportUtils {
    fun writeMessagesJson(context: Context, uri: Uri, messages: List<PushMessage>) {
        val payload = buildMessagesJson(messages)
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }
        }
    }

    fun buildMessagesJson(messages: List<PushMessage>): String {
        val formatter = DateTimeFormatter.ISO_INSTANT
        val array = JSONArray()
        for (message in messages) {
            val obj = JSONObject()
            obj.put("id", message.id)
            obj.put("messageId", message.messageId)
            obj.put("title", message.title)
            obj.put("body", message.body)
            obj.put("channel_id", message.channel)
            obj.put("url", message.url)
            obj.put("isRead", message.isRead)
            obj.put("receivedAt", formatter.format(message.receivedAt))
            obj.put("status", message.status.name)
            obj.put("decryptionState", message.decryptionState?.name)
            obj.put("notificationId", message.notificationId)
            obj.put("serverId", message.serverId)
            obj.put("rawPayload", parseRawPayload(message.rawPayloadJson))
            array.put(obj)
        }
        return array.toString(2)
    }

    private fun parseRawPayload(raw: String): Any {
        return try {
            JSONObject(raw)
        } catch (ex: Exception) {
            raw
        }
    }
}
