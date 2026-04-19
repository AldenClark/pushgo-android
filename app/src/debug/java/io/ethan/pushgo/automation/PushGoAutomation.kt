package io.ethan.pushgo.automation

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.util.Base64

object PushGoAutomation {
    const val EXTRA_REQUEST_JSON = "io.ethan.pushgo.automation.REQUEST_JSON"
    const val EXTRA_RESPONSE_PATH = "io.ethan.pushgo.automation.RESPONSE_PATH"
    const val EXTRA_STATE_PATH = "io.ethan.pushgo.automation.STATE_PATH"
    const val EXTRA_EVENTS_PATH = "io.ethan.pushgo.automation.EVENTS_PATH"
    const val EXTRA_TRACE_PATH = "io.ethan.pushgo.automation.TRACE_PATH"
    const val EXTRA_GATEWAY_BASE_URL = "io.ethan.pushgo.automation.GATEWAY_BASE_URL"
    const val EXTRA_GATEWAY_TOKEN = "io.ethan.pushgo.automation.GATEWAY_TOKEN"

    data class AutomationRequest(
        val id: String?,
        val name: String,
        val args: Map<String, String>,
    ) {
        fun stringArg(key: String): String? = args[key]?.trim()?.ifEmpty { null }

        fun booleanArg(key: String): Boolean? {
            return when (stringArg(key)?.lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
        }
    }

    data class AutomationState(
        val platform: String = "android",
        val buildMode: String = "debug",
        val activeTab: String,
        val visibleScreen: String,
        val openedMessageId: String?,
        val openedMessageDecryptionState: String?,
        val openedEntityType: String?,
        val openedEntityId: String?,
        val pendingEventId: String?,
        val pendingThingId: String?,
        val unreadMessageCount: Int,
        val totalMessageCount: Int,
        val eventCount: Int,
        val thingCount: Int,
        val messagePageEnabled: Boolean,
        val eventPageEnabled: Boolean,
        val thingPageEnabled: Boolean,
        val notificationKeyConfigured: Boolean,
        val notificationKeyEncoding: String?,
        val gatewayBaseUrl: String?,
        val gatewayTokenPresent: Boolean,
        val useFcmChannel: Boolean,
        val providerMode: String,
        val deviceKeyPresent: Boolean,
        val privateRoute: String,
        val privateTransport: String,
        val privateStage: String,
        val privateDetail: String?,
        val ackPendingCount: Int,
        val channelCount: Int,
        val lastNotificationAction: String?,
        val lastNotificationTarget: String?,
        val lastFixtureImportPath: String?,
        val lastFixtureImportMessageCount: Int,
        val lastFixtureImportEntityRecordCount: Int,
        val lastFixtureImportSubscriptionCount: Int,
        val runtimeErrorCount: Int,
        val latestRuntimeErrorSource: String?,
        val latestRuntimeErrorCategory: String?,
        val latestRuntimeErrorCode: String?,
        val latestRuntimeErrorMessage: String?,
        val latestRuntimeErrorTimestamp: String?,
    )

    data class RuntimeErrorSnapshot(
        val source: String,
        val category: String,
        val code: String?,
        val message: String,
        val timestamp: String,
    )

    data class TraceRecord(
        val timestamp: String,
        val platform: String = "android",
        val kind: String,
        val traceId: String,
        val spanId: String,
        val parentSpanId: String?,
        val requestId: String?,
        val sessionId: String?,
        val domain: String,
        val operation: String,
        val status: String?,
        val durationMs: Int?,
        val attributes: Map<String, String>,
        val errorCode: String?,
        val errorMessage: String?,
    )

    data class ActiveTrace(
        val traceId: String,
        val spanId: String,
        val requestId: String?,
        val command: String,
        val startedAtMillis: Long,
    )

    private var responsePath: String? = null
    private var statePath: String? = null
    private var eventsPath: String? = null
    private var tracePath: String? = null
    private var filesDirectory: File? = null
    private var pendingRequest: AutomationRequest? = null
    private var lastPublishedState: AutomationState? = null
    private var runtimeErrorCount: Int = 0
    private var latestRuntimeError: RuntimeErrorSnapshot? = null
    private var startupGatewayBaseUrl: String? = null
    private var startupGatewayToken: String? = null
    private var activeTrace: ActiveTrace? = null
    var requestVersion by mutableIntStateOf(0)
        private set

    fun configureFromIntent(intent: Intent?, filesDir: File? = null) {
        if (intent == null) return
        filesDirectory = filesDir ?: filesDirectory
        responsePath = normalized(intent.getStringExtra(EXTRA_RESPONSE_PATH)) ?: responsePath
        statePath = normalized(intent.getStringExtra(EXTRA_STATE_PATH)) ?: statePath
        eventsPath = normalized(intent.getStringExtra(EXTRA_EVENTS_PATH)) ?: eventsPath
        tracePath = normalized(intent.getStringExtra(EXTRA_TRACE_PATH)) ?: tracePath
        startupGatewayBaseUrl = normalized(intent.getStringExtra(EXTRA_GATEWAY_BASE_URL)) ?: startupGatewayBaseUrl
        startupGatewayToken = normalized(intent.getStringExtra(EXTRA_GATEWAY_TOKEN)) ?: startupGatewayToken
        val rawRequest = normalized(intent.getStringExtra(EXTRA_REQUEST_JSON)) ?: return
        pendingRequest = decodeRequest(rawRequest)
        requestVersion += 1
    }

    fun consumePendingRequest(): AutomationRequest? {
        val request = pendingRequest
        pendingRequest = null
        return request
    }

    fun publishState(state: AutomationState) {
        val previousState = lastPublishedState
        lastPublishedState = state
        writeJson(statePath, state.toJson().toString(2))
        writeEvent(
            type = "state.updated",
            command = null,
            details = JSONObject()
                .put("active_tab", state.activeTab)
                .put("visible_screen", state.visibleScreen),
        )
        writeDerivedEvents(previousState, state)
    }

    fun writeResponse(
        request: AutomationRequest?,
        ok: Boolean,
        state: AutomationState?,
        error: String?,
    ) {
        val json = JSONObject()
            .put("id", request?.id)
            .put("ok", ok)
            .put("platform", "android")
            .put("error", error)
            .put("state", state?.toJson())
        writeJson(responsePath, json.toString(2))
    }

    fun writeEvent(
        type: String,
        command: String?,
        details: JSONObject = JSONObject(),
    ) {
        val target = resolvedFile(eventsPath) ?: return
        runCatching {
            val payload = JSONObject()
                .put("timestamp", java.time.Instant.now().toString())
                .put("platform", "android")
                .put("type", type)
                .put("command", command)
                .put("details", details)
            target.apply {
                parentFile?.mkdirs()
                appendText(payload.toString() + "\n")
            }
        }
        writeTraceAnnotation(type, command, details)
    }

    fun startCommandTrace(request: AutomationRequest) {
        val requestId = normalized(request.id)
        val traceId = requestId ?: java.util.UUID.randomUUID().toString().lowercase()
        val spanId = java.util.UUID.randomUUID().toString().lowercase()
        activeTrace = ActiveTrace(
            traceId = traceId,
            spanId = spanId,
            requestId = requestId,
            command = request.name,
            startedAtMillis = System.currentTimeMillis(),
        )
        writeTraceRecord(
            TraceRecord(
                timestamp = java.time.Instant.now().toString(),
                kind = "span.start",
                traceId = traceId,
                spanId = spanId,
                parentSpanId = null,
                requestId = requestId,
                sessionId = resolvedFile(statePath)?.parentFile?.name,
                domain = "automation",
                operation = "command",
                status = null,
                durationMs = null,
                attributes = mapOf("command" to request.name),
                errorCode = null,
                errorMessage = null,
            )
        )
    }

    fun finishCommandTrace(error: String?) {
        val trace = activeTrace ?: return
        val durationMs = (System.currentTimeMillis() - trace.startedAtMillis).toInt().coerceAtLeast(0)
        writeTraceRecord(
            TraceRecord(
                timestamp = java.time.Instant.now().toString(),
                kind = if (error == null) "span.end" else "span.error",
                traceId = trace.traceId,
                spanId = trace.spanId,
                parentSpanId = null,
                requestId = trace.requestId,
                sessionId = resolvedFile(statePath)?.parentFile?.name,
                domain = "automation",
                operation = "command",
                status = if (error == null) "ok" else "error",
                durationMs = durationMs,
                attributes = mapOf("command" to trace.command),
                errorCode = null,
                errorMessage = error,
            )
        )
        activeTrace = null
    }

    private fun decodeRequest(raw: String): AutomationRequest {
        val payload = decodePayload(raw)
        val json = JSONObject(payload)
        val args = json.optJSONObject("args")
        val argMap = linkedMapOf<String, String>()
        if (args != null) {
            args.keys().forEach { key ->
                val value = normalized(args.optString(key))
                if (value != null) {
                    argMap[key] = value
                }
            }
        }
        return AutomationRequest(
            id = normalized(json.optString("id")),
            name = json.optString("name"),
            args = argMap,
        )
    }

    private fun decodePayload(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("base64:")) {
            return trimmed
        }
        val encoded = trimmed.removePrefix("base64:")
        val decoded = Base64.getDecoder().decode(encoded)
        return String(decoded, Charsets.UTF_8)
    }

