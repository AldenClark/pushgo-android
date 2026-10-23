package io.ethan.pushgo.data

import org.junit.Assert.assertThrows
import org.junit.Test

class EntityThingWriteTicketTest {
    @Test
    fun deletionInvalidatesOnlyAlreadyActiveMatchingThingAndNewInvocationIsEligible() {
        val tickets = ThingWriteTickets()
        val old = requireNotNull(tickets.register(" thing ", "channel"))
        val unrelated = requireNotNull(tickets.register("other", "channel"))
        tickets.invalidateThings(listOf("thing"))
        assertThrows(ThingWriteInvalidatedException::class.java) { old.requireCurrent() }
        unrelated.requireCurrent()
        requireNotNull(tickets.register("thing", "channel")).use { it.requireCurrent() }
        old.close()
        unrelated.close()
    }

    @Test
    fun channelDeletePreservesCrossChannelScopeAndAllDeleteCancelsRemainingWrites() {
        val tickets = ThingWriteTickets()
        val removed = requireNotNull(tickets.register("thing", "remove"))
        val retained = requireNotNull(tickets.register("thing", "keep"))
        tickets.invalidateChannel("remove")
        assertThrows(ThingWriteInvalidatedException::class.java) { removed.requireCurrent() }
        retained.requireCurrent()
        tickets.invalidateChannel(null)
        assertThrows(ThingWriteInvalidatedException::class.java) { retained.requireCurrent() }
        removed.close()
        retained.close()
    }

    @Test
    fun closedOperationCannotBeReusedForLaterWrites() {
        val tickets = ThingWriteTickets()
        val complete = requireNotNull(tickets.register("thing", null))
        complete.close()
        assertThrows(ThingWriteInvalidatedException::class.java) { complete.requireCurrent() }
        tickets.invalidateThings(listOf("thing"))
        assertThrows(ThingWriteInvalidatedException::class.java) { complete.requireCurrent() }
        requireNotNull(tickets.register("thing", null)).use { it.requireCurrent() }
    }
}
