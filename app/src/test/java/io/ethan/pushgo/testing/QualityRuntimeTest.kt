package io.ethan.pushgo.testing

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class QualityRuntimeTest {
    @After
    fun tearDown() {
        QualityRuntime.resetForTesting()
    }

    @Test
    fun typedSessionRoundTripsAndSelectsANonProductionDatabase() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "android-pr-123_retry-1",
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(
                messageLoadDelayMs = 250,
                messageRefreshDelayMs = 2_500,
            ),
            messageRefreshScenario = QualityMessageRefreshScenario.FAIL_ONCE_THEN_NEW_MESSAGE,
            eventCloseScenario = QualityEventCloseScenario.ACCEPTED_AND_DELIVERED,
        )

        val decoded = QualityRuntime.decode(QualityRuntime.encode(session))

        assertEquals(session, decoded)
        assertTrue(decoded.databaseName.startsWith("pushgo-quality-"))
        assertTrue(decoded.databaseName != "pushgo.db")
        assertEquals(QualityEventCloseScenario.ACCEPTED_AND_DELIVERED, decoded.eventCloseScenario)
    }

    @Test
    fun everyAppOwnedFixtureRoundTripsThroughTheSessionAllowlist() {
        QualityFixture.entries.forEachIndexed { index, fixture ->
            val session = QualitySessionDescriptor(
                schemaVersion = 1,
                sessionId = "fixture-roundtrip-$index",
                fixture = fixture,
                faults = QualityFaults(),
            )

            assertEquals(fixture, QualityRuntime.decode(QualityRuntime.encode(session)).fixture)
        }
    }

    @Test
    fun pathTraversalSessionIdIsRejected() {
        val encoded = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "../../pushgo")
                .put("fixture", "empty.clean")
        )

        assertThrows(IllegalArgumentException::class.java) {
            QualityRuntime.decode(encoded)
        }
    }

    @Test
    fun unboundedDelayFaultIsRejected() {
        val encoded = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "slow-load-negative-control")
                .put("fixture", "messages.standard")
                .put("faults", JSONObject().put("message_load_delay_ms", 30_001))
        )

        assertThrows(IllegalArgumentException::class.java) {
            QualityRuntime.decode(encoded)
        }
    }

    @Test
    fun unboundedRefreshDelayFaultIsRejected() {
        val encoded = encodeJson(
            JSONObject()
                .put("schema_version", 1)
                .put("session_id", "slow-refresh-negative-control")
                .put("fixture", "messages.standard")
                .put("faults", JSONObject().put("message_refresh_delay_ms", 30_001))
        )

        assertThrows(IllegalArgumentException::class.java) {
            QualityRuntime.decode(encoded)
        }
    }

    @Test
    fun artifactPathIsContainedUnderTheAppFilesDirectory() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "contained-session",
            fixture = QualityFixture.EMPTY_CLEAN,
            faults = QualityFaults(),
        )
        QualityRuntime.configure(QualityRuntime.encode(session))
        val filesDir = File("/app/data/files")

        val artifact = checkNotNull(
            QualityRuntime.artifactFileFromFilesDir(filesDir, "quality-readiness.json")
        )

        assertTrue(artifact.path.startsWith(filesDir.path + File.separator))
        assertTrue(!artifact.path.contains(".."))
    }

    @Test
    fun messageLoadFaultFailsOnceThenAllowsARealRetry() = runBlocking {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "retry-contract",
            fixture = QualityFixture.EMPTY_CLEAN,
            faults = QualityFaults(failMessageLoad = true),
        )
        QualityRuntime.configure(QualityRuntime.encode(session))

        var didFail = false
        try {
            QualityRuntime.beforeMessageListLoad()
        } catch (_: QualityMessageLoadException) {
            didFail = true
        }
        assertTrue(didFail)
        QualityRuntime.beforeMessageListLoad()
    }

    @Test
    fun providerRefreshScenarioFailsOnceThenReturnsARealIngressPage() {
        val session = QualitySessionDescriptor(
            schemaVersion = 1,
            sessionId = "refresh-recovery-contract",
            fixture = QualityFixture.MESSAGES_STANDARD,
            faults = QualityFaults(),
            messageRefreshScenario = QualityMessageRefreshScenario.FAIL_ONCE_THEN_NEW_MESSAGE,
        )
        QualityRuntime.configure(QualityRuntime.encode(session))

        assertTrue(checkNotNull(QualityRuntime.takeMessageRefreshPullOverride()).isFailure)
        val page = checkNotNull(QualityRuntime.takeMessageRefreshPullOverride()).getOrThrow()
        assertEquals("quality-refresh-result", page.items.single().payload["message_id"])
        assertTrue(checkNotNull(QualityRuntime.takeMessageRefreshPullOverride()).getOrThrow().items.isEmpty())
    }

    private fun encodeJson(json: JSONObject): String {
        return java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())
    }
}
