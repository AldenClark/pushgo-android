package io.ethan.pushgo.data.model

import java.time.Instant

data class NotificationKeyMaterial(
    val algorithm: String,
    val keyBytes: ByteArray,
    val updatedAt: Instant,
)

data class ServerConfig(
    val baseUrl: String,
    val token: String?,
    val notificationKeyMaterial: NotificationKeyMaterial?,
    val updatedAt: Instant,
)
