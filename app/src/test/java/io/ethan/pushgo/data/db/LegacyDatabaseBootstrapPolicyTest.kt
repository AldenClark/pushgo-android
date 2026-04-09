package io.ethan.pushgo.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyDatabaseBootstrapPolicyTest {

    @Test
    fun pickBest_prefersHigherContentScoreOverFilenamePriority() {
        val best = LegacyDatabaseBootstrapPolicy.pickBest(
            listOf(
                candidate(name = "pushgo-v22.db", contentScore = 0, priority = 2, lastModified = 200),
                candidate(name = "pushgo-v21.db", contentScore = 3, priority = 1, lastModified = 100),
            )
        )

        assertEquals("pushgo-v21.db", best?.file?.name)
    }

    @Test
    fun pickBest_usesPriorityWhenContentScoreMatches() {
        val best = LegacyDatabaseBootstrapPolicy.pickBest(
            listOf(
                candidate(name = "pushgo-v21.db", contentScore = 1, priority = 1, lastModified = 100),
                candidate(name = "pushgo-v22.db", contentScore = 1, priority = 2, lastModified = 50),
            )
        )

        assertEquals("pushgo-v22.db", best?.file?.name)
    }

    @Test
    fun pickBest_usesLastModifiedAsFinalTiebreaker() {
        val best = LegacyDatabaseBootstrapPolicy.pickBest(
            listOf(
                candidate(name = "a.db", contentScore = 1, priority = 1, lastModified = 10),
                candidate(name = "b.db", contentScore = 1, priority = 1, lastModified = 20),
            )
        )

        assertEquals("b.db", best?.file?.name)
    }

    @Test
    fun pickBest_returnsNullForEmptyCandidates() {
        assertNull(LegacyDatabaseBootstrapPolicy.pickBest(emptyList()))
    }

    @Test
    fun priorityFor_respectsConfiguredOrder() {
        val ordered = listOf("pushgo-v22.db", "pushgo-v21.db")

        assertEquals(2, LegacyDatabaseBootstrapPolicy.priorityFor("pushgo-v22.db", ordered))
        assertEquals(1, LegacyDatabaseBootstrapPolicy.priorityFor("pushgo-v21.db", ordered))
        assertEquals(0, LegacyDatabaseBootstrapPolicy.priorityFor("other.db", ordered))
    }

    private fun candidate(
        name: String,
        contentScore: Int,
        priority: Int,
        lastModified: Long,
    ): LegacyDatabaseBootstrapPolicy.Candidate {
        val dir = File(System.getProperty("java.io.tmpdir"), "pushgo-legacy-bootstrap-tests").apply {
            mkdirs()
        }
        val file = File(dir, name).apply {
            writeText(name)
            setLastModified(lastModified)
        }
        return LegacyDatabaseBootstrapPolicy.Candidate(
            file = file,
            contentScore = contentScore,
            priority = priority,
        )
    }
}