    private fun AutomationState.toJson(): JSONObject {
        return JSONObject()
            .put("platform", platform)
            .put("build_mode", buildMode)
            .put("active_tab", activeTab)
            .put("visible_screen", visibleScreen)
            .put("opened_message_id", openedMessageId)
            .put("opened_message_decryption_state", openedMessageDecryptionState)
            .put("opened_entity_type", openedEntityType)
            .put("opened_entity_id", openedEntityId)
            .put("pending_event_id", pendingEventId)
            .put("pending_thing_id", pendingThingId)
            .put("unread_message_count", unreadMessageCount)
            .put("total_message_count", totalMessageCount)
            .put("event_count", eventCount)
            .put("thing_count", thingCount)
            .put("message_page_enabled", messagePageEnabled)
            .put("event_page_enabled", eventPageEnabled)
            .put("thing_page_enabled", thingPageEnabled)
            .put("notification_key_configured", notificationKeyConfigured)
            .put("notification_key_encoding", notificationKeyEncoding)
            .put("gateway_base_url", gatewayBaseUrl)
            .put("gateway_token_present", gatewayTokenPresent)
            .put("use_fcm_channel", useFcmChannel)
            .put("provider_mode", providerMode)
            .put("device_key_present", deviceKeyPresent)
            .put("private_route", privateRoute)
            .put("private_transport", privateTransport)
            .put("private_stage", privateStage)
            .put("private_detail", privateDetail)
            .put("ack_pending_count", ackPendingCount)
            .put("channel_count", channelCount)
            .put("last_notification_action", lastNotificationAction)
            .put("last_notification_target", lastNotificationTarget)
            .put("last_fixture_import_path", lastFixtureImportPath)
            .put("last_fixture_import_message_count", lastFixtureImportMessageCount)
            .put("last_fixture_import_entity_record_count", lastFixtureImportEntityRecordCount)
            .put("last_fixture_import_subscription_count", lastFixtureImportSubscriptionCount)
            .put("runtime_error_count", runtimeErrorCount)
            .put("latest_runtime_error_source", latestRuntimeErrorSource)
            .put("latest_runtime_error_category", latestRuntimeErrorCategory)
            .put("latest_runtime_error_code", latestRuntimeErrorCode)
            .put("latest_runtime_error_message", latestRuntimeErrorMessage)
            .put("latest_runtime_error_timestamp", latestRuntimeErrorTimestamp)
    }

