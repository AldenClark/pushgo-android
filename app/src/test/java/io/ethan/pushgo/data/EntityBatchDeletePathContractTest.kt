package io.ethan.pushgo.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityBatchDeletePathContractTest {
    @Test
    fun repositoryExposesBatchDeleteEntriesForEventsAndThings() {
        val source = readSource("src/main/java/io/ethan/pushgo/data/EntityRepository.kt")
        assertTrue(source.contains("suspend fun deleteEvents(eventIds: Collection<String>): Int"))
        assertTrue(source.contains("suspend fun deleteThings(thingIds: Collection<String>): Int"))
    }

    @Test
    fun thingDeletePathsAlsoClearPendingThingScopedEvents() {
        val source = readSource("src/main/java/io/ethan/pushgo/data/EntityRepository.kt")
        assertTrue(source.contains("pendingThingEventDao.deleteByThingId(normalized)"))
        assertTrue(source.contains("pendingThingEventDao.deleteByThingIds(normalizedIds)"))
        assertTrue(source.contains("pendingThingEventDao.deleteByChannel(normalizedChannel)"))
        assertTrue(source.contains("pendingThingEventDao.deleteAll()"))
    }

    @Test
    fun batchUiPathsUseSingleRepositoryBatchCallsInsteadOfPerItemLoops() {
        val eventScreen = readSource("src/main/java/io/ethan/pushgo/ui/screens/EventListScreen.kt")
        val thingScreen = readSource("src/main/java/io/ethan/pushgo/ui/screens/ThingListScreen.kt")

        assertTrue(eventScreen.contains("container.entityRepository.deleteEvents(uniqueEvents.map { it.eventId })"))
        assertFalse(eventScreen.contains("uniqueEvents.forEach { event ->"))

        assertTrue(thingScreen.contains("container.entityRepository.deleteThings(uniqueThings.map { it.thingId })"))
        assertFalse(thingScreen.contains("uniqueThings.forEach { thing ->"))
    }

    private fun readSource(relativePath: String): String {
        val file = File(relativePath)
        require(file.exists()) { "Missing expected source file: $relativePath" }
        return file.readText()
    }
}
