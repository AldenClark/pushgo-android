package io.ethan.pushgo.data

import io.ethan.pushgo.data.db.TransportTransitionDao
import io.ethan.pushgo.data.db.TransportTransitionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

enum class TransportTransitionPhase { LOCAL_INTENT, REMOTE_PREPARED, REMOTE_COMMITTED, LOCAL_APPLIED }
enum class TransportTransitionRemoteState { PREPARED, COMMITTED, ABORTED, EXPIRED }

data class TransportTransitionContext(
    val gatewayUrl: String,
    val deviceKey: String,
    val routeRevision: Long,
    val routeTransitionV2: Boolean,
)

data class PreparedTransportTransition(val transitionId: String, val baseRevision: Long)
data class CommittedTransportTransition(val routeRevision: Long, val channelType: String)
data class TransportTransitionSnapshot(
    val state: TransportTransitionRemoteState,
    val routeRevision: Long?,
    val channelType: String?,
    val transitionId: String? = null,
    val committedRevision: Long? = null,
    val candidateChannelType: String? = null,
    val currentProviderTokenSha256: String? = null,
)

interface TransportTransitionGateway {
    suspend fun loadContext(): TransportTransitionContext
    suspend fun prepare(
        operationId: String,
        context: TransportTransitionContext,
        channelType: String,
        providerToken: String?,
    ): PreparedTransportTransition
    suspend fun commit(
        operationId: String,
        transitionId: String,
        gatewayUrl: String,
    ): CommittedTransportTransition
    suspend fun abort(
        operationId: String,
        transitionId: String,
        gatewayUrl: String,
    ): TransportTransitionSnapshot
    suspend fun query(
        operationId: String,
        transitionId: String?,
        deviceKey: String,
        gatewayUrl: String,
    ): TransportTransitionSnapshot
}

class TransportRevisionConflictException(message: String) : Exception(message)
class TransportTransitionNotFoundException(message: String) : Exception(message)
class TransportTransitionSupersededException(message: String) : Exception(message)
class TransportTransitionUnavailableException(message: String) : Exception(message)

interface TransportSelectionApplier {
    suspend fun apply(useFcm: Boolean, providerToken: String?)
    /** Returns false when the current remote route cannot be proven to match this device. */
    suspend fun reconcileActiveRoute(channelType: String, providerTokenSha256: String?): Boolean
}

interface TransportSwitcher {
    val pendingRecovery: StateFlow<Boolean>? get() = null
    suspend fun switchToFcm(providerToken: String)
    suspend fun switchToPrivate()
    suspend fun recoverPending()
    suspend fun hasPendingRecovery(): Boolean = false
}

/** Serializes ordinary route writes with a durable transport transition. */
interface TransportRouteWriterGate {
    suspend fun <T> run(expectedChannelType: String?, block: suspend () -> T): T
}

object UnlockedTransportRouteWriterGate : TransportRouteWriterGate {
    override suspend fun <T> run(expectedChannelType: String?, block: suspend () -> T): T = block()
}

class CoordinatorTransportRouteWriterGate(
    private val coordinator: () -> TransportSwitchCoordinator,
    private val currentChannelType: suspend () -> String,
) : TransportRouteWriterGate {
    override suspend fun <T> run(expectedChannelType: String?, block: suspend () -> T): T =
        coordinator().withIdleRouteWriter {
            if (expectedChannelType != null && currentChannelType() != expectedChannelType) {
                throw TransportTransitionUnavailableException(
                    "Transport mode changed before the route write"
                )
            }
            block()
        }
}

interface TransportTransitionStore {
    suspend fun getPending(): TransportTransitionEntity?
    suspend fun insert(entity: TransportTransitionEntity)
    suspend fun update(entity: TransportTransitionEntity)
    suspend fun delete(operationId: String)
}

class RoomTransportTransitionStore(private val dao: TransportTransitionDao) : TransportTransitionStore {
    override suspend fun getPending(): TransportTransitionEntity? = dao.getPending()
    override suspend fun insert(entity: TransportTransitionEntity) = dao.insert(entity)
    override suspend fun update(entity: TransportTransitionEntity) = dao.update(entity)
    override suspend fun delete(operationId: String) = dao.delete(operationId)
}

