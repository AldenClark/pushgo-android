package io.ethan.pushgo.data

import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.automation.PushGoAutomation

object AppConstants {
    val defaultServerAddress: String
        get() = PushGoAutomation.startupGatewayBaseUrl() ?: BuildConfig.DEFAULT_SERVER_ADDRESS
    val defaultGatewayToken: String?
        get() = PushGoAutomation.startupGatewayToken()
    const val notificationChannelBaseId = "pushgo_messages"
    const val notificationChannelSchemaVersion = 5
    const val notificationChannelName = "PushGo Messages"
    const val notificationChannelDescription = "Push messages from PushGo"
    const val fcmTokenTimeoutMs = 10_000L
    const val updateCheckIntervalSeconds = 21_600L
    const val updateImpatientIntervalSeconds = 604_800L
    val defaultUpdateFeedUrl: String
        get() = BuildConfig.DEFAULT_UPDATE_FEED_URL
    val updateFeedEd25519PublicKeyBase64: String
        get() = BuildConfig.UPDATE_FEED_PUBLIC_KEY_B64
    val updateFeedEcdsaP256PublicKeyBase64: String
        get() = BuildConfig.UPDATE_FEED_ECDSA_P256_PUBLIC_KEY_B64
}
