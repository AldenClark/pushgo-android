package io.ethan.pushgo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ChannelSubscribeResult(
    val channelId: String,
    val channelName: String,
    val created: Boolean,
    val subscribed: Boolean,
)

data class ChannelExistsResult(
    val exists: Boolean,
    val channelName: String?,
)

data class ChannelRenameResult(
    val channelId: String,
    val channelName: String,
)

data class PushSummary(
    val channelId: String,
    val channelName: String,
    val messageId: String,
    val total: Int,
    val accepted: Int,
    val rejected: Int,
)

class ChannelSubscriptionService {
    suspend fun channelExists(
        baseUrl: String,
        token: String?,
        channelId: String,
    ): ChannelExistsResult = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(channelId, "UTF-8")
        val endpoint = buildUrl(baseUrl, "/channel/exists?channel_id=$encoded")
        val response = execute(
            endpoint = endpoint,
            token = token,
            method = "GET",
            payload = null,
        )
        val data = response.data ?: throw ChannelSubscriptionException("Invalid response")
        val resolvedName = data.optString("channel_name", "").trim().ifEmpty { null }
        return@withContext ChannelExistsResult(
            exists = data.optBoolean("exists", false),
            channelName = resolvedName,
        )
    }

    suspend fun subscribe(
        baseUrl: String,
        token: String?,
        channelId: String?,
        channelName: String?,
        password: String,
        deviceToken: String,
        platform: String,
    ): ChannelSubscribeResult = withContext(Dispatchers.IO) {
        val endpoint = buildUrl(baseUrl, "/channel/subscribe")
        val payload = JSONObject().apply {
            if (!channelId.isNullOrBlank()) {
                put("channel_id", channelId)
            }
            if (!channelName.isNullOrBlank()) {
                put("channel_name", channelName)
            }
            put("password", password)
            put("device_token", deviceToken)
            put("platform", platform)
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: throw ChannelSubscriptionException("Invalid response")
        val created = data.optBoolean("created", false)
        val subscribed = data.optBoolean("subscribed", false)
        val returnedId = data.optString("channel_id", channelId ?: "")
        val returnedName = data.optString("channel_name", channelName ?: returnedId)
        return@withContext ChannelSubscribeResult(
            channelId = returnedId,
            channelName = returnedName,
            created = created,
            subscribed = subscribed,
        )
    }

    suspend fun unsubscribe(
        baseUrl: String,
        token: String?,
        channelId: String,
        deviceToken: String,
        platform: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = buildUrl(baseUrl, "/channel/unsubscribe")
        val payload = JSONObject().apply {
            put("channel_id", channelId)
            put("device_token", deviceToken)
            put("platform", platform)
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: return@withContext false
        return@withContext data.optBoolean("removed", false)
    }

    suspend fun renameChannel(
        baseUrl: String,
        token: String?,
        channelId: String,
        channelName: String,
        password: String,
    ): ChannelRenameResult = withContext(Dispatchers.IO) {
        val endpoint = buildUrl(baseUrl, "/channel/rename")
        val payload = JSONObject().apply {
            put("channel_id", channelId)
            put("channel_name", channelName)
            put("password", password)
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: throw ChannelSubscriptionException("Invalid response")
        val returnedId = data.optString("channel_id", channelId)
        val returnedName = data.optString("channel_name", channelName)
        return@withContext ChannelRenameResult(
            channelId = returnedId,
            channelName = returnedName,
        )
    }

    suspend fun retireDevice(
        baseUrl: String,
        token: String?,
        deviceToken: String,
        platform: String,
    ): Int = withContext(Dispatchers.IO) {
        val endpoint = buildUrl(baseUrl, "/device/retire")
        val payload = JSONObject().apply {
            put("device_token", deviceToken)
            put("platform", platform)
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: return@withContext 0
        return@withContext data.optInt("removed_subscriptions", 0)
    }

    suspend fun pushToChannel(
        baseUrl: String,
        token: String?,
        payload: JSONObject,
    ): PushSummary = withContext(Dispatchers.IO) {
        val endpoint = buildUrl(baseUrl, "/push")
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: throw ChannelSubscriptionException("Invalid response")
        return@withContext PushSummary(
            channelId = data.optString("channel_id", ""),
            channelName = data.optString("channel_name", ""),
            messageId = data.optString("message_id", ""),
            total = data.optInt("total", 0),
            accepted = data.optInt("accepted", 0),
            rejected = data.optInt("rejected", 0),
        )
    }

    private fun execute(
        endpoint: String,
        token: String?,
        method: String,
        payload: JSONObject?,
    ): StatusResponse {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer ${token.trim()}")
            }
            connectTimeout = 15000
            readTimeout = 15000
        }
        return try {
            val payloadBytes = payload?.toString()?.toByteArray(Charsets.UTF_8)
            connection.doOutput = payloadBytes != null
            if (payloadBytes != null) {
                connection.setFixedLengthStreamingMode(payloadBytes.size)
                connection.outputStream.use { output ->
                    output.write(payloadBytes)
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val body = stream?.use { reader ->
                BufferedReader(InputStreamReader(reader, Charsets.UTF_8)).readText()
            } ?: ""
            if (code == 401 || code == 403) {
                throw ChannelSubscriptionException("Auth failed")
            }
            if (code !in 200..299) {
                throw ChannelSubscriptionException("Server error: $code")
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (json != null) {
                val success = json.optBoolean("success", false)
                val error = json.optString("error", "").trim()
                val data = json.optJSONObject("data")
                if (!success && error.isNotEmpty()) {
                    throw ChannelSubscriptionException(error)
                }
                if (!success) {
                    throw ChannelSubscriptionException("Request failed")
                }
                return StatusResponse(success = success, data = data)
            }
            throw ChannelSubscriptionException("Invalid response")
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUrl(baseUrl: String, path: String): String {
        val trimmed = baseUrl.trim().removeSuffix("/")
        val suffix = if (path.startsWith("/")) path else "/$path"
        return trimmed + suffix
    }

    private data class StatusResponse(
        val success: Boolean,
        val data: JSONObject?,
    )
}

class ChannelSubscriptionException(message: String) : Exception(message)
