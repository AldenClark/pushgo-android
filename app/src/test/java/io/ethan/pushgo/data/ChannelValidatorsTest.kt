package io.ethan.pushgo.data

import io.ethan.pushgo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ChannelValidatorsTest {

    @Test
    fun channelNameValidator_trimsAndAcceptsValidName() {
        val normalized = ChannelNameValidator.normalize("  Ops Alerts  ")
        assertEquals("Ops Alerts", normalized)
    }

    @Test
    fun channelNameValidator_rejectsControlCharacters() {
        try {
            ChannelNameValidator.normalize("ops\u0000alerts")
            fail("expected ChannelNameException")
        } catch (error: ChannelNameException) {
            assertEquals(R.string.error_channel_name_invalid_character, error.resId)
        }
    }

    @Test
    fun channelIdValidator_normalizesAmbiguousCharsAndSeparators() {
        val normalized = ChannelIdValidator.normalize("06j0-fzgl-y8xg-g14vtq4y3g10mr")
        assertEquals("06J0FZG1Y8XGG14VTQ4Y3G10MR", normalized)
    }

    @Test
    fun channelIdValidator_rejectsInvalidAlphabet() {
        try {
            ChannelIdValidator.normalize("06J0FZG1Y8XGG14VTQ4Y3G10M_")
            fail("expected ChannelIdException")
        } catch (error: ChannelIdException) {
            assertEquals(R.string.error_channel_id_invalid, error.resId)
        }
    }

    @Test
    fun channelPasswordValidator_enforcesLengthBounds() {
        assertEquals("Passw0rd#", ChannelPasswordValidator.normalize("  Passw0rd#  "))

        try {
            ChannelPasswordValidator.normalize("short")
            fail("expected ChannelPasswordException")
        } catch (error: ChannelPasswordException) {
            assertEquals(R.string.error_channel_password_length, error.resId)
        }
    }
}
