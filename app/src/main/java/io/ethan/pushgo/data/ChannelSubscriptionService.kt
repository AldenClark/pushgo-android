package io.ethan.pushgo.data

import io.ethan.pushgo.util.UrlValidators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

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
    val problem: GatewayProblem? = null,
) {
    val resolvedErrorCode: String?
        get() = problem?.code?.trim()?.ifEmpty { null } ?: errorCode?.trim()?.ifEmpty { null }
}

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

enum class ProviderPullContract {
    V2,
    LEGACY,
}

enum class ProviderAckContract(val persistedValue: String) {
    V2_BATCH("v2_batch"),
    LEGACY_SINGLE("legacy_single");

    companion object {
        fun fromPersistedValue(raw: String): ProviderAckContract? =
            entries.firstOrNull { it.persistedValue == raw.trim() }
    }
}

data class ProviderAckDestination(
    val baseUrl: String,
    val deviceKey: String,
)

@ConsistentCopyVisibility
data class ProviderAckIdentity private constructor(
    val destination: ProviderAckDestination,
    val contract: ProviderAckContract,
    val source: String,
) {
    val gatewayUrl: String
        get() = destination.baseUrl

    val deviceKey: String
        get() = destination.deviceKey

    companion object {
        fun create(
            destination: ProviderAckDestination,
            contract: ProviderAckContract,
            source: String,
        ): ProviderAckIdentity? {
            val gatewayUrl = UrlValidators.normalizeGatewayBaseUrl(destination.baseUrl) ?: return null
            val deviceKey = destination.deviceKey.trim().takeIf { it.isNotEmpty() } ?: return null
            val normalizedSource = source.trim().takeIf { it.isNotEmpty() } ?: return null
            return ProviderAckIdentity(
                destination = ProviderAckDestination(
                    baseUrl = gatewayUrl,
                    deviceKey = deviceKey,
                ),
                contract = contract,
                source = normalizedSource,
            )
        }

        fun fromDirectPayload(payload: Map<String, String>): ProviderAckIdentity? {
            return create(
                destination = ProviderAckDestination(
                    baseUrl = payload["base_url"].orEmpty(),
                    deviceKey = payload["provider_device_key"].orEmpty(),
                ),
                contract = ProviderAckContract.LEGACY_SINGLE,
                source = "provider_direct",
            )
        }
    }
}

internal fun ProviderAckIdentity?.scopedDeliveryStorageKey(deliveryId: String): String {
    val normalizedDeliveryId = deliveryId.trim()
    val identity = this ?: return normalizedDeliveryId
    val scope = "${identity.gatewayUrl}\u0000${identity.deviceKey}\u0000$normalizedDeliveryId"
    val digest = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
    return buildString(17 + digest.size * 2) {
        append("provider-scoped:")
        digest.forEach { byte ->
            append(((byte.toInt() ushr 4) and 0x0f).toString(16))
            append((byte.toInt() and 0x0f).toString(16))
        }
    }
}

data class ProviderAckAttemptResult(
    val requestedCount: Int,
    val removedCount: Int,
)

data class ProviderPullPage(
    val items: List<PullItem>,
    val hasMore: Boolean,
    val contract: ProviderPullContract,
    val destination: ProviderAckDestination? = null,
)

data class ProviderBatchAckResult(
    val requestedCount: Int,
    val removedCount: Int,
)

enum class GatewayErrorCategory {
    VALIDATION,
    AUTH,
    PERMISSION,
    NOT_FOUND,
    CONFLICT,
    FEATURE_DISABLED,
    RATE_LIMIT,
    TOO_BUSY,
    NETWORK,
    UPSTREAM,
    LOCAL,
    INTERNAL;

    companion object {
        fun fromWireValue(raw: String?): GatewayErrorCategory? {
            return when (raw?.trim()?.lowercase()) {
                "validation" -> VALIDATION
                "auth" -> AUTH
                "permission" -> PERMISSION
                "not_found" -> NOT_FOUND
                "conflict" -> CONFLICT
                "feature_disabled" -> FEATURE_DISABLED
                "rate_limit" -> RATE_LIMIT
                "too_busy" -> TOO_BUSY
                "network" -> NETWORK
                "upstream" -> UPSTREAM
                "local" -> LOCAL
                "internal" -> INTERNAL
                else -> null
            }
        }
    }
}

