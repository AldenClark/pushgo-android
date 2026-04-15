package io.ethan.pushgo.util

import android.content.Context
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
    return getSharedPreferences(REMINDER_SNOOZE_PREFS, Context.MODE_PRIVATE)
        .getLong(key, 0L)
}

private fun Context.setReminderSnoozeUntilMs(key: String, untilMs: Long) {
    getSharedPreferences(REMINDER_SNOOZE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putLong(key, untilMs)
        .apply()
}
