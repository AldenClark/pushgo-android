package io.ethan.pushgo.notifications

import androidx.annotation.StringRes
import io.ethan.pushgo.R

data class RingtoneChoice(
    val id: String,
    @param:StringRes val nameResId: Int,
) {
    val displayNameRes: Int
        get() = nameResId
}

object RingtoneCatalog {
    private val systemDefault = RingtoneChoice(
        id = "system-default",
        nameResId = R.string.ringtone_notification,
    )

    fun ringtoneForLevel(level: String?): RingtoneChoice {
        return systemDefault
    }
}
