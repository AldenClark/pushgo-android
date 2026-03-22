package io.ethan.pushgo.data

import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.automation.PushGoAutomation

object AppConstants {
    val defaultServerAddress: String
        get() = PushGoAutomation.startupGatewayBaseUrl() ?: BuildConfig.DEFAULT_SERVER_ADDRESS
    val defaultGatewayToken: String?
        get() = PushGoAutomation.startupGatewayToken()
    const val notificationChannelBaseId = "pushgo_messages"
    const val notificationChannelSchemaVersion = 2
    const val notificationChannelName = "PushGo Messages"
    const val notificationChannelDescription = "Push messages from PushGo"
    const val fcmTokenTimeoutMs = 10_000L
}
