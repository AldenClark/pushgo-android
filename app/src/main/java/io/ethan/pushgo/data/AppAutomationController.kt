package io.ethan.pushgo.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.db.OperationLedgerDao
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.AckDrainOutcome
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.notifications.PrivateChannelClient
import io.ethan.pushgo.notifications.PrivateChannelServiceManager
import io.ethan.pushgo.util.UrlValidators
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID

data class AutomationSnapshot(
    val gatewayBaseUrl: String?,
    val gatewayTokenPresent: Boolean,
    val useFcmChannel: Boolean,
    val providerMode: String,
    val providerDeviceKeyPresent: Boolean,
    val privateRoute: String,
    val privateTransport: String,
    val privateStage: String,
    val privateDetail: String?,
    val ackPendingCount: Int,
    val channelCount: Int,
    val totalMessageCount: Int,
    val unreadMessageCount: Int,
    val eventCount: Int,
    val thingCount: Int,
    val messagePageEnabled: Boolean,
    val eventPageEnabled: Boolean,
    val thingPageEnabled: Boolean,
    val notificationKeyConfigured: Boolean,
    val notificationKeyEncoding: String,
    val lastNotificationAction: String?,
    val lastNotificationTarget: String?,
    val lastFixtureImportPath: String?,
    val lastFixtureImportMessageCount: Int,
    val lastFixtureImportEntityRecordCount: Int,
    val lastFixtureImportSubscriptionCount: Int,
    val openedMessageDecryptionState: String?,
)

data class AutomationNotificationTarget(
    val messageId: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
)

data class FixtureImportSummary(
    val importedMessages: Int,
    val importedEntities: Int,
    val importedSubscriptions: Int,
)

