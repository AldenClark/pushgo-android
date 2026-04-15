package io.ethan.pushgo.update

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object CanonicalJson {
    fun encode(element: JsonElement): String {
        return when (element) {
            is JsonObject -> {
                element.entries
                    .sortedBy { it.key }
                    .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                        "\"${escapeString(key)}\":${encode(value)}"
                    }
            }
            is JsonArray -> {
                element.joinToString(separator = ",", prefix = "[", postfix = "]") { encode(it) }
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    "\"${escapeString(element.content)}\""
                } else {
                    element.toString()
                }
            }
        }
    }

    private fun escapeString(value: String): String {
        val builder = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (ch.code in 0x00..0x1F) {
                        builder.append("\\u%04x".format(ch.code))
                    } else {
                        builder.append(ch)
                    }
                }
            }
        }
        return builder.toString()
    }
}
