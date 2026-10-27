package io.ethan.pushgo.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TransportSwitchCoordinatorTest {
    private class Harness {
        var gatewayUrl = "https://gateway.test"
        var deviceKey = "device"
        var useFcm = true
        var savedToken: String? = "current-fcm-token"
        var route = DeviceRouteSnapshot(gatewayUrl, deviceKey, "fcm", 1, true)
        var writes = 0
        var reads = 0
        var applyAttempts = 0
        var failApply = 0
        var reject = false
        var loseResponse = false
        var failRead = false
        var legacy = false
        val gateway = object : TransportRouteGateway {
            override suspend fun loadRoute(): DeviceRouteSnapshot? {
                reads++
                if (failRead) error("offline")
                return route.takeUnless { legacy }
            }
            override suspend fun switchRoute(channelType: String, providerToken: String?): DeviceRouteSnapshot {
                writes++
                if (reject) error("rejected")
                route = route.copy(channelType = channelType, routeRevision = route.routeRevision + 1,
                    providerTokenPresent = providerToken != null, issuedNewKey = false)
                if (loseResponse) error("response lost after remote success")
                return route
            }
        }
        val applier = object : TransportSelectionApplier {
            override suspend fun apply(useFcm: Boolean, providerToken: String?) {
                applyAttempts++
                if (failApply-- > 0) error("local save failed")
                this@Harness.useFcm = useFcm
                savedToken = providerToken.takeIf { useFcm }
            }
            override suspend fun currentUseFcm() = useFcm
            override suspend fun providerToken() = savedToken ?: "current-fcm-token"
        }
        fun coordinator() = TransportSwitchCoordinator(gateway, applier, { gatewayUrl }, { deviceKey })
    }

    @Test fun existingRemotePrivateWinsOverOldLocalFcmWithoutRouteWrite() = runBlocking {
        val h = Harness(); h.route = h.route.copy(channelType = "private", providerTokenPresent = false)
        h.coordinator().synchronize()
        assertFalse(h.useFcm); assertEquals(0, h.writes); assertEquals(1, h.reads)
    }

    @Test fun throwingObserverCannotChangeWritesOriginalFailureOrReleaseOfWriterLock() = runBlocking {
        val h = Harness()
        var observations = 0
        val coordinator = TransportSwitchCoordinator(h.gateway, h.applier, { h.gatewayUrl }, { h.deviceKey },
            writerObserver = { _, _, _ -> observations++; error("observer failure") })
        coordinator.switchToPrivate()
        assertFalse(h.useFcm)
        assertEquals("private", h.route.channelType)
        assertEquals(1, h.writes)
        h.reject = true
        val originalFailure = runCatching { coordinator.switchToFcm("candidate") }.exceptionOrNull()
        assertEquals("rejected", originalFailure?.message)
        assertEquals(2, h.writes)
        assertFalse(h.useFcm)
        h.reject = false
        coordinator.switchToFcm("fresh")
        assertTrue(h.useFcm)
        assertEquals(3, h.writes)
        assertTrue(observations > 0)
        assertTrue(coordinator.diagnosticLockState().contains("observerAvailable=false"))
    }

    @Test fun directSwitchInBothDirectionsUsesOneWriteEachAndScopesProviderToken() = runBlocking {
        val h = Harness(); val c = h.coordinator()
        c.switchToPrivate(); assertFalse(h.useFcm); assertEquals("private", h.route.channelType)
        assertNull(h.savedToken)
        c.switchToFcm("fresh-token"); assertTrue(h.useFcm); assertEquals("fresh-token", h.savedToken)
        assertEquals("fcm", h.route.channelType); assertEquals(2, h.writes); assertEquals(0, h.reads)
    }

    @Test fun lostResponseObservesSuccessfulRemoteSwitchWithoutInverseWrite() = runBlocking {
        val h = Harness(); h.loseResponse = true
        h.coordinator().switchToPrivate()
        assertFalse(h.useFcm); assertEquals("private", h.route.channelType)
        assertEquals(1, h.writes); assertEquals(1, h.reads)
    }

    @Test fun rejectionKeepsActualOldRouteAndReportsFailure() = runBlocking {
        val h = Harness(); h.reject = true
        assertTrue(runCatching { h.coordinator().switchToPrivate() }.isFailure)
        assertTrue(h.useFcm); assertEquals("fcm", h.route.channelType); assertEquals(1, h.writes)
    }

    @Test fun unknownResultAndOfflineQueryDoNotIssueCompensation() = runBlocking {
        val h = Harness(); h.loseResponse = true; h.failRead = true
        assertTrue(runCatching { h.coordinator().switchToPrivate() }.isFailure)
        assertTrue(h.useFcm); assertEquals("private", h.route.channelType); assertEquals(1, h.writes)
    }

    @Test fun oneLocalSaveRetryCompletesSuccessfulRemoteSwitch() = runBlocking {
        val h = Harness(); h.failApply = 1
        h.coordinator().switchToPrivate()
        assertFalse(h.useFcm); assertEquals(2, h.applyAttempts); assertEquals(1, h.writes)
    }

    @Test fun startupAfterLocalSaveFailureConvergesWithoutAnOperationJournal() = runBlocking {
        val h = Harness(); h.failApply = 2
        assertTrue(runCatching { h.coordinator().switchToPrivate() }.isFailure)
        assertTrue(h.useFcm); assertEquals("private", h.route.channelType)
        h.coordinator().synchronize() // A fresh coordinator reads current state, not a saved operation.
        assertFalse(h.useFcm); assertEquals(1, h.writes)
    }

    @Test fun olderOrDifferentGatewayAndDeviceResponsesCannotReplaceCurrentSelection() = runBlocking {
        val h = Harness(); val c = h.coordinator()
        c.observeRoute(h.route.copy(channelType = "private", routeRevision = 3, providerTokenPresent = false))
        c.observeRoute(h.route.copy(routeRevision = 2))
        c.observeRoute(h.route.copy(gatewayUrl = "https://old.test", routeRevision = 4))
        c.observeRoute(h.route.copy(deviceKey = "retired-device", routeRevision = 4))
        assertFalse(h.useFcm); assertEquals(1, h.applyAttempts); assertEquals(0, h.writes)
    }

    @Test fun ordinaryFcmWriterChecksObservedRemoteSelectionBeforeWriting() = runBlocking {
        val h = Harness(); h.route = h.route.copy(channelType = "private", providerTokenPresent = false)
        val c = h.coordinator(); val gate = CoordinatorTransportRouteWriterGate({ c }, { if(h.useFcm) "fcm" else "private" })
        c.synchronize() // Existing registration applies the remote route before ordinary upsert.
        var executed = false
        assertTrue(runCatching { gate.run("fcm") { executed = true } }.isFailure)
        assertFalse(executed); assertFalse(h.useFcm); assertEquals(0, h.writes)
    }

    @Test fun newIdentityUsesExistingInitialSelectionOnce() = runBlocking {
        val h = Harness(); h.route = h.route.copy(channelType = "private", providerTokenPresent = false, issuedNewKey = true)
        h.coordinator().synchronize()
        assertTrue(h.useFcm); assertEquals("fcm", h.route.channelType); assertEquals(1, h.writes)
    }

    @Test fun oldGatewayWithoutRouteMetadataDoesNotRequireV2Capability() = runBlocking {
        val h = Harness(); h.legacy = true
        val c = h.coordinator(); c.synchronize(); c.switchToPrivate()
        assertFalse(h.useFcm); assertEquals(1, h.writes)
    }
}
