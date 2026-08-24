package io.ethan.pushgo.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReplayPolicyTest {
    @Test
    fun silentReplay_silencesSystemAlertAndNeverStartsCustomPlayback() {
        val behavior = NotificationHelper.NotificationPostBehavior.SILENT_REPLAY

        assertTrue(behavior.silenceSystemAlert)
        assertFalse(behavior.startCustomAlert)
        assertTrue(behavior.onlyAlertOnce)
    }

    @Test
    fun newDelivery_retainsAlertPlayback() {
        val behavior = NotificationHelper.NotificationPostBehavior.NEW_DELIVERY

        assertFalse(behavior.silenceSystemAlert)
        assertTrue(behavior.startCustomAlert)
        assertFalse(behavior.onlyAlertOnce)
    }
}
