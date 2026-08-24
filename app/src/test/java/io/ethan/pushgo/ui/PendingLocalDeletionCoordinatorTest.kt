package io.ethan.pushgo.ui

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingLocalDeletionCoordinatorTest {
    @Test
    fun schedulingNewDeletionQueuesWithoutShorteningPreviousUndoWindow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commits = CopyOnWriteArrayList<String>()
        val firstCommit = CountDownLatch(1)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 5_000L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )

            coordinator.schedule(
                summary = "first",
                scope = PendingLocalDeletionCoordinator.Scope(messageIds = setOf("m1")),
                onCommit = {
                    commits += "first"
                    firstCommit.countDown()
                },
            )
            coordinator.schedule(
                summary = "second",
                scope = PendingLocalDeletionCoordinator.Scope(messageIds = setOf("m2")),
                onCommit = {
                    commits += "second"
                },
            )

            assertFalse(firstCommit.await(100, TimeUnit.MILLISECONDS))
            assertTrue(commits.isEmpty())
            val pending = coordinator.pendingDeletion.value
            assertNotNull(pending)
            assertEquals(setOf("m1"), pending?.scope?.messageIds)
            assertEquals(setOf("m1", "m2"), coordinator.effectiveScope.value.messageIds)

            coordinator.undoCurrent()

            assertEquals(setOf("m2"), coordinator.pendingDeletion.value?.scope?.messageIds)
            assertEquals(setOf("m2"), coordinator.effectiveScope.value.messageIds)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun undoClearsPendingDeletionWithoutRunningCommit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var committed = false
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 100L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )

            coordinator.schedule(
                summary = "message",
                scope = PendingLocalDeletionCoordinator.Scope(messageIds = setOf("m1")),
                onCommit = {
                    committed = true
                },
            )
            coordinator.undoCurrent()

            assertNull(coordinator.pendingDeletion.value)
            Thread.sleep(180)
            assertFalse(committed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completionCallbackExceptionDoesNotCrashCommitPath() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commitLatch = CountDownLatch(1)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 5L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )

            coordinator.schedule(
                summary = "message",
                scope = PendingLocalDeletionCoordinator.Scope(messageIds = setOf("m1")),
                onCommit = {
                    commitLatch.countDown()
                },
                onCompletion = {
                    throw IllegalStateException("ui callback failed")
                },
            )

            assertTrue(commitLatch.await(1, TimeUnit.SECONDS))
            Thread.sleep(60)
            assertNull(coordinator.pendingDeletion.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun countdownExpiryCommitsPendingDeletion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commitLatch = CountDownLatch(1)
        var committed = false
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 20L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )

            coordinator.schedule(
                summary = "message",
                scope = PendingLocalDeletionCoordinator.Scope(messageIds = setOf("m1")),
                onCommit = {
                    delay(5L)
                    committed = true
                    commitLatch.countDown()
                },
            )

            assertTrue(commitLatch.await(1, TimeUnit.SECONDS))
            assertTrue(committed)
            assertNull(coordinator.pendingDeletion.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun commitCurrentIfNeededCommitsImmediatelyBeforeCountdownExpiry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commitLatch = CountDownLatch(1)
        val completionLatch = CountDownLatch(1)
        var committed = false
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 5_000L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )

            coordinator.schedule(
                summary = "message",
                scope = PendingLocalDeletionCoordinator.Scope(messageIds = setOf("m1")),
                onCommit = {
                    committed = true
                    commitLatch.countDown()
                },
                onCompletion = {
                    if (it.isSuccess) {
                        completionLatch.countDown()
                    }
                },
            )

            coordinator.commitCurrentIfNeeded()

            assertTrue(commitLatch.await(1, TimeUnit.SECONDS))
            assertTrue(completionLatch.await(1, TimeUnit.SECONDS))
            assertTrue(committed)
            assertNull(coordinator.pendingDeletion.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun effectiveScopeRemainsActiveUntilCommitFinishes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 5_000L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )

            coordinator.schedule(
                summary = "event",
                scope = PendingLocalDeletionCoordinator.Scope(eventIds = setOf("event-1")),
                onCommit = {
                    commitStarted.countDown()
                    assertTrue(releaseCommit.await(1, TimeUnit.SECONDS))
                },
            )

            val commitJob = scope.launch {
                coordinator.commitCurrentIfNeeded()
            }
            assertTrue(commitStarted.await(1, TimeUnit.SECONDS))
            assertNull(coordinator.pendingDeletion.value)
            assertTrue(
                coordinator.effectiveScope.value.suppressesEvent(
                    id = "event-1",
                    channelId = null,
                ),
            )

            releaseCommit.countDown()
            commitJob.join()
            assertFalse(
                coordinator.effectiveScope.value.suppressesEvent(
                    id = "event-1",
                    channelId = null,
                ),
            )
        } finally {
            releaseCommit.countDown()
            scope.cancel()
        }
    }

    @Test
    fun completionCallbackUsesConfiguredDispatcher() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatchCount = AtomicInteger(0)
        val completionDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatchCount.incrementAndGet()
                block.run()
            }
        }
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 5_000L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = completionDispatcher,
            )

            coordinator.schedule(
                summary = "message",
                scope = PendingLocalDeletionCoordinator.Scope(messageIds = setOf("m1")),
                onCommit = {},
                onCompletion = {},
            )
            coordinator.commitCurrentIfNeeded()

            assertEquals(1, dispatchCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun explicitDrainCommitsNewlyQueuedWorkAfterOlderCommitFinishes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstCommitStarted = CountDownLatch(1)
        val releaseFirstCommit = CountDownLatch(1)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 5_000L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )

            coordinator.schedule(
                summary = "first event",
                scope = PendingLocalDeletionCoordinator.Scope(eventIds = setOf("event-1")),
                onCommit = {
                    firstCommitStarted.countDown()
                    assertTrue(releaseFirstCommit.await(1, TimeUnit.SECONDS))
                },
            )
            val firstCommit = scope.launch {
                coordinator.commitCurrentIfNeeded()
            }
            assertTrue(firstCommitStarted.await(1, TimeUnit.SECONDS))

            coordinator.schedule(
                summary = "second thing",
                scope = PendingLocalDeletionCoordinator.Scope(thingIds = setOf("thing-2")),
                onCommit = {},
            )
            assertTrue(coordinator.effectiveScope.value.suppressesEvent("event-1", null))
            assertTrue(coordinator.effectiveScope.value.suppressesThing("thing-2", null))

            releaseFirstCommit.countDown()
            firstCommit.join()

            assertFalse(coordinator.effectiveScope.value.suppressesEvent("event-1", null))
            assertFalse(coordinator.effectiveScope.value.suppressesThing("thing-2", null))
            assertNull(coordinator.pendingDeletion.value)
        } finally {
            releaseFirstCommit.countDown()
            scope.cancel()
        }
    }

    @Test
    fun inactiveInteractionCommitsImmediatelyInsteadOfFreezingCountdown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commitLatch = CountDownLatch(1)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 80L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )

            coordinator.schedule(
                summary = "message",
                scope = PendingLocalDeletionCoordinator.Scope(),
                onCommit = { commitLatch.countDown() },
            )
            coordinator.setInteractionActive(false)
            assertTrue(commitLatch.await(1, TimeUnit.SECONDS))
            assertNull(coordinator.pendingDeletion.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleLifecycleUpdateCannotResurrectDeletionCommittedInBackground() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commitCount = AtomicInteger(0)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 5_000L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )
            coordinator.schedule(
                summary = "message",
                scope = PendingLocalDeletionCoordinator.Scope(),
                onCommit = { commitCount.incrementAndGet() },
            )

            coordinator.setInteractionActive(false, generation = 2L)
            coordinator.setInteractionActive(true, generation = 1L)

            assertEquals(1, commitCount.get())
            assertNull(coordinator.pendingDeletion.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentCommitClaimsEntryOnlyOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commitCount = AtomicInteger(0)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 5_000L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )
            coordinator.schedule(
                summary = "message",
                scope = PendingLocalDeletionCoordinator.Scope(),
                onCommit = {
                    commitCount.incrementAndGet()
                    Thread.sleep(40)
                },
            )

            val first = scope.launch { coordinator.commitCurrentIfNeeded() }
            val second = scope.launch { coordinator.commitCurrentIfNeeded() }
            first.join()
            second.join()

            assertEquals(1, commitCount.get())
        } finally {
            scope.cancel()
        }
    }
}