    private fun writeJson(path: String?, payload: String) {
        val target = resolvedFile(path) ?: return
        runCatching {
            target.apply {
                parentFile?.mkdirs()
                writeText(payload)
            }
        }
    }

    private fun normalized(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        return value.ifEmpty { null }
    }

    private fun resolvedFile(path: String?): File? {
        val resolved = normalized(path) ?: return null
        val root = filesDirectory
        return when {
            resolved.startsWith("files://") -> {
                val relative = resolved.removePrefix("files://").trimStart('/')
                root?.resolve(relative)
            }
            resolved.startsWith("/") -> File(resolved)
            else -> root?.resolve(resolved)
        }
    }

    private fun writeDerivedEvents(previous: AutomationState?, current: AutomationState) {
        openedEntityEvent(previous, current)?.let {
            writeEvent(type = "entity.opened", command = null, details = it)
        }
        settingsChangedEvent(previous, current)?.let {
            writeEvent(type = "settings.changed", command = null, details = it)
        }
        if (previous?.unreadMessageCount != current.unreadMessageCount) {
            writeEvent(
                type = "badge.updated",
                command = null,
                details = JSONObject().put("unread_message_count", current.unreadMessageCount),
            )
        }
    }

    private fun openedEntityEvent(previous: AutomationState?, current: AutomationState): JSONObject? {
        val currentType = current.openedEntityType ?: if (current.openedMessageId != null) "message" else null
        val currentId = current.openedEntityId ?: current.openedMessageId
        if (currentType == null || currentId == null) {
            return null
        }
        val previousType = previous?.openedEntityType ?: if (previous?.openedMessageId != null) "message" else null
        val previousId = previous?.openedEntityId ?: previous?.openedMessageId
        if (currentType == previousType && currentId == previousId) {
            return null
        }
        return JSONObject()
            .put("entity_type", currentType)
            .put("entity_id", currentId)
            .put("projection_destination", projectionDestination(currentType))
    }

