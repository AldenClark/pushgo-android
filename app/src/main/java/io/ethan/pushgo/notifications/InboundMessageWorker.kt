package io.ethan.pushgo.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.ethan.pushgo.data.ChannelSubscriptionException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class InboundMessageWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val rawMessageData = inputData.getString(KEY_MESSAGE_DATA_JSON) ?: return Result.success()
        val messageData = InboundMessagePayloadCodec.decode(rawMessageData) ?: return Result.success()
        val transportMessageId = inputData.getString(KEY_TRANSPORT_MESSAGE_ID)

        return runCatching {
            DefaultInboundMessageProcessor.process(
                context = applicationContext,
                messageData = messageData,
                transportMessageId = transportMessageId,
            )
            Result.success()
        }.getOrElse { error ->
            io.ethan.pushgo.util.SilentSink.w(
                TAG,
                "inbound worker failed attempt=${runAttemptCount + 1}",
                error,
            )
            if (shouldRetryInboundFailure(error)) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "InboundMessageWorker"
        internal const val KEY_MESSAGE_DATA_JSON = "message_data_json"
        internal const val KEY_TRANSPORT_MESSAGE_ID = "transport_message_id"

        fun enqueue(
            context: Context,
            messageData: Map<String, String>,
            transportMessageId: String?,
        ) {
            val payload = InboundMessagePayloadCodec.encode(messageData)
            val input = workDataOf(
                KEY_MESSAGE_DATA_JSON to payload,
                KEY_TRANSPORT_MESSAGE_ID to transportMessageId,
            )
            val request = OneTimeWorkRequestBuilder<InboundMessageWorker>()
                .setInputData(input)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS,
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                buildUniqueWorkName(transportMessageId, payload),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        internal fun buildUniqueWorkName(
            transportMessageId: String?,
            payload: String,
        ): String {
            val normalizedTransportId = transportMessageId?.trim().orEmpty()
            val payloadObject = runCatching { JSONObject(payload) }.getOrNull()
            val messageId = payloadObject?.optString("message_id", "")?.trim().orEmpty()
            val channelId = payloadObject?.optString("channel_id", "")?.trim().orEmpty()
            val normalizedChannelId = if (channelId.isNotEmpty()) {
                channelId
            } else {
                "no-channel-id"
            }
            if (normalizedTransportId.isNotEmpty()) {
                val normalizedMessageId = messageId.ifEmpty { "no-message-id" }
                return "inbound:$normalizedTransportId:$normalizedChannelId:$normalizedMessageId"
            }
            if (messageId.isNotEmpty()) {
                return "inbound:$normalizedChannelId:$messageId"
            }
            val deliveryId = payloadObject?.optString("delivery_id", "")?.trim().orEmpty()
            if (deliveryId.isNotEmpty()) {
                val gatewayUrl = payloadObject?.optString("base_url", "")?.trim().orEmpty()
                val providerDevice = payloadObject?.optString("provider_device_key", "")?.trim().orEmpty()
                val scopedDelivery = if (gatewayUrl.isNotEmpty() && providerDevice.isNotEmpty()) {
                    lengthPrefixed(gatewayUrl, providerDevice, deliveryId)
                } else {
                    deliveryId
                }
                return "inbound:delivery:${stableWorkKey(scopedDelivery)}"
            }
            val entityType = payloadObject?.optString("entity_type", "")?.trim().orEmpty()
            val entityId = payloadObject?.optString("entity_id", "")?.trim().orEmpty()
            val operationId = payloadObject?.optString("op_id", "")?.trim().orEmpty()
            if (entityType.isNotEmpty() && entityId.isNotEmpty() && operationId.isNotEmpty()) {
                return "inbound:entity:${stableWorkKey(lengthPrefixed(entityType, entityId, operationId))}"
            }
            return "inbound:payload:${stableWorkKey(canonicalPayload(payload))}"
        }

        private fun canonicalPayload(payload: String): String {
            val decoded = InboundMessagePayloadCodec.decode(payload) ?: return payload
            return decoded.toSortedMap().entries.joinToString(separator = "") { entry ->
                lengthPrefixed(entry.key, entry.value)
            }
        }

        private fun lengthPrefixed(vararg parts: String): String {
            return parts.joinToString(separator = "") { part -> "${part.length}:$part" }
        }

        internal fun stableWorkKey(value: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}

internal fun shouldRetryInboundFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    val visited = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<Throwable, Boolean>()
    )
    while (current != null && visited.add(current)) {
        if (current is ChannelSubscriptionException) return current.retryable
        current = current.cause
    }
    // Unknown failures include local storage/runtime failures. Retaining the durable
    // WorkManager item is safer than silently dropping an uncommitted delivery.
    return true
}

internal object InboundMessagePayloadCodec {
    fun encode(messageData: Map<String, String>): String {
        return JSONObject(messageData as Map<*, *>).toString()
    }

    fun decode(raw: String): Map<String, String>? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val map = LinkedHashMap<String, String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = root.opt(key)?.toString().orEmpty()
        }
        return map
    }
}
