package io.ethan.pushgo.data

import io.ethan.pushgo.data.db.TransportTransitionEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSwitchCoordinatorTest {
    @Test
    fun roomIntentInsertFailureClearsPendingProtectedToken() = runBlocking {
        val harness = Harness()
        harness.store.insertFailure = IllegalStateException("Room unavailable")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.coordinator.switchToFcm("candidate-token") }
        }

        assertNull(harness.secrets.pendingTransportToken("operation-1"))
        assertNull(harness.store.pending)
        assertEquals(0, harness.gateway.prepareCount)
    }

    @Test
    fun missingV2CapabilityBlocksSwitchWithoutLegacyFallback() = runBlocking {
        val harness = Harness()
        harness.gateway.capabilityEnabled = false

        assertThrows(TransportTransitionUnavailableException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }

        assertEquals(0, harness.gateway.prepareCount)
        assertEquals(0, harness.gateway.inverseRouteWrites)
        assertNull(harness.store.pending)
        assertTrue(harness.selection.useFcm)
    }

    @Test
    fun prepareFailureDoesNotOverwriteLocalSelection() = runBlocking {
        val harness = Harness()
        harness.gateway.prepareFailure = IllegalStateException("prepare rejected")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }

        assertTrue(harness.selection.useFcm)
        assertEquals(0, harness.selection.applyCount)
        assertEquals(TransportTransitionPhase.LOCAL_INTENT.name, harness.store.pending?.phase)
        assertEquals(1, harness.recoveryRequested)
        assertFalse(harness.gateway.commitCalled)
    }

    @Test
    fun pendingStatusRemainsReadableWhileGatewayPrepareIsInFlight() = runBlocking {
        val harness = Harness()
        val prepareEntered = CompletableDeferred<Unit>()
        val finishPrepare = CompletableDeferred<Unit>()
        harness.gateway.onPrepareSuspend = {
            prepareEntered.complete(Unit)
            finishPrepare.await()
        }
        val switching = launch { harness.coordinator.switchToPrivate() }
        prepareEntered.await()
        try {
            assertTrue(withTimeout(1_000) { harness.coordinator.hasPendingRecovery() })
        } finally {
            finishPrepare.complete(Unit)
            switching.join()
        }
    }

    @Test
    fun definitivePrepareRejectionDoesNotLeaveARecoveryIntent() = runBlocking {
        val harness = Harness()
        harness.gateway.prepareFailure = ChannelSubscriptionException(
            message = "rejected",
            category = GatewayErrorCategory.VALIDATION,
            httpStatus = 400,
        )

        assertThrows(ChannelSubscriptionException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }

        assertNull(harness.store.pending)
        assertEquals(0, harness.recoveryRequested)
        assertTrue(harness.selection.useFcm)
    }

    @Test
    fun lostPrepareResponseQueriesExactOperationBeforeCommit() = runBlocking {
        val harness = Harness()
        harness.gateway.prepareResponseFailure = IllegalStateException("prepare response lost")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }
        assertEquals(TransportTransitionPhase.LOCAL_INTENT.name, harness.store.pending?.phase)

        harness.gateway.prepareResponseFailure = null
        harness.coordinator.recoverPending()

        assertEquals(1, harness.gateway.prepareCount)
        assertEquals(3, harness.gateway.queryCount)
        assertFalse(harness.selection.useFcm)
        assertNull(harness.store.pending)
    }

    @Test
    fun missingOperationThenRevisionConflictCleansSupersededIntent() = runBlocking {
        val harness = Harness()
        harness.gateway.prepareFailure = IllegalStateException("prepare rejected before persist")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }
        harness.gateway.prepareFailure = TransportRevisionConflictException("newer winner")

        assertThrows(TransportTransitionSupersededException::class.java) {
            runBlocking { harness.coordinator.recoverPending() }
        }

        assertTrue(harness.selection.useFcm)
        assertNull(harness.store.pending)
        assertEquals(0, harness.gateway.inverseRouteWrites)
    }

    @Test
    fun lostCommitResponseQueriesCommittedStateAndFinalizesForward() = runBlocking {
        val harness = Harness()
        harness.gateway.commitFailure = IllegalStateException("response lost")
        harness.gateway.querySnapshot = committedPrivate(revision = 8)

        harness.coordinator.switchToPrivate()

        assertFalse(harness.selection.useFcm)
        assertEquals(2, harness.gateway.queryCount)
        assertEquals(1, harness.gateway.commitCount)
        assertEquals(listOf("https://gateway.invalid"), harness.gateway.commitGatewayUrls)
        assertEquals(listOf("https://gateway.invalid", "https://gateway.invalid"), harness.gateway.queryGatewayUrls)
        assertNull(harness.store.pending)
    }

    @Test
    fun queuedRouteWriterRechecksSelectionAfterTransportSwitchCompletes() = runBlocking {
        val harness = Harness()
        val localApplyEntered = CompletableDeferred<Unit>()
        val finishLocalApply = CompletableDeferred<Unit>()
        harness.selection.onAppliedSuspend = {
            localApplyEntered.complete(Unit)
            finishLocalApply.await()
        }
        val switching = launch { harness.coordinator.switchToPrivate() }
        localApplyEntered.await()

        var staleProviderRouteWritten = false
        var staleProviderRouteRejected = false
        val writerGate = CoordinatorTransportRouteWriterGate(
            coordinator = { harness.coordinator },
            currentChannelType = { if (harness.selection.useFcm) "fcm" else "private" },
        )
        val oldWriter = launch {
            try {
                writerGate.run("fcm") { staleProviderRouteWritten = true }
            } catch (_: TransportTransitionUnavailableException) {
                staleProviderRouteRejected = true
            }
        }
        yield()
        assertFalse(oldWriter.isCompleted)
        finishLocalApply.complete(Unit)
        switching.join()
        oldWriter.join()

        assertFalse(staleProviderRouteWritten)
        assertTrue(staleProviderRouteRejected)
        assertNull(harness.store.pending)
    }

    @Test
    fun queuedPrivateRouteWriterCannotReverseCommittedProviderSelection() = runBlocking {
        val harness = Harness()
        harness.selection.useFcm = false
        harness.selection.token = null
        harness.gateway.committedChannelType = "fcm"
        val localApplyEntered = CompletableDeferred<Unit>()
        val finishLocalApply = CompletableDeferred<Unit>()
        harness.selection.onAppliedSuspend = {
            localApplyEntered.complete(Unit)
            finishLocalApply.await()
        }
        val switching = launch { harness.coordinator.switchToFcm("new-token") }
        localApplyEntered.await()

        var stalePrivateRouteWritten = false
        var stalePrivateRouteRejected = false
        val writerGate = CoordinatorTransportRouteWriterGate(
            coordinator = { harness.coordinator },
            currentChannelType = { if (harness.selection.useFcm) "fcm" else "private" },
        )
        val oldWriter = launch {
            try {
                writerGate.run("private") { stalePrivateRouteWritten = true }
            } catch (_: TransportTransitionUnavailableException) {
                stalePrivateRouteRejected = true
            }
        }
        yield()
        assertFalse(oldWriter.isCompleted)
        finishLocalApply.complete(Unit)
        switching.join()
        oldWriter.join()

        assertFalse(stalePrivateRouteWritten)
        assertTrue(stalePrivateRouteRejected)
        assertNull(harness.store.pending)
    }

    @Test
    fun revisionConflictKeepsWinningRouteAndNeverCompensates() = runBlocking {
        val harness = Harness()
        harness.gateway.commitFailure = TransportRevisionConflictException("winner revision=8")

        assertThrows(TransportRevisionConflictException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }

        assertTrue(harness.selection.useFcm)
        assertEquals(0, harness.selection.applyCount)
        assertEquals(1, harness.gateway.abortCount)
        assertEquals(0, harness.gateway.inverseRouteWrites)
        assertNull(harness.store.pending)
    }

    @Test
    fun localFinalizeFailureRemainsDurableAndStartupRecoveryCompletes() = runBlocking {
        val harness = Harness()
        harness.selection.failNext = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }
        assertEquals(
            TransportTransitionPhase.REMOTE_COMMITTED.name,
            harness.store.pending?.phase,
        )
        assertEquals(1, harness.recoveryRequested)
        assertTrue(harness.selection.useFcm)

        harness.gateway.querySnapshot = committedPrivate(revision = 8)
        harness.coordinator.recoverPending()

        assertFalse(harness.selection.useFcm)
        assertEquals(2, harness.selection.applyCount)
        assertNull(harness.store.pending)
    }

    @Test
    fun failedLocalFinalizeDoesNotApplyAfterNewerTransitionSupersedesIt() = runBlocking {
        val harness = Harness()
        harness.selection.failNext = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }
        harness.gateway.querySnapshot = TransportTransitionSnapshot(
            state = TransportTransitionRemoteState.COMMITTED,
            routeRevision = 9,
            channelType = "fcm",
            committedRevision = 8,
            candidateChannelType = "private",
            currentProviderTokenSha256 = transportTokenSha256("old-token"),
        )

        assertThrows(TransportTransitionSupersededException::class.java) {
            runBlocking { harness.coordinator.recoverPending() }
        }

        assertTrue(harness.selection.useFcm)
        assertEquals(2, harness.selection.applyCount)
        assertNull(harness.store.pending)
    }

    @Test
    fun partialLocalApplyIsReconciledToVerifiedNewerProviderRoute() = runBlocking {
        val harness = Harness()
        harness.selection.failAfterMutationNext = true
        assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }
        assertFalse(harness.selection.useFcm)
        assertNull(harness.selection.token)
        assertEquals(TransportTransitionPhase.REMOTE_COMMITTED.name, harness.store.pending?.phase)

        harness.gateway.querySnapshot = newerProviderRoute(transportTokenSha256("old-token"))
        assertThrows(TransportTransitionSupersededException::class.java) {
            runBlocking { harness.coordinator.recoverPending() }
        }

        assertTrue(harness.selection.useFcm)
        assertEquals("old-token", harness.selection.token)
        assertNull(harness.store.pending)
    }

    @Test
    fun unknownNewerProviderTokenKeepsRecoveryPending() = runBlocking {
        val harness = Harness()
        harness.selection.failAfterMutationNext = true
        assertThrows(IllegalStateException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }
        harness.gateway.querySnapshot = newerProviderRoute(transportTokenSha256("different-device-token"))

        assertThrows(TransportTransitionUnavailableException::class.java) {
            runBlocking { harness.coordinator.recoverPending() }
        }

        assertEquals(TransportTransitionPhase.REMOTE_COMMITTED.name, harness.store.pending?.phase)
        assertFalse(harness.selection.useFcm)
        assertNull(harness.selection.token)
    }

    @Test
    fun newerRouteDuringSuccessfulLocalApplyIsRecheckedBeforeCleanup() = runBlocking {
        val harness = Harness()
        harness.selection.onApplied = {
            harness.gateway.querySnapshot = newerProviderRoute(transportTokenSha256("old-token"))
        }

        assertThrows(TransportTransitionSupersededException::class.java) {
            runBlocking { harness.coordinator.switchToPrivate() }
        }

        assertTrue(harness.selection.useFcm)
        assertEquals("old-token", harness.selection.token)
        assertNull(harness.store.pending)
    }

    private class Harness {
        val store = FakeStore()
        val secrets = FakeSecretStore()
        val gateway = FakeGateway()
        val selection = FakeSelection()
        var recoveryRequested = 0
        val coordinator = TransportSwitchCoordinator(
            store = store,
            secretStore = secrets,
            gateway = gateway,
            selectionApplier = selection,
            nowMillis = { 1_000L },
            newOperationId = { "operation-1" },
            requestRecovery = { recoveryRequested += 1 },
        )
    }

    private class FakeStore : TransportTransitionStore {
        var pending: TransportTransitionEntity? = null
        var insertFailure: Throwable? = null
        override suspend fun getPending(): TransportTransitionEntity? = pending
        override suspend fun insert(entity: TransportTransitionEntity) {
            insertFailure?.let { throw it }
            check(pending == null)
            pending = entity
        }
        override suspend fun update(entity: TransportTransitionEntity) {
            check(pending?.operationId == entity.operationId)
            pending = entity
        }
        override suspend fun delete(operationId: String) {
            if (pending?.operationId == operationId) pending = null
        }
    }

    private class FakeGateway : TransportTransitionGateway {
        var prepareFailure: Throwable? = null
        var onPrepareSuspend: suspend () -> Unit = {}
        var prepareResponseFailure: Throwable? = null
        var commitFailure: Throwable? = null
        var querySnapshot = TransportTransitionSnapshot(
            state = TransportTransitionRemoteState.PREPARED,
            routeRevision = 7,
            channelType = "fcm",
        )
        var commitCount = 0
        var queryCount = 0
        var abortCount = 0
        var inverseRouteWrites = 0
        var prepareCount = 0
        val commitGatewayUrls = mutableListOf<String>()
        val queryGatewayUrls = mutableListOf<String>()
        var capabilityEnabled = true
        var committedChannelType = "private"
        var hasOperation = false
        val commitCalled: Boolean get() = commitCount > 0

        override suspend fun loadContext() = TransportTransitionContext(
            gatewayUrl = "https://gateway.invalid",
            deviceKey = "device-1",
            routeRevision = 7,
            routeTransitionV2 = capabilityEnabled,
        )

        override suspend fun prepare(
            operationId: String,
            context: TransportTransitionContext,
            channelType: String,
            providerToken: String?,
        ): PreparedTransportTransition {
            prepareCount += 1
            onPrepareSuspend()
            prepareFailure?.let { throw it }
            hasOperation = true
            querySnapshot = TransportTransitionSnapshot(
                state = TransportTransitionRemoteState.PREPARED,
                routeRevision = context.routeRevision,
                channelType = "fcm",
                transitionId = "transition-1",
            )
            prepareResponseFailure?.let { throw it }
            return PreparedTransportTransition("transition-1", context.routeRevision)
        }

        override suspend fun commit(
            operationId: String,
            transitionId: String,
            gatewayUrl: String,
        ): CommittedTransportTransition {
            commitCount += 1
            commitGatewayUrls += gatewayUrl
            commitFailure?.let { failure ->
                if (failure is TransportRevisionConflictException) throw failure
                querySnapshot = committedSelection(revision = 8)
                throw failure
            }
            querySnapshot = committedSelection(revision = 8)
            return CommittedTransportTransition(routeRevision = 8, channelType = committedChannelType)
        }

        private fun committedSelection(revision: Long) = TransportTransitionSnapshot(
            state = TransportTransitionRemoteState.COMMITTED,
            routeRevision = revision,
            channelType = committedChannelType,
            committedRevision = revision,
            candidateChannelType = committedChannelType,
        )

        override suspend fun abort(
            operationId: String,
            transitionId: String,
            gatewayUrl: String,
        ): TransportTransitionSnapshot {
            abortCount += 1
            hasOperation = true
            return TransportTransitionSnapshot(TransportTransitionRemoteState.ABORTED, 8, "fcm")
        }

        override suspend fun query(
            operationId: String,
            transitionId: String?,
            deviceKey: String,
            gatewayUrl: String,
        ): TransportTransitionSnapshot {
            queryCount += 1
            queryGatewayUrls += gatewayUrl
            if (!hasOperation) {
                throw TransportTransitionNotFoundException("operation not found")
            }
            return querySnapshot
        }
    }

    private class FakeSelection : TransportSelectionApplier {
        var useFcm = true
        var token: String? = "old-token"
        var failNext = false
        var failAfterMutationNext = false
        var onApplied: (() -> Unit)? = null
        var onAppliedSuspend: (suspend () -> Unit)? = null
        var applyCount = 0
        override suspend fun apply(useFcm: Boolean, providerToken: String?) {
            applyCount += 1
            if (failNext) {
                failNext = false
                throw IllegalStateException("local finalize failed")
            }
            this.useFcm = useFcm
            token = providerToken
            if (failAfterMutationNext) {
                failAfterMutationNext = false
                throw IllegalStateException("local finalize failed after mutation")
            }
            onApplied?.invoke()
            onAppliedSuspend?.invoke()
        }

        override suspend fun reconcileActiveRoute(
            channelType: String,
            providerTokenSha256: String?,
        ): Boolean {
            if (channelType == "private" && providerTokenSha256 == null) {
                apply(useFcm = false, providerToken = null)
                return true
            }
            val systemToken = "old-token"
            if (
                channelType == "fcm" &&
                providerTokenSha256 == transportTokenSha256(systemToken)
            ) {
                apply(useFcm = true, providerToken = systemToken)
                return true
            }
            return false
        }
    }

    private class FakeSecretStore : SecureSecretStore {
        private val values = mutableMapOf<String, String>()
        override fun gatewayToken(): String? = values["gateway"]
        override fun setGatewayToken(token: String?) = set("gateway", token)
        override fun gatewayAckToken(gatewayUrl: String): String? = values["ack:$gatewayUrl"]
        override fun setGatewayAckToken(gatewayUrl: String, token: String?) = set("ack:$gatewayUrl", token)
        override fun fcmToken(): String? = values["fcm"]
        override fun setFcmToken(token: String?) = set("fcm", token)
        override fun pendingTransportToken(operationId: String): String? = values["pending:$operationId"]
        override fun setPendingTransportToken(operationId: String, token: String?) = set("pending:$operationId", token)
        override fun deviceKey(): String? = values["device"]
        override fun setDeviceKey(deviceKey: String?) = set("device", deviceKey)
        override fun notificationKeyBytes(): ByteArray? = null
        override fun setNotificationKeyBytes(value: ByteArray?) = Unit
        override fun channelPassword(gatewayUrl: String, channelId: String): String? = null
        override fun setChannelPassword(gatewayUrl: String, channelId: String, password: String?) = Unit
        override fun removeChannelPassword(gatewayUrl: String, channelId: String) = Unit
        override fun clearAll() = values.clear()
        private fun set(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private companion object {
        fun newerProviderRoute(tokenFingerprint: String) = TransportTransitionSnapshot(
            state = TransportTransitionRemoteState.COMMITTED,
            routeRevision = 9,
            channelType = "fcm",
            committedRevision = 8,
            candidateChannelType = "private",
            currentProviderTokenSha256 = tokenFingerprint,
        )

        fun committedPrivate(revision: Long) = TransportTransitionSnapshot(
            state = TransportTransitionRemoteState.COMMITTED,
            routeRevision = revision,
            channelType = "private",
            committedRevision = revision,
            candidateChannelType = "private",
        )
    }
}
