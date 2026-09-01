package io.ethan.pushgo.ui.viewmodel

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppConstants
import io.ethan.pushgo.data.ChannelIdException
import io.ethan.pushgo.data.ChannelIdValidator
import io.ethan.pushgo.data.ChannelNameException
import io.ethan.pushgo.data.ChannelNameValidator
import io.ethan.pushgo.data.ChannelPasswordException
import io.ethan.pushgo.data.ChannelPasswordValidator
import io.ethan.pushgo.data.ChannelSubscriptionException
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.NotificationKeyValidationException
import io.ethan.pushgo.data.NotificationKeyValidator
import io.ethan.pushgo.data.PushTokenProvider
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.TransportSwitcher
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.EncryptedMessageRecoveryService
import io.ethan.pushgo.notifications.PrivateChannelClient
import io.ethan.pushgo.notifications.PrivateChannelServiceManager
import io.ethan.pushgo.testing.QualityChannelMutationScenario
import io.ethan.pushgo.testing.QualityRuntime
import io.ethan.pushgo.update.UpdateCandidate
import io.ethan.pushgo.update.UpdateCheckScheduler
import io.ethan.pushgo.update.UpdateInstallStartResult
import io.ethan.pushgo.update.UpdateInstallProgressStage
import io.ethan.pushgo.update.UpdateManager
import io.ethan.pushgo.update.UpdateInstallUiEvents
import io.ethan.pushgo.util.FcmSupport
import io.ethan.pushgo.util.UrlValidators
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit


