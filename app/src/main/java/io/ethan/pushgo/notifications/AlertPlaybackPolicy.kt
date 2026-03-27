package io.ethan.pushgo.notifications

data class AlertPlaybackSpec(
    val mode: AlertPlaybackMode,
    val maxDurationMs: Long?,
) {
    val isEnabled: Boolean
        get() = mode != AlertPlaybackMode.OFF
}

enum class AlertPlaybackMode {
    OFF,
    TIMED,
    CONTINUOUS,
}

object AlertPlaybackPolicy {
    private const val HIGH_DURATION_MS = 15_000L

    fun specForLevel(level: String?): AlertPlaybackSpec {
        return when (normalize(level)) {
            "critical" -> AlertPlaybackSpec(
                mode = AlertPlaybackMode.CONTINUOUS,
                maxDurationMs = null,
            )
            "high" -> AlertPlaybackSpec(
                mode = AlertPlaybackMode.TIMED,
                maxDurationMs = HIGH_DURATION_MS,
            )
            "normal" -> AlertPlaybackSpec(
                mode = AlertPlaybackMode.OFF,
                maxDurationMs = null,
            )
            "low" -> AlertPlaybackSpec(
                mode = AlertPlaybackMode.OFF,
                maxDurationMs = null,
            )
            else -> AlertPlaybackSpec(
                mode = AlertPlaybackMode.OFF,
                maxDurationMs = null,
            )
        }
    }

    fun severityRank(level: String?): Int {
        return when (normalize(level)) {
            "critical" -> 3
            "high" -> 2
            "normal" -> 1
            else -> 0
        }
    }

    private fun normalize(level: String?): String {
        return level?.trim()?.lowercase().orEmpty()
    }
}
