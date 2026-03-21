package io.ethan.pushgo.data

import io.ethan.pushgo.util.JsonCompat
import org.json.JSONArray
import org.json.JSONObject

data class ParsedEntityProfile(
    val title: String?,
    val description: String?,
    val state: String?,
    val status: String?,
    val message: String?,
    val severity: String?,
    val tags: List<String>,
    val imageUrl: String?,
    val imageUrls: List<String>,
    val createdAt: Long?,
)

fun parseEventProfile(raw: String?): ParsedEntityProfile? {
    val json = parseProfileObject(raw) ?: return null
    return parseProfile(
        json = json,
        primaryImageKey = null,
        imageKeys = listOf("images"),
    )
}

fun parseThingProfile(raw: String?): ParsedEntityProfile? {
    val json = parseProfileObject(raw) ?: return null
    return parseProfile(
        json = json,
        primaryImageKey = "primary_image",
        imageKeys = listOf("images"),
    )
}

private fun parseProfile(
    json: Map<String, Any?>,
    primaryImageKey: String?,
    imageKeys: List<String>,
): ParsedEntityProfile {
    val mergedImages = linkedSetOf<String>().apply {
        primaryImageKey
            ?.let(json::stringValue)
            ?.takeIf { it.isNotEmpty() }
            ?.let(::add)
        imageKeys.forEach { key ->
            collectStringsFromArray(json, key).forEach { add(it) }
        }
    }.toList()

    return ParsedEntityProfile(
        title = json.stringValue("title"),
        description = json.stringValue("description"),
        state = json.stringValue("state"),
        status = json.stringValue("status"),
        message = json.stringValue("message"),
        severity = json.stringValue("severity"),
        tags = stringArray(json, "tags"),
        imageUrl = mergedImages.firstOrNull(),
        imageUrls = mergedImages,
        createdAt = json.longValue("created_at")?.takeIf { it > 0L },
    )
}

private fun parseProfileObject(raw: String?): Map<String, Any?>? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    JsonCompat.parseObject(text)?.let { return it }
    return runCatching {
        jsonObjectToMap(JSONObject(text))
    }.getOrNull()
}

private fun stringArray(
    json: Map<String, Any?>,
    key: String,
): List<String> {
    val array = json[key] as? List<*> ?: return emptyList()
    val out = mutableListOf<String>()
    for (entry in array) {
        val value = entry?.toString()?.trim().orEmpty()
        if (value.isNotEmpty() && !out.contains(value)) {
            out.add(value)
        }
    }
    return out
}

private fun collectStringsFromArray(
    json: Map<String, Any?>,
    key: String,
): List<String> {
    val array = json[key] as? List<*> ?: return emptyList()
    val values = mutableListOf<String>()
    for (entry in array) {
        val value = entry?.toString()?.trim().orEmpty()
        if (value.isNotEmpty() && !values.contains(value)) {
            values.add(value)
        }
    }
    return values
}

private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out[key] = jsonValueToCompat(json.opt(key))
    }
    return out
}

private fun jsonArrayToList(array: JSONArray): List<Any?> {
    val out = ArrayList<Any?>(array.length())
    for (index in 0 until array.length()) {
        out += jsonValueToCompat(array.opt(index))
    }
    return out
}

private fun jsonValueToCompat(value: Any?): Any? {
    return when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        else -> value
    }
}

private fun Map<String, Any?>.stringValue(key: String): String? {
    return this[key]?.toString()?.trim()?.ifEmpty { null }
}

private fun Map<String, Any?>.longValue(key: String): Long? {
    val value = this[key] ?: return null
    return when (value) {
        is Number -> value.toLong()
        else -> value.toString().trim().toLongOrNull()
    }
}