/** The only legal owner of remote and local transport-switch ordering. */
class TransportSwitchCoordinator(
    private val store: TransportTransitionStore,
    private val secretStore: SecureSecretStore,
    private val gateway: TransportTransitionGateway,
    private val selectionApplier: TransportSelectionApplier,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
    private val requestRecovery: () -> Unit = {},
) : TransportSwitcher {
    private val mutex = Mutex()
    private val mutablePendingRecovery = MutableStateFlow(false)
    override val pendingRecovery: StateFlow<Boolean> = mutablePendingRecovery

    override suspend fun switchToFcm(providerToken: String) {
        val token = providerToken.trim()
        require(token.isNotEmpty()) { "FCM provider token is required" }
        switch(CHANNEL_FCM, token)
    }

    override suspend fun switchToPrivate() = switch(CHANNEL_PRIVATE, null)

    override suspend fun recoverPending() {
        mutex.withLock {
            val pending = store.getPending()
            mutablePendingRecovery.value = pending != null
            pending?.let { recoverLocked(it) }
        }
    }

    // The UI must be able to report a durable pending intent while the coordinator
    // holds its mutex across a slow Gateway request.
    override suspend fun hasPendingRecovery(): Boolean = store.getPending() != null

    suspend fun <T> withIdleRouteWriter(block: suspend () -> T): T = mutex.withLock {
        check(store.getPending() == null) { "A transport transition is still pending recovery" }
        block()
    }

    private suspend fun switch(channelType: String, providerToken: String?) {
        try {
            mutex.withLock { switchLocked(channelType, providerToken) }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                if (runCatching { store.getPending() }.getOrNull() != null) {
                    runCatching { requestRecovery() }
                }
            }
            throw error
        }
    }

    private suspend fun switchLocked(channelType: String, providerToken: String?) {
        store.getPending()?.let { recoverLocked(it) }
        check(store.getPending() == null) { "A transport transition is still pending recovery" }
        val context = gateway.loadContext()
        if (!context.routeTransitionV2) {
            throw TransportTransitionUnavailableException(
                "Gateway must support route_transition_v2 before transport can be changed"
            )
        }
        val operationId = newOperationId()
        val now = nowMillis()
        var pending = TransportTransitionEntity(
            operationId = operationId,
            gatewayUrl = context.gatewayUrl,
            deviceKey = context.deviceKey,
            targetChannelType = channelType,
            transitionId = null,
            baseRevision = context.routeRevision,
            committedRevision = null,
            phase = TransportTransitionPhase.LOCAL_INTENT.name,
            candidateTokenFingerprint = providerToken?.let(::transportTokenSha256),
            createdAt = now,
            updatedAt = now,
            lastError = null,
        )
        try {
            secretStore.setPendingTransportToken(operationId, providerToken)
            store.insert(pending)
            mutablePendingRecovery.value = true
        } catch (error: Throwable) {
            runCatching { secretStore.setPendingTransportToken(operationId, null) }
            throw error
        }
        val prepared = try {
            gateway.prepare(operationId, context, channelType, providerToken)
        } catch (conflict: TransportRevisionConflictException) {
            cleanupLocal(pending)
            throw conflict
        } catch (rejection: ChannelSubscriptionException) {
            if ((rejection.httpStatus ?: 0) in 400..499 && !rejection.retryable) {
                cleanupLocal(pending)
            }
            throw rejection
        }
        try {
            check(prepared.baseRevision == context.routeRevision) {
                "Gateway prepared transport against an unexpected route revision"
            }
            pending = pending.copy(
                transitionId = prepared.transitionId,
                baseRevision = prepared.baseRevision,
                phase = TransportTransitionPhase.REMOTE_PREPARED.name,
                updatedAt = nowMillis(),
            )
            store.update(pending)
            commitAndFinalizeLocked(pending)
        } catch (error: Throwable) {
            throw error
        }
    }

    private suspend fun recoverLocked(pending: TransportTransitionEntity) {
        when (phaseOf(pending)) {
            TransportTransitionPhase.LOCAL_INTENT -> resumeLocalIntentLocked(pending)
            TransportTransitionPhase.REMOTE_PREPARED -> reconcilePreparedLocked(pending)
            TransportTransitionPhase.REMOTE_COMMITTED -> reconcileCommittedLocked(pending)
            TransportTransitionPhase.LOCAL_APPLIED -> finishAppliedLocked(pending)
        }
    }

    private suspend fun resumeLocalIntentLocked(pending: TransportTransitionEntity) {
        val existing = try {
            gateway.query(
                operationId = pending.operationId,
                transitionId = null,
                deviceKey = pending.deviceKey,
                gatewayUrl = pending.gatewayUrl,
            )
        } catch (_: TransportTransitionNotFoundException) {
            null
        }
        if (existing != null) {
            when (existing.state) {
                TransportTransitionRemoteState.PREPARED -> {
                    val transitionId = existing.transitionId
                        ?: error("Prepared transition query is missing transition id")
                    val resumed = pending.copy(
                        transitionId = transitionId,
                        phase = TransportTransitionPhase.REMOTE_PREPARED.name,
                        updatedAt = nowMillis(),
                        lastError = null,
                    )
                    store.update(resumed)
                    commitAndFinalizeLocked(resumed)
                }
                TransportTransitionRemoteState.COMMITTED ->
                    markCommittedAndFinalize(pending, existing)
                TransportTransitionRemoteState.ABORTED,
                TransportTransitionRemoteState.EXPIRED,
                -> cleanupLocal(pending)
            }
            return
        }
        val context = gateway.loadContext()
        if (!context.routeTransitionV2 || context.gatewayUrl != pending.gatewayUrl ||
            context.deviceKey != pending.deviceKey
        ) {
            throw TransportTransitionUnavailableException(
                "Pending transport transition cannot be resumed against this Gateway identity"
            )
        }
        val prepared = try {
            gateway.prepare(
                pending.operationId,
                context.copy(routeRevision = pending.baseRevision),
                pending.targetChannelType,
                candidateToken(pending),
            )
        } catch (conflict: TransportRevisionConflictException) {
            cleanupLocal(pending)
            throw TransportTransitionSupersededException(
                "Pending transport intent was superseded before prepare"
            )
        } catch (rejection: ChannelSubscriptionException) {
            if ((rejection.httpStatus ?: 0) in 400..499 && !rejection.retryable) {
                cleanupLocal(pending)
            }
            throw rejection
        }
        check(prepared.baseRevision == pending.baseRevision) {
            "Gateway resumed transport against an unexpected route revision"
        }
        val updated = pending.copy(
            transitionId = prepared.transitionId,
            baseRevision = prepared.baseRevision,
            phase = TransportTransitionPhase.REMOTE_PREPARED.name,
            updatedAt = nowMillis(),
            lastError = null,
        )
        store.update(updated)
        commitAndFinalizeLocked(updated)
    }

    private suspend fun reconcilePreparedLocked(pending: TransportTransitionEntity) {
        val snapshot = query(pending)
        when (snapshot.state) {
            TransportTransitionRemoteState.COMMITTED -> markCommittedAndFinalize(pending, snapshot)
            TransportTransitionRemoteState.PREPARED -> commitAndFinalizeLocked(pending)
            TransportTransitionRemoteState.ABORTED,
            TransportTransitionRemoteState.EXPIRED,
            -> cleanupLocal(pending)
        }
    }

    private suspend fun reconcileCommittedLocked(pending: TransportTransitionEntity) {
        val snapshot = query(pending)
        when (snapshot.state) {
            TransportTransitionRemoteState.COMMITTED -> markCommittedAndFinalize(pending, snapshot)
            TransportTransitionRemoteState.PREPARED -> error("Gateway regressed committed transition")
            TransportTransitionRemoteState.ABORTED,
            TransportTransitionRemoteState.EXPIRED,
            -> error("Gateway lost committed transition; local state was not overwritten")
        }
    }

    private suspend fun commitAndFinalizeLocked(pending: TransportTransitionEntity) {
        val transitionId = checkNotNull(pending.transitionId)
        val committed = try {
            gateway.commit(pending.operationId, transitionId, pending.gatewayUrl)
        } catch (conflict: TransportRevisionConflictException) {
            runCatching { gateway.abort(pending.operationId, transitionId, pending.gatewayUrl) }
            cleanupLocal(pending)
            throw conflict
        } catch (unknown: Throwable) {
            // The response may have been lost after commit. Query only: never retry or compensate.
            val snapshot = try {
                query(pending)
            } catch (_: Throwable) {
                recordFailure(pending, unknown)
                throw unknown
            }
            if (snapshot.state != TransportTransitionRemoteState.COMMITTED) {
                recordFailure(pending, unknown)
                throw unknown
            }
            markCommittedAndFinalize(pending, snapshot)
            return
        }
        val committedPending = pending.copy(
            committedRevision = committed.routeRevision,
            phase = TransportTransitionPhase.REMOTE_COMMITTED.name,
            updatedAt = nowMillis(),
            lastError = null,
        )
        requireCommittedMatches(
            pending = pending,
            routeRevision = committed.routeRevision,
            channelType = committed.channelType,
        )
        store.update(committedPending)
        markCommittedAndFinalize(committedPending, query(committedPending))
    }

    private suspend fun markCommittedAndFinalize(
        pending: TransportTransitionEntity,
        snapshot: TransportTransitionSnapshot,
    ) {
        check(snapshot.state == TransportTransitionRemoteState.COMMITTED) {
            "Gateway did not retain the committed transport transition"
        }
        val committedRevision = snapshot.committedRevision
            ?: error("Committed transition query is missing committed revision")
        val candidateChannelType = snapshot.candidateChannelType
            ?: error("Committed transition query is missing candidate channel type")
        requireCommittedMatches(pending, committedRevision, candidateChannelType)
        if (
            snapshot.routeRevision != committedRevision ||
            snapshot.channelType != candidateChannelType
        ) {
            reconcileSupersededLocked(pending, snapshot)
        }
        val committed = pending.copy(
            committedRevision = committedRevision,
            phase = TransportTransitionPhase.REMOTE_COMMITTED.name,
            updatedAt = nowMillis(),
            lastError = null,
        )
        store.update(committed)
        finalizeLocalLocked(committed)
    }

    private suspend fun finalizeLocalLocked(pending: TransportTransitionEntity) {
        try {
            selectionApplier.apply(
                useFcm = pending.targetChannelType == CHANNEL_FCM,
                providerToken = candidateToken(pending),
            )
        } catch (error: Throwable) {
            recordFailure(pending, error)
            throw error
        }
        val applied = pending.copy(
            phase = TransportTransitionPhase.LOCAL_APPLIED.name,
            updatedAt = nowMillis(),
            lastError = null,
        )
        store.update(applied)
        finishAppliedLocked(applied)
    }

    private suspend fun finishAppliedLocked(pending: TransportTransitionEntity) {
        val snapshot = query(pending)
        check(snapshot.state == TransportTransitionRemoteState.COMMITTED) {
            "Gateway did not retain the committed transport transition"
        }
        val committedRevision = pending.committedRevision
            ?: error("Locally applied transition has no committed revision")
        if (
            snapshot.routeRevision != committedRevision ||
            snapshot.channelType != pending.targetChannelType
        ) {
            reconcileSupersededLocked(pending, snapshot)
        }
        cleanupLocal(pending)
    }

    private suspend fun reconcileSupersededLocked(
        pending: TransportTransitionEntity,
        snapshot: TransportTransitionSnapshot,
    ): Nothing {
        val activeRevision = snapshot.routeRevision
            ?: throw TransportTransitionUnavailableException(
                "Superseding transport route has no active revision"
            )
        val activeChannel = snapshot.channelType
            ?: throw TransportTransitionUnavailableException(
                "Superseding transport route has no active channel"
            )
        if (!selectionApplier.reconcileActiveRoute(
                activeChannel,
                snapshot.currentProviderTokenSha256,
            )
        ) {
            throw TransportTransitionUnavailableException(
                "Superseding transport route cannot be matched to this device"
            )
        }
        val checked = query(pending)
        if (
            checked.state != TransportTransitionRemoteState.COMMITTED ||
            checked.routeRevision != activeRevision ||
            checked.channelType != activeChannel ||
            checked.currentProviderTokenSha256 != snapshot.currentProviderTokenSha256
        ) {
            throw TransportTransitionUnavailableException(
                "Superseding transport route changed during local reconciliation"
            )
        }
        cleanupLocal(pending)
        throw TransportTransitionSupersededException(
            "Transport transition was superseded by a newer active route"
        )
    }

    private suspend fun query(pending: TransportTransitionEntity): TransportTransitionSnapshot =
        gateway.query(
            pending.operationId,
            pending.transitionId,
            pending.deviceKey,
            pending.gatewayUrl,
        )

    private fun candidateToken(pending: TransportTransitionEntity): String? {
        if (pending.targetChannelType != CHANNEL_FCM) return null
        return secretStore.pendingTransportToken(pending.operationId)
            ?: error("Pending FCM token is unavailable")
    }

    private suspend fun recordFailure(pending: TransportTransitionEntity, error: Throwable) {
        store.update(
            pending.copy(
                updatedAt = nowMillis(),
                lastError = error::class.java.simpleName.take(80),
            )
        )
    }

    private suspend fun cleanupLocal(pending: TransportTransitionEntity) {
        store.delete(pending.operationId)
        mutablePendingRecovery.value = false
        // A failure here leaves an encrypted orphan, not an unrecoverable pending row.
        secretStore.setPendingTransportToken(pending.operationId, null)
    }

    private fun phaseOf(pending: TransportTransitionEntity): TransportTransitionPhase =
        runCatching { TransportTransitionPhase.valueOf(pending.phase) }
            .getOrElse { error("Unknown transport transition phase") }

    private fun requireCommittedMatches(
        pending: TransportTransitionEntity,
        routeRevision: Long,
        channelType: String,
    ) {
        check(channelType == pending.targetChannelType) {
            "Committed transition channel does not match local intent"
        }
        check(routeRevision > pending.baseRevision) {
            "Committed transition did not advance route revision"
        }
    }

    private companion object {
        const val CHANNEL_FCM = "fcm"
        const val CHANNEL_PRIVATE = "private"
    }
}

internal fun transportTokenSha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
