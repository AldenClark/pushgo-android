package io.ethan.pushgo.ui

import io.ethan.pushgo.util.UrlValidators
import org.json.JSONArray
import org.json.JSONObject

object MessagePayloadUtils {
    fun extractImageUrl(rawPayload: String): String? {
        return extractImageUrls(rawPayload).firstOrNull()
    }

    fun extractImageUrls(rawPayload: String): List<String> {
        val json = parseJson(rawPayload) ?: return emptyList()
        val urls = linkedSetOf<String>()
        appendUrls(json.opt("images"), urls)
        return urls.toList()
    }

    private fun appendUrls(raw: Any?, urls: MutableSet<String>) {
        when (raw) {
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) {
                    return
                }
                val parsed = runCatching { JSONArray(trimmed) }.getOrNull()
                if (parsed != null) {
                    for (index in 0 until parsed.length()) {
                        UrlValidators.normalizeHttpsUrl(parsed.optString(index, ""))?.let { urls += it }
                    }
                }
            }
        }
    }

    private fun parseJson(rawPayload: String): JSONObject? {
        return runCatching { JSONObject(rawPayload) }.getOrNull()
    }
}
