package io.ethan.pushgo.ui

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingLocalDeletionCoordinatorTest {
    @Test
    fun schedulingNewDeletionCommitsPreviousEntryImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val commits = CopyOnWriteArrayList<String>()
        val firstCommit = CountDownLatch(1)
        try {
            val coordinator = PendingLocalDeletionCoordinator(
                appScope = scope,
                countdownMillis = 5_000L,
                elapsedRealtimeMillis = { System.nanoTime() / 1_000_000L },
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

            assertTrue(firstCommit.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("first"), commits.toList())
            val pending = coordinator.pendingDeletion.value
            assertNotNull(pending)
            assertEquals(setOf("m2"), pending?.scope?.messageIds)
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
            )

            coordinator.schedule(
                summary = "message",
                scope = PendingLocalDeletionCoordinator.Scope(messageIds = setOf("m1")),
                onCommit = {
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
}
