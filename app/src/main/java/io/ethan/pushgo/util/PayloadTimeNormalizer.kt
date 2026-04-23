package io.ethan.pushgo.util

object PayloadTimeNormalizer {
    private const val MILLIS_THRESHOLD = 1_000_000_000_000L

    fun epochMillis(value: String?): Long? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.toLongOrNull()?.let(::normalizeEpochMillis)
    }

    fun epochMillis(value: Any?): Long? {
        val raw = when (value) {
            null -> return null
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: return null
            else -> return null
        }
        return normalizeEpochMillis(raw)
    }

    fun epochSecondsFromJson(payload: org.json.JSONObject?, key: String): Long? {
        return epochMillisFromJson(payload, key)?.let { it / 1000 }
    }

    fun epochMillisFromJson(payload: org.json.JSONObject?, key: String): Long? {
        val source = payload ?: return null
        if (!source.has(key)) return null
        val raw = source.opt(key)
        if (raw == null || raw == org.json.JSONObject.NULL) return null
        return epochMillis(raw)?.takeIf { it > 0L }
    }

    fun epochSeconds(value: String?): Long? {
        return epochMillis(value)?.let { it / 1000 }
    }

    fun epochSeconds(value: Any?): Long? {
        return epochMillis(value)?.let { it / 1000 }
    }

    private fun normalizeEpochMillis(raw: Long): Long {
        return if (raw >= MILLIS_THRESHOLD || raw <= -MILLIS_THRESHOLD) {
            raw
        } else {
            multiplySecondsToMillis(raw)
        }
    }

    private fun multiplySecondsToMillis(seconds: Long): Long {
        if (seconds > Long.MAX_VALUE / 1000) return Long.MAX_VALUE
        if (seconds < Long.MIN_VALUE / 1000) return Long.MIN_VALUE
        return seconds * 1000
    }
}
