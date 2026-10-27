package io.ethan.pushgo.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DeviceRouteSnapshot(
    val gatewayUrl: String,
    val deviceKey: String,
    val channelType: String,
    val routeRevision: Long,
    val providerTokenPresent: Boolean,
    val issuedNewKey: Boolean = false,
)

interface TransportRouteGateway {
    suspend fun loadRoute(): DeviceRouteSnapshot?
    suspend fun switchRoute(channelType: String, providerToken: String?): DeviceRouteSnapshot
}

class TransportRouteUnavailableException(message: String) : Exception(message)

interface TransportSelectionApplier {
    suspend fun apply(useFcm: Boolean, providerToken: String?)
    suspend fun currentUseFcm(): Boolean
    suspend fun providerToken(): String?
}

interface TransportSwitcher {
    suspend fun switchToFcm(providerToken: String)
    suspend fun switchToPrivate()
    suspend fun synchronize() = Unit
}

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
        coordinator().withRouteWriter(expectedChannelType) {
            if (expectedChannelType != null && currentChannelType() != expectedChannelType) {
                throw TransportRouteUnavailableException("Transport mode changed before the route write")
            }
            block()
        }
}

/** Remote route is authoritative; local values are a cache, not a recoverable operation log. */
class TransportSwitchCoordinator(
    private val gateway: TransportRouteGateway,
    private val selectionApplier: TransportSelectionApplier,
    private val currentGatewayUrl: suspend () -> String,
    private val currentDeviceKey: suspend () -> String?,
    private val writerObserver: ((Int, String, String) -> Unit)? = null,
) : TransportSwitcher {
    private val writerMutex = Mutex()
    // Registration/sync replies may arrive inside a serialized writer. Applying a
    // snapshot does no network work and uses its own mutex to avoid recursive locking.
    private val applyMutex = Mutex()
    private var lastApplied: DeviceRouteSnapshot? = null
    @Volatile private var observerFailed = false

    override suspend fun switchToFcm(providerToken: String) {
        val token = providerToken.trim()
        require(token.isNotEmpty()) { "FCM provider token is required" }
        switch("fcm", token)
    }

    override suspend fun switchToPrivate() = switch("private", null)

    override suspend fun synchronize() = withObservedWriter("synchronize") { synchronizeLocked() }

    suspend fun <T> withRouteWriter(block: suspend () -> T): T = withObservedWriter("route-writer", block)

    internal suspend fun <T> withRouteWriter(expectedChannelType: String?, block: suspend () -> T): T =
        withObservedWriter(when (expectedChannelType) {
            "fcm" -> "route-writer:fcm"
            "private" -> "route-writer:private"
            else -> "route-writer:any"
        }, block)

    internal fun diagnosticLockState(): String = "writerLocked=${writerMutex.isLocked}, applyLocked=${applyMutex.isLocked}, observerAvailable=${writerObserver != null && !observerFailed}"

    private fun observe(operation: String, phase: String) {
        val observer = writerObserver ?: return
        try {
            observer(System.identityHashCode(this), operation, phase)
        } catch (_: Throwable) {
            // Only observation is isolated. Original business failures/cancellation propagate.
            observerFailed = true
        }
    }

    private suspend fun <T> withObservedWriter(operation: String, block: suspend () -> T): T {
        observe(operation, "requested")
        return writerMutex.withLock {
            observe(operation, "acquired")
            try { block() } finally { observe(operation, "released") }
        }
    }

    private suspend fun observedLoadRoute(): DeviceRouteSnapshot? {
        observe("gateway.load", "entered")
        return try { gateway.loadRoute() } finally { observe("gateway.load", "finished") }
    }

    private suspend fun observedSwitchRoute(channelType: String, providerToken: String?): DeviceRouteSnapshot {
        observe("gateway.switch", "entered")
        return try { gateway.switchRoute(channelType, providerToken) } finally { observe("gateway.switch", "finished") }
    }

    suspend fun observeRoute(snapshot: DeviceRouteSnapshot) {
        if (!snapshot.issuedNewKey) applySnapshot(snapshot)
    }

    private suspend fun synchronizeLocked() {
        val snapshot = observedLoadRoute() ?: return // Older Gateway: retain its existing single-write path.
        if (snapshot.issuedNewKey) {
            // A freshly allocated default route is initialization, not a saved user selection.
            val useFcm = selectionApplier.currentUseFcm()
            val token = if (useFcm) selectionApplier.providerToken() else null
            if (useFcm && token.isNullOrBlank()) {
                throw TransportRouteUnavailableException("FCM provider token is unavailable")
            }
            applySnapshot(observedSwitchRoute(if (useFcm) "fcm" else "private", token), token)
        } else {
            applySnapshot(snapshot)
        }
    }

    private suspend fun switch(channelType: String, providerToken: String?) = withObservedWriter("switch:$channelType") {
        val snapshot = try {
            observedSwitchRoute(channelType, providerToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A lost response is not a rejection. Observe current state; never issue
            // an inverse remote write. Later ordinary registration can converge again.
            val actual = try {
                observedLoadRoute()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw error
            }
            if (actual == null || actual.issuedNewKey) throw error
            if (!applySnapshot(actual, providerToken.takeIf { actual.channelType == channelType }) ||
                actual.channelType != channelType
            ) throw error
            return@withObservedWriter
        }
        if (!applySnapshot(snapshot, providerToken)) {
            throw TransportRouteUnavailableException("Gateway identity changed while switching transport")
        }
    }

    private suspend fun applySnapshot(snapshot: DeviceRouteSnapshot, candidateToken: String? = null): Boolean =
        applyMutex.withLock {
            val url = currentGatewayUrl().trimEnd('/')
            if (snapshot.gatewayUrl.trimEnd('/') != url || snapshot.deviceKey != currentDeviceKey()) return@withLock false
            val previous = lastApplied
            if (previous != null && previous.gatewayUrl.trimEnd('/') == url &&
                previous.deviceKey == snapshot.deviceKey && snapshot.routeRevision < previous.routeRevision
            ) return@withLock false
            val useFcm = when (snapshot.channelType) {
                "fcm" -> true
                "private" -> false
                else -> throw TransportRouteUnavailableException("Unsupported remote transport route")
            }
            val token = if (useFcm) candidateToken ?: selectionApplier.providerToken() else null
            if (useFcm && (!snapshot.providerTokenPresent || token.isNullOrBlank())) {
                throw TransportRouteUnavailableException("Remote FCM route is not ready")
            }
            if (previous == snapshot && selectionApplier.currentUseFcm() == useFcm) return@withLock true
            try {
                selectionApplier.apply(useFcm, token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retry the local save once without changing the successful remote route.
                selectionApplier.apply(useFcm, token)
            }
            lastApplied = snapshot
            true
        }
}
