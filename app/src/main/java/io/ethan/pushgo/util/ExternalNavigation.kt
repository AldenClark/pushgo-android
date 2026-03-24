package io.ethan.pushgo.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder

fun Context.openExternalUrl(raw: String): Boolean {
    val target = normalizeExternalOpenUrl(raw) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, target.toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { startActivity(intent) }
        .map { true }
        .getOrElse { error ->
            if (error is ActivityNotFoundException) {
                false
            } else {
                throw error
            }
        }
}

fun normalizeExternalImageUrl(raw: String): String? {
    val normalized = normalizeExternalOpenUrl(raw) ?: return null
    val parsed = runCatching { URI(normalized) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = parsed.host?.trim().orEmpty()
    if (host.isEmpty()) return null
    if (isBlockedRemoteHost(host)) return null
    return parsed.toASCIIString()
}

fun rewriteVisibleUrlsInText(raw: String): String {
    if (raw.isEmpty() || !raw.contains("](")) return raw
    val bytes = raw.toByteArray()
    var cursor = 0
    var copyStart = 0
    val out = StringBuilder(raw.length)
    while (cursor + 1 < bytes.size) {
        if (bytes[cursor] == ']'.code.toByte() && bytes[cursor + 1] == '('.code.toByte()) {
            val destinationStart = cursor + 2
            var end = destinationStart
            var parenDepth = 0
            while (end < bytes.size) {
                when (bytes[end]) {
                    '('.code.toByte() -> parenDepth += 1
                    ')'.code.toByte() -> if (parenDepth == 0) {
                        break
                    } else {
                        parenDepth -= 1
                    }
                }
                end += 1
            }
            if (end >= bytes.size) break
            out.append(raw, copyStart, destinationStart)
            val destination = raw.substring(destinationStart, end)
            out.append(rewriteMarkdownDestination(destination))
            out.append(')')
            cursor = end + 1
            copyStart = cursor
            continue
        }
        cursor += 1
    }
    if (copyStart == 0) return raw
    out.append(raw.substring(copyStart))
    return out.toString()
}

private val AllowedExternalSchemes: Set<String> = setOf(
    "http",
    "https",
    "ftp",
    "ftps",
    "mailto",
    "tel",
    "sms",
    "app",
    "pushgo",
)

private val BlockedExternalSchemes: Set<String> = setOf(
    "javascript",
    "data",
    "file",
    "content",
    "intent",
    "vbscript",
)

private val CandidateHostPattern = Regex("^[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}$")
private const val MaxUrlLength = 4096

fun normalizeExternalOpenUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.length > MaxUrlLength) return null
    if (trimmed.any { it.isISOControl() }) return null
    if (containsBlockedEncodedScheme(trimmed)) return null

    val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase()
    if (!scheme.isNullOrBlank()) {
        if (scheme in BlockedExternalSchemes || scheme !in AllowedExternalSchemes) return null
        if (scheme in setOf("http", "https", "ftp", "ftps")) {
            val host = parsed.host?.trim().orEmpty()
            if (host.isEmpty()) return null
            if (!parsed.userInfo.isNullOrBlank()) return null
        }
        return parsed.toASCIIString()
    }

    // No scheme: only auto-upgrade host-like inputs to https.
    val hostLike = trimmed.substringBefore('/').substringBefore(':')
    if (!CandidateHostPattern.matches(hostLike)) return null
    val upgraded = "https://$trimmed"
    val upgradedUri = runCatching { URI(upgraded) }.getOrNull() ?: return null
    val host = upgradedUri.host?.trim().orEmpty()
    if (host.isEmpty()) return null
    return upgradedUri.toASCIIString()
}

