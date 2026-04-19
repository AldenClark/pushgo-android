package io.ethan.pushgo.data

interface SecureSecretStore {
    fun gatewayToken(): String?
    fun setGatewayToken(token: String?)

    fun fcmToken(): String?
    fun setFcmToken(token: String?)

    fun deviceKey(): String?
    fun setDeviceKey(deviceKey: String?)

    fun notificationKeyBytes(): ByteArray?
    fun setNotificationKeyBytes(value: ByteArray?)

    fun channelPassword(gatewayUrl: String, channelId: String): String?
    fun setChannelPassword(gatewayUrl: String, channelId: String, password: String?)
    fun removeChannelPassword(gatewayUrl: String, channelId: String)

    fun clearAll()
}