class AppAutomationController(
    private val appContext: Context,
    private val operationLedgerDao: OperationLedgerDao,
    private val settingsRepository: SettingsRepository,
    private val channelStore: ChannelSubscriptionStore,
    private val messageRepository: MessageRepository,
    private val entityRepository: EntityRepository,
    private val messageStateCoordinator: MessageStateCoordinator,
    private val channelRepository: ChannelSubscriptionRepository,
    private val privateChannelClient: PrivateChannelClient,
    private val inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
    private val messageImageStore: MessageImageStore,
) {
    private var lastNotificationAction: String? = null
    private var lastNotificationTarget: String? = null
    private var lastFixtureImportPath: String? = null
    private var lastFixtureImportMessageCount: Int = 0
    private var lastFixtureImportEntityRecordCount: Int = 0
    private var lastFixtureImportSubscriptionCount: Int = 0

    suspend fun setPageVisibility(page: String, enabled: Boolean) {
        when (page.trim().lowercase()) {
            "message", "messages" -> settingsRepository.setMessagePageEnabled(enabled)
            "event", "events" -> settingsRepository.setEventPageEnabled(enabled)
            "thing", "things" -> settingsRepository.setThingPageEnabled(enabled)
            else -> error("Unsupported page: $page")
        }
    }

    suspend fun setDecryptionKey(key: String?, encoding: String?) {
        val normalizedEncoding = parseKeyEncoding(encoding)
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            settingsRepository.setNotificationKeyBytes(null)
            settingsRepository.setKeyEncoding(normalizedEncoding)
            return
        }
        val normalizedBytes = NotificationKeyValidator.normalizedKeyBytes(trimmed, normalizedEncoding)
        settingsRepository.setNotificationKeyBytes(normalizedBytes)
        settingsRepository.setKeyEncoding(normalizedEncoding)
    }

    suspend fun setGatewayServer(baseUrl: String?, token: String?) {
        val previousAddress = settingsRepository.getServerAddress()
            ?.trim()
            ?.ifEmpty { null }
            ?: AppConstants.defaultServerAddress
        val previousToken = settingsRepository.getGatewayToken()
            ?.trim()
            ?.ifEmpty { null }
        val normalizedAddress = baseUrl
            ?.trim()
            ?.ifEmpty { null }
            ?.let { normalizeAutomationGatewayBaseUrl(it) }
            ?: AppConstants.defaultServerAddress
        val normalizedToken = token?.trim()?.ifEmpty { null }
        settingsRepository.setServerAddress(normalizedAddress)
        settingsRepository.setGatewayToken(normalizedToken)
        if ("${previousAddress}|${previousToken.orEmpty()}" != "${normalizedAddress}|${normalizedToken.orEmpty()}") {
            privateChannelClient.onGatewayConfigChanged()
        }

        val useFcmChannel = settingsRepository.getUseFcmChannel()
        if (useFcmChannel) {
            val activeFcmToken = settingsRepository.getFcmToken()?.trim()?.ifEmpty { null }
            if (activeFcmToken != null) {
                channelRepository.syncProviderDeviceToken(activeFcmToken)
                channelRepository.syncSubscriptionsIfNeeded(activeFcmToken)
            }
            privateChannelClient.setRuntime(
                fcmAvailable = true,
                systemToken = activeFcmToken,
            )
            PrivateChannelServiceManager.refreshForMode(appContext, true)
            return
        }

        val previousFcmToken = settingsRepository.getFcmToken()
        settingsRepository.setFcmToken(null)
        privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
        PrivateChannelServiceManager.refreshForMode(appContext, false)
        runCatching {
            privateChannelClient.switchToPrivateAndRetireProvider("fcm", previousFcmToken)
        }
    }

    private fun normalizeAutomationGatewayBaseUrl(raw: String): String? {
        val normalized = UrlValidators.normalizeGatewayBaseUrl(raw)
        if (normalized != null) {
            return normalized
        }
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.trim()?.lowercase()
        val host = uri.host?.trim()?.lowercase()
        if (scheme != "http" || host.isNullOrEmpty()) {
            return null
        }
        if (host != "127.0.0.1" && host != "localhost" && host != "10.0.2.2") {
            return null
        }
        val port = uri.port
        return if (port > 0) {
            "http://$host:$port"
        } else {
            "http://$host"
        }
    }

    fun triggerProviderWakeupRecovery() {
        privateChannelClient.triggerProviderWakeupRecovery()
    }

    suspend fun drainPrivateAcks(): AckDrainOutcome {
        return privateChannelClient.drainAckOutboxNow()
    }

    suspend fun resolveNotificationTarget(notificationRequestId: String): AutomationNotificationTarget? {
        val message = resolveNotificationMessage(
            notificationRequestId = notificationRequestId,
            messageId = null,
        ) ?: return null
        return AutomationNotificationTarget(messageId = message.id)
    }

    suspend fun importFixture(path: String): FixtureImportSummary {
        return importFixture(path, FixtureImportMode.ALL)
    }

    suspend fun seedFixtureMessages(path: String): FixtureImportSummary {
        return importFixture(path, FixtureImportMode.MESSAGES)
    }

    suspend fun seedFixtureEntityRecords(path: String): FixtureImportSummary {
        return importFixture(path, FixtureImportMode.ENTITY_RECORDS)
    }

    suspend fun seedFixtureSubscriptions(path: String): FixtureImportSummary {
        return importFixture(path, FixtureImportMode.SUBSCRIPTIONS)
    }

    private suspend fun importFixture(path: String, mode: FixtureImportMode): FixtureImportSummary {
        val file = resolveFixtureFile(path.trim())
        require(file.isFile) { "Fixture file not found: ${file.path}" }
        val payload = JSONObject(file.readText())

        var importedMessages = 0
        if (mode.includesMessages) {
            payload.optJSONArrayCompat("messages", "message_records")?.let { array ->
                for (index in 0 until array.length()) {
                    val record = array.optJSONObject(index) ?: continue
                    if (messageRepository.insertIncoming(record.asFixtureMessage())) {
                        importedMessages += 1
                    }
                }
            }
        }

        var importedEntities = 0
        if (mode.includesEntityRecords) {
            payload.optJSONArrayCompat("entity_records")?.let { array ->
                for (index in 0 until array.length()) {
                    val record = array.optJSONObject(index)?.asFixtureEntityRecord() ?: continue
                    if (entityRepository.insertIncoming(record)) {
                        importedEntities += 1
                    }
                }
            }
        }

        val defaultGatewayUrl = settingsRepository.getServerAddress()?.trim()?.ifEmpty { null }
            ?: AppConstants.defaultServerAddress
        var importedSubscriptions = 0
        if (mode.includesSubscriptions) {
            payload.optJSONArrayCompat("channel_subscriptions")?.let { array ->
                for (index in 0 until array.length()) {
                    val record = array.optJSONObject(index) ?: continue
                    val channelId = record.optStringCompat("channel_id") ?: continue
                    val displayName = record.optStringCompat("display_name")
                        ?: channelId
                    val gatewayUrl = record.optStringCompat("gateway_base_url")
                        ?.let { UrlValidators.normalizeGatewayBaseUrl(it) }
                        ?: defaultGatewayUrl
                    channelStore.upsertSubscription(
                        gatewayUrl = gatewayUrl,
                        channelId = channelId,
                        displayName = displayName,
                        password = record.optStringCompat("password", "channel_password").orEmpty(),
                        lastSyncedAt = record.optEpochMillisCompat("last_synced_at"),
                    )
                    importedSubscriptions += 1
                }
            }
        }

        lastFixtureImportPath = file.path
        lastFixtureImportMessageCount = importedMessages
        lastFixtureImportEntityRecordCount = importedEntities
        lastFixtureImportSubscriptionCount = importedSubscriptions
        PushGoAutomation.writeEvent(
            type = "fixture.imported",
            command = null,
            details = JSONObject()
                .put("path", file.path)
                .put("message_count", importedMessages)
                .put("entity_record_count", importedEntities)
                .put("subscription_count", importedSubscriptions),
        )

        return FixtureImportSummary(
            importedMessages = importedMessages,
            importedEntities = importedEntities,
            importedSubscriptions = importedSubscriptions,
        )
    }

    suspend fun markNotificationRead(
        notificationRequestId: String?,
        messageId: String?,
    ): String? {
        val message = resolveNotificationMessage(notificationRequestId, messageId) ?: return null
        messageStateCoordinator.markRead(message.id)
        recordNotificationAction("mark_read", message.id)
        return message.id
    }

    suspend fun deleteNotification(
        notificationRequestId: String?,
        messageId: String?,
    ): String? {
        val message = resolveNotificationMessage(notificationRequestId, messageId) ?: return null
        messageStateCoordinator.deleteMessage(message.id)
        recordNotificationAction("delete", message.id)
        return message.id
    }

    suspend fun copyNotification(
        notificationRequestId: String?,
        messageId: String?,
    ): String? {
        val message = resolveNotificationMessage(notificationRequestId, messageId) ?: return null
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "pushgo",
                buildString {
                    append(message.title)
                    if (message.body.isNotBlank()) {
                        append("\n")
                        append(message.body)
                    }
                }
            )
        )
        NotificationHelper.cancelMessageNotification(appContext, message.id)
        recordNotificationAction("copy", message.id)
        return message.id
    }

    suspend fun resetLocalState() {
        messageStateCoordinator.deleteAllMessages()
        entityRepository.deleteAll()
        inboundDeliveryLedgerRepository.clearAll()
        operationLedgerDao.deleteAll()
        channelStore.clearAll()
        settingsRepository.resetForAutomation(defaultServerAddress = AppConstants.defaultServerAddress)
        messageImageStore.clearAll()
        privateChannelClient.resetForAutomation()
        privateChannelClient.setRuntime(
            fcmAvailable = settingsRepository.getUseFcmChannel(),
            systemToken = settingsRepository.getFcmToken()?.trim()?.ifEmpty { null },
        )
        PrivateChannelServiceManager.refresh(appContext)
        lastNotificationAction = null
        lastNotificationTarget = null
        lastFixtureImportPath = null
        lastFixtureImportMessageCount = 0
        lastFixtureImportEntityRecordCount = 0
        lastFixtureImportSubscriptionCount = 0
    }

    suspend fun snapshot(): AutomationSnapshot {
        return snapshotForOpenedMessage(null)
    }

    suspend fun snapshotForOpenedMessage(messageId: String?): AutomationSnapshot {
        val gatewayBaseUrl = settingsRepository.getServerAddress()?.trim()?.ifEmpty { null }
            ?: AppConstants.defaultServerAddress
        val gatewayTokenPresent = settingsRepository.getGatewayToken()?.trim()?.isNotEmpty() == true
        val useFcmChannel = settingsRepository.getUseFcmChannel()
        val providerDeviceKeyPresent = settingsRepository
            .getProviderDeviceKey(platform = "android")
            ?.trim()
            ?.isNotEmpty() == true
        val notificationKeyBytes = settingsRepository.getNotificationKeyBytes()
        val keyEncoding = settingsRepository.getKeyEncoding()
        val privateStatus = privateChannelClient.readTransportStatus()
        val ackPendingCount = inboundDeliveryLedgerRepository.loadPendingAckIds(limit = 500).size
        val channelCount = channelStore.countActive(gatewayBaseUrl)
        val openedMessageDecryptionState = messageId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { currentMessageId ->
                messageRepository.getByMessageId(currentMessageId)
                    ?: messageRepository.getById(currentMessageId)
            }
            ?.decryptionState
            ?.toWireValue()

        return AutomationSnapshot(
            gatewayBaseUrl = gatewayBaseUrl,
            gatewayTokenPresent = gatewayTokenPresent,
            useFcmChannel = useFcmChannel,
            providerMode = if (useFcmChannel) "fcm" else "none",
            providerDeviceKeyPresent = providerDeviceKeyPresent,
            privateRoute = privateStatus.route,
            privateTransport = privateStatus.transport,
            privateStage = privateStatus.stage,
            privateDetail = privateStatus.detail,
            ackPendingCount = ackPendingCount,
            channelCount = channelCount,
            totalMessageCount = messageRepository.totalCount(),
            unreadMessageCount = messageRepository.unreadCount(),
            eventCount = entityRepository.eventCount(),
            thingCount = entityRepository.thingCount(),
            messagePageEnabled = settingsRepository.getMessagePageEnabled(),
            eventPageEnabled = settingsRepository.getEventPageEnabled(),
            thingPageEnabled = settingsRepository.getThingPageEnabled(),
            notificationKeyConfigured = notificationKeyBytes?.isNotEmpty() == true,
            notificationKeyEncoding = keyEncoding.name.lowercase(),
            lastNotificationAction = lastNotificationAction,
            lastNotificationTarget = lastNotificationTarget,
            lastFixtureImportPath = lastFixtureImportPath,
            lastFixtureImportMessageCount = lastFixtureImportMessageCount,
            lastFixtureImportEntityRecordCount = lastFixtureImportEntityRecordCount,
            lastFixtureImportSubscriptionCount = lastFixtureImportSubscriptionCount,
            openedMessageDecryptionState = openedMessageDecryptionState,
        )
    }

    private fun recordNotificationAction(action: String, messageId: String) {
        lastNotificationAction = action
        lastNotificationTarget = messageId
        PushGoAutomation.writeEvent(
            type = "notification.action",
            command = null,
            details = JSONObject()
                .put("action", action)
                .put("target", messageId),
        )
    }

    private fun resolveFixtureFile(rawPath: String): File {
        val path = rawPath.trim()
        if (path.startsWith("files://")) {
            val relative = path.removePrefix("files://").trimStart('/')
            return appContext.filesDir.resolve(relative)
        }
        val direct = File(path)
        if (direct.isFile) {
            return direct
        }
        val externalFiles = appContext.getExternalFilesDir(null)
        val externalPrefix = "/sdcard/Android/data/${appContext.packageName}/files/"
        if (externalFiles != null && path.startsWith(externalPrefix)) {
            val relative = path.removePrefix(externalPrefix).trimStart('/')
            return externalFiles.resolve(relative)
        }
        return direct
    }

    private fun parseKeyEncoding(raw: String?): KeyEncoding {
        return NotificationKeyValidator.parseEncoding(raw)
    }

    private enum class FixtureImportMode(
        val includesMessages: Boolean,
        val includesEntityRecords: Boolean,
        val includesSubscriptions: Boolean,
    ) {
        ALL(true, true, true),
        MESSAGES(true, false, false),
        ENTITY_RECORDS(false, true, false),
        SUBSCRIPTIONS(false, false, true),
    }

    private suspend fun resolveNotificationMessage(
        notificationRequestId: String?,
        messageId: String?,
    ): PushMessage? {
        notificationRequestId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { requestId ->
                messageRepository.getByNotificationId(requestId)?.let { return it }
            }

        val normalizedMessageId = messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return messageRepository.getByMessageId(normalizedMessageId)
            ?: messageRepository.getById(normalizedMessageId)
    }
}