private fun containsBlockedEncodedScheme(raw: String): Boolean {
    var candidate = raw
    repeat(3) {
        val scheme = leadingSchemeToken(candidate)
        if (scheme != null && scheme in BlockedExternalSchemes) return true
        val decoded = runCatching { URLDecoder.decode(candidate, "UTF-8") }.getOrNull()
            ?: return@repeat
        if (decoded == candidate) return@repeat
        candidate = decoded
    }
    val scheme = leadingSchemeToken(candidate)
    return scheme != null && scheme in BlockedExternalSchemes
}

private fun rewriteMarkdownDestination(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    val leading = raw.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: 0
    val trailingExclusive = raw.indexOfLast { !it.isWhitespace() }
        .takeIf { it >= 0 }
        ?.plus(1)
        ?: raw.length
    val inner = raw.substring(leading, trailingExclusive)
    val tokenEnd = inner.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: inner.length
    val token = inner.substring(0, tokenEnd)
    val suffix = inner.substring(tokenEnd)
    val unwrapped = if (token.startsWith("<") && token.endsWith(">")) {
        token.substring(1, token.length - 1)
    } else {
        token
    }
    val rewritten = normalizeExternalOpenUrl(unwrapped)?.let { safe ->
        if (token.startsWith("<") && token.endsWith(">")) "<$safe>" else safe
    } ?: if (looksLikeUrlToken(unwrapped)) {
        "#"
    } else {
        token
    }
    return buildString(raw.length + 8) {
        append(raw.substring(0, leading))
        append(rewritten)
        append(suffix)
        append(raw.substring(trailingExclusive))
    }
}

private fun looksLikeUrlToken(raw: String): Boolean {
    return raw.contains(':') || raw.startsWith("www.", ignoreCase = true)
}

private fun leadingSchemeToken(raw: String): String? {
    val text = raw.trimStart()
    if (text.isEmpty()) return null
    val token = StringBuilder()
    var sawColon = false
    for (ch in text) {
        if (ch == ':') {
            sawColon = true
            break
        }
        if (ch.isWhitespace() || ch.isISOControl()) continue
        if (ch.isLetterOrDigit() || ch == '+' || ch == '-' || ch == '.') {
            token.append(ch.lowercaseChar())
            if (token.length > 32) return null
            continue
        }
        return null
    }
    if (!sawColon || token.isEmpty()) return null
    return token.toString()
}

private fun isBlockedRemoteHost(host: String): Boolean {
    val normalized = host.trim().trim('[', ']').lowercase()
    if (normalized.isEmpty()) return true
    if (normalized == "localhost" || normalized.endsWith(".localhost")) return true

    parseIpv4Literal(normalized)?.let { return isBlockedIpv4(it) }

    // Only treat host as IPv6 literal when every character matches literal grammar.
    if (normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) {
        val numericAddress = runCatching { InetAddress.getByName(normalized) }.getOrNull()
        if (numericAddress is Inet6Address) {
            return isBlockedIpv6(numericAddress.address ?: return true)
        }
    }
    return false
}

private fun parseIpv4Literal(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        bytes[index] = value.toByte()
    }
    return bytes
}

private fun isBlockedIpv4(bytes: ByteArray): Boolean {
    if (bytes.size != 4) return true
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    if (b0 == 0 || b0 == 10 || b0 == 127) return true
    if (b0 == 169 && b1 == 254) return true
    if (b0 == 172 && b1 in 16..31) return true
    if (b0 == 192 && b1 == 168) return true
    if (b0 == 100 && b1 in 64..127) return true
    if (b0 >= 224) return true
    return false
}

private fun isBlockedIpv6(bytes: ByteArray): Boolean {
    if (bytes.size != 16) return true
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    val isAllZero = bytes.all { it.toInt() == 0 }
    if (isAllZero) return true
    val isLoopback = bytes.dropLast(1).all { it.toInt() == 0 } && bytes.last().toInt() == 1
    if (isLoopback) return true
    if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true // fe80::/10 link-local
    if ((b0 and 0xFE) == 0xFC) return true // fc00::/7 unique local
    return false
}