data class GatewayProblem(
    val code: String?,
    val category: GatewayErrorCategory,
    val status: Int,
    val title: String?,
    val detail: String?,
    val localizedMessage: String?,
    val locale: String?,
    val retryable: Boolean,
    val requestId: String?,
)

class ChannelSubscriptionService(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        internal const val DEVICE_REGISTER_ENDPOINT = "/device/register"
        internal const val DEVICE_ROUTE_ENDPOINT = "/channel/device"
        internal const val DEVICE_CHANNEL_DELETE_ENDPOINT = "/channel/device/delete"
        internal const val PROVIDER_TOKEN_RETIRE_ENDPOINT = "/channel/device/provider-token/retire"
        internal const val PULL_MESSAGE_V2_ENDPOINT = "/v2/messages/pull"
        internal const val PULL_MESSAGE_ENDPOINT = "/messages/pull"
        internal const val ACK_MESSAGE_ENDPOINT = "/messages/ack"
        internal const val ACK_MESSAGE_V2_ENDPOINT = "/v2/messages/ack"
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
        val data = response.data ?: throw ChannelSubscriptionException.local(
            message = "Request failed",
            code = "gateway_invalid_response",
            category = GatewayErrorCategory.INTERNAL,
        )
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
        val data = response.data ?: throw ChannelSubscriptionException.local(
            message = "Request failed",
            code = "gateway_invalid_response",
            category = GatewayErrorCategory.INTERNAL,
        )
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
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "gateway_response_missing_device_key",
                category = GatewayErrorCategory.INTERNAL,
            )
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
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "missing_device_key",
                category = GatewayErrorCategory.VALIDATION,
            )
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
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "gateway_response_missing_device_key",
                category = GatewayErrorCategory.INTERNAL,
            )
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
    ): ProviderPullPage = withContext(ioDispatcher) {
        val normalizedDeviceKey = deviceKey.trim()
        if (normalizedDeviceKey.isEmpty()) {
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "missing_device_key",
                category = GatewayErrorCategory.VALIDATION,
            )
        }
        val normalizedDeliveryId = deliveryId?.trim()?.takeIf { it.isNotEmpty() }
        val payload = JSONObject().apply {
            put("device_key", normalizedDeviceKey)
            if (normalizedDeliveryId != null) {
                put("delivery_id", normalizedDeliveryId)
            }
        }
        val (response, contract) = try {
            execute(buildUrl(baseUrl, PULL_MESSAGE_V2_ENDPOINT), token, "POST", payload) to
                ProviderPullContract.V2
        } catch (error: ChannelSubscriptionException) {
            if (error.httpStatus == 404 && error.matchesCode("route_not_found")) {
                execute(buildUrl(baseUrl, PULL_MESSAGE_ENDPOINT), token, "POST", payload) to
                    ProviderPullContract.LEGACY
            } else {
                throw error
            }
        }
        val data = response.data ?: throw ChannelSubscriptionException.local(
            message = "Request failed",
            code = "gateway_invalid_response",
            category = GatewayErrorCategory.INTERNAL,
        )
        val wireItems = data.optJSONArray("items")
        val items = buildList {
            if (wireItems == null) return@buildList
            for (index in 0 until wireItems.length()) {
                val item = wireItems.optJSONObject(index) ?: continue
                val itemPayload = item.optJSONObject("payload")?.toStringMap() ?: continue
                val resolvedDeliveryId = item.optString("delivery_id", "").trim()
                if (resolvedDeliveryId.isEmpty()) continue
                add(
                    PullItem(
                        deliveryId = resolvedDeliveryId,
                        payload = itemPayload,
                    )
                )
            }
        }
        return@withContext ProviderPullPage(
            items = items,
            hasMore = contract == ProviderPullContract.V2 && data.optBoolean("has_more", false),
            contract = contract,
        )
    }

    suspend fun ackMessage(
        baseUrl: String,
        token: String?,
        deviceKey: String,
        deliveryId: String,
    ): Boolean = withContext(ioDispatcher) {
        val normalizedDeviceKey = deviceKey.trim()
        if (normalizedDeviceKey.isEmpty()) {
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "missing_device_key",
                category = GatewayErrorCategory.VALIDATION,
            )
        }
        val normalizedDeliveryId = deliveryId.trim()
        if (normalizedDeliveryId.isEmpty()) {
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "missing_delivery_id",
                category = GatewayErrorCategory.VALIDATION,
            )
        }
        val endpoint = buildUrl(baseUrl, ACK_MESSAGE_ENDPOINT)
        val payload = JSONObject().apply {
            put("device_key", normalizedDeviceKey)
            put("delivery_id", normalizedDeliveryId)
        }
        val response = execute(endpoint, token, "POST", payload)
        val data = response.data ?: throw ChannelSubscriptionException.local(
            message = "Request failed",
            code = "gateway_invalid_response",
            category = GatewayErrorCategory.INTERNAL,
        )
        return@withContext data.optBoolean("removed", false)
    }

    suspend fun ackMessages(
        baseUrl: String,
        token: String?,
        deviceKey: String,
        deliveryIds: Collection<String>,
    ): ProviderBatchAckResult = withContext(ioDispatcher) {
        val normalizedDeviceKey = deviceKey.trim()
        if (normalizedDeviceKey.isEmpty()) {
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "missing_device_key",
                category = GatewayErrorCategory.VALIDATION,
            )
        }
        val normalizedDeliveryIds = deliveryIds.mapNotNull { value ->
            value.trim().takeIf { it.isNotEmpty() }
        }.distinct().sorted()
        if (normalizedDeliveryIds.isEmpty() || normalizedDeliveryIds.size > 200) {
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "invalid_delivery_ids",
                category = GatewayErrorCategory.VALIDATION,
            )
        }
        val payload = JSONObject().apply {
            put("device_key", normalizedDeviceKey)
            put("delivery_ids", JSONArray(normalizedDeliveryIds))
        }
        val response = execute(buildUrl(baseUrl, ACK_MESSAGE_V2_ENDPOINT), token, "POST", payload)
        val data = response.data ?: throw ChannelSubscriptionException.local(
            message = "Request failed",
            code = "gateway_invalid_response",
            category = GatewayErrorCategory.INTERNAL,
        )
        val requestedCount = data.strictInt("requested_count") ?: -1
        val removedCount = data.strictInt("removed_count") ?: -1
        if (requestedCount != normalizedDeliveryIds.size || removedCount !in 0..requestedCount) {
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "gateway_ack_count_mismatch",
                category = GatewayErrorCategory.INTERNAL,
            )
        }
        ProviderBatchAckResult(
            requestedCount = requestedCount,
            removedCount = removedCount,
        )
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
        val data = response.data ?: throw ChannelSubscriptionException.local(
            message = "Request failed",
            code = "gateway_invalid_response",
            category = GatewayErrorCategory.INTERNAL,
        )
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
        val data = response.data ?: throw ChannelSubscriptionException.local(
            message = "Request failed",
            code = "gateway_invalid_response",
            category = GatewayErrorCategory.INTERNAL,
        )
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
                            problem = item.optJSONObject("problem")?.toGatewayProblem(
                                statusCode = 400,
                                fallbackCode = item.optString("error_code", "").trim().ifEmpty { null },
                                fallbackDetail = item.optString("error", "").trim().ifEmpty { null },
                            ),
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
        val data = response.data ?: throw ChannelSubscriptionException.local(
            message = "Request failed",
            code = "gateway_invalid_response",
            category = GatewayErrorCategory.INTERNAL,
        )
        val eventId = data.optString("event_id", "").trim()
        if (eventId.isEmpty()) {
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "gateway_response_missing_event_id",
                category = GatewayErrorCategory.INTERNAL,
            )
        }
        if (!data.optBoolean("accepted", false)) {
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "event_request_rejected",
                category = GatewayErrorCategory.INTERNAL,
            )
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
            setRequestProperty("Accept-Language", gatewayAcceptLanguageValue())
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
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (json != null) {
                val success = json.optBoolean("success", false)
                val error = json.optString("error", "").trim()
                val errorCode = json.optString("error_code", "").trim()
                val problem = json.optJSONObject("problem")?.toGatewayProblem(
                    statusCode = code,
                    fallbackCode = errorCode,
                    fallbackDetail = error,
                )
                val data = json.optJSONObject("data")
                if (!success) {
                    throw ChannelSubscriptionException.fromGateway(
                        httpStatus = code,
                        errorCode = errorCode.ifEmpty { null },
                        legacyDetail = error.ifEmpty { null },
                        problem = problem,
                    )
                }
                if (code !in 200..299) {
                    throw ChannelSubscriptionException.fromGateway(
                        httpStatus = code,
                        errorCode = errorCode.ifEmpty { null },
                        legacyDetail = error.ifEmpty { null },
                        problem = problem,
                    )
                }
                return StatusResponse(success = success, data = data)
            }
            if (code !in 200..299) {
                throw ChannelSubscriptionException.fromGateway(
                    httpStatus = code,
                    errorCode = null,
                    legacyDetail = null,
                    problem = null,
                )
            }
            throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "gateway_invalid_response",
                category = GatewayErrorCategory.INTERNAL,
            )
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

    private fun JSONObject.strictInt(key: String): Int? {
        val value = opt(key) as? Number ?: return null
        val longValue = value.toLong()
        if (longValue !in Int.MIN_VALUE..Int.MAX_VALUE) return null
        if (value.toDouble() != longValue.toDouble()) return null
        return longValue.toInt()
    }

    private fun gatewayAcceptLanguageValue(): String {
        val languageTag = Locale.getDefault().toLanguageTag().trim()
        return if (languageTag.isEmpty()) "en" else languageTag
    }

    private fun JSONObject.toGatewayProblem(
        statusCode: Int,
        fallbackCode: String?,
        fallbackDetail: String?,
    ): GatewayProblem? {
        val category = GatewayErrorCategory.fromWireValue(
            opt("category")?.toString()?.trim()?.ifEmpty { null }
        )
            ?: return null
        val code = optString("code", "").trim().ifEmpty { fallbackCode?.trim()?.ifEmpty { null } }
        val status = optInt("status", statusCode).takeIf { it > 0 } ?: statusCode
        val title = optString("title", "").trim().ifEmpty { null }
        val detail = optString("detail", "").trim().ifEmpty { fallbackDetail?.trim()?.ifEmpty { null } }
        val localizedMessage = optString("localized_message", "").trim().ifEmpty { null }
        val locale = optString("locale", "").trim().ifEmpty { null }
        val requestId = optString("request_id", "").trim().ifEmpty { null }
        return GatewayProblem(
            code = code,
            category = category,
            status = status,
            title = title,
            detail = detail,
            localizedMessage = localizedMessage,
            locale = locale,
            retryable = optBoolean("retryable", false),
            requestId = requestId,
        )
    }

    private data class StatusResponse(
        val success: Boolean,
        val data: JSONObject?,
    )
}

