package io.ethan.pushgo.data

import org.junit.Assert.*
import org.junit.Test

class ImageHttpPolicyTest {
    private val date = 1_600_000_000_000L
    private fun headers(vararg values: Pair<String, String>) = mapOf("Date" to "Sun, 13 Sep 2020 12:26:40 GMT", *values)

    @Test fun freshUsesResponseDelayAgeAndResidentTimeWithoutRestarting() {
        val policy = ImageHttpPolicy.from(headers("Cache-Control" to "max-age=60", "Age" to "10"), date, date + 2_000)
        assertEquals(12_000L, policy.initialAge)
        assertTrue(policy.fresh(date + 49_000))
        assertFalse(policy.fresh(date + 50_000))
        assertEquals(60_000L, policy.currentAge(date + 50_000))
    }
    @Test fun apparentAgeDominatesSmallerAgeHeader() {
        val policy = ImageHttpPolicy.from(headers("Cache-Control" to "max-age=30", "Age" to "1"), date + 15_000, date + 20_000)
        assertEquals(20_000L, policy.initialAge)
        assertFalse(policy.fresh(date + 30_000))
    }
    @Test fun noFreshnessInformationRequiresRevalidationNotTenMinuteMinimum() {
        val policy = ImageHttpPolicy.from(headers(), date, date)
        assertFalse(policy.fresh(date))
        assertEquals(0L, policy.lifetime)
    }
    @Test fun noCacheAndMustRevalidateForbidFailureStale() {
        for (directive in listOf("no-cache", "must-revalidate")) {
            val policy = ImageHttpPolicy.from(headers("Cache-Control" to "$directive,max-age=60"), date, date)
            assertFalse(policy.mayShowStale())
        }
        assertFalse(ImageHttpPolicy.from(headers("Cache-Control" to "no-cache,max-age=60"), date, date).fresh(date))
    }
    @Test fun noStoreCannotPersistOrReuse() {
        val policy = ImageHttpPolicy.from(headers("Cache-Control" to "no-store,max-age=60"), date, date)
        assertTrue(policy.noStore)
        assertFalse(policy.fresh(date))
        assertFalse(policy.mayShowStale())
    }
    @Test fun expiresIsRelativeToDateNotLocalReceipt() {
        val policy = ImageHttpPolicy.from(headers("Expires" to "Sun, 13 Sep 2020 12:27:40 GMT"), date + 40_000, date + 40_000)
        assertTrue(policy.fresh(date + 59_999))
        assertFalse(policy.fresh(date + 60_000))
    }
    @Test fun maxAgePrecedesExpiresAndCanBeZero() {
        val policy = ImageHttpPolicy.from(headers("Cache-Control" to "max-age=0", "Expires" to "Sun, 13 Sep 2020 12:27:40 GMT"), date, date)
        assertFalse(policy.fresh(date))
    }
    @Test fun duplicateOrInvalidMaxAgeCannotTurnAmbiguousResponseFresh() {
        for (directive in listOf("max-age=0,max-age=3600", "max-age=3600,max-age=3600", "max-age=-1", "max-age", "max-age=invalid")) {
            val policy = ImageHttpPolicy.from(headers("Cache-Control" to directive, "Expires" to "Sun, 13 Sep 2020 12:27:40 GMT"), date, date)
            assertFalse(directive, policy.fresh(date))
            assertEquals(0L, policy.lifetime)
        }
    }
    @Test fun maxAgeDoesNotOverflowAndVaryStarRemainsExplicit() {
        val policy = ImageHttpPolicy.from(headers("Cache-Control" to "max-age=9223372036854775807", "Vary" to "Accept, X-Flavor, *"), date, date)
        assertTrue(policy.fresh(date))
        assertEquals(listOf("accept", "x-flavor", "*"), policy.vary)
    }
}
