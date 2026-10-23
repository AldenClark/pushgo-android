package io.ethan.pushgo.data

import io.ethan.pushgo.testing.QualityChannelMutationScenario
import io.ethan.pushgo.testing.QualityFaults
import io.ethan.pushgo.testing.QualityFixture
import io.ethan.pushgo.testing.QualitySessionDescriptor
import io.ethan.pushgo.testing.QualityTransportSwitchScenario
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportCapabilityProbeTest {
    private fun session(
        transport: QualityTransportSwitchScenario = QualityTransportSwitchScenario.NONE,
        mutation: QualityChannelMutationScenario = QualityChannelMutationScenario.NONE,
    ) = QualitySessionDescriptor(
        schemaVersion = 1,
        sessionId = "capability-probes",
        fixture = QualityFixture.MESSAGES_STANDARD,
        faults = QualityFaults(),
        transportSwitchScenario = transport,
        channelMutationScenario = mutation,
    )

    @Test
    fun absentAndInactiveSessionsInvokeRealProbesAndPreserveUnavailableResults() = runBlocking {
        for (session in listOf(null, session())) {
            var fcmCalls = 0
            var gatewayCalls = 0
            val fcm = selectTransportCapabilityProbe<() -> Boolean>(session,
                realProbe = { fcmCalls++; false },
                controlledProbe = { error("Inactive session must not fake FCM support") },
            )
            val gateway = selectTransportCapabilityProbe<suspend () -> Boolean?>(session,
                realProbe = { gatewayCalls++; null },
                controlledProbe = { error("Inactive session must not fake Gateway support") },
            )
            assertEquals(false, fcm())
            assertEquals(null, gateway())
            assertEquals(1, fcmCalls)
            assertEquals(1, gatewayCalls)
        }
    }

    @Test
    fun activeSwitchAndMutationSessionsUseControlledProbesWithoutRealNetwork() = runBlocking {
        val sessions = QualityTransportSwitchScenario.entries.filter { it != QualityTransportSwitchScenario.NONE }
            .map { session(transport = it) } +
            QualityChannelMutationScenario.entries.filter { it != QualityChannelMutationScenario.NONE }
                .map { session(mutation = it) }
        for (session in sessions) {
            val fcm = selectTransportCapabilityProbe<() -> Boolean>(session,
                realProbe = { error("Controlled journey must not probe real FCM") },
                controlledProbe = { true },
            )
            val gateway = selectTransportCapabilityProbe<suspend () -> Boolean?>(session,
                realProbe = { error("Controlled journey must not probe real Gateway") },
                controlledProbe = { true },
            )
            assertEquals(true, fcm())
            assertEquals(true, gateway())
        }
    }
}
