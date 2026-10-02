package io.ethan.pushgo.util

import android.content.Context
import androidx.core.content.edit
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.testing.QualityRuntime
import java.util.concurrent.TimeUnit

private const val REMINDER_SNOOZE_PREFS = "pushgo_reminder_snooze"
private const val KEY_DOZE_REMINDER_UNTIL_MS = "doze_until_ms"
private val ONE_MONTH_SNOOZE_MILLIS: Long = TimeUnit.DAYS.toMillis(30)

fun Context.isDozeReminderSnoozed(nowMs: Long = System.currentTimeMillis()): Boolean {
    return getReminderSnoozeUntilMs(KEY_DOZE_REMINDER_UNTIL_MS) > nowMs
}

fun Context.snoozeDozeReminderForOneMonth(nowMs: Long = System.currentTimeMillis()) {
    setReminderSnoozeUntilMs(
        key = KEY_DOZE_REMINDER_UNTIL_MS,
        untilMs = nowMs + ONE_MONTH_SNOOZE_MILLIS,
    )
}

private fun Context.getReminderSnoozeUntilMs(key: String): Long {
    return getSharedPreferences(reminderSnoozePreferencesName(), Context.MODE_PRIVATE)
        .getLong(key, 0L)
}

private fun Context.setReminderSnoozeUntilMs(key: String, untilMs: Long) {
    getSharedPreferences(reminderSnoozePreferencesName(), Context.MODE_PRIVATE)
        .edit {
            putLong(key, untilMs)
        }
}

private fun reminderSnoozePreferencesName(): String {
    if (BuildConfig.QUALITY_SESSION_CONTROL_ENABLED) {
        QualityRuntime.currentSession()?.let { return it.reminderSnoozePreferencesName }
    }
    return REMINDER_SNOOZE_PREFS
}
