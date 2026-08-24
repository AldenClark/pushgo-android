package io.ethan.pushgo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextNormalizerTest {
    @Test
    fun normalizeFoldsCaseDiacriticsWidthAndUnicodeCaseVariants() {
        assertEquals("creme brulee", SearchTextNormalizer.normalize("CRÈME BRÛLÉE"))
        assertEquals("pushgo 123", SearchTextNormalizer.normalize("ＰｕｓｈＧｏ １２３"))
        assertEquals("strasse", SearchTextNormalizer.normalize("Straße"))
        assertEquals("οσ", SearchTextNormalizer.normalize("ΟΣ"))
        assertEquals("οσ", SearchTextNormalizer.normalize("ος"))
    }

    @Test
    fun joinedCandidatesKeepsFieldBoundariesAndNormalizesEveryValue() {
        val joined = SearchTextNormalizer.joinedCandidates(listOf("Café", null, "Ａlert"))

        assertTrue(joined.contains("cafe"))
        assertTrue(joined.contains("alert"))
        assertTrue(joined.contains(SearchTextNormalizer.TOKEN_SEPARATOR))
        assertFalse(joined.contains("cafealert"))
    }
}
