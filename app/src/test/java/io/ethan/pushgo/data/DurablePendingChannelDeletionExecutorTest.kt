package io.ethan.pushgo.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DurablePendingChannelDeletionExecutorTest {
    @Test
    fun deletionStateMachineRunsInsideChannelMutationBarrier() = runBlocking {
        val backend = FakeBackend(state = PendingChannelDeletionTargetState.ALREADY_DELETED)

        DurablePendingChannelDeletionExecutor(backend).execute(OPERATION)

        assertEquals(1, backend.mutationLockCount)
    }

    @Test
    fun alreadyDeletedIsIdempotentSuccessWithoutRemoteCall() = runBlocking {
        val backend = FakeBackend(state = PendingChannelDeletionTargetState.ALREADY_DELETED)

        DurablePendingChannelDeletionExecutor(backend).execute(OPERATION)

        assertEquals(0, backend.remoteUnsubscribeCount)
        assertEquals(0, backend.localDeleteCount)
        assertEquals(1, backend.cleanupCredentialCount)
    }

    @Test
    fun newerVersionIsPermanentConflictAndIsNeverDeleted() {
        val backend = FakeBackend(state = PendingChannelDeletionTargetState.CONFLICTING_VERSION)

        assertThrows(PermanentPendingLocalDeletionException::class.java) {
            runBlocking { DurablePendingChannelDeletionExecutor(backend).execute(OPERATION) }
        }

        assertEquals(0, backend.remoteUnsubscribeCount)
        assertEquals(0, backend.localDeleteCount)
    }

    @Test
    fun replayAfterLocalCommitReturnsSuccessWithoutRepeatingRemoteMutation() = runBlocking {
        val backend = FakeBackend(state = PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION)
        val executor = DurablePendingChannelDeletionExecutor(backend)

        executor.execute(OPERATION)
        executor.execute(OPERATION)

        assertEquals(1, backend.remoteUnsubscribeCount)
        assertEquals(1, backend.localDeleteCount)
        assertEquals(PendingChannelDeletionTargetState.ALREADY_DELETED, backend.state)
    }

    @Test
    fun versionChangeDuringLocalCommitRestoresNewSubscriptionAndFailsPermanently() {
        val backend = FakeBackend(
            state = PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION,
            conflictDuringLocalDelete = true,
        )

        assertThrows(PermanentPendingLocalDeletionException::class.java) {
            runBlocking { DurablePendingChannelDeletionExecutor(backend).execute(OPERATION) }
        }

        assertEquals(1, backend.remoteUnsubscribeCount)
        assertEquals(1, backend.localDeleteCount)
        assertEquals(1, backend.restoreCount)
        assertEquals(PendingChannelDeletionTargetState.CONFLICTING_VERSION, backend.state)
    }

    @Test
    fun providerModeUsesOnlyExistingTokenAndNeverRequestsOne() {
        val backend = FakeBackend(
            state = PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION,
            providerToken = null,
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { DurablePendingChannelDeletionExecutor(backend).execute(OPERATION) }
        }

        assertEquals(1, backend.existingTokenReadCount)
        assertEquals(0, backend.remoteUnsubscribeCount)
    }

    @Test
    fun privateModeReplayIsIdempotentWhenFirstExecutionCompletedLocally() = runBlocking {
        val backend = FakeBackend(
            state = PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION,
            usesProvider = false,
        )
        val operation = PendingLocalDeletionOperation.channel(
            id = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            expectedGatewayUrl = "https://gateway.example",
            expectedUpdatedAt = 42L,
            expectedUseProvider = false,
        )
        val executor = DurablePendingChannelDeletionExecutor(backend)

        executor.execute(operation)
        executor.execute(operation)

        assertEquals(1, backend.remoteUnsubscribeCount)
        assertEquals(1, backend.localDeleteCount)
    }

    @Test
    fun knownMissingRemoteSubscriptionIsClassifiedAsIdempotentSuccess() {
        val knownMissing = ChannelSubscriptionException(
            message = "not subscribed",
            code = "subscription_not_found",
        )
        val unrelated = ChannelSubscriptionException(
            message = "timeout",
            code = "gateway_timeout",
        )

        assertTrue(knownMissing.isAlreadyUnsubscribedForPendingDeletion())
        assertFalse(unrelated.isAlreadyUnsubscribedForPendingDeletion())
    }

    @Test
    fun gatewaySwitchBeforePrivateRemoteCallFailsPermanentlyWithoutLocalDelete() {
        val backend = FakeBackend(
            state = PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION,
            usesProvider = false,
            privateError = ChannelSubscriptionException(
                message = "gateway changed",
                code = "gateway_changed_during_channel_removal",
                category = GatewayErrorCategory.CONFLICT,
                retryable = false,
            ),
        )
        val operation = PendingLocalDeletionOperation.channel(
            id = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            expectedGatewayUrl = "https://old.example",
            expectedUpdatedAt = 42L,
            expectedUseProvider = false,
        )

        assertThrows(PermanentPendingLocalDeletionException::class.java) {
            runBlocking { DurablePendingChannelDeletionExecutor(backend).execute(operation) }
        }

        assertEquals(0, backend.localDeleteCount)
    }

    @Test
    fun unauthorizedGatewayResponseBecomesPermanentFailure() {
        val backend = FakeBackend(
            state = PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION,
            providerError = ChannelSubscriptionException(
                message = "unauthorized",
                code = "authentication_failed",
                category = GatewayErrorCategory.AUTH,
                httpStatus = 401,
                retryable = false,
            ),
        )

        assertThrows(PermanentPendingLocalDeletionException::class.java) {
            runBlocking { DurablePendingChannelDeletionExecutor(backend).execute(OPERATION) }
        }

        assertEquals(0, backend.localDeleteCount)
    }

    @Test
    fun alreadyDeletedCredentialCleanupIsRetriedAfterInterruption() = runBlocking {
        val backend = FakeBackend(
            state = PendingChannelDeletionTargetState.ALREADY_DELETED,
            cleanupFailuresRemaining = 1,
        )
        val executor = DurablePendingChannelDeletionExecutor(backend)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { executor.execute(OPERATION) }
        }
        executor.execute(OPERATION)

        assertEquals(2, backend.cleanupCredentialCount)
    }

    @Test
    fun firstPostCommitCredentialCleanupFailureRequiresDurableReplay() = runBlocking {
        val backend = FakeBackend(
            state = PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION,
            failCredentialCleanupAfterLocalDelete = true,
        )
        val executor = DurablePendingChannelDeletionExecutor(backend)

        assertThrows(PendingLocalDeletionCredentialCleanupException::class.java) {
            runBlocking { executor.execute(OPERATION) }
        }
        assertEquals(PendingChannelDeletionTargetState.ALREADY_DELETED, backend.state)
        assertEquals(0, backend.cleanupCredentialCount)

        executor.execute(OPERATION)

        assertEquals(1, backend.cleanupCredentialCount)
    }

    @Test
    fun postCommitNotificationFailureIsRetriedBeforeCredentialCleanup() = runBlocking {
        val backend = FakeBackend(
            state = PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION,
            failNotificationCleanupAfterLocalDelete = true,
        )
        val firstProcessExecutor = DurablePendingChannelDeletionExecutor(backend)

        assertThrows(PendingLocalDeletionNotificationReconciliationException::class.java) {
            runBlocking { firstProcessExecutor.execute(OPERATION) }
        }
        assertEquals(PendingChannelDeletionTargetState.ALREADY_DELETED, backend.state)
        assertEquals(0, backend.notificationReconcileCount)
        assertEquals(0, backend.cleanupCredentialCount)

        val restartedProcessExecutor = DurablePendingChannelDeletionExecutor(backend)
        restartedProcessExecutor.execute(OPERATION)

        assertEquals(1, backend.notificationReconcileCount)
        assertEquals(1, backend.cleanupCredentialCount)
    }

    private class FakeBackend(
        var state: PendingChannelDeletionTargetState,
        private val providerToken: String? = "cached-token",
        private val conflictDuringLocalDelete: Boolean = false,
        private val usesProvider: Boolean = true,
        private val providerError: ChannelSubscriptionException? = null,
        private val privateError: ChannelSubscriptionException? = null,
        private var cleanupFailuresRemaining: Int = 0,
        private var failCredentialCleanupAfterLocalDelete: Boolean = false,
        private var failNotificationCleanupAfterLocalDelete: Boolean = false,
    ) : PendingChannelDeletionBackend {
        var remoteUnsubscribeCount = 0
        var localDeleteCount = 0
        var restoreCount = 0
        var existingTokenReadCount = 0
        var cleanupCredentialCount = 0
        var notificationReconcileCount = 0
        var mutationLockCount = 0

        override suspend fun <T> withChannelMutationLock(
            operation: PendingLocalDeletionOperation,
            block: suspend () -> T,
        ): T {
            mutationLockCount += 1
            return block()
        }

        override suspend fun inspect(operation: PendingLocalDeletionOperation) = state

        override suspend fun reconcileDeletedChannelNotifications(operation: PendingLocalDeletionOperation) {
            notificationReconcileCount += 1
        }

        override suspend fun cleanupCredential(operation: PendingLocalDeletionOperation) {
            cleanupCredentialCount += 1
            if (cleanupFailuresRemaining > 0) {
                cleanupFailuresRemaining -= 1
                error("credential cleanup interrupted")
            }
        }

        override fun currentlyUsesProvider() = usesProvider

        override suspend fun existingProviderToken(): String? {
            existingTokenReadCount += 1
            return providerToken
        }

        override suspend fun syncProviderToken(token: String, operation: PendingLocalDeletionOperation) = Unit

        override suspend fun unsubscribeProvider(
            token: String,
            operation: PendingLocalDeletionOperation,
        ) {
            providerError?.let { throw it }
            remoteUnsubscribeCount += 1
        }

        override suspend fun unsubscribePrivate(operation: PendingLocalDeletionOperation) {
            privateError?.let { throw it }
            remoteUnsubscribeCount += 1
        }

        override suspend fun deleteLocal(operation: PendingLocalDeletionOperation) {
            localDeleteCount += 1
            if (conflictDuringLocalDelete) {
                state = PendingChannelDeletionTargetState.CONFLICTING_VERSION
                error("conditional delete rejected newer version")
            }
            state = PendingChannelDeletionTargetState.ALREADY_DELETED
            if (failNotificationCleanupAfterLocalDelete) {
                failNotificationCleanupAfterLocalDelete = false
                throw PendingLocalDeletionNotificationReconciliationException(
                    message = "post-commit notification cleanup failed",
                    cause = IllegalStateException("notification manager unavailable"),
                )
            }
            if (failCredentialCleanupAfterLocalDelete) {
                failCredentialCleanupAfterLocalDelete = false
                throw PendingLocalDeletionCredentialCleanupException(
                    message = "post-commit credential cleanup failed",
                    cause = IllegalStateException("keystore unavailable"),
                )
            }
        }

        override suspend fun currentCredential(operation: PendingLocalDeletionOperation) = "new-secret"

        override suspend fun restoreProvider(
            token: String,
            credential: String,
            operation: PendingLocalDeletionOperation,
        ) {
            restoreCount += 1
        }

        override suspend fun restorePrivate(
            credential: String,
            operation: PendingLocalDeletionOperation,
        ) {
            restoreCount += 1
        }
    }

    private companion object {
        val OPERATION = PendingLocalDeletionOperation.channel(
            id = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            expectedGatewayUrl = "https://gateway.example",
            expectedUpdatedAt = 42L,
            expectedUseProvider = true,
        )
    }
}
