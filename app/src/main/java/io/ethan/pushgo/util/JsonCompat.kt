package io.ethan.pushgo.util

internal object JsonCompat {
    fun parseObject(raw: String?): Map<String, Any?>? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val parser = Parser(text)
            val value = parser.parseValue() as? Map<*, *> ?: return null
            parser.expectEnd()
            buildMap(value.size) {
                for ((key, entryValue) in value) {
                    val objectKey = key as? String ?: return null
                    put(objectKey, entryValue)
                }
            }
        }.getOrNull()
    }

    fun parseArray(raw: String?): List<Any?>? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val parser = Parser(text)
            val value = parser.parseValue() as? List<Any?> ?: return null
            parser.expectEnd()
            value
        }.getOrNull()
    }

    fun stringify(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> buildString {
                append('"')
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> {
                            if (ch.code < 0x20) {
                                append("\\u%04x".format(ch.code))
                            } else {
                                append(ch)
                            }
                        }
                    }
                }
                append('"')
            }
            is Boolean, is Int, is Long, is Short, is Byte, is Double, is Float -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, entryValue) ->
                stringify(key?.toString() ?: "") + ":" + stringify(entryValue)
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { stringify(it) }
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { stringify(it) }
            else -> stringify(value.toString())
        }
    }

    private class Parser(private val text: String) {
        private var index: Int = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (index >= text.length) error("Unexpected end of JSON")
            return when (val ch = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseTrue()
                'f' -> parseFalse()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected character '$ch'")
            }
        }

        fun expectEnd() {
            skipWhitespace()
            if (index != text.length) {
                error("Unexpected trailing content")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            if (peek('}')) {
                index += 1
                return emptyMap()
            }
            val result = linkedMapOf<String, Any?>()
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when {
                    peek('}') -> {
                        index += 1
                        return result
                    }
                    peek(',') -> index += 1
                    else -> error("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            if (peek(']')) {
                index += 1
                return emptyList()
            }
            val result = mutableListOf<Any?>()
            while (true) {
                result += parseValue()
                skipWhitespace()
                when {
                    peek(']') -> {
                        index += 1
                        return result
                    }
                    peek(',') -> index += 1
                    else -> error("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < text.length) {
                val ch = text[index++]
                when (ch) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (index >= text.length) error("Invalid escape sequence")
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) error("Invalid unicode escape")
                                val hex = text.substring(index, index + 4)
                                result.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> error("Unsupported escape sequence")
                        }
                    }
                    else -> result.append(ch)
                }
            }
            error("Unterminated string")
        }

        private fun parseTrue(): Boolean {
            expectKeyword("true")
            return true
        }

        private fun parseFalse(): Boolean {
            expectKeyword("false")
            return false
        }

        private fun parseNull(): Nothing? {
            expectKeyword("null")
            return null
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek('-')) index += 1
            consumeDigits()
            var isFloatingPoint = false
            if (peek('.')) {
                isFloatingPoint = true
                index += 1
                consumeDigits()
            }
            if (peek('e') || peek('E')) {
                isFloatingPoint = true
                index += 1
                if (peek('+') || peek('-')) index += 1
                consumeDigits()
            }
            val raw = text.substring(start, index)
            return if (isFloatingPoint) raw.toDouble() else raw.toLong()
        }

        private fun consumeDigits() {
            if (index >= text.length || !text[index].isDigit()) error("Expected digit")
            while (index < text.length && text[index].isDigit()) {
                index += 1
            }
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            if (index >= text.length || text[index] != expected) {
                error("Expected '$expected'")
            }
            index += 1
        }

        private fun expectKeyword(keyword: String) {
            skipWhitespace()
            if (!text.startsWith(keyword, index)) {
                error("Expected '$keyword'")
            }
            index += keyword.length
        }

        private fun peek(expected: Char): Boolean {
            skipWhitespace()
            return index < text.length && text[index] == expected
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) {
                index += 1
            }
        }

        private fun error(message: String): Nothing {
            throw IllegalArgumentException("$message at index $index")
        }
    }
}
