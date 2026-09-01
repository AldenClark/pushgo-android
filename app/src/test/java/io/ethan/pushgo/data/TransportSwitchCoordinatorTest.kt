package io.ethan.pushgo.data

import io.ethan.pushgo.data.db.TransportTransitionEntity
import kotlinx.coroutines.runBlocking
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
        assertFalse(harness.gateway.commitCalled)
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
        assertEquals(1, harness.gateway.queryCount)
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
        assertEquals(1, harness.gateway.queryCount)
        assertEquals(1, harness.gateway.commitCount)
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
        )

        assertThrows(TransportTransitionSupersededException::class.java) {
            runBlocking { harness.coordinator.recoverPending() }
        }

        assertTrue(harness.selection.useFcm)
        assertEquals(1, harness.selection.applyCount)
        assertNull(harness.store.pending)
    }

    private class Harness {
        val store = FakeStore()
        val secrets = FakeSecretStore()
        val gateway = FakeGateway()
        val selection = FakeSelection()
        val coordinator = TransportSwitchCoordinator(
            store = store,
            secretStore = secrets,
            gateway = gateway,
            selectionApplier = selection,
            nowMillis = { 1_000L },
            newOperationId = { "operation-1" },
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
        var capabilityEnabled = true
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
        ): CommittedTransportTransition {
            commitCount += 1
            commitFailure?.let { failure ->
                if (failure is TransportRevisionConflictException) throw failure
                querySnapshot = committedPrivate(revision = 8)
                throw failure
            }
            querySnapshot = committedPrivate(revision = 8)
            return CommittedTransportTransition(routeRevision = 8, channelType = "private")
        }

        override suspend fun abort(
            operationId: String,
            transitionId: String,
        ): TransportTransitionSnapshot {
            abortCount += 1
            hasOperation = true
            return TransportTransitionSnapshot(TransportTransitionRemoteState.ABORTED, 8, "fcm")
        }

        override suspend fun query(
            operationId: String,
            transitionId: String?,
            deviceKey: String,
        ): TransportTransitionSnapshot {
            queryCount += 1
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
        var applyCount = 0
        override suspend fun apply(useFcm: Boolean, providerToken: String?) {
            applyCount += 1
            if (failNext) {
                failNext = false
                throw IllegalStateException("local finalize failed")
            }
            this.useFcm = useFcm
            token = providerToken
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
        fun committedPrivate(revision: Long) = TransportTransitionSnapshot(
            state = TransportTransitionRemoteState.COMMITTED,
            routeRevision = revision,
            channelType = "private",
            committedRevision = revision,
            candidateChannelType = "private",
        )
    }
}