class ChannelSubscriptionException(
    message: String,
    val code: String? = null,
    val category: GatewayErrorCategory? = null,
    val localizedMessageText: String? = null,
    val detail: String? = null,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
    val requestId: String? = null,
) : Exception(message) {
    fun matchesCode(expected: String): Boolean {
        return code?.equals(expected, ignoreCase = true) == true
    }

    fun containsLegacyText(expected: String): Boolean {
        val normalized = expected.trim().lowercase()
        if (normalized.isEmpty()) return false
        val candidates = listOfNotNull(detail, message)
        return candidates.any { value ->
            value.trim().lowercase().contains(normalized)
        }
    }

    companion object {
        fun local(
            message: String,
            code: String? = null,
            category: GatewayErrorCategory? = null,
        ): ChannelSubscriptionException {
            return ChannelSubscriptionException(
                message = message,
                code = code,
                category = category,
                localizedMessageText = null,
                detail = message,
                httpStatus = null,
                retryable = false,
                requestId = null,
            )
        }

        fun fromGateway(
            httpStatus: Int,
            errorCode: String?,
            legacyDetail: String?,
            problem: GatewayProblem?,
        ): ChannelSubscriptionException {
            val normalizedCode = errorCode?.trim()?.ifEmpty { null } ?: problem?.code
            val normalizedDetail = legacyDetail?.trim()?.ifEmpty { null } ?: problem?.detail
            val resolved = problem ?: fallbackProblem(
                httpStatus = httpStatus,
                errorCode = normalizedCode,
                detail = normalizedDetail,
            )
            val message = resolved?.localizedMessage?.takeIf { it.isNotBlank() }
                ?: normalizedDetail
                ?: normalizedCode
                ?: if (httpStatus in 200..299) "Request failed" else "Server error: $httpStatus"
            return ChannelSubscriptionException(
                message = message,
                code = resolved?.code ?: normalizedCode,
                category = resolved?.category,
                localizedMessageText = resolved?.localizedMessage,
                detail = resolved?.detail ?: normalizedDetail,
                httpStatus = httpStatus,
                retryable = resolved?.retryable ?: (httpStatus == 429 || httpStatus >= 500),
                requestId = resolved?.requestId,
            )
        }

        private fun fallbackProblem(
            httpStatus: Int,
            errorCode: String?,
            detail: String?,
        ): GatewayProblem? {
            val normalizedCode = errorCode?.lowercase()
            val normalizedDetail = detail?.lowercase()
            val inferred = when (normalizedCode) {
                "authentication_failed" -> Triple("authentication_failed", GatewayErrorCategory.AUTH, false)
                "device_key_not_found", "channel_not_found", "device_not_found" ->
                    Triple(normalizedCode, GatewayErrorCategory.NOT_FOUND, false)
                "invalid_channel_id", "invalid_password", "invalid_platform", "invalid_device_token", "provider_token_missing", "provider_token_required", "channel_subscriber_limit_exceeded" ->
                    Triple(normalizedCode, GatewayErrorCategory.VALIDATION, false)
                "password_mismatch", "invalid_channel_password", "platform_mismatch", "channel_type_mismatch" ->
                    Triple(normalizedCode, GatewayErrorCategory.CONFLICT, false)
                "private_channel_disabled" ->
                    Triple("private_channel_disabled", GatewayErrorCategory.FEATURE_DISABLED, false)
                "server_busy", "private_channel_runtime_unavailable" ->
                    Triple(normalizedCode, GatewayErrorCategory.TOO_BUSY, true)
                "upstream_error" ->
                    Triple("upstream_error", GatewayErrorCategory.UPSTREAM, true)
                "internal_error", "store_error" ->
                    Triple(normalizedCode, GatewayErrorCategory.INTERNAL, true)
                else -> inferFallbackFromStatus(httpStatus, normalizedDetail)
            } ?: return null
            return GatewayProblem(
                code = inferred.first,
                category = inferred.second,
                status = httpStatus,
                title = null,
                detail = detail,
                localizedMessage = null,
                locale = null,
                retryable = inferred.third,
                requestId = null,
            )
        }

        private fun inferFallbackFromStatus(
            httpStatus: Int,
            detail: String?,
        ): Triple<String?, GatewayErrorCategory, Boolean>? {
            if (detail?.contains("private channel is disabled") == true) {
                return Triple("private_channel_disabled", GatewayErrorCategory.FEATURE_DISABLED, false)
            }
            if (detail?.contains("device_key not found") == true || detail?.contains("device key not found") == true) {
                return Triple("device_key_not_found", GatewayErrorCategory.NOT_FOUND, false)
            }
            if (detail?.contains("device_not_found") == true || detail?.contains("device not found") == true) {
                return Triple("device_not_found", GatewayErrorCategory.NOT_FOUND, false)
            }
            if (detail?.contains("channel_not_found") == true || detail?.contains("channel not found") == true) {
                return Triple("channel_not_found", GatewayErrorCategory.NOT_FOUND, false)
            }
            if (detail?.contains("password_mismatch") == true ||
                detail?.contains("password mismatch") == true ||
                detail?.contains("invalid channel password") == true
            ) {
                return Triple("password_mismatch", GatewayErrorCategory.CONFLICT, false)
            }
            if (detail?.contains("invalid_channel_id") == true || detail?.contains("invalid channel id") == true) {
                return Triple("invalid_channel_id", GatewayErrorCategory.VALIDATION, false)
            }
            if (detail?.contains("channel_id_required") == true || detail?.contains("channel id required") == true) {
                return Triple("channel_id_required", GatewayErrorCategory.VALIDATION, false)
            }
            if (detail?.contains("invalid_password") == true || detail?.contains("invalid password") == true) {
                return Triple("invalid_password", GatewayErrorCategory.VALIDATION, false)
            }
            if (detail?.contains("channel_subscriber_limit_exceeded") == true ||
                detail?.contains("subscriber limit") == true
            ) {
                return Triple("channel_subscriber_limit_exceeded", GatewayErrorCategory.VALIDATION, false)
            }
            return when (httpStatus) {
                400, 422 -> Triple(null, GatewayErrorCategory.VALIDATION, false)
                401 -> Triple("authentication_failed", GatewayErrorCategory.AUTH, false)
                403 -> Triple(null, GatewayErrorCategory.PERMISSION, false)
                404 -> Triple(null, GatewayErrorCategory.NOT_FOUND, false)
                429 -> Triple(null, GatewayErrorCategory.RATE_LIMIT, true)
                502, 504 -> Triple("upstream_error", GatewayErrorCategory.UPSTREAM, true)
                503 -> Triple("server_busy", GatewayErrorCategory.TOO_BUSY, true)
                in 500..599 -> Triple("internal_error", GatewayErrorCategory.INTERNAL, true)
                else -> null
            }
        }
    }
}
