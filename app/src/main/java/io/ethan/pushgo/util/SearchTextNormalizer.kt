package io.ethan.pushgo.util

import java.text.Normalizer
import java.util.Locale

/** Shared search normalization matching Apple's case/diacritic/width-insensitive contract. */
internal object SearchTextNormalizer {
    const val TOKEN_SEPARATOR: Char = '\u001F'

    fun normalize(raw: String): String {
        val compatibilityDecomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
        return buildString(compatibilityDecomposed.length) {
            var offset = 0
            while (offset < compatibilityDecomposed.length) {
                val codePoint = compatibilityDecomposed.codePointAt(offset)
                offset += Character.charCount(codePoint)
                when (Character.getType(codePoint)) {
                    Character.NON_SPACING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt(),
                    -> Unit

                    else -> when (codePoint) {
                        TOKEN_SEPARATOR.code -> append(' ')
                        0x00DF -> append("ss") // Unicode full case fold for sharp s.
                        0x03C2 -> append('\u03C3') // Final sigma folds with sigma.
                        else -> appendCodePoint(codePoint)
                    }
                }
            }
        }
    }

    fun normalizedQuery(raw: String): String = normalize(raw.trim())

    fun joinedCandidates(values: Iterable<String?>): String = values
        .mapNotNull { value -> value?.let(::normalize)?.takeIf(String::isNotEmpty) }
        .joinToString(TOKEN_SEPARATOR.toString())
}
