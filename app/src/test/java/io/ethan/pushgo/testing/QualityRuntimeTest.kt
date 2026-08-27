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
            faults = QualityFaults(messageLoadDelayMs = 250),
        )

        val decoded = QualityRuntime.decode(QualityRuntime.encode(session))

        assertEquals(session, decoded)
        assertTrue(decoded.databaseName.startsWith("pushgo-quality-"))
        assertTrue(decoded.databaseName != "pushgo.db")
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

    private fun encodeJson(json: JSONObject): String {
        return java.util.Base64.getEncoder().encodeToString(json.toString().toByteArray())
    }
}
