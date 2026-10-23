package io.ethan.pushgo.data

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** RFC 9111 private-cache age calculation. No heuristic freshness or minimum render TTL. */
internal data class ImageHttpPolicy(
    val storedAt: Long,
    val initialAge: Long,
    val lifetime: Long,
    val noStore: Boolean,
    val noCache: Boolean,
    val mustRevalidate: Boolean,
    val vary: List<String>,
) {
    fun fresh(now: Long): Boolean = !noStore && !noCache && currentAge(now) < lifetime
    fun currentAge(now: Long): Long = saturatingAdd(initialAge, (now - storedAt).coerceAtLeast(0))
    fun mayShowStale(): Boolean = !noStore && !noCache && !mustRevalidate

    companion object {
        fun from(headers: Map<String, String>, requestAt: Long, responseAt: Long): ImageHttpPolicy {
            fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
            val directives = header("cache-control").orEmpty().split(',').associate { part ->
                val pair = part.trim().split('=', limit = 2)
                pair[0].lowercase(Locale.ROOT) to pair.getOrNull(1)?.trim()?.trim('"')
            }
            val date = header("date")?.let(::httpDate) ?: responseAt
            val age = header("age")?.trim()?.toLongOrNull()?.coerceAtLeast(0)?.let(::secondsToMillis) ?: 0
            val delay = (responseAt - requestAt).coerceAtLeast(0)
            val correctedAge = maxOf((responseAt - date).coerceAtLeast(0), saturatingAdd(age, delay))
            val maxAgeValues = header("cache-control").orEmpty().split(',').map { it.trim().split('=', limit = 2) }
                .filter { it[0].trim().equals("max-age", ignoreCase = true) }
            // Duplicate or invalid freshness information is stale, not whichever value appeared last.
            val lifetime = if (maxAgeValues.isNotEmpty()) {
                maxAgeValues.singleOrNull()?.getOrNull(1)?.trim()?.trim('"')?.toLongOrNull()
                    ?.takeIf { it >= 0 }?.let(::secondsToMillis) ?: 0
            } else header("expires")?.let(::httpDate)?.let { (it - date).coerceAtLeast(0) } ?: 0
            return ImageHttpPolicy(responseAt, correctedAge, lifetime,
                "no-store" in directives, "no-cache" in directives,
                "must-revalidate" in directives, header("vary").orEmpty().split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct())
        }
        internal fun httpDate(value: String): Long? = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy").firstNotNullOfOrNull { format ->
            runCatching { SimpleDateFormat(format, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }.parse(value)?.time }.getOrNull()
        }
        private fun secondsToMillis(value: Long): Long = if (value > Long.MAX_VALUE / 1000) Long.MAX_VALUE else value * 1000
        private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    }
}
