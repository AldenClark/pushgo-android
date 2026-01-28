package io.ethan.pushgo.ui

import io.ethan.pushgo.util.UrlValidators
import org.json.JSONObject

object MessagePayloadUtils {
    private val imageKeys = listOf("image", "image_url", "imageUrl", "picture", "pic")
    private val iconKeys = listOf("icon", "icon_url", "iconUrl", "avatar")

    fun extractImageUrl(rawPayload: String): String? {
        val json = parseJson(rawPayload) ?: return null
        return extractHttpsUrl(json, imageKeys)
            ?: json.optJSONObject("meta")?.let { extractHttpsUrl(it, imageKeys) }
    }

    fun extractIconUrl(rawPayload: String): String? {
        val json = parseJson(rawPayload) ?: return null
        return extractHttpsUrl(json, iconKeys)
            ?: json.optJSONObject("meta")?.let { extractHttpsUrl(it, iconKeys) }
    }

    private fun extractHttpsUrl(json: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            val value = UrlValidators.normalizeHttpsUrl(json.optString(key, ""))
            if (value != null) return value
        }
        return null
    }

    private fun parseJson(rawPayload: String): JSONObject? {
        return runCatching { JSONObject(rawPayload) }.getOrNull()
    }
}
