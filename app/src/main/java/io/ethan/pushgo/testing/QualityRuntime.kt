package io.ethan.pushgo.testing

import android.content.Context
import io.ethan.pushgo.BuildConfig
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.json.JSONObject

enum class QualityFixture(val wireValue: String) {
    EMPTY_CLEAN("empty.clean"),
    MESSAGES_STANDARD("messages.standard"),
    MESSAGES_WORKFLOW("messages.workflow"),
    MESSAGES_LARGE("messages.large"),
    EVENT_STANDARD("event.standard"),
    THING_STANDARD("thing.standard");

    companion object {
        fun fromWireValue(value: String): QualityFixture? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

data class QualityFaults(
    val messageLoadDelayMs: Int? = null,
    val failMessageLoad: Boolean = false,
)

data class QualitySessionDescriptor(
    val schemaVersion: Int,
    val sessionId: String,
    val fixture: QualityFixture,
    val faults: QualityFaults,
) {
    val databaseName: String
        get() = "pushgo-quality-$sessionId.db"
}

sealed interface RuntimeProfile {
    data object Production : RuntimeProfile
    data class Quality(val session: QualitySessionDescriptor) : RuntimeProfile
}

object QualityRuntime {
    const val ARG_SESSION_BASE64 = "pushgoQualitySessionBase64"
    private const val SCHEMA_VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 65_536
    private val sessionIdRegex = Regex("[A-Za-z0-9_-]{1,64}")
    private val artifactFilenameRegex = Regex("[A-Za-z0-9._-]{1,80}")

    @Volatile
    private var configuredProfile: RuntimeProfile = RuntimeProfile.Production
    private val pendingMessageLoadDelay = AtomicBoolean(false)
    private val remainingMessageLoadFailures = AtomicInteger(0)

    fun configure(encodedSession: String?): RuntimeProfile {
        configuredProfile = resolve(encodedSession)
        val faults = currentSession()?.faults
        pendingMessageLoadDelay.set((faults?.messageLoadDelayMs ?: 0) > 0)
        remainingMessageLoadFailures.set(if (faults?.failMessageLoad == true) 1 else 0)
        return configuredProfile
    }

    suspend fun beforeMessageListLoad() {
        val faults = currentSession()?.faults ?: return
        if (pendingMessageLoadDelay.compareAndSet(true, false)) {
            delay(faults.messageLoadDelayMs?.toLong() ?: 0L)
        }
        if (remainingMessageLoadFailures.getAndUpdate { value -> (value - 1).coerceAtLeast(0) } > 0) {
            throw QualityMessageLoadException()
        }
    }

    fun currentSession(): QualitySessionDescriptor? {
        return (configuredProfile as? RuntimeProfile.Quality)?.session
    }

    fun resolve(encodedSession: String?): RuntimeProfile {
        if (!BuildConfig.DEBUG || encodedSession.isNullOrBlank()) {
            return RuntimeProfile.Production
        }
        return RuntimeProfile.Quality(decode(encodedSession))
    }

    fun decode(encodedSession: String): QualitySessionDescriptor {
        require(encodedSession.toByteArray().size <= MAX_PAYLOAD_BYTES) {
            "quality session payload exceeds 64 KiB"
        }
        val decoded = runCatching { Base64.getDecoder().decode(encodedSession) }
            .getOrElse { throw IllegalArgumentException("quality session is not valid base64", it) }
        val payload = runCatching { JSONObject(String(decoded, Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("quality session is not valid JSON", it) }
        val schemaVersion = payload.optInt("schema_version", -1)
        require(schemaVersion == SCHEMA_VERSION) {
            "unsupported quality session schema version: $schemaVersion"
        }
        val sessionId = payload.optString("session_id").trim()
        require(sessionIdRegex.matches(sessionId)) {
            "quality session ID contains unsupported characters"
        }
        val fixtureValue = payload.optString("fixture").trim()
        val fixture = requireNotNull(QualityFixture.fromWireValue(fixtureValue)) {
            "unsupported quality fixture: $fixtureValue"
        }
        val faultsJson = payload.optJSONObject("faults")
        val delay = faultsJson?.takeIf { it.has("message_load_delay_ms") }
            ?.getInt("message_load_delay_ms")
        require(delay == null || delay in 0..30_000) {
            "message load delay must be between 0 and 30000 ms"
        }
        return QualitySessionDescriptor(
            schemaVersion = schemaVersion,
            sessionId = sessionId,
            fixture = fixture,
            faults = QualityFaults(
                messageLoadDelayMs = delay,
                failMessageLoad = faultsJson?.optBoolean("fail_message_load", false) ?: false,
            ),
        )
    }

    fun encode(session: QualitySessionDescriptor): String {
        val faults = JSONObject()
            .put("fail_message_load", session.faults.failMessageLoad)
        session.faults.messageLoadDelayMs?.let {
            faults.put("message_load_delay_ms", it)
        }
        val payload = JSONObject()
            .put("schema_version", session.schemaVersion)
            .put("session_id", session.sessionId)
            .put("fixture", session.fixture.wireValue)
            .put("faults", faults)
        return Base64.getEncoder().encodeToString(payload.toString().toByteArray())
    }

    fun sessionRoot(context: Context): File? {
        val session = currentSession() ?: return null
        return File(context.filesDir, "quality/sessions/${session.sessionId}")
    }

    fun artifactFile(context: Context, filename: String): File? {
        return artifactFileFromFilesDir(context.filesDir, filename)
    }

    fun artifactFileFromFilesDir(filesDir: File, filename: String): File? {
        require(artifactFilenameRegex.matches(filename) && filename != "." && filename != "..") {
            "unsupported quality artifact filename"
        }
        val session = currentSession() ?: return null
        return File(filesDir, "quality/sessions/${session.sessionId}/artifacts/$filename")
    }

    internal fun resetForTesting() {
        configuredProfile = RuntimeProfile.Production
        pendingMessageLoadDelay.set(false)
        remainingMessageLoadFailures.set(0)
    }
}

class QualityMessageLoadException : IllegalStateException("Injected message list load failure")
