package io.ethan.pushgo.data

import io.ethan.pushgo.BuildConfig

object AppConstants {
    val defaultServerAddress = BuildConfig.DEFAULT_SERVER_ADDRESS
    const val notificationChannelBaseId = "pushgo_messages"
    const val notificationChannelName = "PushGo Messages"
    const val notificationChannelDescription = "Push messages from PushGo"
    const val longRingtonePrefix = "pushgo_long_"
    const val longRingtoneDirectory = "long_ringtones"
    const val markdownRenderPayloadKey = "body_render_payload"
    const val markdownRenderPayloadMaxCharacters = 4000
    const val markdownRenderPayloadListSoftCap = 2400
    const val markdownRenderPayloadMinCharacters = 240
    const val autoCleanupBatchSize = 300
    const val performanceSampleWindow = 6
    const val performanceDegradationCooldownMs = 180_000L
    const val fcmTokenTimeoutMs = 10_000L
}
