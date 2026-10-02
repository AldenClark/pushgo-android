package io.ethan.pushgo.data

import android.content.SharedPreferences
import androidx.core.content.edit
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.data.db.AppSettingsDao
import io.ethan.pushgo.data.db.AppSettingsEntity
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.data.model.MessageListSortMode
import io.ethan.pushgo.testing.QualityRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/** A durable switch record; credential values are kept out of normal prefs. */
data class GatewayTransitionSnapshot(
    val address: String?,
    val gatewayToken: String?,
    val fcmToken: String?,
    val deviceKey: String?,
    val candidateAckToken: String?,
)

enum class GatewayTransitionStage {
    PREPARED,
    ADDRESS_WRITTEN,
    GATEWAY_TOKEN_WRITTEN,
    DEVICE_KEY_WRITTEN,
    FCM_TOKEN_WRITTEN,
    ACK_WRITTEN,
    COMMITTED,
}

data class GatewayTransitionJournal(
    val previous: GatewayTransitionSnapshot,
    val candidate: GatewayTransitionSnapshot,
    val stage: GatewayTransitionStage,
    val fcmCleanupPending: Boolean,
    val privateCleanupPending: Boolean,
    /** Null only for a journal written by an older build. */
    val previousChannelType: String? = null,
)

/** Result of the one recovery pass at the application composition boundary. */
enum class GatewayTransitionStartupRecovery {
    NONE,
    ROLLED_BACK,
    COMMITTED,
}