private fun DecryptionState.toWireValue(): String {
    return when (this) {
        DecryptionState.NOT_CONFIGURED -> "notConfigured"
        DecryptionState.ALG_MISMATCH -> "algMismatch"
        DecryptionState.DECRYPT_OK -> "decryptOk"
        DecryptionState.DECRYPT_FAILED -> "decryptFailed"
    }
}

private fun JSONObject.asFixtureMessage(): PushMessage {
    val rawPayloadJson = resolveRawPayloadJson(
        fallback = buildDefaultMessagePayload(),
    )
    return PushMessage(
        id = optStringCompat("id") ?: UUID.randomUUID().toString(),
        messageId = optStringCompat("message_id"),
        title = optStringCompat("title").orEmpty(),
        body = optStringCompat("body").orEmpty(),
        channel = optStringCompat("channel", "channel_id"),
        url = optStringCompat("url"),
        isRead = optBooleanCompat("is_read") ?: false,
        receivedAt = optInstantCompat("received_at") ?: Instant.now(),
        rawPayloadJson = rawPayloadJson,
        status = optStringCompat("status").toMessageStatus(),
        decryptionState = optStringCompat("decryption_state").toDecryptionState(),
        notificationId = optStringCompat(
            "notification_request_id",
            "notification_id",
        ),
        serverId = optStringCompat("server_id"),
        bodyPreview = optStringCompat("body_preview"),
    )
}

