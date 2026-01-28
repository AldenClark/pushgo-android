package io.ethan.pushgo.data

import androidx.annotation.StringRes
import io.ethan.pushgo.R

class ChannelNameException(
    @param:StringRes val resId: Int,
    val args: List<Any> = emptyList(),
) : IllegalArgumentException()

class ChannelIdException(
    @param:StringRes val resId: Int,
) : IllegalArgumentException()

class ChannelPasswordException(
    @param:StringRes val resId: Int,
) : IllegalArgumentException()

object ChannelNameValidator {
    const val MAX_LENGTH = 128

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw ChannelNameException(R.string.error_channel_name_required)
        }
        if (trimmed.length > MAX_LENGTH) {
            throw ChannelNameException(R.string.error_channel_name_too_long, listOf(MAX_LENGTH))
        }
        val invalid = trimmed.firstOrNull { it.isISOControl() }
        if (invalid != null) {
            throw ChannelNameException(
                R.string.error_channel_name_invalid_character,
                listOf(invalid.toString()),
            )
        }
        return trimmed
    }
}

object ChannelIdValidator {
    private const val EXPECTED_LENGTH = 26
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw ChannelIdException(R.string.error_channel_id_required)
        }
        val normalized = StringBuilder(EXPECTED_LENGTH)
        for (ch in trimmed) {
            if (ch.isWhitespace() || ch == '-') continue
            val upper = ch.uppercaseChar()
            val mapped = when (upper) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> upper
            }
            if (!ALPHABET.contains(mapped)) {
                throw ChannelIdException(R.string.error_channel_id_invalid)
            }
            normalized.append(mapped)
        }
        if (normalized.length != EXPECTED_LENGTH) {
            throw ChannelIdException(R.string.error_channel_id_invalid)
        }
        return normalized.toString()
    }
}

object ChannelPasswordValidator {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length < MIN_LENGTH || trimmed.length > MAX_LENGTH) {
            throw ChannelPasswordException(R.string.error_channel_password_length)
        }
        return trimmed
    }
}
