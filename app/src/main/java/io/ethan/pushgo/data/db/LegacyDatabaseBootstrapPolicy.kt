package io.ethan.pushgo.data.db

import java.io.File

internal object LegacyDatabaseBootstrapPolicy {
    data class Candidate(
        val file: File,
        val contentScore: Int,
        val priority: Int,
    )

    fun pickBest(candidates: List<Candidate>): Candidate? {
        return candidates.maxWithOrNull(
            compareBy<Candidate> { it.contentScore }
                .thenBy { it.priority }
                .thenBy { it.file.lastModified() }
        )
    }

    fun priorityFor(name: String, orderedNames: List<String>): Int {
        val index = orderedNames.indexOf(name)
        if (index < 0) {
            return 0
        }
        return orderedNames.size - index
    }
}
