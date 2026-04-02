package io.ethan.pushgo.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundNotificationPresentationStateTest {

    @Test
    fun suppressesOnlyMatchingEntityTypeWhenTopOfList() {
        ForegroundNotificationPresentationState.clearMessage()
        ForegroundNotificationPresentationState.clearEvent()
        ForegroundNotificationPresentationState.clearThing()

        ForegroundNotificationPresentationState.reportEvent(
            isAtTop = true,
            suppressionEligible = true,
        )

        assertTrue(ForegroundNotificationPresentationState.shouldSuppress("event"))
        assertFalse(ForegroundNotificationPresentationState.shouldSuppress("message"))
        assertFalse(ForegroundNotificationPresentationState.shouldSuppress("thing"))
    }

    @Test
    fun detailOrScrolledStateDoesNotSuppressForegroundNotification() {
        ForegroundNotificationPresentationState.clearMessage()
        ForegroundNotificationPresentationState.clearEvent()
        ForegroundNotificationPresentationState.clearThing()

        ForegroundNotificationPresentationState.reportMessage(
            isAtTop = true,
            suppressionEligible = false,
        )
        assertFalse(ForegroundNotificationPresentationState.shouldSuppress("message"))

        ForegroundNotificationPresentationState.reportThing(
            isAtTop = false,
            suppressionEligible = true,
        )
        assertFalse(ForegroundNotificationPresentationState.shouldSuppress("thing"))
    }
}