private fun JSONObject.asFixtureEntityRecord(): IncomingEntityRecord? {
    val rawPayload = optRawPayloadObject()
    val entityType = (
        optStringCompat("entity_type")
            ?: rawPayload?.optStringCompat("entity_type")
        )
        ?.trim()
        ?.lowercase()
        ?.takeIf { it == "event" || it == "thing" }
        ?: return null
    val entityId = optStringCompat("entity_id")
        ?: rawPayload?.optStringCompat("entity_id")
        ?: when (entityType) {
            "event" -> optStringCompat("event_id")
                ?: rawPayload?.optStringCompat("event_id")
            "thing" -> optStringCompat("thing_id")
                ?: rawPayload?.optStringCompat("thing_id")
            else -> null
        }
        ?: return null
    val rawPayloadJson = resolveRawPayloadJson(
        fallback = buildDefaultEntityPayload(entityType = entityType, entityId = entityId),
    )
    return IncomingEntityRecord(
        entityType = entityType,
        entityId = entityId,
        channel = optStringCompat("channel", "channel_id"),
        title = optStringCompat("title").orEmpty(),
        body = optStringCompat("body", "description").orEmpty(),
        rawPayloadJson = rawPayloadJson,
        receivedAt = optInstantCompat("received_at") ?: Instant.now(),
        opId = optStringCompat("op_id"),
        deliveryId = optStringCompat("delivery_id"),
        serverId = optStringCompat("server_id"),
        eventId = optStringCompat("event_id")
            ?: rawPayload?.optStringCompat("event_id"),
        thingId = optStringCompat("thing_id")
            ?: rawPayload?.optStringCompat("thing_id"),
        eventState = optStringCompat("event_state")
            ?: rawPayload?.optStringCompat("event_state"),
        eventTimeEpoch = optEpochMillisCompat("event_time_epoch"),
        observedTimeEpoch = optEpochMillisCompat("observed_time_epoch"),
    )
}