    private fun settingsChangedEvent(previous: AutomationState?, current: AutomationState): JSONObject? {
        val changedKeys = mutableListOf<String>()
        if (previous?.gatewayBaseUrl != current.gatewayBaseUrl) {
            changedKeys += "gateway_base_url"
        }
        if (previous?.messagePageEnabled != current.messagePageEnabled) {
            changedKeys += "message_page_enabled"
        }
        if (previous?.eventPageEnabled != current.eventPageEnabled) {
            changedKeys += "event_page_enabled"
        }
        if (previous?.thingPageEnabled != current.thingPageEnabled) {
            changedKeys += "thing_page_enabled"
        }
        if (previous?.notificationKeyConfigured != current.notificationKeyConfigured) {
            changedKeys += "notification_key_configured"
        }
        if (previous?.notificationKeyEncoding != current.notificationKeyEncoding) {
            changedKeys += "notification_key_encoding"
        }
        if (changedKeys.isEmpty()) {
            return null
        }
        return JSONObject()
            .put("gateway_base_url", current.gatewayBaseUrl)
            .put("message_page_enabled", current.messagePageEnabled)
            .put("event_page_enabled", current.eventPageEnabled)
            .put("thing_page_enabled", current.thingPageEnabled)
            .put("notification_key_configured", current.notificationKeyConfigured)
            .put("notification_key_encoding", current.notificationKeyEncoding)
            .put("changed_keys", changedKeys.joinToString(","))
    }

    private fun projectionDestination(entityType: String): String {
        return when (entityType) {
            "thing" -> "thing_head"
            "event" -> "event_head"
            else -> "message_head"
        }
    }

