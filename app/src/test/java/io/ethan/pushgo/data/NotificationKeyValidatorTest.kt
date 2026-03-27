package io.ethan.pushgo.data

import io.ethan.pushgo.data.model.KeyEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class NotificationKeyValidatorTest {

    @Test
    fun parseEncoding_supportsKnownAliases() {
        assertEquals(KeyEncoding.BASE64, NotificationKeyValidator.parseEncoding(null))
        assertEquals(KeyEncoding.BASE64, NotificationKeyValidator.parseEncoding("base64"))
        assertEquals(KeyEncoding.HEX, NotificationKeyValidator.parseEncoding("HEX"))
        assertEquals(KeyEncoding.PLAINTEXT, NotificationKeyValidator.parseEncoding("plain"))
    }

    @Test
    fun normalizedKeyBytes_decodesBase64AndHex() {
        val seed = "0123456789ABCDEF".toByteArray()
        val base64 = Base64.getEncoder().encodeToString(seed)
        assertArrayEquals(seed, NotificationKeyValidator.normalizedKeyBytes(base64, KeyEncoding.BASE64))

        val hex = "00112233445566778899AABBCCDDEEFF"
        assertEquals(16, NotificationKeyValidator.normalizedKeyBytes(hex, KeyEncoding.HEX).size)
    }

    @Test
    fun normalizedKeyBytes_rejectsInvalidFormatsAndLengths() {
        try {
            NotificationKeyValidator.normalizedKeyBytes("%%%", KeyEncoding.BASE64)
            fail("expected InvalidBase64")
        } catch (_: NotificationKeyValidationException.InvalidBase64) {
        }

        try {
            NotificationKeyValidator.normalizedKeyBytes("ABC", KeyEncoding.HEX)
            fail("expected InvalidHex")
        } catch (_: NotificationKeyValidationException.InvalidHex) {
        }

        try {
            NotificationKeyValidator.normalizedKeyBytes("short", KeyEncoding.PLAINTEXT)
            fail("expected InvalidLength")
        } catch (_: NotificationKeyValidationException.InvalidLength) {
        }
    }
}
