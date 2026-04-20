package io.ethan.pushgo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
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

data class ChannelSyncItem(
    val channelId: String,
    val password: String,
)

data class ChannelSyncResult(
    val channelId: String,
    val channelName: String?,
    val subscribed: Boolean,
    val errorCode: String?,
    val error: String?,
)

data class ChannelSyncSummary(
    val success: Int,
    val failed: Int,
    val channels: List<ChannelSyncResult>,
)

data class DeviceChannelUpsertResult(
    val deviceKey: String,
)

data class DeviceRegisterResult(
    val deviceKey: String,
)

data class PullItem(
    val deliveryId: String,
    val payload: Map<String, String>,
)

class ChannelSubscriptionService(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        internal const val DEVICE_REGISTER_ENDPOINT = "/device/register"
        internal const val DEVICE_ROUTE_ENDPOINT = "/channel/device"
        internal const val DEVICE_CHANNEL_DELETE_ENDPOINT = "/channel/device/delete"
        internal const val PROVIDER_TOKEN_RETIRE_ENDPOINT = "/channel/device/provider-token/retire"
        internal const val PULL_MESSAGE_ENDPOINT = "/messages/pull"
        internal const val ACK_MESSAGE_ENDPOINT = "/messages/ack"
    }

    data class EventSendResult(
        val eventId: String,
        val thingId: String?,
    )

    suspend fun channelExists(
        baseUrl: String,
        token: String?,
        channelId: String,
    ): ChannelExistsResult = withContext(ioDispatcher) {
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
        deviceKey: String,
        channelId: String?,
        channelName: String?,
        password: String,
    ): ChannelSubscribeResult = withContext(ioDispatcher) {
        val endpoint = buildUrl(baseUrl, "/channel/subscribe")
        val payload = JSONObject().apply {
            put("device_key", deviceKey)
            if (!channelId.isNullOrBlank()) {
                put("channel_id", channelId)
            }
            if (!channelName.isNullOrBlank()) {
                put("channel_name", channelName)
            }
            put("password", password)
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
        deviceKey: String,
        channelId: String,
    ): Boolean = withContext(ioDispatcher) {
        val endpoint = buildUrl(baseUrl, "/channel/unsubscribe")
        val payload = JSONObject().apply {
            put("device_key", deviceKey)
            put("channel_id", channelId)
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: return@withContext false
        return@withContext data.optBoolean("removed", false)
    }

    suspend fun registerDevice(
        baseUrl: String,
        token: String?,
        platform: String,
        deviceKey: String?,
    ): DeviceRegisterResult = withContext(ioDispatcher) {
        val endpoint = buildUrl(baseUrl, DEVICE_REGISTER_ENDPOINT)
        val payload = JSONObject().apply {
            if (!deviceKey.isNullOrBlank()) {
                put("device_key", deviceKey.trim())
            }
            put("platform", platform.trim().lowercase())
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data
        val resolved = data?.optString("device_key", "")?.trim().orEmpty()
        if (resolved.isEmpty()) {
            throw ChannelSubscriptionException("gateway response missing device_key")
        }
        DeviceRegisterResult(deviceKey = resolved)
    }

    suspend fun upsertDeviceChannel(
        baseUrl: String,
        token: String?,
        deviceKey: String?,
        platform: String,
        channelType: String,
        providerToken: String?,
    ): DeviceChannelUpsertResult = withContext(ioDispatcher) {
        val normalizedDeviceKey = deviceKey?.trim().orEmpty()
        if (normalizedDeviceKey.isEmpty()) {
            throw ChannelSubscriptionException("Missing device_key")
        }
        val endpoint = buildUrl(baseUrl, DEVICE_ROUTE_ENDPOINT)
        val payload = JSONObject().apply {
            put("device_key", normalizedDeviceKey)
            put("platform", platform.trim().lowercase())
            put("channel_type", channelType.trim().lowercase())
            if (!providerToken.isNullOrBlank()) {
                put("provider_token", providerToken.trim())
            }
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data
        val resolved = data?.optString("device_key", "")?.trim().orEmpty()
        if (resolved.isEmpty()) {
            throw ChannelSubscriptionException("gateway response missing device_key")
        }
        DeviceChannelUpsertResult(deviceKey = resolved)
    }

    suspend fun deleteDeviceChannel(
        baseUrl: String,
        token: String?,
        deviceKey: String,
        channelType: String,
    ) = withContext(ioDispatcher) {
        val endpoint = buildUrl(baseUrl, DEVICE_CHANNEL_DELETE_ENDPOINT)
        val payload = JSONObject().apply {
            put("device_key", deviceKey)
            put("channel_type", channelType.trim().lowercase())
        }
        execute(endpoint, token, "POST", payload)
        Unit
    }

    suspend fun retireProviderToken(
        baseUrl: String,
        token: String?,
        platform: String,
        providerToken: String,
    ) = withContext(ioDispatcher) {
        val normalizedProviderToken = providerToken.trim()
        if (normalizedProviderToken.isEmpty()) {
            return@withContext
        }
        val endpoint = buildUrl(baseUrl, PROVIDER_TOKEN_RETIRE_ENDPOINT)
        val payload = JSONObject().apply {
            put("platform", platform.trim().lowercase())
            put("provider_token", normalizedProviderToken)
        }
        execute(endpoint, token, "POST", payload)
        Unit
    }

    suspend fun pullMessages(
        baseUrl: String,
        token: String?,
        deviceKey: String,
        deliveryId: String? = null,
    ): List<PullItem> = withContext(ioDispatcher) {
        val normalizedDeviceKey = deviceKey.trim()
        if (normalizedDeviceKey.isEmpty()) {
            throw ChannelSubscriptionException("Missing device_key")
        }
        val normalizedDeliveryId = deliveryId?.trim()?.takeIf { it.isNotEmpty() }
        val endpoint = buildUrl(baseUrl, PULL_MESSAGE_ENDPOINT)
        val payload = JSONObject().apply {
            put("device_key", normalizedDeviceKey)
            if (normalizedDeliveryId != null) {
                put("delivery_id", normalizedDeliveryId)
            }
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: throw ChannelSubscriptionException("Invalid response")
        val items = data.optJSONArray("items") ?: return@withContext emptyList()
        return@withContext buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val itemPayload = item.optJSONObject("payload")?.toStringMap() ?: continue
                val resolvedDeliveryId = item.optString("delivery_id", "")
                    .trim()
                    .ifEmpty { normalizedDeliveryId.orEmpty() }
                if (resolvedDeliveryId.isEmpty()) continue
                add(
                    PullItem(
                        deliveryId = resolvedDeliveryId,
                        payload = itemPayload,
                    )
                )
            }
        }
    }

    suspend fun ackMessage(
        baseUrl: String,
        token: String?,
        deviceKey: String,
        deliveryId: String,
    ): Boolean = withContext(ioDispatcher) {
        val normalizedDeviceKey = deviceKey.trim()
        if (normalizedDeviceKey.isEmpty()) {
            throw ChannelSubscriptionException("Missing device_key")
        }
        val normalizedDeliveryId = deliveryId.trim()
        if (normalizedDeliveryId.isEmpty()) {
            throw ChannelSubscriptionException("Missing delivery_id")
        }
        val endpoint = buildUrl(baseUrl, ACK_MESSAGE_ENDPOINT)
        val payload = JSONObject().apply {
            put("device_key", normalizedDeviceKey)
            put("delivery_id", normalizedDeliveryId)
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: throw ChannelSubscriptionException("Invalid response")
        return@withContext data.optBoolean("removed", false)
    }

    suspend fun renameChannel(
        baseUrl: String,
        token: String?,
        channelId: String,
        channelName: String,
        password: String,
    ): ChannelRenameResult = withContext(ioDispatcher) {
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

    suspend fun sync(
        baseUrl: String,
        token: String?,
        deviceKey: String,
        channels: List<ChannelSyncItem>,
    ): ChannelSyncSummary = withContext(ioDispatcher) {
        val endpoint = buildUrl(baseUrl, "/channel/sync")
        val payload = JSONObject().apply {
            put("device_key", deviceKey)
            put("channels", org.json.JSONArray().apply {
                channels.forEach { item ->
                    put(
                        JSONObject().apply {
                            put("channel_id", item.channelId)
                            put("password", item.password)
                        }
                    )
                }
            })
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: throw ChannelSubscriptionException("Invalid response")
        val channelArray = data.optJSONArray("channels")
        val parsedChannels = buildList {
            if (channelArray != null) {
                for (index in 0 until channelArray.length()) {
                    val item = channelArray.optJSONObject(index) ?: continue
                    val channelId = item.optString("channel_id", "").trim()
                    if (channelId.isEmpty()) continue
                    add(
                        ChannelSyncResult(
                            channelId = channelId,
                            channelName = item.optString("channel_name", "").trim().ifEmpty { null },
                            subscribed = item.optBoolean("subscribed", false),
                            errorCode = item.optString("error_code", "").trim().ifEmpty { null },
                            error = item.optString("error", "").trim().ifEmpty { null },
                        )
                    )
                }
            }
        }
        return@withContext ChannelSyncSummary(
            success = data.optInt("success", 0),
            failed = data.optInt("failed", 0),
            channels = parsedChannels,
        )
    }

    suspend fun eventToChannel(
        baseUrl: String,
        token: String?,
        payload: JSONObject,
        endpointPath: String = "/event/update",
    ): EventSendResult = withContext(ioDispatcher) {
        val endpoint = buildUrl(baseUrl, endpointPath)
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: throw ChannelSubscriptionException("Invalid response")
        val eventId = data.optString("event_id", "").trim()
        if (eventId.isEmpty()) {
            throw ChannelSubscriptionException("gateway response missing event_id")
        }
        if (!data.optBoolean("accepted", false)) {
            throw ChannelSubscriptionException("Request failed")
        }
        val thingId = data.optString("thing_id", "").trim().ifEmpty { null }
        EventSendResult(eventId = eventId, thingId = thingId)
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
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (json != null) {
                val success = json.optBoolean("success", false)
                val error = json.optString("error", "").trim()
                val errorCode = json.optString("error_code", "").trim()
                val data = json.optJSONObject("data")
                if (errorCode.isNotEmpty() || error.isNotEmpty()) {
                    val message = when {
                        errorCode.isNotEmpty() && error.isNotEmpty() -> "$errorCode: $error"
                        errorCode.isNotEmpty() -> errorCode
                        else -> error
                    }
                    throw ChannelSubscriptionException(message)
                }
                if (!success && code !in 200..299) {
                    throw ChannelSubscriptionException("Server error: $code")
                }
                if (!success) {
                    throw ChannelSubscriptionException("Request failed")
                }
                if (code !in 200..299) {
                    throw ChannelSubscriptionException("Server error: $code")
                }
                return StatusResponse(success = success, data = data)
            }
            if (code !in 200..299) {
                throw ChannelSubscriptionException("Server error: $code")
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

    private fun JSONObject.toStringMap(): Map<String, String> {
        val output = LinkedHashMap<String, String>(length())
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val value = opt(key) ?: continue
            output[key] = value.toString()
        }
        return output
    }

    private data class StatusResponse(
        val success: Boolean,
        val data: JSONObject?,
    )
}

class ChannelSubscriptionException(message: String) : Exception(message)
