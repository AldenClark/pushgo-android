package io.ethan.pushgo.util

object UrlValidators {
    private const val HTTPS_PREFIX = "https://"

    fun normalizeHttpsUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith(HTTPS_PREFIX)) trimmed else null
    }
}
