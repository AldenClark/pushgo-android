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
        coordinator().withRouteWriter {
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
) : TransportSwitcher {
    private val writerMutex = Mutex()
    // Registration/sync replies may arrive inside a serialized writer. Applying a
    // snapshot does no network work and uses its own mutex to avoid recursive locking.
    private val applyMutex = Mutex()
    private var lastApplied: DeviceRouteSnapshot? = null

    override suspend fun switchToFcm(providerToken: String) {
        val token = providerToken.trim()
        require(token.isNotEmpty()) { "FCM provider token is required" }
        switch("fcm", token)
    }

    override suspend fun switchToPrivate() = switch("private", null)

    override suspend fun synchronize() = writerMutex.withLock { synchronizeLocked() }

    suspend fun <T> withRouteWriter(block: suspend () -> T): T = writerMutex.withLock { block() }

    suspend fun observeRoute(snapshot: DeviceRouteSnapshot) {
        if (!snapshot.issuedNewKey) applySnapshot(snapshot)
    }

    private suspend fun synchronizeLocked() {
        val snapshot = gateway.loadRoute() ?: return // Older Gateway: retain its existing single-write path.
        if (snapshot.issuedNewKey) {
            // A freshly allocated default route is initialization, not a saved user selection.
            val useFcm = selectionApplier.currentUseFcm()
            val token = if (useFcm) selectionApplier.providerToken() else null
            if (useFcm && token.isNullOrBlank()) {
                throw TransportRouteUnavailableException("FCM provider token is unavailable")
            }
            applySnapshot(gateway.switchRoute(if (useFcm) "fcm" else "private", token), token)
        } else {
            applySnapshot(snapshot)
        }
    }

    private suspend fun switch(channelType: String, providerToken: String?) = writerMutex.withLock {
        val snapshot = try {
            gateway.switchRoute(channelType, providerToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A lost response is not a rejection. Observe current state; never issue
            // an inverse remote write. Later ordinary registration can converge again.
            val actual = try {
                gateway.loadRoute()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw error
            }
            if (actual == null || actual.issuedNewKey) throw error
            if (!applySnapshot(actual, providerToken.takeIf { actual.channelType == channelType }) ||
                actual.channelType != channelType
            ) throw error
            return@withLock
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
