package io.ethan.pushgo.notifications

import androidx.annotation.StringRes
import io.ethan.pushgo.R


data class BuiltInRingtone(
    val id: String,
    @param:StringRes val nameResId: Int,
    val filename: String,
    val rawResId: Int?,
) {
    val displayNameRes: Int
        get() = nameResId
}

object RingtoneCatalog {
    val catalog: List<BuiltInRingtone> = listOf(
        BuiltInRingtone(
            id = "alert",
            nameResId = R.string.ringtone_alert,
            filename = "alert.caf",
            rawResId = R.raw.ring_alert,
        ),
        BuiltInRingtone(
            id = "level-up",
            nameResId = R.string.ringtone_level_up,
            filename = "level-up.caf",
            rawResId = R.raw.ring_level_up,
        ),
        BuiltInRingtone(
            id = "bubble-pop",
            nameResId = R.string.ringtone_bubble_pop,
            filename = "bubble-pop.caf",
            rawResId = R.raw.ring_bubble_pop,
        ),
    )

    fun ringtoneForLevel(level: String?): BuiltInRingtone? {
        val normalized = level?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "critical" -> catalog.first { it.id == "alert" }
            "high" -> catalog.first { it.id == "level-up" }
            "low" -> null
            else -> catalog.first { it.id == "bubble-pop" }
        }
    }
}
