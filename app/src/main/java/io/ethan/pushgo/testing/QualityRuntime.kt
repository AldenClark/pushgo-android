package io.ethan.pushgo.testing

import android.content.Context
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.data.ProviderAckDestination
import io.ethan.pushgo.data.ProviderPullContract
import io.ethan.pushgo.data.ProviderPullPage
import io.ethan.pushgo.data.PullItem
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

enum class QualityFixture(val wireValue: String) {
    EMPTY_CLEAN("empty.clean"),
    MESSAGES_STANDARD("messages.standard"),
    MESSAGES_ENCRYPTED_VALID("messages.encrypted.valid"),
    MESSAGES_ENCRYPTED_CORRUPT("messages.encrypted.corrupt"),
    MESSAGES_WORKFLOW("messages.workflow"),
    MESSAGES_FILTERS("messages.filters"),
    MESSAGES_CLEANUP("messages.cleanup"),
    MESSAGES_MARKDOWN("messages.markdown"),
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
    ACCEPTED_AND_DELIVERED("accepted_and_delivered"),
    FAIL_ONCE_THEN_ACCEPTED_AND_DELIVERED("fail_once_then_accepted_and_delivered");

    companion object {
        fun fromWireValue(value: String): QualityEventCloseScenario? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class QualityChannelMutationScenario(val wireValue: String) {
    NONE("none"),
    ACCEPTED("accepted"),
    REJECT_ONCE_THEN_ACCEPTED("reject_once_then_accepted"),
    RENAME_REJECT_ONCE_THEN_ACCEPTED("rename_reject_once_then_accepted"),
    REQUIRE_CREATE_COMPENSATION("require_create_compensation"),
    EXISTING_SUBSCRIBE_MUST_NOT_COMPENSATE("existing_subscribe_must_not_compensate");

    companion object {
        fun fromWireValue(value: String): QualityChannelMutationScenario? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class QualityTransportSwitchScenario(val wireValue: String) {
    NONE("none"),
    ACCEPTED("accepted"),
    REJECT_ONCE_THEN_ACCEPTED("reject_once_then_accepted");

    companion object {
        fun fromWireValue(value: String): QualityTransportSwitchScenario? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class QualityUpdateScenario(val wireValue: String) {
    NONE("none"),
    AVAILABLE_STABLE("available_stable"),
    AVAILABLE_STABLE_AND_BETA("available_stable_and_beta");

    companion object {
        fun fromWireValue(value: String): QualityUpdateScenario? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class QualitySystemCapability(val wireValue: String) {
    PRIVATE_FOREGROUND_SERVICE("private_foreground_service"),
    NOTIFICATION_PERMISSION_JOURNEY("notification_permission_journey"),
    DOZE_REMINDER_JOURNEY("doze_reminder_journey");

    companion object {
        fun fromWireValue(value: String): QualitySystemCapability? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

data class QualityUpdateArtifact(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSha256: String,
)

data class QualityFaults(
    val failLocalStoreInitialization: Boolean = false,
    val messageLoadDelayMs: Int? = null,
    val messagePageLoadDelayMs: Int? = null,
    val messageRefreshDelayMs: Int? = null,
    val messageRefreshPresentationDelayMs: Int? = null,
    val messageSearchDelayMs: Int? = null,
    val failMessageLoad: Boolean = false,
    val failMessagePageLoadOnce: Boolean = false,
    val failMessageSearchOnce: Boolean = false,
    val failGatewaySwitchValidationOnce: Boolean = false,
    val failGatewaySwitchCommitOnce: Boolean = false,
    val pauseGatewaySwitchBeforeCommit: Boolean = false,
    val failGatewayPostCommitSyncOnce: Boolean = false,
    val failNotificationKeyPersistenceOnce: Boolean = false,
    val failChannelSubscriptionPersistenceOnce: Boolean = false,
    val failTransportSelectionPersistenceOnce: Boolean = false,
)

data class QualitySessionDescriptor(
    val schemaVersion: Int,
    val sessionId: String,
    val fixture: QualityFixture,
    val faults: QualityFaults,
    val messageRefreshScenario: QualityMessageRefreshScenario = QualityMessageRefreshScenario.NONE,
    val eventCloseScenario: QualityEventCloseScenario = QualityEventCloseScenario.NONE,
    val channelMutationScenario: QualityChannelMutationScenario = QualityChannelMutationScenario.NONE,
    val expectedChannelMutationGatewayUrl: String? = null,
    val expectedGatewayPreparationUrl: String? = null,
    val transportSwitchScenario: QualityTransportSwitchScenario = QualityTransportSwitchScenario.NONE,
    val updateScenario: QualityUpdateScenario = QualityUpdateScenario.NONE,
    val updateArtifact: QualityUpdateArtifact? = null,
    val systemCapabilities: Set<QualitySystemCapability> = emptySet(),
    /**
     * Optional bounded undo window for a host-driven quality journey.  This is
     * intentionally session-scoped so a process-restart test can leave enough
     * time to cross a real PID boundary without changing production timing.
     */
    val pendingDeletionUndoWindowMillis: Long? = null,
) {
    val databaseName: String
        get() = "pushgo-quality-$sessionId.db"

    val securePreferencesName: String
        get() = "pushgo-quality-$sessionId-secure-secrets"

    val settingsCachePreferencesName: String
        get() = "pushgo-quality-$sessionId-settings-cache"

    val reminderSnoozePreferencesName: String
        get() = "pushgo-quality-$sessionId-reminder-snooze"
}

sealed interface RuntimeProfile {
    data object Production : RuntimeProfile
    data class Quality(val session: QualitySessionDescriptor) : RuntimeProfile
}

object QualityRuntime {
    const val ARG_SESSION_BASE64 = "pushgoQualitySessionBase64"
    private const val SESSION_CONTROL_PREFERENCES = "pushgo_quality_session_control"
    private const val SESSION_CONTROL_KEY = "encoded_session"
    private const val SCHEMA_VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 65_536
    private const val FIXTURE_INITIALIZATION_FILENAME = "fixture-initialization.json"
    private val sessionIdRegex = Regex("[A-Za-z0-9_-]{1,64}")
    private val artifactFilenameRegex = Regex("[A-Za-z0-9._-]{1,80}")

    @Volatile
    private var configuredProfile: RuntimeProfile = RuntimeProfile.Production
    private val pendingMessageLoadDelay = AtomicBoolean(false)
    private val pendingMessagePageLoadDelay = AtomicBoolean(false)
    private val pendingMessageRefreshDelay = AtomicBoolean(false)
    private val pendingMessageRefreshPresentationDelay = AtomicBoolean(false)
    private val pendingMessageSearchDelay = AtomicBoolean(false)
    private val remainingMessageLoadFailures = AtomicInteger(0)
    private val remainingMessagePageLoadFailures = AtomicInteger(0)
    private val remainingMessageSearchFailures = AtomicInteger(0)
    private val remainingGatewaySwitchValidationFailures = AtomicInteger(0)
    private val remainingGatewaySwitchCommitFailures = AtomicInteger(0)
    private val gatewaySwitchPreCommitPaused = AtomicBoolean(false)
    @Volatile
    private var gatewaySwitchPreCommitBarrier: CompletableDeferred<Unit>? = null
    private val remainingGatewayPostCommitSyncFailures = AtomicInteger(0)
    private val pendingGatewayPostCommitSyncFailure = AtomicBoolean(false)
    private val remainingNotificationKeyPersistenceFailures = AtomicInteger(0)
    private val pendingChannelSubscriptionPersistenceFailure = AtomicBoolean(false)
    private val remainingChannelSubscriptionPersistenceFailures = AtomicInteger(0)
    private val remainingTransportSelectionPersistenceFailures = AtomicInteger(0)
    private val messageRefreshScenarioAttempts = AtomicInteger(0)
    // Durable-for-the-session presentation evidence. Compose state can be
    // consumed immediately after a Toast is shown, so a test-visible ledger
    // must outlive the transient errorMessage state instead of relying on a
    // race-prone host marker.
    private val globalErrorPresentationCount = AtomicInteger(0)

    fun allowsSystemCapability(capability: QualitySystemCapability): Boolean =
        currentSession()?.systemCapabilities?.contains(capability) == true

    fun configure(encodedSession: String?): RuntimeProfile {
        configuredProfile = resolve(encodedSession)
        val faults = currentSession()?.faults
        pendingMessageLoadDelay.set((faults?.messageLoadDelayMs ?: 0) > 0)
        pendingMessagePageLoadDelay.set((faults?.messagePageLoadDelayMs ?: 0) > 0)
        pendingMessageRefreshDelay.set((faults?.messageRefreshDelayMs ?: 0) > 0)
        pendingMessageRefreshPresentationDelay.set(false)
        pendingMessageSearchDelay.set((faults?.messageSearchDelayMs ?: 0) > 0)
        remainingMessageLoadFailures.set(if (faults?.failMessageLoad == true) 1 else 0)
        remainingMessagePageLoadFailures.set(if (faults?.failMessagePageLoadOnce == true) 1 else 0)
        remainingMessageSearchFailures.set(if (faults?.failMessageSearchOnce == true) 1 else 0)
        remainingGatewaySwitchValidationFailures.set(
            if (faults?.failGatewaySwitchValidationOnce == true) 1 else 0
        )
        remainingGatewaySwitchCommitFailures.set(
            if (faults?.failGatewaySwitchCommitOnce == true) 1 else 0
        )
        gatewaySwitchPreCommitBarrier?.cancel()
        gatewaySwitchPreCommitPaused.set(false)
        gatewaySwitchPreCommitBarrier = if (faults?.pauseGatewaySwitchBeforeCommit == true) {
            CompletableDeferred()
        } else {
            null
        }
        remainingGatewayPostCommitSyncFailures.set(0)
        pendingGatewayPostCommitSyncFailure.set(faults?.failGatewayPostCommitSyncOnce == true)
        remainingNotificationKeyPersistenceFailures.set(
            if (faults?.failNotificationKeyPersistenceOnce == true) 1 else 0
        )
        pendingChannelSubscriptionPersistenceFailure.set(
            faults?.failChannelSubscriptionPersistenceOnce == true
        )
        remainingChannelSubscriptionPersistenceFailures.set(0)
        remainingTransportSelectionPersistenceFailures.set(
            if (faults?.failTransportSelectionPersistenceOnce == true) 1 else 0
        )
        messageRefreshScenarioAttempts.set(0)
        globalErrorPresentationCount.set(0)
        return configuredProfile
    }

    /** Records a host-level error presentation for the active quality session. */
    fun recordGlobalErrorPresentation() {
        if (currentSession() != null) {
            globalErrorPresentationCount.incrementAndGet()
        }
    }

    /** Number of host-level error presentations observed in this session. */
    fun globalErrorPresentationCount(): Int = globalErrorPresentationCount.get()

    suspend fun beforeMessageListLoad() {
        val faults = currentSession()?.faults ?: return
        if (pendingMessageLoadDelay.compareAndSet(true, false)) {
            delay(faults.messageLoadDelayMs?.toLong() ?: 0L)
        }
        if (remainingMessageLoadFailures.getAndUpdate { value -> (value - 1).coerceAtLeast(0) } > 0) {
            throw QualityMessageLoadException()
        }
    }

    suspend fun beforeMessagePageLoad() {
        val faults = currentSession()?.faults ?: return
        if (pendingMessagePageLoadDelay.compareAndSet(true, false)) {
            delay(faults.messagePageLoadDelayMs?.toLong() ?: 0L)
        }
        if (remainingMessagePageLoadFailures.getAndUpdate { value ->
                (value - 1).coerceAtLeast(0)
            } > 0
        ) {
            throw QualityMessagePageLoadException()
        }
    }

    suspend fun beforeMessageRefresh() {
        val faults = currentSession()?.faults ?: return
        if (pendingMessageRefreshDelay.compareAndSet(true, false)) {
            delay(faults.messageRefreshDelayMs?.toLong() ?: 0L)
        }
    }

    fun armMessageRefreshPresentationDelay() {
        val delayMs = currentSession()?.faults?.messageRefreshPresentationDelayMs ?: 0
        pendingMessageRefreshPresentationDelay.set(delayMs > 0)
    }

    suspend fun beforeMessageRefreshPresentation() {
        val faults = currentSession()?.faults ?: return
        if (pendingMessageRefreshPresentationDelay.compareAndSet(true, false)) {
            delay(faults.messageRefreshPresentationDelayMs?.toLong() ?: 0L)
        }
    }

    suspend fun beforeMessageSearch(rawQuery: String) {
        if (rawQuery.isBlank()) return
        val faults = currentSession()?.faults ?: return
        if (pendingMessageSearchDelay.compareAndSet(true, false)) {
            delay(faults.messageSearchDelayMs?.toLong() ?: 0L)
        }
    }

    fun beforeMessageSearchLoad() {
        if (remainingMessageSearchFailures.getAndUpdate { value ->
                (value - 1).coerceAtLeast(0)
            } > 0
        ) {
            throw QualityMessageSearchException()
        }
    }

    fun beforeGatewaySwitchValidation() {
        if (remainingGatewaySwitchValidationFailures.getAndUpdate { value ->
                (value - 1).coerceAtLeast(0)
            } > 0
        ) {
            throw QualityGatewaySwitchValidationException()
        }
    }

    fun afterGatewayAddressPersistence() {
        if (remainingGatewaySwitchCommitFailures.getAndUpdate { value ->
                (value - 1).coerceAtLeast(0)
            } > 0
        ) {
            throw QualityGatewaySwitchCommitException()
        }
    }

    /**
     * Quality-only observation seam after candidate registration and before
     * any active-Gateway write. Tests release it explicitly; there is no
     * timer, retry, or production behavior involved.
     */
    suspend fun awaitGatewaySwitchPreCommitPhase() {
        val barrier = gatewaySwitchPreCommitBarrier ?: return
        gatewaySwitchPreCommitPaused.set(true)
        try {
            barrier.await()
        } finally {
            gatewaySwitchPreCommitPaused.set(false)
        }
    }

    fun isGatewaySwitchPreCommitPaused(): Boolean = gatewaySwitchPreCommitPaused.get()

    fun continueGatewaySwitchPreCommitPhase() {
        gatewaySwitchPreCommitBarrier?.complete(Unit)
    }

    fun beforeGatewayPostCommitSync() {
        if (remainingGatewayPostCommitSyncFailures.getAndUpdate { value ->
                (value - 1).coerceAtLeast(0)
            } > 0
        ) {
            throw QualityGatewayPostCommitSyncException()
        }
    }

    /**
     * Arms the one-shot sync fault only after the gateway commit boundary.
     * Startup/channel-entry sync must not consume a fault intended to model
     * work that follows a user-confirmed gateway switch.
     */
    fun armGatewayPostCommitSyncFailure() {
        if (pendingGatewayPostCommitSyncFailure.compareAndSet(true, false)) {
            remainingGatewayPostCommitSyncFailures.set(1)
        }
    }

    fun afterNotificationKeySecretPersistence() {
        if (remainingNotificationKeyPersistenceFailures.getAndUpdate { value ->
                (value - 1).coerceAtLeast(0)
            } > 0
        ) {
            throw QualityNotificationKeyPersistenceException()
        }
    }

    fun armChannelSubscriptionPersistenceFailure() {
        if (pendingChannelSubscriptionPersistenceFailure.compareAndSet(true, false)) {
            remainingChannelSubscriptionPersistenceFailures.set(1)
        }
    }

    fun afterChannelSubscriptionSecretPersistence() {
        if (remainingChannelSubscriptionPersistenceFailures.getAndUpdate { value ->
                (value - 1).coerceAtLeast(0)
            } > 0
        ) {
            throw QualityChannelSubscriptionPersistenceException()
        }
    }

    fun afterTransportSelectionPersistence() {
        if (remainingTransportSelectionPersistenceFailures.getAndUpdate { value ->
                (value - 1).coerceAtLeast(0)
            } > 0
        ) {
            throw QualityTransportSelectionPersistenceException()
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

    fun isAppOwnedSessionConfigured(): Boolean =
        BuildConfig.QUALITY_SESSION_CONTROL_ENABLED && currentSession() != null

    fun resolve(encodedSession: String?): RuntimeProfile {
        if (!BuildConfig.QUALITY_RUNTIME_ENABLED || encodedSession.isNullOrBlank()) {
            return RuntimeProfile.Production
        }
        return RuntimeProfile.Quality(decode(encodedSession))
    }

    fun configureFromAppStorage(context: Context): RuntimeProfile {
        check(BuildConfig.QUALITY_SESSION_CONTROL_ENABLED) {
            "persistent quality-session control is unavailable in this build"
        }
        val encodedSession = context.getSharedPreferences(
            SESSION_CONTROL_PREFERENCES,
            Context.MODE_PRIVATE,
        ).getString(SESSION_CONTROL_KEY, null)
        return configure(encodedSession)
    }

    fun persistAppOwnedSession(context: Context, encodedSession: String?) {
        check(BuildConfig.QUALITY_SESSION_CONTROL_ENABLED) {
            "persistent quality-session control is unavailable in this build"
        }
        encodedSession?.let(::decode)
        val preferences = context.getSharedPreferences(
            SESSION_CONTROL_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val committed = if (encodedSession.isNullOrBlank()) {
            preferences.edit().remove(SESSION_CONTROL_KEY).commit()
        } else {
            preferences.edit().putString(SESSION_CONTROL_KEY, encodedSession).commit()
        }
        check(committed) { "quality-session control could not be persisted" }
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
        val expectedChannelMutationGatewayUrl = payload
            .optString("expected_channel_mutation_gateway_url")
            .trim()
            .ifEmpty { null }
        val expectedGatewayPreparationUrl = payload
            .optString("expected_gateway_preparation_url")
            .trim()
            .ifEmpty { null }
        val transportSwitchScenarioValue = payload.optString("transport_switch_scenario", "none").trim()
        val transportSwitchScenario = requireNotNull(
            QualityTransportSwitchScenario.fromWireValue(transportSwitchScenarioValue)
        ) {
            "unsupported transport switch scenario: $transportSwitchScenarioValue"
        }
        val updateScenarioValue = payload.optString("update_scenario", "none").trim()
        val updateScenario = requireNotNull(QualityUpdateScenario.fromWireValue(updateScenarioValue)) {
            "unsupported update scenario: $updateScenarioValue"
        }
        val updateArtifact = payload.optJSONObject("update_artifact")?.let { artifact ->
            val versionCode = artifact.optInt("version_code", -1)
            val versionName = artifact.optString("version_name").trim()
            val apkUrl = artifact.optString("apk_url").trim()
            val apkSha256 = artifact.optString("apk_sha256").trim().lowercase()
            require(versionCode > 0) { "quality update artifact version code must be positive" }
            require(versionName.isNotEmpty() && versionName.length <= 64) {
                "quality update artifact version name is invalid"
            }
            require(apkSha256.matches(Regex("[0-9a-f]{64}"))) {
                "quality update artifact SHA-256 is invalid"
            }
            val uri = runCatching { URI(apkUrl) }
                .getOrElse { throw IllegalArgumentException("quality update artifact URL is invalid", it) }
            require(!uri.host.isNullOrBlank()) {
                "quality update artifact URL must include a host"
            }
            val httpLoopback = uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")
            require(uri.scheme == "https" || httpLoopback) {
                "quality update artifact URL must use HTTPS or loopback HTTP"
            }
            require(uri.userInfo == null && uri.fragment == null) {
                "quality update artifact URL cannot contain credentials or a fragment"
            }
            QualityUpdateArtifact(
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = apkUrl,
                apkSha256 = apkSha256,
            )
        }
        require(updateArtifact == null || updateScenario == QualityUpdateScenario.AVAILABLE_STABLE) {
            "quality update artifact requires the available_stable scenario"
        }
        val systemCapabilities = payload.optJSONArray("system_capabilities")?.let { values ->
            buildSet {
                for (index in 0 until values.length()) {
                    val wireValue = values.optString(index).trim()
                    require(wireValue.isNotEmpty()) {
                        "quality system capability must be a non-empty string"
                    }
                    add(
                        requireNotNull(QualitySystemCapability.fromWireValue(wireValue)) {
                            "unsupported quality system capability: $wireValue"
                        }
                    )
                }
            }
        }.orEmpty()
        val pendingDeletionUndoWindowMillis = if (
            payload.has("pending_deletion_undo_window_ms")
        ) {
            payload.getLong("pending_deletion_undo_window_ms")
        } else {
            null
        }
        require(
            pendingDeletionUndoWindowMillis == null ||
                pendingDeletionUndoWindowMillis in 5_000L..120_000L,
        ) {
            "pending deletion undo window must be between 5000 and 120000 ms"
        }
        val faultsJson = payload.optJSONObject("faults")
        val delay = faultsJson?.takeIf { it.has("message_load_delay_ms") }
            ?.getInt("message_load_delay_ms")
        require(delay == null || delay in 0..30_000) {
            "message load delay must be between 0 and 30000 ms"
        }
        val pageDelay = faultsJson?.takeIf { it.has("message_page_load_delay_ms") }
            ?.getInt("message_page_load_delay_ms")
        require(pageDelay == null || pageDelay in 0..30_000) {
            "message page load delay must be between 0 and 30000 ms"
        }
        val refreshDelay = faultsJson?.takeIf { it.has("message_refresh_delay_ms") }
            ?.getInt("message_refresh_delay_ms")
        require(refreshDelay == null || refreshDelay in 0..30_000) {
            "message refresh delay must be between 0 and 30000 ms"
        }
        val refreshPresentationDelay = faultsJson
            ?.takeIf { it.has("message_refresh_presentation_delay_ms") }
            ?.getInt("message_refresh_presentation_delay_ms")
        require(refreshPresentationDelay == null || refreshPresentationDelay in 0..30_000) {
            "message refresh presentation delay must be between 0 and 30000 ms"
        }
        val searchDelay = faultsJson?.takeIf { it.has("message_search_delay_ms") }
            ?.getInt("message_search_delay_ms")
        require(searchDelay == null || searchDelay in 0..30_000) {
            "message search delay must be between 0 and 30000 ms"
        }
        return QualitySessionDescriptor(
            schemaVersion = schemaVersion,
            sessionId = sessionId,
            fixture = fixture,
            faults = QualityFaults(
                failLocalStoreInitialization = faultsJson?.optBoolean(
                    "fail_local_store_initialization",
                    false,
                ) ?: false,
                messageLoadDelayMs = delay,
                messagePageLoadDelayMs = pageDelay,
                messageRefreshDelayMs = refreshDelay,
                messageRefreshPresentationDelayMs = refreshPresentationDelay,
                messageSearchDelayMs = searchDelay,
                failMessageLoad = faultsJson?.optBoolean("fail_message_load", false) ?: false,
                failMessagePageLoadOnce = faultsJson?.optBoolean(
                    "fail_message_page_load_once",
                    false,
                ) ?: false,
                failMessageSearchOnce = faultsJson?.optBoolean(
                    "fail_message_search_once",
                    false,
                ) ?: false,
                failGatewaySwitchValidationOnce = faultsJson?.optBoolean(
                    "fail_gateway_switch_validation_once",
                    false,
                ) ?: false,
                failGatewaySwitchCommitOnce = faultsJson?.optBoolean(
                    "fail_gateway_switch_commit_once",
                    false,
                ) ?: false,
                pauseGatewaySwitchBeforeCommit = faultsJson?.optBoolean(
                    "pause_gateway_switch_before_commit",
                    false,
                ) ?: false,
                failGatewayPostCommitSyncOnce = faultsJson?.optBoolean(
                    "fail_gateway_post_commit_sync_once",
                    false,
                ) ?: false,
                failNotificationKeyPersistenceOnce = faultsJson?.optBoolean(
                    "fail_notification_key_persistence_once",
                    false,
                ) ?: false,
                failChannelSubscriptionPersistenceOnce = faultsJson?.optBoolean(
                    "fail_channel_subscription_persistence_once",
                    false,
                ) ?: false,
                failTransportSelectionPersistenceOnce = faultsJson?.optBoolean(
                    "fail_transport_selection_persistence_once",
                    false,
                ) ?: false,
            ),
            messageRefreshScenario = refreshScenario,
            eventCloseScenario = eventCloseScenario,
            channelMutationScenario = channelMutationScenario,
            expectedChannelMutationGatewayUrl = expectedChannelMutationGatewayUrl,
            expectedGatewayPreparationUrl = expectedGatewayPreparationUrl,
            transportSwitchScenario = transportSwitchScenario,
            updateScenario = updateScenario,
            updateArtifact = updateArtifact,
            systemCapabilities = systemCapabilities,
            pendingDeletionUndoWindowMillis = pendingDeletionUndoWindowMillis,
        )
    }

    fun encode(session: QualitySessionDescriptor): String {
        val faults = JSONObject()
            .put("fail_local_store_initialization", session.faults.failLocalStoreInitialization)
            .put("fail_message_load", session.faults.failMessageLoad)
            .put("fail_message_page_load_once", session.faults.failMessagePageLoadOnce)
            .put("fail_message_search_once", session.faults.failMessageSearchOnce)
            .put(
                "fail_gateway_switch_validation_once",
                session.faults.failGatewaySwitchValidationOnce,
            )
            .put(
                "fail_gateway_switch_commit_once",
                session.faults.failGatewaySwitchCommitOnce,
            )
            .put(
                "pause_gateway_switch_before_commit",
                session.faults.pauseGatewaySwitchBeforeCommit,
            )
            .put(
                "fail_gateway_post_commit_sync_once",
                session.faults.failGatewayPostCommitSyncOnce,
            )
            .put(
                "fail_notification_key_persistence_once",
                session.faults.failNotificationKeyPersistenceOnce,
            )
            .put(
                "fail_channel_subscription_persistence_once",
                session.faults.failChannelSubscriptionPersistenceOnce,
            )
            .put(
                "fail_transport_selection_persistence_once",
                session.faults.failTransportSelectionPersistenceOnce,
            )
        session.faults.messageLoadDelayMs?.let {
            faults.put("message_load_delay_ms", it)
        }
        session.faults.messagePageLoadDelayMs?.let {
            faults.put("message_page_load_delay_ms", it)
        }
        session.faults.messageRefreshDelayMs?.let {
            faults.put("message_refresh_delay_ms", it)
        }
        session.faults.messageRefreshPresentationDelayMs?.let {
            faults.put("message_refresh_presentation_delay_ms", it)
        }
        session.faults.messageSearchDelayMs?.let {
            faults.put("message_search_delay_ms", it)
        }
        val payload = JSONObject()
            .put("schema_version", session.schemaVersion)
            .put("session_id", session.sessionId)
            .put("fixture", session.fixture.wireValue)
            .put("message_refresh_scenario", session.messageRefreshScenario.wireValue)
            .put("event_close_scenario", session.eventCloseScenario.wireValue)
            .put("channel_mutation_scenario", session.channelMutationScenario.wireValue)
            .put("expected_channel_mutation_gateway_url", session.expectedChannelMutationGatewayUrl)
            .put("expected_gateway_preparation_url", session.expectedGatewayPreparationUrl)
            .put("transport_switch_scenario", session.transportSwitchScenario.wireValue)
            .put("update_scenario", session.updateScenario.wireValue)
            .put(
                "system_capabilities",
                JSONArray(session.systemCapabilities.map(QualitySystemCapability::wireValue)),
            )
            .put("faults", faults)
        session.pendingDeletionUndoWindowMillis?.let {
            payload.put("pending_deletion_undo_window_ms", it)
        }
        session.updateArtifact?.let { artifact ->
            payload.put(
                "update_artifact",
                JSONObject()
                    .put("version_code", artifact.versionCode)
                    .put("version_name", artifact.versionName)
                    .put("apk_url", artifact.apkUrl)
                    .put("apk_sha256", artifact.apkSha256),
            )
        }
        return Base64.getEncoder().encodeToString(payload.toString().toByteArray())
    }

    fun sessionRoot(context: Context): File? {
        return sessionRootFromFilesDir(context.filesDir)
    }

    internal fun sessionRoot(
        context: Context,
        session: QualitySessionDescriptor,
    ): File = sessionRootFromFilesDir(context.filesDir, session)

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
        pendingMessagePageLoadDelay.set(false)
        pendingMessageRefreshDelay.set(false)
        pendingMessageSearchDelay.set(false)
        remainingMessageLoadFailures.set(0)
        remainingMessageSearchFailures.set(0)
        remainingGatewaySwitchValidationFailures.set(0)
        remainingGatewaySwitchCommitFailures.set(0)
        gatewaySwitchPreCommitBarrier?.cancel()
        gatewaySwitchPreCommitBarrier = null
        gatewaySwitchPreCommitPaused.set(false)
        remainingGatewayPostCommitSyncFailures.set(0)
        pendingGatewayPostCommitSyncFailure.set(false)
        remainingNotificationKeyPersistenceFailures.set(0)
        pendingChannelSubscriptionPersistenceFailure.set(false)
        remainingChannelSubscriptionPersistenceFailures.set(0)
        remainingTransportSelectionPersistenceFailures.set(0)
        messageRefreshScenarioAttempts.set(0)
        globalErrorPresentationCount.set(0)
    }
}

class QualityMessageLoadException : IllegalStateException("Injected message list load failure")

class QualityMessagePageLoadException : IllegalStateException("Injected message page load failure")

class QualityMessageSearchException : IllegalStateException("Injected message search failure")

class QualityMessageRefreshException : IllegalStateException("Injected provider refresh failure")

class QualityGatewaySwitchValidationException :
    IllegalStateException("Injected candidate gateway registration failure")

class QualityGatewaySwitchCommitException :
    IllegalStateException("Injected candidate gateway local commit failure")

class QualityGatewayPostCommitSyncException :
    IllegalStateException("Injected post-commit gateway subscription sync failure")

class QualityNotificationKeyPersistenceException :
    IllegalStateException("Injected protected notification key persistence failure")

class QualityChannelSubscriptionPersistenceException :
    IllegalStateException("Injected channel subscription persistence failure")

class QualityTransportSelectionPersistenceException :
    IllegalStateException("Injected notification transport selection persistence failure")
