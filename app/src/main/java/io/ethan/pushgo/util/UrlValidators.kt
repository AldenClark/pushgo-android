package io.ethan.pushgo.util

import java.net.URI

object UrlValidators {
    private const val HTTPS_PREFIX = "https://"
    private val LOOPBACK_HTTP_HOSTS = setOf("127.0.0.1", "localhost", "10.0.2.2", "::1")

    fun normalizeHttpsUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith(HTTPS_PREFIX)) trimmed else null
    }

    fun normalizeGatewayBaseUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.trim()?.lowercase()
        val host = uri.host?.trim()?.lowercase()
        if (host.isNullOrEmpty()) return null
        val port = uri.port
        return when (scheme) {
            "https" -> {
                if (port > 0 && port != 443) {
                    "https://$host:$port"
                } else {
                    "https://$host"
                }
            }
            "http" -> {
                if (!LOOPBACK_HTTP_HOSTS.contains(host)) {
                    return null
                }
                if (port > 0) {
                    "http://$host:$port"
                } else {
                    "http://$host"
                }
            }
            else -> null
        }
    }
}
