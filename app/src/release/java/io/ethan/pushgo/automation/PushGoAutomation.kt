package io.ethan.pushgo.automation

import android.content.Intent
import java.io.File

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

        fun booleanArg(key: String): Boolean? = null
    }

    data class AutomationState(
        val platform: String = "android",
        val buildMode: String = "release",
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

    val requestVersion: Int = 0

    fun configureFromIntent(intent: Intent?, filesDir: File? = null) = Unit

    fun consumePendingRequest(): AutomationRequest? = null

    fun publishState(state: AutomationState) = Unit

    fun writeResponse(
        request: AutomationRequest?,
        ok: Boolean,
        state: AutomationState?,
        error: String?,
    ) = Unit

    fun writeEvent(
        type: String,
        command: String?,
        details: org.json.JSONObject = org.json.JSONObject(),
    ) = Unit

    fun startCommandTrace(request: AutomationRequest) = Unit

    fun finishCommandTrace(error: String?) = Unit

    fun recordRuntimeError(
        source: String,
        error: Throwable? = null,
        category: String = "runtime",
        code: String? = null,
        message: String? = null,
    ) = Unit

    fun currentRuntimeErrorCount(): Int = 0

    fun latestRuntimeErrorSnapshot(): RuntimeErrorSnapshot? = null

    fun startupGatewayBaseUrl(): String? = null

    fun startupGatewayToken(): String? = null

    fun isSessionConfigured(): Boolean = false
}
