package io.ethan.pushgo.testing

import android.content.Context
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.data.ProviderAckDestination
import io.ethan.pushgo.data.ProviderPullContract
import io.ethan.pushgo.data.ProviderPullPage
import io.ethan.pushgo.data.PullItem
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.json.JSONObject

enum class QualityFixture(val wireValue: String) {
    EMPTY_CLEAN("empty.clean"),
    MESSAGES_STANDARD("messages.standard"),
    MESSAGES_ENCRYPTED_VALID("messages.encrypted.valid"),
    MESSAGES_WORKFLOW("messages.workflow"),
    MESSAGES_LARGE("messages.large"),
    EVENT_STANDARD("event.standard"),
    THING_STANDARD("thing.standard"),
    CHANNELS_STANDARD("channels.standard");

    companion object {
        fun fromWireValue(value: String): QualityFixture? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class QualityMessageRefreshScenario(val wireValue: String) {
    NONE("none"),
    NEW_MESSAGE("new_message"),
    FAIL_ONCE_THEN_NEW_MESSAGE("fail_once_then_new_message");

    companion object {
        fun fromWireValue(value: String): QualityMessageRefreshScenario? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class QualityEventCloseScenario(val wireValue: String) {
    NONE("none"),
    ACCEPTED_AND_DELIVERED("accepted_and_delivered");

    companion object {
        fun fromWireValue(value: String): QualityEventCloseScenario? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class QualityChannelMutationScenario(val wireValue: String) {
    NONE("none"),
    ACCEPTED("accepted");

    companion object {
        fun fromWireValue(value: String): QualityChannelMutationScenario? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

data class QualityFaults(
    val messageLoadDelayMs: Int? = null,
    val messageRefreshDelayMs: Int? = null,
    val failMessageLoad: Boolean = false,
)

data class QualitySessionDescriptor(
    val schemaVersion: Int,
    val sessionId: String,
    val fixture: QualityFixture,
    val faults: QualityFaults,
    val messageRefreshScenario: QualityMessageRefreshScenario = QualityMessageRefreshScenario.NONE,
    val eventCloseScenario: QualityEventCloseScenario = QualityEventCloseScenario.NONE,
    val channelMutationScenario: QualityChannelMutationScenario = QualityChannelMutationScenario.NONE,
) {
    val databaseName: String
        get() = "pushgo-quality-$sessionId.db"

    val securePreferencesName: String
        get() = "pushgo-quality-$sessionId-secure-secrets"

    val settingsCachePreferencesName: String
        get() = "pushgo-quality-$sessionId-settings-cache"
}

sealed interface RuntimeProfile {
    data object Production : RuntimeProfile
    data class Quality(val session: QualitySessionDescriptor) : RuntimeProfile
}

object QualityRuntime {
    const val ARG_SESSION_BASE64 = "pushgoQualitySessionBase64"
    private const val SCHEMA_VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 65_536
    private const val FIXTURE_INITIALIZATION_FILENAME = "fixture-initialization.json"
    private val sessionIdRegex = Regex("[A-Za-z0-9_-]{1,64}")
    private val artifactFilenameRegex = Regex("[A-Za-z0-9._-]{1,80}")

    @Volatile
    private var configuredProfile: RuntimeProfile = RuntimeProfile.Production
    private val pendingMessageLoadDelay = AtomicBoolean(false)
    private val pendingMessageRefreshDelay = AtomicBoolean(false)
    private val remainingMessageLoadFailures = AtomicInteger(0)
    private val messageRefreshScenarioAttempts = AtomicInteger(0)

    fun configure(encodedSession: String?): RuntimeProfile {
        configuredProfile = resolve(encodedSession)
        val faults = currentSession()?.faults
        pendingMessageLoadDelay.set((faults?.messageLoadDelayMs ?: 0) > 0)
        pendingMessageRefreshDelay.set((faults?.messageRefreshDelayMs ?: 0) > 0)
        remainingMessageLoadFailures.set(if (faults?.failMessageLoad == true) 1 else 0)
        messageRefreshScenarioAttempts.set(0)
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

    suspend fun beforeMessageRefresh() {
        val faults = currentSession()?.faults ?: return
        if (pendingMessageRefreshDelay.compareAndSet(true, false)) {
            delay(faults.messageRefreshDelayMs?.toLong() ?: 0L)
        }
    }

    fun takeMessageRefreshPullOverride(): Result<ProviderPullPage>? {
        val scenario = currentSession()?.messageRefreshScenario ?: return null
        if (scenario == QualityMessageRefreshScenario.NONE) return null
        val attempt = messageRefreshScenarioAttempts.incrementAndGet()
        if (scenario == QualityMessageRefreshScenario.FAIL_ONCE_THEN_NEW_MESSAGE && attempt == 1) {
            return Result.failure(QualityMessageRefreshException())
        }
        val shouldReturnMessage = attempt == 1 ||
            (scenario == QualityMessageRefreshScenario.FAIL_ONCE_THEN_NEW_MESSAGE && attempt == 2)
        val items = if (shouldReturnMessage) {
            listOf(
                PullItem(
                    deliveryId = "quality-delivery-refresh-result",
                    payload = mapOf(
                        "entity_type" to "message",
                        "entity_id" to "quality-refresh-result",
                        "message_id" to "quality-refresh-result",
                        "title" to "P2 Refresh Result",
                        "body" to "Persisted through the provider refresh ingress path.",
                        "channel" to "quality",
                        "received_at" to "2026-01-16T08:00:00Z",
                    ),
                ),
            )
        } else {
            emptyList()
        }
        return Result.success(
            ProviderPullPage(
                items = items,
                hasMore = false,
                contract = ProviderPullContract.LEGACY,
                destination = ProviderAckDestination(
                    baseUrl = "https://quality.invalid",
                    deviceKey = "quality-device",
                ),
            ),
        )
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
        val refreshScenarioValue = payload.optString("message_refresh_scenario", "none").trim()
        val refreshScenario = requireNotNull(
            QualityMessageRefreshScenario.fromWireValue(refreshScenarioValue)
        ) {
            "unsupported message refresh scenario: $refreshScenarioValue"
        }
        val eventCloseScenarioValue = payload.optString("event_close_scenario", "none").trim()
        val eventCloseScenario = requireNotNull(
            QualityEventCloseScenario.fromWireValue(eventCloseScenarioValue)
        ) {
            "unsupported event close scenario: $eventCloseScenarioValue"
        }
        val channelMutationScenarioValue = payload.optString("channel_mutation_scenario", "none").trim()
        val channelMutationScenario = requireNotNull(
            QualityChannelMutationScenario.fromWireValue(channelMutationScenarioValue)
        ) {
            "unsupported channel mutation scenario: $channelMutationScenarioValue"
        }
        val faultsJson = payload.optJSONObject("faults")
        val delay = faultsJson?.takeIf { it.has("message_load_delay_ms") }
            ?.getInt("message_load_delay_ms")
        require(delay == null || delay in 0..30_000) {
            "message load delay must be between 0 and 30000 ms"
        }
        val refreshDelay = faultsJson?.takeIf { it.has("message_refresh_delay_ms") }
            ?.getInt("message_refresh_delay_ms")
        require(refreshDelay == null || refreshDelay in 0..30_000) {
            "message refresh delay must be between 0 and 30000 ms"
        }
        return QualitySessionDescriptor(
            schemaVersion = schemaVersion,
            sessionId = sessionId,
            fixture = fixture,
            faults = QualityFaults(
                messageLoadDelayMs = delay,
                messageRefreshDelayMs = refreshDelay,
                failMessageLoad = faultsJson?.optBoolean("fail_message_load", false) ?: false,
            ),
            messageRefreshScenario = refreshScenario,
            eventCloseScenario = eventCloseScenario,
            channelMutationScenario = channelMutationScenario,
        )
    }

    fun encode(session: QualitySessionDescriptor): String {
        val faults = JSONObject()
            .put("fail_message_load", session.faults.failMessageLoad)
        session.faults.messageLoadDelayMs?.let {
            faults.put("message_load_delay_ms", it)
        }
        session.faults.messageRefreshDelayMs?.let {
            faults.put("message_refresh_delay_ms", it)
        }
        val payload = JSONObject()
            .put("schema_version", session.schemaVersion)
            .put("session_id", session.sessionId)
            .put("fixture", session.fixture.wireValue)
            .put("message_refresh_scenario", session.messageRefreshScenario.wireValue)
            .put("event_close_scenario", session.eventCloseScenario.wireValue)
            .put("channel_mutation_scenario", session.channelMutationScenario.wireValue)
            .put("faults", faults)
        return Base64.getEncoder().encodeToString(payload.toString().toByteArray())
    }

    fun sessionRoot(context: Context): File? {
        return sessionRootFromFilesDir(context.filesDir)
    }

    fun fixtureInitializationWasRecorded(filesDir: File): Boolean {
        val session = currentSession() ?: return false
        val marker = File(
            sessionRootFromFilesDir(filesDir, session),
            FIXTURE_INITIALIZATION_FILENAME,
        )
        if (!marker.exists()) return false
        check(marker.isFile) {
            "quality fixture initialization marker is not a regular file"
        }
        val payload = runCatching { JSONObject(marker.readText(Charsets.UTF_8)) }
            .getOrElse {
                throw IllegalStateException(
                    "quality fixture initialization marker is unreadable or invalid",
                    it,
                )
            }
        check(payload.optInt("schema_version", -1) == session.schemaVersion) {
            "quality fixture initialization marker schema does not match the session"
        }
        check(payload.optString("session_id") == session.sessionId) {
            "quality fixture initialization marker session does not match"
        }
        check(payload.optString("fixture") == session.fixture.wireValue) {
            "quality fixture initialization marker fixture does not match the session"
        }
        return true
    }

    fun recordFixtureInitialization(filesDir: File) {
        val session = currentSession() ?: return
        if (fixtureInitializationWasRecorded(filesDir)) return
        val root = sessionRootFromFilesDir(filesDir, session)
        check(root.isDirectory || root.mkdirs()) {
            "quality session directory could not be created"
        }
        val marker = File(root, FIXTURE_INITIALIZATION_FILENAME)
        val temporaryMarker = File.createTempFile(
            ".fixture-initialization-",
            ".tmp",
            root,
        )
        try {
            temporaryMarker.writeText(
                JSONObject()
                    .put("schema_version", session.schemaVersion)
                    .put("session_id", session.sessionId)
                    .put("fixture", session.fixture.wireValue)
                    .toString(2),
                Charsets.UTF_8,
            )
            check(temporaryMarker.renameTo(marker)) {
                "quality fixture initialization marker could not be committed"
            }
        } finally {
            if (temporaryMarker.exists()) {
                temporaryMarker.delete()
            }
        }
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

    private fun sessionRootFromFilesDir(filesDir: File): File? {
        val session = currentSession() ?: return null
        return sessionRootFromFilesDir(filesDir, session)
    }

    private fun sessionRootFromFilesDir(
        filesDir: File,
        session: QualitySessionDescriptor,
    ): File = File(filesDir, "quality/sessions/${session.sessionId}")

    internal fun resetForTesting() {
        configuredProfile = RuntimeProfile.Production
        pendingMessageLoadDelay.set(false)
        pendingMessageRefreshDelay.set(false)
        remainingMessageLoadFailures.set(0)
        messageRefreshScenarioAttempts.set(0)
    }
}

class QualityMessageLoadException : IllegalStateException("Injected message list load failure")

class QualityMessageRefreshException : IllegalStateException("Injected provider refresh failure")