private fun JSONObject.resolveRawPayloadJson(fallback: JSONObject): String {
    optJsonStringCompat("raw_payload_json", "raw_payload")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    return fallback.toString()
}

private fun JSONObject.buildDefaultMessagePayload(): JSONObject {
    val payload = JSONObject()
    optStringCompat("message_id")?.let {
        payload.put("message_id", it)
        payload.put("delivery_id", it)
        payload.put("entity_id", it)
    }
    optStringCompat("channel", "channel_id")?.let { payload.put("channel_id", it) }
    optStringCompat("title")?.let { payload.put("title", it) }
    optStringCompat("body")?.let { payload.put("body", it) }
    optStringCompat("url")?.let { payload.put("url", it) }
    optStringCompat("thing_id")?.let { payload.put("thing_id", it) }
    optStringCompat("event_id")?.let { payload.put("event_id", it) }
    optStringCompat("event_state")?.let { payload.put("event_state", it) }
    optStringCompat("severity")?.let { payload.put("severity", it) }
    optStringCompat("op_id")?.let { payload.put("op_id", it) }
    optStringCompat("delivery_id")?.let { payload.put("delivery_id", it) }
    optJsonValueCompat("metadata")?.let { payload.put("metadata", it) }
    optJsonValueCompat("images")?.let { payload.put("images", it) }
    optJsonValueCompat("tags")?.let { payload.put("tags", it) }
    payload.put("entity_type", "message")
    return payload
}

