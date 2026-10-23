package io.ethan.pushgo.test

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncodedClassSelectionInstrumentedTest {
    @Test
    fun encodedSelectionRestoresEveryClassAndMethodWithoutTouchingSession() {
        val scopes = "example.Messages,example.Entities#close,example.Channels,example.Settings#save"
        val arguments = Bundle().apply {
            putString(ENCODED_CLASS_SELECTION, encode(scopes))
            putString("pushgoQualitySessionBase64", "c2Vzc2lvbg==")
        }
        restoreEncodedClassSelection(arguments)
        assertEquals(scopes, arguments.getString("class"))
        assertEquals("c2Vzc2lvbg==", arguments.getString("pushgoQualitySessionBase64"))
        assertFalse(arguments.containsKey(ENCODED_CLASS_SELECTION))

        val standard = Bundle().apply { putString("class", "example.Messages#one") }
        restoreEncodedClassSelection(standard)
        assertEquals("example.Messages#one", standard.getString("class"))
        restoreEncodedClassSelection(null)
    }

    @Test
    fun malformedOrAmbiguousSelectionFailsBeforeStandardDiscovery() {
        for (value in listOf("", "%%%", "YQ", encode(""), encode("example.A,,example.B"),
            encode("example.A\nexample.B"), "YQ==\n", "YQ===")) {
            val arguments = Bundle().apply { putString(ENCODED_CLASS_SELECTION, value) }
            assertThrows("Unexpectedly accepted $value", IllegalArgumentException::class.java) {
                restoreEncodedClassSelection(arguments)
            }
            assertFalse(arguments.containsKey("class"))
        }
        val ambiguous = Bundle().apply {
            putString("class", "example.Standard")
            putString(ENCODED_CLASS_SELECTION, encode("example.Encoded"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            restoreEncodedClassSelection(ambiguous)
        }
        assertEquals("example.Standard", ambiguous.getString("class"))
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
