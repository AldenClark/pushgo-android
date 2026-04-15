package io.ethan.pushgo.notifications

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationHelperLevelMappingTest {
    @Test
    fun notificationPresentationNormalLevelKeepsChannelTag() {
        assertEquals(
            "normal",
            NotificationHelper.normalizeNotificationPresentationLevel("normal"),
        )
    }

    @Test
    fun notificationPresentationOtherLevelsRemainUnchanged() {
        assertEquals(
            "critical",
            NotificationHelper.normalizeNotificationPresentationLevel("critical"),
        )
        assertEquals(
            "high",
            NotificationHelper.normalizeNotificationPresentationLevel("high"),
        )
        assertEquals(
            "low",
            NotificationHelper.normalizeNotificationPresentationLevel("low"),
        )
    }

    @Test
    fun lockscreenVisibilityForNormalAndHigherDefaultsToPrivate() {
        assertEquals(
            NotificationCompat.VISIBILITY_PRIVATE,
            NotificationHelper.defaultLockscreenVisibilityForLevel("normal"),
        )
        assertEquals(
            NotificationCompat.VISIBILITY_PRIVATE,
            NotificationHelper.defaultLockscreenVisibilityForLevel("high"),
        )
        assertEquals(
            NotificationCompat.VISIBILITY_PRIVATE,
            NotificationHelper.defaultLockscreenVisibilityForLevel("critical"),
        )
    }
}