private fun JSONObject.buildDefaultEntityPayload(
    entityType: String,
    entityId: String,
): JSONObject {
    val payload = JSONObject()
    payload.put("entity_type", entityType)
    payload.put("entity_id", entityId)
    optStringCompat("channel", "channel_id")?.let { payload.put("channel_id", it) }
    optStringCompat("title")?.let { payload.put("title", it) }
    optStringCompat("body", "description")?.let { payload.put("body", it) }
    optStringCompat("op_id")?.let { payload.put("op_id", it) }
    optStringCompat("delivery_id")?.let { payload.put("delivery_id", it) }
    optStringCompat("event_id")?.let { payload.put("event_id", it) }
    optStringCompat("thing_id")?.let { payload.put("thing_id", it) }
    optStringCompat("event_state")?.let { payload.put("event_state", it) }
    optStringCompat("severity")?.let { payload.put("severity", it) }
    optJsonValueCompat("metadata")?.let { payload.put("metadata", it) }
    return payload
}

private fun JSONObject.optStringCompat(vararg keys: String): String? {
    keys.forEach { key ->
        if (!has(key) || isNull(key)) return@forEach
        val value = optString(key).trim()
        if (value.isNotEmpty()) {
            return value
        }
    }
    return null
}

private fun JSONObject.optBooleanCompat(vararg keys: String): Boolean? {
    keys.forEach { key ->
        if (!has(key) || isNull(key)) return@forEach
        return when (val value = opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
            else -> null
        }
    }
    return null
}

private fun JSONObject.optEpochMillisCompat(vararg keys: String): Long? {
    keys.forEach { key ->
        if (!has(key) || isNull(key)) return@forEach
        val value = opt(key)
        when (value) {
            is Number -> {
                val epoch = value.toLong()
                return if (epoch > 9_999_999_999L) epoch else epoch * 1_000L
            }
            is String -> {
                val raw = value.trim()
                if (raw.isEmpty()) return@forEach
                raw.toLongOrNull()?.let { epoch ->
                    return if (epoch > 9_999_999_999L) epoch else epoch * 1_000L
                }
                runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()?.let { return it }
            }
        }
    }
    return null
}

private fun JSONObject.optInstantCompat(vararg keys: String): Instant? {
    return optEpochMillisCompat(*keys)?.let(Instant::ofEpochMilli)
}

private fun JSONObject.optJSONArrayCompat(vararg keys: String): JSONArray? {
    keys.forEach { key ->
        val value = opt(key)
        when (value) {
            is JSONArray -> return value
            is String -> runCatching { JSONArray(value) }.getOrNull()?.let { return it }
        }
    }
    return null
}

private fun JSONObject.optJsonStringCompat(vararg keys: String): String? {
    keys.forEach { key ->
        val value = opt(key)
        when (value) {
            is JSONObject, is JSONArray -> return value.toString()
            is String -> {
                val trimmed = value.trim()
                if (trimmed.isNotEmpty()) {
                    return trimmed
                }
            }
        }
    }
    return null
}

private fun JSONObject.optRawPayloadObject(): JSONObject? {
    val value = opt("raw_payload")
    return when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
        ?: optJsonStringCompat("raw_payload_json", "raw_payload")
            ?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }
}

private fun JSONObject.optJsonValueCompat(vararg keys: String): Any? {
    keys.forEach { key ->
        val value = opt(key)
        when (value) {
            null, JSONObject.NULL -> Unit
            is String -> {
                val trimmed = value.trim()
                if (trimmed.isEmpty()) {
                    Unit
                } else {
                    runCatching { JSONArray(trimmed) }.getOrNull()?.let { return it }
                    runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }
                    return trimmed
                }
            }
            else -> return value
        }
    }
    return null
}

private fun String?.toMessageStatus(): MessageStatus {
    return when (this?.trim()?.uppercase()) {
        "MISSING" -> MessageStatus.MISSING
        "PARTIALLY_DECRYPTED" -> MessageStatus.PARTIALLY_DECRYPTED
        "DECRYPTED" -> MessageStatus.DECRYPTED
        else -> MessageStatus.NORMAL
    }
}

private fun String?.toDecryptionState(): DecryptionState? {
    return when (this?.trim()?.uppercase()) {
        "NOT_CONFIGURED", "NOTCONFIGURED" -> DecryptionState.NOT_CONFIGURED
        "ALG_MISMATCH", "ALGMISMATCH" -> DecryptionState.ALG_MISMATCH
        "DECRYPT_OK", "DECRYPTOK" -> DecryptionState.DECRYPT_OK
        "DECRYPT_FAILED", "DECRYPTFAILED" -> DecryptionState.DECRYPT_FAILED
        else -> null
    }
}