    fun recordRuntimeError(
        source: String,
        error: Throwable? = null,
        category: String = "runtime",
        code: String? = null,
        message: String? = null,
    ) {
        val normalizedMessage = message?.trim().takeUnless { it.isNullOrEmpty() }
            ?: error?.message?.trim().takeUnless { it.isNullOrEmpty() }
            ?: error?.javaClass?.simpleName
            ?: "Unknown runtime error"
        val snapshot = RuntimeErrorSnapshot(
            source = source.trim().ifEmpty { "unknown" },
            category = category.trim().ifEmpty { "runtime" },
            code = code?.trim()?.ifEmpty { null },
            message = normalizedMessage,
            timestamp = java.time.Instant.now().toString(),
        )
        latestRuntimeError = snapshot
        runtimeErrorCount += 1
        writeTraceRecord(
            TraceRecord(
                timestamp = snapshot.timestamp,
                kind = "span.error",
                traceId = activeTrace?.traceId ?: java.util.UUID.randomUUID().toString().lowercase(),
                spanId = activeTrace?.spanId ?: java.util.UUID.randomUUID().toString().lowercase(),
                parentSpanId = null,
                requestId = activeTrace?.requestId,
                sessionId = resolvedFile(statePath)?.parentFile?.name,
                domain = snapshot.category,
                operation = snapshot.source,
                status = "error",
                durationMs = null,
                attributes = mapOf("count" to runtimeErrorCount.toString()),
                errorCode = snapshot.code,
                errorMessage = snapshot.message,
            )
        )
        writeEvent(
            type = "runtime.error",
            command = null,
            details = JSONObject()
                .put("source", snapshot.source)
                .put("category", snapshot.category)
                .put("code", snapshot.code)
                .put("message", snapshot.message)
                .put("timestamp", snapshot.timestamp)
                .put("count", runtimeErrorCount),
        )
    }

    fun currentRuntimeErrorCount(): Int = runtimeErrorCount

    fun latestRuntimeErrorSnapshot(): RuntimeErrorSnapshot? = latestRuntimeError

    fun startupGatewayBaseUrl(): String? = startupGatewayBaseUrl

    fun startupGatewayToken(): String? = startupGatewayToken

    fun isSessionConfigured(): Boolean {
        return pendingRequest != null ||
            responsePath != null ||
            statePath != null ||
            eventsPath != null ||
            tracePath != null
    }

    private fun writeTraceAnnotation(type: String, command: String?, details: JSONObject) {
        val (domain, operation) = traceDescriptor(type)
        val attributes = linkedMapOf<String, String>()
        val detailKeys = details.keys()
        while (detailKeys.hasNext()) {
            val key = detailKeys.next()
            attributes[key] = details.opt(key)?.toString().orEmpty()
        }
        if (!command.isNullOrBlank()) {
            attributes["command"] = command
        }
        writeTraceRecord(
            TraceRecord(
                timestamp = java.time.Instant.now().toString(),
                kind = "annotation",
                traceId = activeTrace?.traceId ?: java.util.UUID.randomUUID().toString().lowercase(),
                spanId = java.util.UUID.randomUUID().toString().lowercase(),
                parentSpanId = activeTrace?.spanId,
                requestId = activeTrace?.requestId,
                sessionId = resolvedFile(statePath)?.parentFile?.name,
                domain = domain,
                operation = operation,
                status = null,
                durationMs = null,
                attributes = attributes,
                errorCode = null,
                errorMessage = null,
            )
        )
    }

    private fun writeTraceRecord(record: TraceRecord) {
        val target = resolvedFile(tracePath) ?: return
        runCatching {
            val payload = JSONObject()
                .put("timestamp", record.timestamp)
                .put("platform", record.platform)
                .put("kind", record.kind)
                .put("trace_id", record.traceId)
                .put("span_id", record.spanId)
                .put("parent_span_id", record.parentSpanId)
                .put("request_id", record.requestId)
                .put("session_id", record.sessionId)
                .put("domain", record.domain)
                .put("operation", record.operation)
                .put("status", record.status)
                .put("duration_ms", record.durationMs)
                .put("attributes", JSONObject(record.attributes))
                .put("error_code", record.errorCode)
                .put("error_message", record.errorMessage)
            target.apply {
                parentFile?.mkdirs()
                appendText(payload.toString() + "\n")
            }
        }
    }

    private fun traceDescriptor(eventType: String): Pair<String, String> {
        return when (eventType) {
            "command.received", "command.completed", "command.failed" -> "automation" to "command"
            "state.updated" -> "ui" to "state.updated"
            "entity.opened" -> "navigation" to "entity.opened"
            "settings.changed" -> "settings" to "changed"
            "badge.updated" -> "ui" to "badge.updated"
            "search.results_updated" -> "search" to "results.updated"
            "export.completed" -> "export" to "completed"
            "runtime.error" -> "runtime" to "error"
            else -> {
                val parts = eventType.split('.', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else "automation" to eventType
            }
        }
    }
}