enum class ClearOption {
    ALL,
    READ,
    READ_7,
    READ_30,
    ALL_7,
    ALL_30,
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val channelRepository: ChannelSubscriptionRepository,
    private val messageRepository: MessageRepository,
    private val messageStateCoordinator: MessageStateCoordinator,
    private val privateChannelClient: PrivateChannelClient,
    private val updateManager: UpdateManager,
    private val pushTokenProvider: PushTokenProvider,
    private val fcmSupportChecker: (Context) -> Boolean = FcmSupport::isAvailable,
    private val gatewayPrivateChannelEnabledFetcher: suspend () -> Boolean? = {
        privateChannelClient.gatewayPrivateChannelEnabled()
    },
    private val transportSwitcher: TransportSwitcher,
) : ViewModel() {
    companion object {
        private const val TAG = "SettingsViewModel"
        private const val FCM_TOKEN_MAX_ATTEMPTS = 3
        private const val FCM_TOKEN_RETRY_BASE_DELAY_MS = 1_500L
    }

    private enum class GatewayRecoveryStatus {
        READY,
        SUBSCRIPTION_SYNC_PENDING,
        PENDING,
    }

    var gatewayAddress by mutableStateOf("")
        private set
    var savedGatewayAddress by mutableStateOf("")
        private set
    var gatewayToken by mutableStateOf("")
        private set
    private var savedGatewayToken = ""
    var gatewayErrorMessage by mutableStateOf<UiMessage?>(null)
        private set
    var channelEntryErrorMessage by mutableStateOf<UiMessage?>(null)
        private set

    var deviceToken by mutableStateOf<String?>(null)
        private set
    var useFcmChannel by mutableStateOf(true)
        private set
    var isFcmSupported by mutableStateOf(true)
    var gatewayPrivateChannelEnabled by mutableStateOf<Boolean?>(null)
        private set
    var isChannelModeLoaded by mutableStateOf(false)
        private set
    var privateTransportStatus by mutableStateOf("未连接")
        private set
    var isSwitchingTransport by mutableStateOf(false)
        private set
    var transportErrorMessage by mutableStateOf<UiMessage?>(null)
        private set

    var decryptionKeyInput by mutableStateOf("")
        private set
    var keyEncoding by mutableStateOf(KeyEncoding.BASE64)
        private set
    var decryptionUpdatedAt by mutableStateOf<Instant?>(null)
        private set
    var isDecryptionConfigured by mutableStateOf(false)
        private set
    var decryptionErrorMessage by mutableStateOf<UiMessage?>(null)
        private set
    private var hasEditedDecryptionKeyInput = false

    var isMessagePageEnabled by mutableStateOf(true)
        private set
    var isEventPageEnabled by mutableStateOf(true)
        private set
    var isThingPageEnabled by mutableStateOf(true)
        private set
    var updateAutoCheckEnabled by mutableStateOf(true)
        private set
    var updateBetaChannelEnabled by mutableStateOf(false)
        private set
    var availableUpdate by mutableStateOf<UpdateCandidate?>(null)
        private set
    var updateSuppressedBySkip by mutableStateOf(false)
        private set
    var updateSuppressedByCooldown by mutableStateOf(false)
        private set

    var channelSubscriptions by mutableStateOf<List<ChannelSubscription>>(emptyList())
        private set
    var channelExists by mutableStateOf<Boolean?>(null)
        private set
    var channelExistsName by mutableStateOf<String?>(null)
        private set
    var isCheckingChannel by mutableStateOf(false)
        private set
    var isSavingChannel by mutableStateOf(false)
        private set
    var isRemovingChannel by mutableStateOf(false)
        private set
    var isRenamingChannel by mutableStateOf(false)
        private set
    var channelRenameErrorMessage by mutableStateOf<UiMessage?>(null)
        private set

    var isSavingGateway by mutableStateOf(false)
        private set
    var isSavingDecryption by mutableStateOf(false)
        private set
    var isClearing by mutableStateOf(false)
        private set
    var isCheckingUpdates by mutableStateOf(false)
        private set
    var isInstallingUpdate by mutableStateOf(false)
        private set
    var updateInstallProgressMessage by mutableStateOf<UiMessage?>(null)
        private set
    var shouldShowInstallPermissionDialog by mutableStateOf(false)
        private set
    var pendingManualInstallApkPath by mutableStateOf<String?>(null)
        private set
    var shouldShowInstallBlockedDialog by mutableStateOf(false)
        private set
    var installBlockedDetail by mutableStateOf<String?>(null)
        private set
    var blockedInstallApkPath by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<UiMessage?>(null)
        private set
    var successMessage by mutableStateOf<UiMessage?>(null)
        private set
    var shouldShowPrivateChannelWhitelistDialog by mutableStateOf(false)
        private set

    val uiState: StateFlow<SettingsUiState> = snapshotFlow { buildUiState() }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = buildUiState(),
        )

    private var hasLoadedGatewayAddress = false
    init {
        viewModelScope.launch {
            settingsRepository.serverAddressFlow.collect { value ->
                savedGatewayAddress = value ?: AppConstants.defaultServerAddress
                if (!hasLoadedGatewayAddress) {
                    gatewayAddress = savedGatewayAddress
                    hasLoadedGatewayAddress = true
                }
            }
        }
        viewModelScope.launch {
            savedGatewayToken = settingsRepository.getGatewayToken().orEmpty()
            gatewayToken = savedGatewayToken
            val initialUseFcm = settingsRepository.getUseFcmChannel()
            useFcmChannel = initialUseFcm
            isFcmSupported = true
            gatewayPrivateChannelEnabled = gatewayPrivateChannelEnabledFetcher()
            val currentKey = settingsRepository.getNotificationKeyBytes()
            isDecryptionConfigured = currentKey?.isNotEmpty() == true
            decryptionUpdatedAt = settingsRepository.getNotificationKeyUpdatedAt()
            keyEncoding = settingsRepository.getKeyEncoding()
            updateAutoCheckEnabled = settingsRepository.getUpdateAutoCheckEnabled()
            updateBetaChannelEnabled = settingsRepository.getUpdateBetaChannelEnabled()
            isChannelModeLoaded = true
        }
        viewModelScope.launch {
            settingsRepository.fcmTokenFlow.collect { token ->
                deviceToken = token
            }
        }
        viewModelScope.launch {
            settingsRepository.useFcmChannelFlow
                .combine(privateChannelClient.connectionSnapshotFlow) { useFcm, snapshot ->
                    useFcm to snapshot
                }
                .collect { (useFcm, snapshot) ->
                    useFcmChannel = useFcm
                    privateTransportStatus = privateChannelClient.summarizeConnectionStatus(
                        snapshot = snapshot,
                        privateModeEnabled = !useFcm,
                    )
                }
        }
        viewModelScope.launch {
            settingsRepository.messagePageEnabledFlow.collect { isMessagePageEnabled = it }
        }
        viewModelScope.launch {
            settingsRepository.eventPageEnabledFlow.collect { isEventPageEnabled = it }
        }
        viewModelScope.launch {
            settingsRepository.thingPageEnabledFlow.collect { isThingPageEnabled = it }
        }
        viewModelScope.launch {
            settingsRepository.updateAutoCheckEnabledFlow.collect { updateAutoCheckEnabled = it }
        }
        viewModelScope.launch {
            settingsRepository.updateBetaChannelEnabledFlow.collect { updateBetaChannelEnabled = it }
        }

        viewModelScope.launch {
            refreshChannelSubscriptions()
        }
        viewModelScope.launch {
            UpdateInstallUiEvents.blockedInstallEvents.collect { event ->
                installBlockedDetail = event.detail
                blockedInstallApkPath = event.apkPath
                shouldShowInstallBlockedDialog = true
                isInstallingUpdate = false
                updateInstallProgressMessage = null
            }
        }
    }

    fun refreshUpdateState(manual: Boolean = false) {
        if (isCheckingUpdates) return
        viewModelScope.launch {
            isCheckingUpdates = true
            try {
                val evaluation = updateManager.evaluate(manual = manual)
                availableUpdate = evaluation.visibleCandidate
                updateSuppressedBySkip = evaluation.suppressedBySkip
                updateSuppressedByCooldown = evaluation.suppressedByCooldown
                val failure = evaluation.failureMessage
                if (!failure.isNullOrBlank()) {
                    errorMessage = TextMessage(failure)
                    return@launch
                }
                if (manual) {
                    if (evaluation.visibleCandidate != null) {
                        successMessage = ResMessage(
                            R.string.message_update_available,
                            listOf(evaluation.visibleCandidate.versionName),
                        )
                    } else {
                        successMessage = ResMessage(R.string.message_update_no_new_version)
                    }
                }
            } finally {
                isCheckingUpdates = false
            }
        }
    }

    fun updateAutoCheckEnabled(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUpdateAutoCheckEnabled(enabled)
            updateAutoCheckEnabled = enabled
            UpdateCheckScheduler.refreshSchedule(context)
            if (enabled) {
                UpdateCheckScheduler.enqueueImmediateProbe(context)
            }
        }
    }

    fun updateBetaChannelEnabled(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUpdateBetaChannelEnabled(enabled)
            updateManager.resetPromptCooldown()
            updateBetaChannelEnabled = enabled
            UpdateCheckScheduler.refreshSchedule(context)
            refreshUpdateState(manual = false)
            if (enabled) {
                successMessage = ResMessage(R.string.message_update_beta_enabled)
            } else {
                successMessage = ResMessage(R.string.message_update_beta_disabled)
            }
        }
    }

    fun installAvailableUpdate() {
        val candidate = availableUpdate ?: run {
            errorMessage = ResMessage(R.string.error_update_no_candidate)
            return
        }
        if (isInstallingUpdate) return
        viewModelScope.launch {
            isInstallingUpdate = true
            pendingManualInstallApkPath = null
            updateInstallProgressMessage = ResMessage(R.string.label_update_install_status_downloading)
            try {
                when (
                    val result = updateManager.install(candidate) { stage ->
                        viewModelScope.launch {
                            updateInstallProgressMessage = when (stage) {
                                UpdateInstallProgressStage.DOWNLOADING_PACKAGE -> {
                                    ResMessage(R.string.label_update_install_status_downloading)
                                }
                                UpdateInstallProgressStage.VERIFYING_PACKAGE -> {
                                    ResMessage(R.string.label_update_install_status_verifying)
                                }
                                UpdateInstallProgressStage.PREPARING_INSTALL -> {
                                    ResMessage(R.string.label_update_install_status_preparing)
                                }
                                UpdateInstallProgressStage.HANDOFF_TO_SYSTEM -> {
                                    ResMessage(R.string.label_update_install_status_handoff)
                                }
                            }
                        }
                    }
                ) {
                    UpdateInstallStartResult.Started -> {
                        successMessage = ResMessage(R.string.message_update_install_started)
                    }
                    is UpdateInstallStartResult.PermissionRequired -> {
                        pendingManualInstallApkPath = result.apkFilePath
                        shouldShowInstallPermissionDialog = true
                    }
                    is UpdateInstallStartResult.Failed -> {
                        io.ethan.pushgo.util.SilentSink.w(TAG, "install start failed: ${result.message}")
                        errorMessage = TextMessage(result.message)
                    }
                }
            } finally {
                isInstallingUpdate = false
            }
        }
    }

    fun skipAvailableUpdate() {
        val candidate = availableUpdate ?: run {
            errorMessage = ResMessage(R.string.error_update_no_candidate)
            return
        }
        viewModelScope.launch {
            updateManager.skipVersion(candidate.versionCode)
            availableUpdate = null
            updateSuppressedBySkip = true
            successMessage = ResMessage(
                R.string.message_update_skipped,
                listOf(candidate.versionName),
            )
        }
    }

    fun remindLaterForAvailableUpdate() {
        val candidate = availableUpdate ?: run {
            errorMessage = ResMessage(R.string.error_update_no_candidate)
            return
        }
        viewModelScope.launch {
            updateManager.recordPromptDismissed(candidate.versionCode)
            availableUpdate = null
            updateSuppressedByCooldown = true
            successMessage = ResMessage(R.string.message_update_remind_later_saved)
        }
    }

    private suspend fun enableFcmProvider(
        context: Context,
        errorSink: (UiMessage) -> Unit = { errorMessage = it },
    ): Boolean {
        isFcmSupported = true
        val token = settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: requireFcmToken(context, errorSink)
        if (token == null) {
            io.ethan.pushgo.util.SilentSink.w(TAG, "FCM enabled but token is unavailable now")
            return false
        }
        return runCatching { transportSwitcher.switchToFcm(token) }
            .onSuccess { useFcmChannel = true }
            .onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "FCM transport transition failed: ${error.message}",
                    error,
                )
            }
            .isSuccess
    }

    fun ensurePrivateTransportWhenFcmUnsupported(context: Context) {
        viewModelScope.launch {
            val supported = isFcmSupported(context)
            isFcmSupported = supported
            if (supported || !useFcmChannel) {
                return@launch
            }
            val privateEnabled = gatewayPrivateChannelEnabledFetcher()
            gatewayPrivateChannelEnabled = privateEnabled
            if (privateEnabled == false) {
                errorMessage = ResMessage(R.string.error_private_disabled_and_fcm_unavailable)
                return@launch
            }
            runCatching { transportSwitcher.switchToPrivate() }
                .onSuccess { useFcmChannel = false }
                .onFailure { error ->
                    io.ethan.pushgo.util.SilentSink.w(
                        TAG,
                        "automatic private transport transition failed: ${error.message}",
                        error,
                    )
                    errorMessage = ResMessage(R.string.error_notification_transport_switch_failed)
                }
        }
    }

    fun updateUseFcmChannel(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            if (isSwitchingTransport) return@launch
            isSwitchingTransport = true
            transportErrorMessage = null
            try {
                val previousUseFcmChannel = useFcmChannel
                isFcmSupported = isFcmSupported(context)
                if (!enabled) {
                    val privateEnabledResult = runCatching {
                        gatewayPrivateChannelEnabledFetcher()
                    }
                    if (privateEnabledResult.isFailure) {
                        val failure = checkNotNull(privateEnabledResult.exceptionOrNull())
                        io.ethan.pushgo.util.SilentSink.w(
                            TAG,
                            "gatewayPrivateChannelEnabledFetcher failed before switch: " +
                                failure.message,
                            failure,
                        )
                        transportErrorMessage =
                            ResMessage(R.string.error_notification_transport_switch_failed)
                        return@launch
                    }
                    val privateEnabled = privateEnabledResult.getOrNull()
                    gatewayPrivateChannelEnabled = privateEnabled
                    if (privateEnabled == false) {
                        transportErrorMessage = if (isFcmSupported) {
                            ResMessage(R.string.error_gateway_private_disabled_use_fcm)
                        } else {
                            ResMessage(R.string.error_private_disabled_and_fcm_unavailable)
                        }
                        return@launch
                    }
                }
                if (enabled == useFcmChannel) {
                    if (!enabled || isFcmSupported) {
                        return@launch
                    }
                }
                if (enabled) {
                    if (!isFcmSupported) {
                        transportErrorMessage = ResMessage(R.string.error_fcm_not_supported)
                        return@launch
                    }
                    val token = requireFcmToken(context) { message ->
                        transportErrorMessage = message
                    } ?: return@launch
                    runCatching { transportSwitcher.switchToFcm(token) }
                        .onFailure { failure ->
                            io.ethan.pushgo.util.SilentSink.w(
                                TAG,
                                "FCM transport transition failed: ${failure.message}",
                                failure,
                            )
                            transportErrorMessage =
                                ResMessage(R.string.error_notification_transport_fcm_switch_failed)
                        }
                        .onSuccess { useFcmChannel = true }
                    if (transportErrorMessage != null) return@launch
                    useFcmChannel = true
                } else {
                    val switchResult = runCatching {
                        transportSwitcher.switchToPrivate()
                    }
                    if (switchResult.isFailure) {
                        val failure = checkNotNull(switchResult.exceptionOrNull())
                        io.ethan.pushgo.util.SilentSink.w(
                            TAG,
                            "private transport transition failed: ${failure.message}",
                            failure,
                        )
                        transportErrorMessage =
                            ResMessage(R.string.error_notification_transport_switch_failed)
                        return@launch
                    }
                    useFcmChannel = false
                    if (previousUseFcmChannel) {
                        shouldShowPrivateChannelWhitelistDialog = true
                    }
                }
            } finally {
                isSwitchingTransport = false
            }
        }
    }

    private suspend fun requireFcmToken(
        context: Context,
        errorSink: (UiMessage) -> Unit = { errorMessage = it },
    ): String? {
        isFcmSupported = isFcmSupported(context)
        if (!isFcmSupported) {
            errorSink(ResMessage(R.string.error_fcm_not_supported))
            return null
        }
        return try {
            fetchFcmTokenWithRetry()
        } catch (ex: TimeoutCancellationException) {
            io.ethan.pushgo.util.SilentSink.w(TAG, "FCM token request timed out", ex)
            errorSink(ResMessage(R.string.error_fcm_token_timeout))
            null
        } catch (ex: Exception) {
            io.ethan.pushgo.util.SilentSink.e(TAG, "Unable to get FCM token: ${ex.message}", ex)
            errorSink(ResMessage(classifyFcmTokenFailureRes(ex)))
            null
        }
    }

    private fun isFcmSupported(context: Context): Boolean {
        return fcmSupportChecker(context)
    }

    private fun shouldUseFcm(context: Context): Boolean {
        isFcmSupported = isFcmSupported(context)
        return useFcmChannel && isFcmSupported
    }

    fun channelRemovalUsesProvider(context: Context): Boolean {
        return shouldUseFcm(context.applicationContext)
    }

    private suspend fun fetchFcmTokenWithRetry(): String {
        var lastError: Throwable? = null
        repeat(FCM_TOKEN_MAX_ATTEMPTS) { attempt ->
            try {
                return fetchFcmTokenOnce()
            } catch (ex: Throwable) {
                lastError = ex
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "fetchFcmToken attempt=${attempt + 1}/$FCM_TOKEN_MAX_ATTEMPTS failed: ${ex.message}",
                    ex
                )
                if (!isRetriableFcmTokenError(ex) || attempt == FCM_TOKEN_MAX_ATTEMPTS - 1) {
                    throw ex
                }
                delay((attempt + 1) * FCM_TOKEN_RETRY_BASE_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Unable to get FCM token")
    }

    private fun isRetriableFcmTokenError(error: Throwable): Boolean {
        val message = collectErrorMessages(error)
        return message.contains("SERVICE_NOT_AVAILABLE")
            || message.contains("INTERNAL_SERVER_ERROR")
            || message.contains("TIMEOUT")
    }

    private fun classifyFcmTokenFailureRes(error: Throwable): Int {
        val message = collectErrorMessages(error)
        if (message.contains("SERVICE_NOT_AVAILABLE")
            || message.contains("INTERNAL_SERVER_ERROR")
            || message.contains("TIMEOUT")
            || message.contains("NETWORK")
            || message.contains("CONNECTION")
            || message.contains("UNAVAILABLE")
            || message.contains("HOST")
        ) {
            return R.string.error_fcm_token_network_unavailable
        }

        if (message.contains("DEFAULT FIREBASEAPP")
            || message.contains("NO DEFAULT FIREBASEAPP")
            || message.contains("MISSING GOOGLE APP ID")
            || message.contains("MISSING_INSTANCEID_SERVICE")
            || message.contains("APPLICATION_ID")
            || message.contains("SENDER_ID")
            || message.contains("PROJECT_NOT_PERMITTED")
            || message.contains("API_KEY")
        ) {
            return R.string.error_fcm_token_project_not_configured
        }

        if (message.contains("FIS_AUTH_ERROR")
            || message.contains("AUTHENTICATION")
            || message.contains("AUTH")
            || message.contains("INVALID_SENDER")
            || message.contains("MISMATCH_SENDER_ID")
            || message.contains("PERMISSION_DENIED")
            || message.contains("UNREGISTERED")
        ) {
            return R.string.error_fcm_token_auth_failed
        }

        return R.string.error_unable_to_get_fcm_token
    }

    private fun collectErrorMessages(error: Throwable): String {
        val messages = buildString {
            var cursor: Throwable? = error
            var depth = 0
            while (cursor != null && depth < 4) {
                append(cursor.message.orEmpty())
                append(' ')
                cursor = cursor.cause
                depth += 1
            }
        }
        return messages.uppercase()
    }

    private suspend fun fetchFcmTokenOnce(): String = withTimeout(AppConstants.fcmTokenTimeoutMs) {
        pushTokenProvider.fetchToken(AppConstants.fcmTokenTimeoutMs)
            ?: throw IllegalStateException("Unable to get FCM token")
    }

    private fun buildUiState(): SettingsUiState {
        return SettingsUiState(
            gatewayAddress = gatewayAddress,
            savedGatewayAddress = savedGatewayAddress,
            gatewayToken = gatewayToken,
            deviceToken = deviceToken,
            useFcmChannel = useFcmChannel,
            isFcmSupported = isFcmSupported,
            gatewayPrivateChannelEnabled = gatewayPrivateChannelEnabled,
            isChannelModeLoaded = isChannelModeLoaded,
            privateTransportStatus = privateTransportStatus,
            isSwitchingTransport = isSwitchingTransport,
            transportErrorMessage = transportErrorMessage,
            decryptionKeyInput = decryptionKeyInput,
            keyEncoding = keyEncoding,
            decryptionUpdatedAt = decryptionUpdatedAt,
            isDecryptionConfigured = isDecryptionConfigured,
            isMessagePageEnabled = isMessagePageEnabled,
            isEventPageEnabled = isEventPageEnabled,
            isThingPageEnabled = isThingPageEnabled,
            updateAutoCheckEnabled = updateAutoCheckEnabled,
            updateBetaChannelEnabled = updateBetaChannelEnabled,
            availableUpdate = availableUpdate,
            updateSuppressedBySkip = updateSuppressedBySkip,
            updateSuppressedByCooldown = updateSuppressedByCooldown,
            isSavingGateway = isSavingGateway,
            isSavingDecryption = isSavingDecryption,
            isClearing = isClearing,
            isCheckingUpdates = isCheckingUpdates,
            isInstallingUpdate = isInstallingUpdate,
            updateInstallProgressMessage = updateInstallProgressMessage,
            shouldShowInstallPermissionDialog = shouldShowInstallPermissionDialog,
            pendingManualInstallApkPath = pendingManualInstallApkPath,
            shouldShowInstallBlockedDialog = shouldShowInstallBlockedDialog,
            installBlockedDetail = installBlockedDetail,
            blockedInstallApkPath = blockedInstallApkPath,
            errorMessage = errorMessage,
            successMessage = successMessage,
            shouldShowPrivateChannelWhitelistDialog = shouldShowPrivateChannelWhitelistDialog,
        )
    }

    fun updateGatewayAddress(value: String) {
        gatewayAddress = value
        gatewayErrorMessage = null
    }

    fun beginGatewayEdit() {
        gatewayAddress = savedGatewayAddress.ifBlank { AppConstants.defaultServerAddress }
        gatewayToken = savedGatewayToken
        gatewayErrorMessage = null
    }

    fun cancelGatewayEdit() {
        gatewayAddress = savedGatewayAddress.ifBlank { AppConstants.defaultServerAddress }
        gatewayToken = savedGatewayToken
        gatewayErrorMessage = null
    }

    fun updateGatewayToken(value: String) {
        gatewayToken = value
        gatewayErrorMessage = null
    }

    fun updateDecryptionKeyInput(value: String) {
        decryptionKeyInput = value
        hasEditedDecryptionKeyInput = true
        decryptionErrorMessage = null
    }

    fun updateKeyEncoding(value: KeyEncoding) {
        keyEncoding = value
        decryptionErrorMessage = null
    }

    fun saveGatewayConfig(context: Context) {
        viewModelScope.launch {
            isSavingGateway = true
            gatewayErrorMessage = null
            var gatewayCommitted = false
            try {
                val previousAddress = UrlValidators.normalizeGatewayBaseUrl(
                    settingsRepository.getServerAddress()
                        ?.trim()
                        ?.ifEmpty { null }
                        ?: AppConstants.defaultServerAddress
                ) ?: AppConstants.defaultServerAddress
                val previousToken = settingsRepository.getGatewayToken()
                    ?.trim()
                    ?.ifEmpty { null }
                val previousDeviceKey = settingsRepository.getDeviceKey()
                    ?.trim()
                    ?.ifEmpty { null }
                val rawAddress = gatewayAddress.trim().ifBlank { AppConstants.defaultServerAddress }
                val normalizedAddress = UrlValidators.normalizeGatewayBaseUrl(rawAddress)
                if (normalizedAddress == null) {
                    gatewayErrorMessage = ResMessage(R.string.error_invalid_server_address)
                    return@launch
                }
                val token = gatewayToken.trim().ifBlank { null }
                val oldIdentity = "${previousAddress}|${previousToken.orEmpty()}"
                val newIdentity = "${normalizedAddress}|${token.orEmpty()}"
                if (oldIdentity == newIdentity) {
                    gatewayAddress = normalizedAddress
                    savedGatewayAddress = normalizedAddress
                    gatewayToken = token.orEmpty()
                    savedGatewayToken = gatewayToken
                    successMessage = ResMessage(R.string.message_gateway_saved)
                    return@launch
                }
                val useProviderRoute = shouldUseFcm(context)
                val activeFcmToken = if (useProviderRoute) {
                    settingsRepository.getFcmToken()
                        ?.trim()
                        ?.ifEmpty { null }
                        ?: fetchFcmTokenWithRetry()
                } else {
                    null
                }
                if (BuildConfig.DEBUG) {
                    QualityRuntime.beforeGatewaySwitchValidation()
                }
                val preparedGateway = channelRepository.prepareGatewaySwitch(
                    address = normalizedAddress,
                    gatewayToken = token,
                    providerToken = activeFcmToken,
                    channelType = if (useProviderRoute) "fcm" else "private",
                )
                channelRepository.commitGatewaySwitch(preparedGateway)
                gatewayCommitted = true
                // A successful local commit is the authority boundary for a
                // gateway switch. Everything below is recoverable delivery
                // setup and must never turn a truthful committed switch into
                // a Sheet-level "save failed" result.
                // Keep the durable hand-off armed until every follow-up step
                // finishes. If the process exits between commit and
                // reconciliation, Channels must still offer recovery rather
                // than silently treating the switch as fully settled.
                settingsRepository.setGatewayRecoveryPending(true)
                if (BuildConfig.DEBUG) {
                    // Arm only after the local commit. The Channels screen
                    // performs a normal sync during startup, and that work
                    // must not consume a fault reserved for this post-commit
                    // user journey.
                    QualityRuntime.armGatewayPostCommitSyncFailure()
                }
                gatewayAddress = preparedGateway.address
                savedGatewayAddress = preparedGateway.address
                gatewayToken = preparedGateway.gatewayToken.orEmpty()
                savedGatewayToken = gatewayToken
                if (
                    BuildConfig.DEBUG &&
                    QualityRuntime.currentSession()?.channelMutationScenario ==
                    QualityChannelMutationScenario.ACCEPTED &&
                    QualityRuntime.currentSession()?.faults?.failGatewayPostCommitSyncOnce != true
                ) {
                    gatewayPrivateChannelEnabled = true
                    refreshChannelSubscriptions()
                    settingsRepository.setGatewayRecoveryPending(false)
                    successMessage = ResMessage(R.string.message_gateway_saved)
                    return@launch
                }
                val recoveryStatus = reconcileCommittedGateway(
                    context = context,
                    preferredProviderToken = activeFcmToken,
                    notifyGatewayChange = oldIdentity != newIdentity,
                )
                if (recoveryStatus != GatewayRecoveryStatus.READY) {
                    settingsRepository.setGatewayRecoveryPending(true)
                }
                if (oldIdentity != newIdentity && !previousDeviceKey.isNullOrBlank()) {
                    val previousGatewayAddress = previousAddress
                    val previousGatewayToken = previousToken
                    val previousGatewayDeviceKey = previousDeviceKey
                    viewModelScope.launch {
                        runCatching {
                            channelRepository.cleanupPreviousGatewayDeviceRoute(
                                previousBaseUrl = previousGatewayAddress,
                                previousToken = previousGatewayToken,
                                previousDeviceKey = previousGatewayDeviceKey,
                            )
                        }.onFailure {
                            io.ethan.pushgo.util.SilentSink.w(
                                TAG,
                                "previous gateway device cleanup failed: ${it.message}",
                                it,
                            )
                        }
                    }
                }
                successMessage = ResMessage(
                    when (recoveryStatus) {
                        GatewayRecoveryStatus.READY -> R.string.message_gateway_saved
                        GatewayRecoveryStatus.SUBSCRIPTION_SYNC_PENDING -> {
                            R.string.message_gateway_saved_sync_pending
                        }
                        GatewayRecoveryStatus.PENDING -> {
                            R.string.message_gateway_saved_recovery_pending
                        }
                    }
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                if (gatewayCommitted) {
                    // The durable gateway data was committed before this
                    // failure. Preserve that fact, persist a retry marker,
                    // and close the Sheet with an honest recovery message.
                    settingsRepository.setGatewayRecoveryPending(true)
                    io.ethan.pushgo.util.SilentSink.w(
                        TAG,
                        "gateway committed; post-commit recovery pending: ${ex.message}",
                        ex,
                    )
                    successMessage = ResMessage(R.string.message_gateway_saved_recovery_pending)
                } else {
                    gatewayErrorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
                }
            } finally {
                isSavingGateway = false
            }
        }
    }

    suspend fun refreshChannelSubscriptions() {
        channelSubscriptions = channelRepository.loadSubscriptions()
    }

    suspend fun syncSubscriptionsOnChannelListEntry(context: Context) {
        try {
            reconcileGatewayRecoveryIfNeeded(context)
            if (shouldUseFcm(context)) {
                val token = settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: requireFcmToken(context)
                    ?: return
                channelRepository.syncProviderDeviceToken(token)
                val outcome = channelRepository.syncSubscriptionsIfNeeded(token)
                if (outcome.passwordMismatchChannels.isNotEmpty()) {
                    val sample = outcome.passwordMismatchChannels.take(3).joinToString(", ")
                    val suffix = if (outcome.passwordMismatchChannels.size > 3) ", ..." else ""
                    errorMessage = ResMessage(
                        R.string.error_channel_password_mismatch_removed,
                        listOf(sample + suffix),
                    )
                }
            }
            refreshChannelSubscriptions()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: ChannelSubscriptionException) {
            errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
        } catch (ex: Exception) {
            errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
        }
    }

    /**
     * Restores delivery setup after a gateway was already committed. This is
     * intentionally called from the Channels entry point: it is a user-facing
     * retry, not a hidden retry loop that can obscure a product failure.
     */
    private suspend fun reconcileGatewayRecoveryIfNeeded(context: Context) {
        if (!settingsRepository.getGatewayRecoveryPending()) return
        reconcileCommittedGateway(
            context = context,
            preferredProviderToken = null,
            notifyGatewayChange = true,
        )
    }

    /**
     * Applies the non-transactional work that follows a committed gateway
     * switch. A false result means the active gateway remains authoritative
     * but the user must be able to retry delivery setup from Channels.
     */
    private suspend fun reconcileCommittedGateway(
        context: Context,
        preferredProviderToken: String?,
        notifyGatewayChange: Boolean,
    ): GatewayRecoveryStatus {
        return try {
            val useProviderRoute = shouldUseFcm(context)
            var providerToken = preferredProviderToken
            if (useProviderRoute) {
                providerToken = providerToken?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: requireFcmToken(context) { message ->
                        io.ethan.pushgo.util.SilentSink.w(
                            TAG,
                            "gateway committed; FCM recovery token unavailable: $message",
                        )
                    }
                    ?: return GatewayRecoveryStatus.PENDING
                privateChannelClient.setRuntime(
                    fcmAvailable = true,
                    systemToken = providerToken,
                )
            } else {
                val privateEnabled = gatewayPrivateChannelEnabledFetcher()
                gatewayPrivateChannelEnabled = privateEnabled
                when (privateEnabled) {
                    true -> transportSwitcher.switchToPrivate()
                    false -> {
                        if (!isFcmSupported(context)) return GatewayRecoveryStatus.PENDING
                        if (!enableFcmProvider(context) { message ->
                                io.ethan.pushgo.util.SilentSink.w(
                                    TAG,
                                    "gateway committed; FCM fallback pending: $message",
                                )
                            }
                        ) {
                            return GatewayRecoveryStatus.PENDING
                        }
                        providerToken = settingsRepository.getFcmToken()
                            ?.trim()
                            ?.ifEmpty { null }
                            ?: return GatewayRecoveryStatus.PENDING
                        privateChannelClient.setRuntime(
                            fcmAvailable = true,
                            systemToken = providerToken,
                        )
                    }
                    null -> return GatewayRecoveryStatus.PENDING
                }
            }
            PrivateChannelServiceManager.refresh(context)
            try {
                providerToken?.let { token ->
                    channelRepository.syncProviderDeviceToken(token)
                    channelRepository.syncSubscriptionsIfNeeded(token)
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "gateway committed; subscription sync pending: ${ex.message}",
                    ex,
                )
                settingsRepository.setGatewayRecoveryPending(true)
                return GatewayRecoveryStatus.SUBSCRIPTION_SYNC_PENDING
            }
            if (notifyGatewayChange) {
                privateChannelClient.onGatewayConfigChanged()
            }
            refreshChannelSubscriptions()
            settingsRepository.setGatewayRecoveryPending(false)
            GatewayRecoveryStatus.READY
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            io.ethan.pushgo.util.SilentSink.w(
                TAG,
                "gateway committed; recovery attempt pending: ${ex.message}",
                ex,
            )
            settingsRepository.setGatewayRecoveryPending(true)
            GatewayRecoveryStatus.PENDING
        }
    }

    fun clearChannelExistsHint() {
        channelExists = null
        channelExistsName = null
    }

    suspend fun checkChannelExists(channelId: String) {
        if (isCheckingChannel) return
        isCheckingChannel = true
        try {
            val result = channelRepository.channelExists(channelId)
            channelExists = result.exists
            channelExistsName = result.channelName
        } catch (ex: ChannelIdException) {
            errorMessage = ResMessage(ex.resId)
        } catch (ex: ChannelNameException) {
            errorMessage = ResMessage(ex.resId, ex.args)
        } catch (ex: ChannelSubscriptionException) {
            errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
        } catch (ex: Exception) {
            errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
        } finally {
            isCheckingChannel = false
        }
    }

    suspend fun createChannel(context: Context, alias: String, password: String): Boolean {
        if (isSavingChannel) return false
        isSavingChannel = true
        channelEntryErrorMessage = null
        return try {
            ChannelNameValidator.normalize(alias)
            ChannelPasswordValidator.normalize(password)
            if (shouldUseFcm(context)) {
                val token = settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: requireFcmToken(context) { channelEntryErrorMessage = it }
                    ?: return false
                channelRepository.syncProviderDeviceToken(token)
                val created = channelRepository.createChannel(alias, password, token)
                refreshChannelSubscriptions()
                val messageRes = if (created.created) {
                    R.string.message_channel_created_and_subscribed
                } else {
                    R.string.message_channel_subscribed
                }
                successMessage = ResMessage(messageRes)
                true
            } else {
                val created = privateChannelClient.privateCreateChannel(alias, password)
                if (!created.subscribed || created.channelId.isBlank()) {
                    channelEntryErrorMessage = ResMessage(R.string.error_private_channel_create_failed)
                    return false
                }
                channelRepository.upsertLocalPrivateCredential(
                    rawChannelId = created.channelId,
                    password = password,
                    displayName = created.channelName,
                )
                channelExists = created.channelId.isNotBlank()
                channelExistsName = created.channelName
                refreshChannelSubscriptions()
                val messageRes = if (created.created) {
                    R.string.message_channel_created_and_subscribed
                } else {
                    R.string.message_channel_subscribed
                }
                successMessage = ResMessage(messageRes)
                true
            }
        } catch (ex: ChannelIdException) {
            channelEntryErrorMessage = ResMessage(ex.resId)
            false
        } catch (ex: ChannelNameException) {
            channelEntryErrorMessage = ResMessage(ex.resId, ex.args)
            false
        } catch (ex: ChannelPasswordException) {
            channelEntryErrorMessage = ResMessage(ex.resId)
            false
        } catch (ex: ChannelSubscriptionException) {
            io.ethan.pushgo.util.SilentSink.w(TAG, "createChannel failed (private) message=${ex.message}", ex)
            channelEntryErrorMessage = ex.toUiErrorMessage(R.string.error_private_channel_create_failed)
            false
        } catch (ex: Exception) {
            io.ethan.pushgo.util.SilentSink.e(TAG, "createChannel unexpected failure (private)", ex)
            channelEntryErrorMessage = ex.toUiErrorMessage(R.string.error_private_channel_create_failed)
            false
        } finally {
            isSavingChannel = false
        }
    }

    suspend fun subscribeChannel(context: Context, channelId: String, password: String): Boolean {
        if (isSavingChannel) return false
        isSavingChannel = true
        channelEntryErrorMessage = null
        return try {
            ChannelIdValidator.normalize(channelId)
            ChannelPasswordValidator.normalize(password)
            if (shouldUseFcm(context)) {
                val token = settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: requireFcmToken(context) { channelEntryErrorMessage = it }
                    ?: return false
                channelRepository.syncProviderDeviceToken(token)
                channelRepository.subscribeChannel(channelId, password, token)
                refreshChannelSubscriptions()
                successMessage = ResMessage(R.string.message_channel_subscribed)
                true
            } else {
                val normalizedChannelId = ChannelIdValidator.normalize(channelId)
                val subscribed = privateChannelClient.privateSubscribeChannel(normalizedChannelId, password)
                if (!subscribed) {
                    channelEntryErrorMessage = ResMessage(R.string.error_private_channel_subscribe_failed)
                    return false
                }
                val existsResult = runCatching { channelRepository.channelExists(normalizedChannelId) }.getOrNull()
                channelRepository.upsertLocalPrivateCredential(
                    rawChannelId = normalizedChannelId,
                    password = password,
                    displayName = existsResult?.channelName
                )
                channelExists = true
                channelExistsName = existsResult?.channelName
                refreshChannelSubscriptions()
                successMessage = ResMessage(R.string.message_channel_subscribed)
                true
            }
        } catch (ex: ChannelIdException) {
            channelEntryErrorMessage = ResMessage(ex.resId)
            false
        } catch (ex: ChannelPasswordException) {
            channelEntryErrorMessage = ResMessage(ex.resId)
            false
        } catch (ex: ChannelSubscriptionException) {
            channelEntryErrorMessage = ex.toUiErrorMessage(R.string.error_private_channel_subscribe_failed)
            false
        } catch (ex: Exception) {
            channelEntryErrorMessage = ex.toUiErrorMessage(R.string.error_private_channel_subscribe_failed)
            false
        } finally {
            isSavingChannel = false
        }
    }

    fun clearChannelEntryError() {
        channelEntryErrorMessage = null
    }

    suspend fun renameChannel(channelId: String, alias: String): Boolean {
        if (isRenamingChannel) return false
        isRenamingChannel = true
        channelRenameErrorMessage = null
        try {
            channelRepository.renameChannel(channelId, alias)
            refreshChannelSubscriptions()
            successMessage = ResMessage(R.string.message_channel_renamed)
            return true
        } catch (ex: ChannelIdException) {
            channelRenameErrorMessage = ResMessage(ex.resId)
        } catch (ex: ChannelNameException) {
            channelRenameErrorMessage = ResMessage(ex.resId, ex.args)
        } catch (ex: ChannelSubscriptionException) {
            channelRenameErrorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
        } catch (ex: Exception) {
            channelRenameErrorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
        } finally {
            isRenamingChannel = false
        }
        return false
    }

    fun clearChannelRenameError() {
        channelRenameErrorMessage = null
    }

    suspend fun unsubscribeChannel(context: Context, channelId: String): Boolean {
        if (isRemovingChannel) return false
        isRemovingChannel = true
        try {
            if (shouldUseFcm(context)) {
                val token = settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: requireFcmToken(context)
                    ?: return false
                channelRepository.syncProviderDeviceToken(token)
                channelRepository.unsubscribeChannel(channelId, token)
            } else {
                val normalizedChannelId = ChannelIdValidator.normalize(channelId)
                val unsubscribed = privateChannelClient.privateUnsubscribeChannel(normalizedChannelId)
                if (!unsubscribed) {
                    errorMessage = ResMessage(R.string.error_private_channel_unsubscribe_failed)
                    return false
                }
                channelRepository.softDeleteLocalSubscription(rawChannelId = normalizedChannelId)
            }
            refreshChannelSubscriptions()
            successMessage = ResMessage(R.string.message_channel_unsubscribed)
            return true
        } catch (ex: ChannelIdException) {
            errorMessage = ResMessage(ex.resId)
        } catch (ex: ChannelSubscriptionException) {
            errorMessage = ex.toUiErrorMessage(R.string.error_private_channel_unsubscribe_failed)
        } catch (ex: Exception) {
            errorMessage = ex.toUiErrorMessage(R.string.error_private_channel_unsubscribe_failed)
        } finally {
            isRemovingChannel = false
        }
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun removedLegacyChannelDeletionPath(
        context: Context,
        channelId: String,
        expectedGateway: String,
        expectedUpdatedAt: Long,
        expectedUseProvider: Boolean,
    ) {
        val normalizedChannelId = ChannelIdValidator.normalize(channelId)
        val useProvider = expectedUseProvider
        if (useProvider) {
            val token = withContext(Dispatchers.Main.immediate) {
                settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: requireFcmToken(context)
            } ?: throw ChannelSubscriptionException.local(
                message = "Request failed",
                code = "provider_token_missing",
                category = io.ethan.pushgo.data.GatewayErrorCategory.VALIDATION,
            )
            channelRepository.syncProviderDeviceToken(
                deviceToken = token,
                expectedGatewayUrl = expectedGateway,
            )
            channelRepository.unsubscribeProviderRemote(
                rawChannelId = normalizedChannelId,
                deviceToken = token,
                expectedGatewayUrl = expectedGateway,
            )
            try {
                channelRepository.deleteLocalHistoryAndSubscription(
                    rawChannelId = normalizedChannelId,
                    expectedGatewayUrl = expectedGateway,
                    expectedUpdatedAt = expectedUpdatedAt,
                )
            } catch (localError: Throwable) {
                val currentPassword = loadCompensationPasswordOrThrow(
                    localError = localError,
                    channelId = normalizedChannelId,
                    expectedGateway = expectedGateway,
                )
                if (currentPassword != null) {
                    compensateOrThrow(localError) {
                        channelRepository.restoreProviderSubscriptionRemote(
                            rawChannelId = normalizedChannelId,
                            password = currentPassword,
                            deviceToken = token,
                            expectedGatewayUrl = expectedGateway,
                        )
                    }
                }
                throw localError
            }
        } else {
            val privateGateway = channelRepository.loadGatewayConfig().first
            if (privateGateway.trim().removeSuffix("/") != expectedGateway.trim().removeSuffix("/")) {
                throw gatewayChangedDuringRemoval()
            }
            if (!privateChannelClient.privateUnsubscribeChannel(normalizedChannelId)) {
                throw ChannelSubscriptionException.local(
                    message = "Private channel unsubscribe failed",
                    code = "private_channel_unsubscribe_failed",
                    category = io.ethan.pushgo.data.GatewayErrorCategory.INTERNAL,
                )
            }
            try {
                channelRepository.deleteLocalHistoryAndSubscription(
                    rawChannelId = normalizedChannelId,
                    expectedGatewayUrl = expectedGateway,
                    expectedUpdatedAt = expectedUpdatedAt,
                )
            } catch (localError: Throwable) {
                val currentPassword = loadCompensationPasswordOrThrow(
                    localError = localError,
                    channelId = normalizedChannelId,
                    expectedGateway = expectedGateway,
                )
                if (currentPassword != null) {
                    compensateOrThrow(localError) {
                        val compensationGateway = channelRepository.loadGatewayConfig().first
                        if (compensationGateway.trim().removeSuffix("/") != expectedGateway.trim().removeSuffix("/")) {
                            throw gatewayChangedDuringRemoval()
                        }
                        if (!privateChannelClient.privateSubscribeChannel(normalizedChannelId, currentPassword)) {
                            throw IllegalStateException("private channel compensation returned false")
                        }
                    }
                }
                throw localError
            }
        }
        withContext(Dispatchers.Main.immediate) {
            // Remote unsubscribe and the Room transaction are already committed.
            // A read-side refresh failure must not turn that durable success into a
            // misleading operation failure or briefly resurrect the deleted row.
            channelSubscriptions = channelSubscriptions.filterNot {
                it.channelId.trim() == normalizedChannelId &&
                    it.updatedAt == expectedUpdatedAt
            }
            runCatching { refreshChannelSubscriptions() }
                .onFailure { error ->
                    io.ethan.pushgo.util.SilentSink.w(
                        TAG,
                        "post-commit channel subscription refresh failed",
                        error,
                    )
                }
        }
    }

    fun handleUnsubscribeAndDeleteHistoryCompletion(result: Result<Unit>) {
        viewModelScope.launch {
            runCatching { refreshChannelSubscriptions() }
            if (result.isSuccess) {
                successMessage = ResMessage(R.string.message_channel_unsubscribed)
                return@launch
            }
            val error = result.exceptionOrNull() ?: return@launch
            errorMessage = when (error) {
                is ChannelIdException -> ResMessage(error.resId)
                is ChannelSubscriptionException -> error.toUiErrorMessage(
                    R.string.error_gateway_local_operation_failed
                )
                else -> error.toUiErrorMessage(R.string.error_gateway_local_operation_failed)
            }
        }
    }

    private suspend fun compensateOrThrow(
        localError: Throwable,
        compensate: suspend () -> Unit,
    ) {
        try {
            compensate()
        } catch (compensationError: Throwable) {
            throw ChannelSubscriptionException(
                message = "Channel removal local transaction and remote compensation both failed",
                code = "channel_removal_compensation_failed",
                category = io.ethan.pushgo.data.GatewayErrorCategory.INTERNAL,
                detail = "local=${localError.message}; compensation=${compensationError.message}",
            )
        }
    }

    private suspend fun loadCompensationPasswordOrThrow(
        localError: Throwable,
        channelId: String,
        expectedGateway: String,
    ): String? = try {
        channelRepository.channelPassword(expectedGateway, channelId)
    } catch (credentialReadError: Throwable) {
        throw ChannelSubscriptionException(
            message = "Channel removal compensation state could not be verified",
            code = "channel_removal_compensation_state_unavailable",
            category = io.ethan.pushgo.data.GatewayErrorCategory.INTERNAL,
            detail = "local=${localError.message}; credential_read=${credentialReadError.message}",
        )
    }

    private fun gatewayChangedDuringRemoval(): ChannelSubscriptionException =
        ChannelSubscriptionException.local(
            message = "Gateway changed while channel removal was pending",
            code = "gateway_changed_during_channel_removal",
            category = io.ethan.pushgo.data.GatewayErrorCategory.VALIDATION,
        )

    fun saveDecryptionConfig(onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            isSavingDecryption = true
            decryptionErrorMessage = null
            try {
                val trimmed = decryptionKeyInput.trim()
                if (trimmed.isEmpty()) {
                    if (!hasEditedDecryptionKeyInput && isDecryptionConfigured) {
                        settingsRepository.setKeyEncoding(keyEncoding)
                        decryptionUpdatedAt = settingsRepository.getNotificationKeyUpdatedAt()
                    } else {
                        settingsRepository.setNotificationKeyBytes(null)
                        decryptionUpdatedAt = null
                        isDecryptionConfigured = false
                    }
                    decryptionKeyInput = ""
                    hasEditedDecryptionKeyInput = false
                    successMessage = ResMessage(R.string.message_decryption_saved)
                    onSuccess()
                    return@launch
                }
                val normalized = NotificationKeyValidator.normalizedKeyBytes(
                    input = trimmed,
                    encoding = keyEncoding,
                )
                settingsRepository.setNotificationKeyBytes(normalized)
                settingsRepository.setKeyEncoding(keyEncoding)
                EncryptedMessageRecoveryService(messageRepository).recover(normalized)
                decryptionUpdatedAt = settingsRepository.getNotificationKeyUpdatedAt() ?: Instant.now()
                isDecryptionConfigured = true
                decryptionKeyInput = ""
                hasEditedDecryptionKeyInput = false
                successMessage = ResMessage(R.string.message_decryption_saved)
                onSuccess()
            } catch (ex: NotificationKeyValidationException) {
                decryptionErrorMessage = when (ex) {
                    is NotificationKeyValidationException.InvalidBase64 -> ResMessage(R.string.error_invalid_base64)
                    is NotificationKeyValidationException.InvalidHex -> ResMessage(R.string.error_invalid_hex)
                    is NotificationKeyValidationException.InvalidLength -> ResMessage(R.string.error_invalid_key_length)
                }
            } catch (ex: Exception) {
                decryptionErrorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
            } finally {
                isSavingDecryption = false
            }
        }
    }

    fun clearMessages(option: ClearOption) {
        if (isClearing) return
        viewModelScope.launch {
            isClearing = true
            try {
                val now = Instant.now()
                val filter = resolveClearFilter(option, now)
                val clearedCount = when {
                    filter.cutoff != null -> messageStateCoordinator.deleteMessagesBefore(filter.readState, filter.cutoff)
                    filter.readState == true -> messageStateCoordinator.deleteAllReadMessages()
                    else -> messageStateCoordinator.deleteAllMessages()
                }
                if (clearedCount <= 0) {
                    successMessage = ResMessage(R.string.message_no_messages_to_clear)
                    return@launch
                }
                successMessage = ResMessage(R.string.message_messages_cleared)
            } catch (ex: Exception) {
                errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
            } finally {
                isClearing = false
            }
        }
    }

    @VisibleForTesting
    internal suspend fun refreshChannelUiStateForTesting() {
        useFcmChannel = settingsRepository.getUseFcmChannel()
        deviceToken = settingsRepository.getFcmToken()
        privateTransportStatus = privateChannelClient.summarizeConnectionStatus(
            snapshot = privateChannelClient.readConnectionSnapshot(),
            privateModeEnabled = !useFcmChannel,
        )
        isChannelModeLoaded = true
    }

    @VisibleForTesting
    internal suspend fun cancelScopeForTesting() {
        viewModelScope.coroutineContext[Job]?.cancelAndJoin()
    }

    suspend fun loadAllMessages() = messageRepository.loadAllForExport()

    fun updateMessagePageVisibility(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setMessagePageEnabled(enabled)
        }
    }

    fun updateEventPageVisibility(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setEventPageEnabled(enabled)
        }
    }

    fun updateThingPageVisibility(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setThingPageEnabled(enabled)
        }
    }

    private fun resolveClearFilter(option: ClearOption, now: Instant): ClearFilter {
        return when (option) {
            ClearOption.ALL -> ClearFilter(readState = null, cutoff = null)
            ClearOption.READ -> ClearFilter(readState = true, cutoff = null)
            ClearOption.READ_7 -> ClearFilter(readState = true, cutoff = now.minus(7, ChronoUnit.DAYS).toEpochMilli())
            ClearOption.READ_30 -> ClearFilter(readState = true, cutoff = now.minus(30, ChronoUnit.DAYS).toEpochMilli())
            ClearOption.ALL_7 -> ClearFilter(readState = null, cutoff = now.minus(7, ChronoUnit.DAYS).toEpochMilli())
            ClearOption.ALL_30 -> ClearFilter(readState = null, cutoff = now.minus(30, ChronoUnit.DAYS).toEpochMilli())
        }
    }

    private data class ClearFilter(
        val readState: Boolean?,
        val cutoff: Long?,
    )

    private fun isValidHttpsUrl(raw: String): Boolean {
        return UrlValidators.normalizeGatewayBaseUrl(raw) != null
    }

    fun consumeError() {
        errorMessage = null
    }

    fun consumeSuccess() {
        successMessage = null
    }

    fun consumePrivateChannelWhitelistDialog() {
        shouldShowPrivateChannelWhitelistDialog = false
    }

    fun consumeInstallPermissionDialog() {
        shouldShowInstallPermissionDialog = false
    }

    fun consumePendingManualInstallApkPath() {
        pendingManualInstallApkPath = null
    }

    fun consumeInstallBlockedDialog() {
        shouldShowInstallBlockedDialog = false
    }

    fun consumeBlockedInstallDetail() {
        installBlockedDetail = null
    }

    fun consumeBlockedInstallApkPath() {
        blockedInstallApkPath = null
    }
}
