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
    fun channelRemovalAlsoClearsPendingThingMessagesThatCouldReplayLater() {
        val repository = readSource("src/main/java/io/ethan/pushgo/data/MessageRepository.kt")
        val channelRepository = readSource("src/main/java/io/ethan/pushgo/data/ChannelSubscriptionRepository.kt")
        assertTrue(repository.contains("pendingThingMessageDao.deleteByChannel(normalizedChannel)"))
        assertTrue(channelRepository.contains("messageRepository.deleteByChannel(channelId)"))
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

    @Test
    fun eventAndThingScreensUseEffectiveDeletionScopeThroughCommitCompletion() {
        val eventScreen = readSource("src/main/java/io/ethan/pushgo/ui/screens/EventListScreen.kt")
        val thingScreen = readSource("src/main/java/io/ethan/pushgo/ui/screens/ThingListScreen.kt")

        assertTrue(eventScreen.contains("pendingLocalDeletionCoordinator.effectiveScope.collectAsStateWithLifecycle()"))
        assertTrue(eventScreen.contains("LaunchedEffect(effectivePendingScope)"))
        assertFalse(eventScreen.contains("pendingLocalDeletionCoordinator.pendingDeletion.collectAsStateWithLifecycle()"))

        assertTrue(thingScreen.contains("pendingLocalDeletionCoordinator.effectiveScope.collectAsStateWithLifecycle()"))
        assertTrue(thingScreen.contains("LaunchedEffect(effectivePendingScope)"))
        assertFalse(thingScreen.contains("pendingLocalDeletionCoordinator.pendingDeletion.collectAsStateWithLifecycle()"))
    }

    @Test
    fun messageDetailClosesForChannelWideEffectiveDeletionScope() {
        val detailScreen = readSource("src/main/java/io/ethan/pushgo/ui/screens/MessageDetailScreen.kt")

        assertTrue(detailScreen.contains("pendingLocalDeletionCoordinator.effectiveScope.collectAsStateWithLifecycle()"))
        assertTrue(detailScreen.contains("effectivePendingScope.suppressesMessage(visibleMessage.id, visibleMessage.channel)"))
    }

    @Test
    fun channelRemovalIntentBindsGatewayVersionAndDeliveryModeBeforeCommit() {
        val channelScreen = readSource("src/main/java/io/ethan/pushgo/ui/screens/ChannelListScreen.kt")
        val settingsViewModel = readSource("src/main/java/io/ethan/pushgo/ui/viewmodel/SettingsViewModel.kt")

        assertTrue(channelScreen.contains("expectedUseProvider = viewModel.channelRemovalUsesProvider(appContext)"))
        assertTrue(channelScreen.contains("expectedUpdatedAt = removalTarget.updatedAt"))
        assertTrue(settingsViewModel.contains("if (useProvider != expectedUseProvider)"))
        assertTrue(settingsViewModel.contains("channel_delivery_mode_changed_during_removal"))
    }

    @Test
    fun channelRemovalDialogIsClaimedBeforeSnapshotReadsAndCompensationUsesOriginalGateway() {
        val channelScreen = readSource("src/main/java/io/ethan/pushgo/ui/screens/ChannelListScreen.kt")
        val settingsViewModel = readSource("src/main/java/io/ethan/pushgo/ui/viewmodel/SettingsViewModel.kt")

        val actionStart = channelScreen.indexOf("Claim this dialog action synchronously")
        val claim = channelScreen.indexOf(
            "pendingChannelRemoval = null",
            startIndex = actionStart.coerceAtLeast(0),
        )
        val snapshotRead = channelScreen.indexOf(
            "loadGatewayConfig().first",
            startIndex = claim.coerceAtLeast(0),
        )
        assertTrue(actionStart >= 0)
        assertTrue(claim >= 0)
        assertTrue(snapshotRead > claim)
        assertTrue(channelScreen.contains("if (error is CancellationException) throw error"))
        assertTrue(settingsViewModel.contains("expectedGatewayUrl = expectedGateway"))
    }

    private fun readSource(relativePath: String): String {
        val file = File(relativePath)
        require(file.exists()) { "Missing expected source file: $relativePath" }
        return file.readText()
    }
}
