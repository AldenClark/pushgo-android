package io.ethan.pushgo.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushGoPlayableImageTest {
    @Test
    fun pushGoIsAnimatedModel_treatsGifAsAnimated() {
        assertTrue(pushGoIsAnimatedModel("https://example.com/a.gif"))
    }

    @Test
    fun pushGoIsAnimatedModel_treatsApngAsAnimated() {
        assertTrue(pushGoIsAnimatedModel("https://example.com/a.apng"))
    }

    @Test
    fun pushGoIsAnimatedModel_doesNotTreatWebpAsAnimatedByExtensionOnly() {
        assertFalse(pushGoIsAnimatedModel("https://example.com/a.webp"))
    }
}
