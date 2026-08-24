package io.ethan.pushgo.data

import io.ethan.pushgo.data.db.ChannelSubscriptionEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMutationBarrierTest {
    @Test
    fun resubscribeVersionAdvancesWhenWallClockHasNotTicked() {
        assertTrue(nextChannelSubscriptionVersion(previous = 42L, wallClockEpochMillis = 42L) > 42L)
    }

    @Test
    fun oldDeletionDoesNotOwnNewOrDifferentlyVersionedCredential() {
        val activeNewVersion = subscription(updatedAt = 43L, isDeleted = false)
        val deletedNewVersion = subscription(updatedAt = 43L, isDeleted = true)
        val deletedExpectedVersion = subscription(updatedAt = 42L, isDeleted = true)

        assertFalse(activeNewVersion.credentialBelongsToDeletedVersion(42L))
        assertFalse(deletedNewVersion.credentialBelongsToDeletedVersion(42L))
        assertTrue(deletedExpectedVersion.credentialBelongsToDeletedVersion(42L))
        assertTrue((null as ChannelSubscriptionEntity?).credentialBelongsToDeletedVersion(42L))
    }

    @Test
    fun sameGatewayAndChannelAreSerialized() = runBlocking {
        val barrier = ChannelMutationBarrier()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = scope.launch {
                barrier.withLock(GATEWAY, CHANNEL) {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(1, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            val second = scope.launch {
                barrier.withLock("$GATEWAY/", CHANNEL) {
                    secondEntered.countDown()
                }
            }

            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.join()
            second.join()
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            scope.cancel()
        }
    }

    @Test
    fun differentChannelDoesNotShareBarrier() = runBlocking {
        val barrier = ChannelMutationBarrier()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val otherEntered = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            scope.launch {
                barrier.withLock(GATEWAY, CHANNEL) {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(1, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            scope.launch {
                barrier.withLock(GATEWAY, OTHER_CHANNEL) {
                    otherEntered.countDown()
                }
            }

            assertTrue(otherEntered.await(1, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            scope.cancel()
        }
    }

    private companion object {
        const val GATEWAY = "https://gateway.example"
        const val CHANNEL = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        const val OTHER_CHANNEL = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
    }

    private fun subscription(updatedAt: Long, isDeleted: Boolean) = ChannelSubscriptionEntity(
        gatewayUrl = GATEWAY,
        channelId = CHANNEL,
        displayName = "channel",
        updatedAt = updatedAt,
        lastSyncedAt = null,
        isDeleted = isDeleted,
        deletedAt = if (isDeleted) 100L else null,
    )
}
