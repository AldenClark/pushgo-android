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
    const val DEFAULT_ID = "notification-sound"

    val catalog: List<BuiltInRingtone> = listOf(
        BuiltInRingtone(
            id = "notification-sound",
            nameResId = R.string.ringtone_notification,
            filename = "notification-sound.caf",
            rawResId = R.raw.ring_notification_sound,
        ),
        BuiltInRingtone(
            id = "alert",
            nameResId = R.string.ringtone_alert,
            filename = "alert.caf",
            rawResId = R.raw.ring_alert,
        ),
        BuiltInRingtone(
            id = "quick-whoosh",
            nameResId = R.string.ringtone_quick_whoosh,
            filename = "quick-whoosh.caf",
            rawResId = R.raw.ring_quick_whoosh,
        ),
        BuiltInRingtone(
            id = "pop",
            nameResId = R.string.ringtone_pop,
            filename = "pop.caf",
            rawResId = R.raw.ring_pop,
        ),
        BuiltInRingtone(
            id = "bubble-pop",
            nameResId = R.string.ringtone_bubble_pop,
            filename = "bubble-pop.caf",
            rawResId = R.raw.ring_bubble_pop,
        ),
        BuiltInRingtone(
            id = "arcade-sound",
            nameResId = R.string.ringtone_arcade,
            filename = "arcade-sound.caf",
            rawResId = R.raw.ring_arcade_sound,
        ),
        BuiltInRingtone(
            id = "cartoon-blinking",
            nameResId = R.string.ringtone_cartoon_blink,
            filename = "cartoon-blinking.caf",
            rawResId = R.raw.ring_cartoon_blinking,
        ),
        BuiltInRingtone(
            id = "cute-chime",
            nameResId = R.string.ringtone_cute_chime,
            filename = "cute-chime.caf",
            rawResId = R.raw.ring_cute_chime,
        ),
        BuiltInRingtone(
            id = "level-up",
            nameResId = R.string.ringtone_level_up,
            filename = "level-up.caf",
            rawResId = R.raw.ring_level_up,
        ),
        BuiltInRingtone(
            id = "festive-chime",
            nameResId = R.string.ringtone_festive_chime,
            filename = "festive-chime.caf",
            rawResId = R.raw.ring_festive_chime,
        ),
    )

    private val byId = catalog.associateBy { it.id }

    fun findById(id: String?): BuiltInRingtone = byId[id] ?: catalog.first()

    fun resolveBySoundValue(sound: String?): BuiltInRingtone? {
        val normalized = sound?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val base = normalized.substringBeforeLast('.')
        return catalog.firstOrNull { ringtone ->
            ringtone.id == base || ringtone.filename.lowercase().substringBeforeLast('.') == base
        }
    }
}
