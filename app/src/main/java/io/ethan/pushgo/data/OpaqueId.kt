package io.ethan.pushgo.data

import java.security.SecureRandom

object OpaqueId {
    private val secureRandom = SecureRandom()
    private val hexChars = "0123456789abcdef".toCharArray()

    fun generateHex128(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        val out = CharArray(32)
        var index = 0
        bytes.forEach { value ->
            val byte = value.toInt() and 0xFF
            out[index] = hexChars[byte ushr 4]
            out[index + 1] = hexChars[byte and 0x0F]
            index += 2
        }
        return String(out)
    }
}
