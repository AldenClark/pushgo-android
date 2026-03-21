package io.ethan.pushgo.data

internal fun canonicalEntityTypeOrEmpty(raw: String?): String {
    return when (raw?.trim()?.lowercase()) {
        "message" -> "message"
        "event" -> "event"
        "thing" -> "thing"
        else -> ""
    }
}
