package io.ethan.pushgo.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
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
                "inbound worker failed attempt=${runAttemptCount + 1}/$MAX_RETRY_ATTEMPTS",
                error,
            )
            if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "InboundMessageWorker"
        private const val MAX_RETRY_ATTEMPTS = 5
        internal const val KEY_MESSAGE_DATA_JSON = "message_data_json"
        internal const val KEY_TRANSPORT_MESSAGE_ID = "transport_message_id"
        private const val DEFAULT_MESSAGE_ID_FALLBACK = "no-message-id"

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
            val normalizedMessageId = if (messageId.isNotEmpty()) {
                messageId
            } else {
                DEFAULT_MESSAGE_ID_FALLBACK
            }
            val normalizedChannelId = if (channelId.isNotEmpty()) {
                channelId
            } else {
                "no-channel-id"
            }
            val dedupeKey = if (normalizedTransportId.isNotEmpty()) {
                "$normalizedTransportId:$normalizedChannelId:$normalizedMessageId"
            } else {
                "$normalizedChannelId:$normalizedMessageId"
            }
            return "inbound:$dedupeKey"
        }
    }
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