class SettingsRepository(
    private val appSettingsDao: AppSettingsDao,
    private val secretStore: SecureSecretStore,
    private val settingsCache: SharedPreferences,
) {
    private val settingsFlow = appSettingsDao.observe()
    private val fcmTokenState = MutableStateFlow(secretStore.fcmToken())

    val serverAddressFlow: Flow<String?> = settingsFlow
        .map { it?.serverAddress }
        .distinctUntilChanged()
    val messagePageEnabledFlow: Flow<Boolean> =
        settingsFlow
            .map { it?.isMessagePageEnabled ?: getCachedMessagePageEnabled() }
            .distinctUntilChanged()
    val eventPageEnabledFlow: Flow<Boolean> =
        settingsFlow
            .map { it?.isEventPageEnabled ?: getCachedEventPageEnabled() }
            .distinctUntilChanged()
    val thingPageEnabledFlow: Flow<Boolean> =
        settingsFlow
            .map { it?.isThingPageEnabled ?: getCachedThingPageEnabled() }
            .distinctUntilChanged()
    val useFcmChannelFlow: Flow<Boolean> =
        settingsFlow
            .map { it?.useFcmChannel ?: getCachedUseFcmChannel() }
            .distinctUntilChanged()
    val fcmTokenFlow: StateFlow<String?> = fcmTokenState.asStateFlow()
    val updateAutoCheckEnabledFlow: Flow<Boolean> =
        settingsFlow
            .map { it?.updateAutoCheckEnabled ?: getCachedUpdateAutoCheckEnabled() }
            .distinctUntilChanged()
    val updateBetaChannelEnabledFlow: Flow<Boolean> =
        settingsFlow
            .map { it?.updateBetaChannelEnabled ?: getCachedUpdateBetaChannelEnabled() }
            .distinctUntilChanged()

    fun getCachedUseFcmChannel(): Boolean =
        settingsCache.getBoolean(KEY_USE_FCM_CHANNEL, true)

    fun getCachedMessagePageEnabled(): Boolean =
        settingsCache.getBoolean(KEY_MESSAGE_PAGE_ENABLED, true)

    fun getCachedEventPageEnabled(): Boolean =
        settingsCache.getBoolean(KEY_EVENT_PAGE_ENABLED, true)

    fun getCachedThingPageEnabled(): Boolean =
        settingsCache.getBoolean(KEY_THING_PAGE_ENABLED, true)

    fun getCachedUpdateAutoCheckEnabled(): Boolean =
        settingsCache.getBoolean(KEY_UPDATE_AUTO_CHECK_ENABLED, true)

    fun getCachedUpdateBetaChannelEnabled(): Boolean =
        settingsCache.getBoolean(KEY_UPDATE_BETA_CHANNEL_ENABLED, false)

    fun getCachedMessageListSortMode(): MessageListSortMode =
        MessageListSortMode.fromPersistedValue(
            settingsCache.getString(KEY_MESSAGE_LIST_SORT_MODE, MessageListSortMode.TIME_DESC.persistedValue)
        )

    fun getCachedMessageUnreadOnlyFilter(): Boolean =
        settingsCache.getBoolean(KEY_MESSAGE_UNREAD_ONLY_FILTER, false)

    fun getCachedUpdateScheduledCheckIntervalSeconds(): Long =
        settingsCache.getLong(KEY_UPDATE_SCHEDULED_CHECK_INTERVAL_SECONDS, AppConstants.updateCheckIntervalSeconds)

    fun getCachedUpdateImpatientReminderIntervalSeconds(): Long =
        settingsCache.getLong(KEY_UPDATE_IMPATIENT_REMINDER_INTERVAL_SECONDS, AppConstants.updateImpatientIntervalSeconds)

    private fun cacheUseFcmChannel(enabled: Boolean) {
        settingsCache.edit {
            putBoolean(KEY_USE_FCM_CHANNEL, enabled)
        }
    }

    private fun cachePageVisibility(settings: AppSettingsEntity) {
        settingsCache.edit {
            putBoolean(KEY_MESSAGE_PAGE_ENABLED, settings.isMessagePageEnabled)
            putBoolean(KEY_EVENT_PAGE_ENABLED, settings.isEventPageEnabled)
            putBoolean(KEY_THING_PAGE_ENABLED, settings.isThingPageEnabled)
        }
    }

    private fun cacheUpdatePreferences(settings: AppSettingsEntity) {
        settingsCache.edit {
            putBoolean(KEY_UPDATE_AUTO_CHECK_ENABLED, settings.updateAutoCheckEnabled)
            putBoolean(KEY_UPDATE_BETA_CHANNEL_ENABLED, settings.updateBetaChannelEnabled)
        }
    }

    fun setCachedMessageListSortMode(sortMode: MessageListSortMode) {
        settingsCache.edit {
            putString(KEY_MESSAGE_LIST_SORT_MODE, sortMode.persistedValue)
        }
    }

    fun setCachedMessageUnreadOnlyFilter(enabled: Boolean) {
        settingsCache.edit {
            putBoolean(KEY_MESSAGE_UNREAD_ONLY_FILTER, enabled)
        }
    }

    fun setCachedUpdatePolicyIntervals(
        scheduledCheckIntervalSeconds: Long,
        impatientReminderIntervalSeconds: Long,
    ) {
        val normalizedScheduled = scheduledCheckIntervalSeconds.coerceAtLeast(15 * 60L)
        val normalizedImpatient = impatientReminderIntervalSeconds.coerceAtLeast(15 * 60L)
        settingsCache.edit {
            putLong(KEY_UPDATE_SCHEDULED_CHECK_INTERVAL_SECONDS, normalizedScheduled)
            putLong(KEY_UPDATE_IMPATIENT_REMINDER_INTERVAL_SECONDS, normalizedImpatient)
        }
    }

    private fun defaultSettings(): AppSettingsEntity {
        return AppSettingsEntity(
            id = 1,
            serverAddress = null,
            token = null,
            notificationKeyUpdatedAt = null,
            fcmToken = null,
            useFcmChannel = true,
            isMessagePageEnabled = true,
            isEventPageEnabled = true,
            isThingPageEnabled = true,
        )
    }

    private suspend fun loadSettings(): AppSettingsEntity {
        return (appSettingsDao.get() ?: defaultSettings()).also {
            cacheUseFcmChannel(it.useFcmChannel)
            cachePageVisibility(it)
            cacheUpdatePreferences(it)
        }
    }

    private suspend fun updateSettings(update: (AppSettingsEntity) -> AppSettingsEntity) {
        val updated = update(loadSettings())
        appSettingsDao.upsert(updated)
        cacheUseFcmChannel(updated.useFcmChannel)
        cachePageVisibility(updated)
        cacheUpdatePreferences(updated)
    }

    suspend fun setServerAddress(address: String?) {
        updateSettings { it.copy(serverAddress = address) }
    }

    suspend fun getServerAddress(): String? = loadSettings().serverAddress

    suspend fun getGatewayToken(): String? {
        return secretStore.gatewayToken()
    }

    suspend fun setGatewayToken(token: String?) {
        val normalized = token?.trim()?.ifEmpty { null }
        secretStore.setGatewayToken(normalized)
        updateSettings { it.copy(token = null) }
    }

    fun getGatewayAckToken(gatewayUrl: String): String? {
        return secretStore.gatewayAckToken(gatewayUrl.trim())
    }

    fun setGatewayAckToken(gatewayUrl: String, token: String?) {
        secretStore.setGatewayAckToken(gatewayUrl.trim(), token?.trim()?.ifEmpty { null })
    }

    fun peekDeviceKey(): String? {
        return secretStore.deviceKey()
    }

    fun persistDeviceKey(deviceKey: String?) {
        val normalized = deviceKey?.trim()?.ifEmpty { null }
        secretStore.setDeviceKey(normalized)
    }

    suspend fun getDeviceKey(): String? {
        return secretStore.deviceKey()
    }

    suspend fun setDeviceKey(deviceKey: String?) {
        persistDeviceKey(deviceKey)
    }

    suspend fun getNotificationKeyBytes(): ByteArray? =
        secretStore.notificationKeyBytes()

    suspend fun setNotificationKeyBytes(value: ByteArray?) {
        val trimmed = value?.takeIf { it.isNotEmpty() }
        val previous = secretStore.notificationKeyBytes()
        try {
            secretStore.setNotificationKeyBytes(trimmed)
            if (BuildConfig.DEBUG) {
                QualityRuntime.afterNotificationKeySecretPersistence()
            }
            updateSettings { current ->
                if (trimmed == null) {
                    current.copy(notificationKeyUpdatedAt = null)
                } else {
                    current.copy(
                        notificationKeyUpdatedAt = System.currentTimeMillis()
                    )
                }
            }
        } catch (commitError: Throwable) {
            try {
                secretStore.setNotificationKeyBytes(previous)
            } catch (rollbackError: Throwable) {
                throw IllegalStateException(
                    "Notification key commit and protected-store rollback both failed",
                    commitError,
                ).apply { addSuppressed(rollbackError) }
            }
            throw commitError
        }
    }

    suspend fun getNotificationKeyUpdatedAt(): Instant? {
        val millis = loadSettings().notificationKeyUpdatedAt ?: return null
        return Instant.ofEpochMilli(millis)
    }

    suspend fun getKeyEncoding(): KeyEncoding {
        val raw = loadSettings().keyEncoding
        return runCatching { KeyEncoding.valueOf(raw) }.getOrNull() ?: KeyEncoding.BASE64
    }

    suspend fun setKeyEncoding(encoding: KeyEncoding) {
        updateSettings { it.copy(keyEncoding = encoding.name) }
    }

    suspend fun getFcmToken(): String? {
        return secretStore.fcmToken()
    }

    suspend fun setFcmToken(token: String?) {
        val normalized = token?.trim()?.ifEmpty { null }
        secretStore.setFcmToken(normalized)
        fcmTokenState.value = normalized
        updateSettings { it.copy(fcmToken = null) }
    }

    suspend fun getUseFcmChannel(): Boolean = loadSettings().useFcmChannel

    suspend fun getMessagePageEnabled(): Boolean = loadSettings().isMessagePageEnabled

    suspend fun getEventPageEnabled(): Boolean = loadSettings().isEventPageEnabled

    suspend fun getThingPageEnabled(): Boolean = loadSettings().isThingPageEnabled

    suspend fun setUseFcmChannel(enabled: Boolean) {
        updateSettings { it.copy(useFcmChannel = enabled) }
    }

    /**
     * Indicates that the active gateway was committed but one or more
     * post-commit delivery/reconciliation steps still need to be retried.
     *
     * This marker deliberately lives in the session-scoped settings cache:
     * it is not gateway data and must survive a normal process restart while
     * remaining isolated from the production preferences during a quality
     * session.
     */
    fun getGatewayRecoveryPending(): Boolean =
        settingsCache.getBoolean(KEY_GATEWAY_RECOVERY_PENDING, false)

    fun setGatewayRecoveryPending(pending: Boolean) {
        // This flag is the durable hand-off between the committed gateway and
        // the next user-visible recovery attempt. Use a synchronous commit so
        // a process death immediately after the save cannot lose the marker.
        settingsCache.edit(commit = true) {
            if (pending) {
                putBoolean(KEY_GATEWAY_RECOVERY_PENDING, true)
            } else {
                remove(KEY_GATEWAY_RECOVERY_PENDING)
            }
        }
    }

    /** Saves an intent before the first mutable gateway write. */
    fun beginGatewayTransition(journal: GatewayTransitionJournal) {
        val encoded = JSONObject().apply {
            put("previous_gateway_token", journal.previous.gatewayToken)
            put("previous_fcm_token", journal.previous.fcmToken)
            put("previous_device_key", journal.previous.deviceKey)
            put("candidate_gateway_token", journal.candidate.gatewayToken)
            put("candidate_fcm_token", journal.candidate.fcmToken)
            put("candidate_device_key", journal.candidate.deviceKey)
            put("candidate_ack_token", journal.candidate.candidateAckToken)
        }.toString()
        // Write protected values first: a visible journal always has the data
        // required to reconstruct either complete configuration.
        try {
            secretStore.setPendingTransportToken(GATEWAY_TRANSITION_JOURNAL_ID, encoded)
            writeGatewayTransitionMetadata(journal)
        } catch (error: Throwable) {
            // A failed intent write must not leave an orphaned encrypted
            // snapshot or a metadata-only record that poisons the next test or
            // startup recovery attempt.
            runCatching {
                settingsCache.edit().remove(KEY_GATEWAY_TRANSITION_METADATA).commit()
            }.onFailure(error::addSuppressed)
            runCatching {
                secretStore.setPendingTransportToken(GATEWAY_TRANSITION_JOURNAL_ID, null)
            }.onFailure(error::addSuppressed)
            throw error
        }
    }

    fun getGatewayTransitionJournal(): GatewayTransitionJournal? {
        val rawMetadata = settingsCache.getString(KEY_GATEWAY_TRANSITION_METADATA, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val encoded = secretStore.pendingTransportToken(GATEWAY_TRANSITION_JOURNAL_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Gateway transition journal is missing protected data")
        return try {
            val metadata = JSONObject(rawMetadata)
            val values = JSONObject(encoded)
            GatewayTransitionJournal(
                previous = GatewayTransitionSnapshot(
                    address = metadata.stringOrNull("previous_address"),
                    gatewayToken = values.stringOrNull("previous_gateway_token"),
                    fcmToken = values.stringOrNull("previous_fcm_token"),
                    deviceKey = values.stringOrNull("previous_device_key"),
                    candidateAckToken = null,
                ),
                candidate = GatewayTransitionSnapshot(
                    address = metadata.stringOrNull("candidate_address"),
                    gatewayToken = values.stringOrNull("candidate_gateway_token"),
                    fcmToken = values.stringOrNull("candidate_fcm_token"),
                    deviceKey = values.stringOrNull("candidate_device_key"),
                    candidateAckToken = values.stringOrNull("candidate_ack_token"),
                ),
                stage = GatewayTransitionStage.valueOf(metadata.getString("stage")),
                fcmCleanupPending = metadata.getBoolean("fcm_cleanup_pending"),
                privateCleanupPending = metadata.getBoolean("private_cleanup_pending"),
                previousChannelType = metadata.optString("previous_channel_type")
                    .trim()
                    .ifEmpty { null },
            )
        } catch (error: Exception) {
            throw IllegalStateException("Gateway transition journal is corrupt", error)
        }
    }

    /**
     * Resolves an interrupted cross-store gateway write before any consumer
     * can read the settings.  A committed record remains authoritative and is
     * handed to the post-commit reconciliation path; every earlier stage is
     * restored to the complete previous snapshot.
     */
    internal suspend fun recoverGatewayTransitionAtStartup(): GatewayTransitionStartupRecovery {
        val journal = getGatewayTransitionJournal() ?: return GatewayTransitionStartupRecovery.NONE
        if (journal.stage == GatewayTransitionStage.COMMITTED) {
            setGatewayRecoveryPending(true)
            return GatewayTransitionStartupRecovery.COMMITTED
        }
        withContext(NonCancellable) {
            rollbackGatewayTransition(journal)
        }
        return GatewayTransitionStartupRecovery.ROLLED_BACK
    }

    fun advanceGatewayTransition(stage: GatewayTransitionStage) {
        val current = getGatewayTransitionJournal()
            ?: throw IllegalStateException("Gateway transition journal disappeared during commit")
        require(stage.ordinal >= current.stage.ordinal) { "Gateway transition stage cannot move backwards" }
        writeGatewayTransitionMetadata(current.copy(stage = stage))
    }

    fun markGatewayTransitionRouteCleanup(route: String, pending: Boolean) {
        val current = getGatewayTransitionJournal() ?: return
        val updated = when (route) {
            "fcm" -> current.copy(fcmCleanupPending = pending)
            "private" -> current.copy(privateCleanupPending = pending)
            else -> throw IllegalArgumentException("Unknown gateway cleanup route: $route")
        }
        writeGatewayTransitionMetadata(updated)
    }

    /**
     * Persists both old-route cleanup flags in one metadata commit.  A route
     * type mismatch can move the obligation from FCM to private; writing those
     * bits separately would create a crash window in which the journal falsely
     * claims that neither route remains pending.
     */
    fun markGatewayTransitionRouteCleanup(
        fcmPending: Boolean,
        privatePending: Boolean,
    ) {
        val current = getGatewayTransitionJournal() ?: return
        writeGatewayTransitionMetadata(
            current.copy(
                fcmCleanupPending = fcmPending,
                privateCleanupPending = privatePending,
            ),
        )
    }

    fun clearGatewayTransitionJournal() {
        val removed = settingsCache.edit()
            .remove(KEY_GATEWAY_TRANSITION_METADATA)
            .commit()
        check(removed) { "Failed to clear gateway transition journal" }
        secretStore.setPendingTransportToken(GATEWAY_TRANSITION_JOURNAL_ID, null)
    }

    /** Restores every active gateway field from a pre-commit journal. */
    internal suspend fun rollbackGatewayTransition(journal: GatewayTransitionJournal) {
        val failures = mutableListOf<Throwable>()
        suspend fun restore(action: suspend () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                failures += error
            }
        }
        restore { setServerAddress(journal.previous.address) }
        restore { setGatewayToken(journal.previous.gatewayToken) }
        restore { setFcmToken(journal.previous.fcmToken) }
        restore { setDeviceKey(journal.previous.deviceKey) }
        restore {
            setGatewayAckToken(
                journal.candidate.address.orEmpty(),
                journal.candidate.candidateAckToken,
            )
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Gateway transition rollback was incomplete")
                .also { aggregate -> failures.forEach(aggregate::addSuppressed) }
        }
        clearGatewayTransitionJournal()
        setGatewayRecoveryPending(false)
    }

    private fun writeGatewayTransitionMetadata(journal: GatewayTransitionJournal) {
        val encoded = JSONObject().apply {
            put("previous_address", journal.previous.address)
            put("candidate_address", journal.candidate.address)
            put("stage", journal.stage.name)
            put("fcm_cleanup_pending", journal.fcmCleanupPending)
            put("private_cleanup_pending", journal.privateCleanupPending)
            put("previous_channel_type", journal.previousChannelType)
        }.toString()
        val committed = settingsCache.edit()
            .putString(KEY_GATEWAY_TRANSITION_METADATA, encoded)
            .commit()
        check(committed) { "Failed to persist gateway transition journal" }
    }

    suspend fun setMessagePageEnabled(enabled: Boolean) {
        updateSettings { it.copy(isMessagePageEnabled = enabled) }
    }

    suspend fun setEventPageEnabled(enabled: Boolean) {
        updateSettings { it.copy(isEventPageEnabled = enabled) }
    }

    suspend fun setThingPageEnabled(enabled: Boolean) {
        updateSettings { it.copy(isThingPageEnabled = enabled) }
    }

    suspend fun getUpdateAutoCheckEnabled(): Boolean = loadSettings().updateAutoCheckEnabled

    suspend fun setUpdateAutoCheckEnabled(enabled: Boolean) {
        updateSettings { it.copy(updateAutoCheckEnabled = enabled) }
    }

    suspend fun getUpdateBetaChannelEnabled(): Boolean = loadSettings().updateBetaChannelEnabled

    suspend fun setUpdateBetaChannelEnabled(enabled: Boolean) {
        updateSettings { current ->
            if (current.updateBetaChannelEnabled == enabled) {
                current
            } else {
                current.copy(
                    updateBetaChannelEnabled = enabled,
                    updatePromptCooldownUntil = null,
                    updatePromptDismissCount = 0,
                )
            }
        }
    }

    suspend fun getUpdateSkippedVersionCode(): Int? = loadSettings().updateSkippedVersionCode

    suspend fun setUpdateSkippedVersionCode(versionCode: Int?) {
        updateSettings { current ->
            current.copy(updateSkippedVersionCode = versionCode)
        }
    }

    suspend fun getUpdateLastPromptedVersionCode(): Int? = loadSettings().updateLastPromptedVersionCode

    suspend fun getUpdatePromptCooldownUntil(): Instant? {
        val millis = loadSettings().updatePromptCooldownUntil ?: return null
        return Instant.ofEpochMilli(millis)
    }

    suspend fun getUpdatePromptDismissCount(): Int = loadSettings().updatePromptDismissCount

    suspend fun recordUpdatePromptDisplayed(versionCode: Int, nextAllowedPromptAtMillis: Long, dismissCount: Int) {
        updateSettings { current ->
            current.copy(
                updateLastPromptedVersionCode = versionCode,
                updatePromptCooldownUntil = nextAllowedPromptAtMillis,
                updatePromptDismissCount = dismissCount,
            )
        }
    }

    suspend fun recordUpdateReminderShown(versionCode: Int, nextAllowedPromptAtMillis: Long) {
        updateSettings { current ->
            current.copy(
                updateLastPromptedVersionCode = versionCode,
                updatePromptCooldownUntil = nextAllowedPromptAtMillis,
                updatePromptDismissCount = if (current.updateLastPromptedVersionCode == versionCode) {
                    current.updatePromptDismissCount
                } else {
                    0
                },
            )
        }
    }

    suspend fun clearUpdatePromptCooldown() {
        updateSettings { current ->
            current.copy(
                updatePromptCooldownUntil = null,
                updatePromptDismissCount = 0,
            )
        }
    }

    suspend fun clearUpdateSkipAndCooldown() {
        updateSettings { current ->
            current.copy(
                updateSkippedVersionCode = null,
                updatePromptCooldownUntil = null,
                updatePromptDismissCount = 0,
            )
        }
    }

    suspend fun getUpdateLastCheckAt(): Instant? {
        val millis = loadSettings().updateLastCheckAt ?: return null
        return Instant.ofEpochMilli(millis)
    }

    suspend fun setUpdateLastCheckAt(millis: Long) {
        updateSettings { current ->
            current.copy(updateLastCheckAt = millis)
        }
    }

    suspend fun reenablePageForEntity(entityType: String) {
        when (entityType.trim().lowercase()) {
            "message" -> setMessagePageEnabled(true)
            "event" -> setEventPageEnabled(true)
            "thing" -> setThingPageEnabled(true)
        }
    }

    suspend fun resetForAutomation(defaultServerAddress: String?) {
        // Remove the ordinary metadata before clearing protected values.  If a
        // test process is interrupted during reset, the next run must not see
        // a transition record whose encrypted snapshot was already erased.
        check(
            settingsCache.edit()
                .remove(KEY_GATEWAY_TRANSITION_METADATA)
                .remove(KEY_GATEWAY_RECOVERY_PENDING)
                .commit()
        ) { "Failed to clear gateway automation metadata" }
        secretStore.clearAll()
        appSettingsDao.deleteAll()
        val normalizedAddress = defaultServerAddress?.trim()?.ifEmpty { null }
        val defaults = defaultSettings().copy(
            serverAddress = normalizedAddress,
            useFcmChannel = false,
        )
        appSettingsDao.upsert(defaults)
        cacheUseFcmChannel(defaults.useFcmChannel)
        cachePageVisibility(defaults)
        cacheUpdatePreferences(defaults)
        setGatewayRecoveryPending(false)
    }

    companion object {
        private const val KEY_USE_FCM_CHANNEL = "use_fcm_channel"
        private const val KEY_MESSAGE_PAGE_ENABLED = "message_page_enabled"
        private const val KEY_EVENT_PAGE_ENABLED = "event_page_enabled"
        private const val KEY_THING_PAGE_ENABLED = "thing_page_enabled"
        private const val KEY_UPDATE_AUTO_CHECK_ENABLED = "update_auto_check_enabled"
        private const val KEY_UPDATE_BETA_CHANNEL_ENABLED = "update_beta_channel_enabled"
        private const val KEY_UPDATE_SCHEDULED_CHECK_INTERVAL_SECONDS = "update_scheduled_check_interval_seconds"
        private const val KEY_UPDATE_IMPATIENT_REMINDER_INTERVAL_SECONDS = "update_impatient_reminder_interval_seconds"
        private const val KEY_MESSAGE_LIST_SORT_MODE = "message_list_sort_mode"
        private const val KEY_MESSAGE_UNREAD_ONLY_FILTER = "message_unread_only_filter"
        private const val KEY_GATEWAY_RECOVERY_PENDING = "gateway_recovery_pending"
        private const val KEY_GATEWAY_TRANSITION_METADATA = "gateway_transition_metadata"
        private const val GATEWAY_TRANSITION_JOURNAL_ID = "gateway-transition-journal-v1"
    }
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).trim().ifEmpty { null }
