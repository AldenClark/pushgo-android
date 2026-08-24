package io.ethan.pushgo

import org.junit.Assert.assertEquals
import org.junit.Test

class StartedActivityInteractionBoundaryTest {
    @Test
    fun configurationStopNeverPublishesBackground() {
        val scheduled = mutableListOf<Runnable>()
        val transitions = mutableListOf<Boolean>()
        val boundary = StartedActivityInteractionBoundary(
            scheduleDelayed = { _, block -> scheduled += block },
            onInteractionChanged = transitions::add,
        )

        boundary.onActivityStarted()
        boundary.onActivityStopped(isChangingConfigurations = true)

        assertEquals(listOf(true), transitions)
        assertEquals(emptyList<Runnable>(), scheduled)
    }

    @Test
    fun stopFollowedByStartBeforeDebounceDoesNotPublishBackground() {
        val scheduled = mutableListOf<Runnable>()
        val transitions = mutableListOf<Boolean>()
        val boundary = StartedActivityInteractionBoundary(
            scheduleDelayed = { _, block -> scheduled += block },
            onInteractionChanged = transitions::add,
        )

        boundary.onActivityStarted()
        boundary.onActivityStopped()
        boundary.onActivityStarted()
        scheduled.single().run()

        assertEquals(listOf(true), transitions)
    }

    @Test
    fun finalStartedStopPublishesBackgroundOnlyAfterDebounce() {
        val scheduled = mutableListOf<Pair<Long, Runnable>>()
        val transitions = mutableListOf<Boolean>()
        val boundary = StartedActivityInteractionBoundary(
            scheduleDelayed = { delay, block -> scheduled += delay to block },
            onInteractionChanged = transitions::add,
        )

        boundary.onActivityStarted()
        boundary.onActivityStopped()
        assertEquals(listOf(true), transitions)

        assertEquals(StartedActivityInteractionBoundary.PROCESS_STOP_DEBOUNCE_MILLIS, scheduled.single().first)
        scheduled.single().second.run()
        assertEquals(listOf(true, false), transitions)
    }
}
