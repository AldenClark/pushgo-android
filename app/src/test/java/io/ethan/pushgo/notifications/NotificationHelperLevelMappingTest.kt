package io.ethan.pushgo.notifications

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun channelNotificationReplayMatchesEveryEntityGroupByExactChannelSegment() {
        val channelId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val prefix = "io.ethan.pushgo.notifications.groups."

        assertTrue(NotificationHelper.notificationGroupBelongsToChannel("${prefix}message|channel=$channelId", channelId))
        assertTrue(NotificationHelper.notificationGroupBelongsToChannel("${prefix}event|channel=$channelId|event=e1", channelId))
        assertTrue(
            NotificationHelper.notificationGroupBelongsToChannel(
                "${prefix}thing|channel=$channelId|event=e1|thing=t1",
                channelId,
            )
        )
        assertFalse(NotificationHelper.notificationGroupBelongsToChannel("${prefix}message|scope=global", channelId))
        assertFalse(
            NotificationHelper.notificationGroupBelongsToChannel(
                "${prefix}message|channel=${channelId.dropLast(1)}0",
                channelId,
            )
        )
        assertFalse(NotificationHelper.notificationGroupBelongsToChannel("unmanaged|channel=$channelId", channelId))
    }

    @Test
    fun entityDeletionReplayMatchesAssociatedMetadataSegmentsExactly() {
        val prefix = "io.ethan.pushgo.notifications.groups."
        val associatedThing = "${prefix}thing|channel=c|event=event-1|thing=thing-1"

        assertTrue(
            NotificationHelper.notificationGroupMatchesEntityDeletion(
                associatedThing,
                eventIds = setOf("event-1"),
                thingIds = emptySet(),
            )
        )
        assertTrue(
            NotificationHelper.notificationGroupMatchesEntityDeletion(
                associatedThing,
                eventIds = emptySet(),
                thingIds = setOf("thing-1"),
            )
        )
        assertFalse(
            NotificationHelper.notificationGroupMatchesEntityDeletion(
                associatedThing,
                eventIds = setOf("event-10"),
                thingIds = setOf("thing-10"),
            )
        )
        assertFalse(
            NotificationHelper.notificationGroupMatchesEntityDeletion(
                "unmanaged|event=event-1|thing=thing-1",
                eventIds = setOf("event-1"),
                thingIds = setOf("thing-1"),
            )
        )
    }
}
