package io.ethan.pushgo.ui.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppConstants
import io.ethan.pushgo.data.ChannelIdException
import io.ethan.pushgo.data.ChannelIdValidator
import io.ethan.pushgo.data.ChannelNameException
import io.ethan.pushgo.data.ChannelPasswordException
import io.ethan.pushgo.data.ChannelSubscriptionException
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.NotificationKeyValidationException
import io.ethan.pushgo.data.NotificationKeyValidator
import io.ethan.pushgo.data.PushTokenProvider
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.PrivateChannelClient
import io.ethan.pushgo.notifications.PrivateChannelServiceManager
import io.ethan.pushgo.update.UpdateCandidate
import io.ethan.pushgo.update.UpdateCheckScheduler
import io.ethan.pushgo.update.UpdateInstallStartResult
import io.ethan.pushgo.update.UpdateInstallProgressStage
import io.ethan.pushgo.update.UpdateManager
import io.ethan.pushgo.update.UpdateInstallUiEvents
import io.ethan.pushgo.util.FcmSupport
import io.ethan.pushgo.util.UrlValidators
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
) : ViewModel() {
    companion object {
        private const val TAG = "SettingsViewModel"
        private const val FCM_TOKEN_MAX_ATTEMPTS = 3
        private const val FCM_TOKEN_RETRY_BASE_DELAY_MS = 1_500L
    }

    var gatewayAddress by mutableStateOf("")
        private set
    var gatewayToken by mutableStateOf("")
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

    var decryptionKeyInput by mutableStateOf("")
        private set
    var keyEncoding by mutableStateOf(KeyEncoding.BASE64)
        private set
    var decryptionUpdatedAt by mutableStateOf<Instant?>(null)
        private set
    var isDecryptionConfigured by mutableStateOf(false)
        private set

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
                if (!hasLoadedGatewayAddress) {
                    gatewayAddress = value ?: AppConstants.defaultServerAddress
                    hasLoadedGatewayAddress = true
                }
            }
        }
        viewModelScope.launch {
            gatewayToken = settingsRepository.getGatewayToken() ?: ""
            deviceToken = settingsRepository.getFcmToken()
            useFcmChannel = settingsRepository.getUseFcmChannel()
            isFcmSupported = true
            gatewayPrivateChannelEnabled = privateChannelClient.gatewayPrivateChannelEnabled()
            val currentKey = settingsRepository.getNotificationKeyBytes()
            isDecryptionConfigured = currentKey?.isNotEmpty() == true
            decryptionUpdatedAt = settingsRepository.getNotificationKeyUpdatedAt()
            keyEncoding = settingsRepository.getKeyEncoding()
            updateAutoCheckEnabled = settingsRepository.getUpdateAutoCheckEnabled()
            updateBetaChannelEnabled = settingsRepository.getUpdateBetaChannelEnabled()
            isChannelModeLoaded = true
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

    private suspend fun enableFcmProvider(context: Context, keepEnabledWhenTokenMissing: Boolean) {
        settingsRepository.setUseFcmChannel(true)
        isFcmSupported = true

        val cachedToken = settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
        if (cachedToken != null) {
            privateChannelClient.setRuntime(fcmAvailable = true, systemToken = cachedToken)
            runCatching {
                channelRepository.syncProviderDeviceToken(cachedToken)
            }.onFailure {
                io.ethan.pushgo.util.SilentSink.w(TAG, "syncProviderDeviceToken failed with cached token: ${it.message}", it)
            }.onSuccess {
                runCatching {
                    channelRepository.syncSubscriptionsIfNeeded(cachedToken)
                }
            }
        } else {
            // Keep private loop disabled while waiting for token.
            privateChannelClient.setRuntime(fcmAvailable = true, systemToken = null)
        }

        val token = requireFcmToken(context) ?: run {
            if (!keepEnabledWhenTokenMissing) {
                settingsRepository.setUseFcmChannel(false)
                privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
            }
            io.ethan.pushgo.util.SilentSink.w(TAG, "FCM enabled but token is unavailable now")
            return
        }
        runCatching {
            channelRepository.syncProviderDeviceToken(token)
        }.onFailure {
            io.ethan.pushgo.util.SilentSink.w(TAG, "syncProviderDeviceToken failed after token fetch: ${it.message}", it)
        }.onSuccess {
            runCatching {
                channelRepository.syncSubscriptionsIfNeeded(token)
            }
        }
        privateChannelClient.setRuntime(fcmAvailable = true, systemToken = token)
    }

    fun ensurePrivateTransportWhenFcmUnsupported(context: Context) {
        viewModelScope.launch {
            val supported = isFcmSupported(context)
            isFcmSupported = supported
            if (supported || !useFcmChannel) {
                return@launch
            }
            val privateEnabled = privateChannelClient.gatewayPrivateChannelEnabled()
            gatewayPrivateChannelEnabled = privateEnabled
            if (privateEnabled == false) {
                errorMessage = ResMessage(R.string.error_private_disabled_and_fcm_unavailable)
                return@launch
            }
            settingsRepository.setUseFcmChannel(false)
            settingsRepository.setFcmToken(null)
            useFcmChannel = false
            privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
            PrivateChannelServiceManager.refreshForMode(context, false)
        }
    }

    fun updateUseFcmChannel(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            val previousUseFcmChannel = useFcmChannel
            isFcmSupported = isFcmSupported(context)
            if (!enabled) {
                val privateEnabled = privateChannelClient.gatewayPrivateChannelEnabled()
                gatewayPrivateChannelEnabled = privateEnabled
                if (privateEnabled == false) {
                    if (isFcmSupported) {
                        errorMessage = ResMessage(R.string.error_gateway_private_disabled_use_fcm)
                    } else {
                        errorMessage = ResMessage(R.string.error_private_disabled_and_fcm_unavailable)
                    }
                    settingsRepository.setUseFcmChannel(true)
                    useFcmChannel = true
                    enableFcmProvider(context, keepEnabledWhenTokenMissing = true)
                    PrivateChannelServiceManager.refreshForMode(context, true)
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
                    settingsRepository.setUseFcmChannel(false)
                    privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
                    PrivateChannelServiceManager.refreshForMode(context, false)
                    errorMessage = ResMessage(R.string.error_fcm_not_supported)
                    return@launch
                }
                enableFcmProvider(context, keepEnabledWhenTokenMissing = true)
                PrivateChannelServiceManager.refreshForMode(context, true)
            } else {
                val oldToken = settingsRepository.getFcmToken()
                runCatching {
                    privateChannelClient.switchToPrivateAndRetireProvider("fcm", oldToken)
                }.onFailure {
                    io.ethan.pushgo.util.SilentSink.w(TAG, "switchToPrivateAndRetireProvider failed: ${it.message}", it)
                }
                settingsRepository.setUseFcmChannel(false)
                settingsRepository.setFcmToken(null)
                privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
                PrivateChannelServiceManager.refreshForMode(context, false)
                if (previousUseFcmChannel) {
                    shouldShowPrivateChannelWhitelistDialog = true
                }
            }
        }
    }

    private suspend fun requireFcmToken(context: Context): String? {
        isFcmSupported = isFcmSupported(context)
        if (!isFcmSupported) {
            errorMessage = ResMessage(R.string.error_fcm_not_supported)
            return null
        }
        return try {
            fetchFcmTokenWithRetry()
        } catch (ex: TimeoutCancellationException) {
            io.ethan.pushgo.util.SilentSink.w(TAG, "FCM token request timed out", ex)
            errorMessage = ResMessage(R.string.error_fcm_token_timeout)
            null
        } catch (ex: Exception) {
            io.ethan.pushgo.util.SilentSink.e(TAG, "Unable to get FCM token: ${ex.message}", ex)
            errorMessage = ResMessage(classifyFcmTokenFailureRes(ex))
            null
        }
    }

    private fun isFcmSupported(context: Context): Boolean {
        return FcmSupport.isAvailable(context)
    }

    private fun shouldUseFcm(context: Context): Boolean {
        isFcmSupported = isFcmSupported(context)
        return useFcmChannel && isFcmSupported
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
            gatewayToken = gatewayToken,
            deviceToken = deviceToken,
            useFcmChannel = useFcmChannel,
            isFcmSupported = isFcmSupported,
            gatewayPrivateChannelEnabled = gatewayPrivateChannelEnabled,
            isChannelModeLoaded = isChannelModeLoaded,
            privateTransportStatus = privateTransportStatus,
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
    }

    fun updateGatewayToken(value: String) {
        gatewayToken = value
    }

    fun updateDecryptionKeyInput(value: String) {
        decryptionKeyInput = value
    }

    fun updateKeyEncoding(value: KeyEncoding) {
        keyEncoding = value
    }

    fun saveGatewayConfig(context: Context) {
        viewModelScope.launch {
            isSavingGateway = true
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
                    errorMessage = ResMessage(R.string.error_invalid_server_address)
                    return@launch
                }
                val token = gatewayToken.trim().ifBlank { null }
                val oldIdentity = "${previousAddress}|${previousToken.orEmpty()}"
                val newIdentity = "${normalizedAddress}|${token.orEmpty()}"
                if (oldIdentity == newIdentity) {
                    gatewayAddress = normalizedAddress
                    gatewayToken = token.orEmpty()
                    successMessage = ResMessage(R.string.message_gateway_saved)
                    return@launch
                }
                settingsRepository.setServerAddress(normalizedAddress)
                settingsRepository.setGatewayToken(token)
                gatewayAddress = normalizedAddress
                gatewayPrivateChannelEnabled = privateChannelClient.gatewayPrivateChannelEnabled()
                var activeFcmToken: String? = null
                if (shouldUseFcm(context)) {
                    val fcmToken = requireFcmToken(context) ?: return@launch
                    channelRepository.syncProviderDeviceToken(fcmToken)
                    activeFcmToken = fcmToken
                    privateChannelClient.setRuntime(fcmAvailable = true, systemToken = fcmToken)
                } else {
                    if (gatewayPrivateChannelEnabled == false) {
                        if (isFcmSupported(context)) {
                            settingsRepository.setUseFcmChannel(true)
                            useFcmChannel = true
                            enableFcmProvider(context, keepEnabledWhenTokenMissing = true)
                            PrivateChannelServiceManager.refreshForMode(context, true)
                            errorMessage = ResMessage(R.string.error_gateway_private_disabled_use_fcm)
                        } else {
                            errorMessage = ResMessage(R.string.error_private_disabled_and_fcm_unavailable)
                        }
                        return@launch
                    }
                    val previousFcmToken = settingsRepository.getFcmToken()
                    settingsRepository.setFcmToken(null)
                    privateChannelClient.setRuntime(fcmAvailable = false, systemToken = null)
                    runCatching {
                        privateChannelClient.switchToPrivateAndRetireProvider("fcm", previousFcmToken)
                    }.onFailure {
                        io.ethan.pushgo.util.SilentSink.w(
                            TAG,
                            "saveGatewayConfig route reconcile failed: ${it.message}",
                            it,
                        )
                    }
                }
                PrivateChannelServiceManager.refresh(context)
                activeFcmToken?.let {
                    channelRepository.syncSubscriptionsIfNeeded(it)
                }
                if (oldIdentity != newIdentity) {
                    privateChannelClient.onGatewayConfigChanged()
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
                refreshChannelSubscriptions()
                successMessage = ResMessage(R.string.message_gateway_saved)
            } catch (ex: Exception) {
                errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
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
        } catch (ex: ChannelSubscriptionException) {
            errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
        } catch (ex: Exception) {
            errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
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
        return try {
            if (shouldUseFcm(context)) {
                val token = settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: requireFcmToken(context)
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
                    errorMessage = ResMessage(R.string.error_private_channel_create_failed)
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
            errorMessage = ResMessage(ex.resId)
            false
        } catch (ex: ChannelNameException) {
            errorMessage = ResMessage(ex.resId, ex.args)
            false
        } catch (ex: ChannelPasswordException) {
            errorMessage = ResMessage(ex.resId)
            false
        } catch (ex: ChannelSubscriptionException) {
            io.ethan.pushgo.util.SilentSink.w(TAG, "createChannel failed (private) message=${ex.message}", ex)
            errorMessage = ex.toUiErrorMessage(R.string.error_private_channel_create_failed)
            false
        } catch (ex: Exception) {
            io.ethan.pushgo.util.SilentSink.e(TAG, "createChannel unexpected failure (private)", ex)
            errorMessage = ex.toUiErrorMessage(R.string.error_private_channel_create_failed)
            false
        } finally {
            isSavingChannel = false
        }
    }

    suspend fun subscribeChannel(context: Context, channelId: String, password: String): Boolean {
        if (isSavingChannel) return false
        isSavingChannel = true
        return try {
            if (shouldUseFcm(context)) {
                val token = settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: requireFcmToken(context)
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
                    errorMessage = ResMessage(R.string.error_private_channel_subscribe_failed)
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
            errorMessage = ResMessage(ex.resId)
            false
        } catch (ex: ChannelPasswordException) {
            errorMessage = ResMessage(ex.resId)
            false
        } catch (ex: ChannelSubscriptionException) {
            errorMessage = ex.toUiErrorMessage(R.string.error_private_channel_subscribe_failed)
            false
        } catch (ex: Exception) {
            errorMessage = ex.toUiErrorMessage(R.string.error_private_channel_subscribe_failed)
            false
        } finally {
            isSavingChannel = false
        }
    }

    suspend fun renameChannel(channelId: String, alias: String) {
        if (isRenamingChannel) return
        isRenamingChannel = true
        try {
            channelRepository.renameChannel(channelId, alias)
            refreshChannelSubscriptions()
            successMessage = ResMessage(R.string.message_channel_renamed)
        } catch (ex: ChannelIdException) {
            errorMessage = ResMessage(ex.resId)
        } catch (ex: ChannelNameException) {
            errorMessage = ResMessage(ex.resId, ex.args)
        } catch (ex: ChannelSubscriptionException) {
            errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
        } catch (ex: Exception) {
            errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
        } finally {
            isRenamingChannel = false
        }
    }

    suspend fun unsubscribeChannel(context: Context, channelId: String, deleteLocalMessages: Boolean) {
        if (isRemovingChannel) return
        isRemovingChannel = true
        try {
            val removedCount = if (shouldUseFcm(context)) {
                val token = settingsRepository.getFcmToken()?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: requireFcmToken(context)
                    ?: return
                channelRepository.syncProviderDeviceToken(token)
                channelRepository.unsubscribeChannel(channelId, token, deleteLocalMessages)
            } else {
                val normalizedChannelId = ChannelIdValidator.normalize(channelId)
                val unsubscribed = privateChannelClient.privateUnsubscribeChannel(normalizedChannelId)
                if (!unsubscribed) {
                    errorMessage = ResMessage(R.string.error_private_channel_unsubscribe_failed)
                    return
                }
                channelRepository.softDeleteLocalSubscription(
                    rawChannelId = normalizedChannelId,
                    deleteLocalMessages = deleteLocalMessages,
                )
            }
            refreshChannelSubscriptions()
            successMessage = if (deleteLocalMessages) {
                PluralResMessage(
                    R.plurals.message_channel_unsubscribed_deleted,
                    removedCount,
                    listOf(removedCount),
                )
            } else {
                ResMessage(R.string.message_channel_unsubscribed)
            }
        } catch (ex: ChannelIdException) {
            errorMessage = ResMessage(ex.resId)
        } catch (ex: ChannelSubscriptionException) {
            errorMessage = ex.toUiErrorMessage(R.string.error_private_channel_unsubscribe_failed)
        } catch (ex: Exception) {
            errorMessage = ex.toUiErrorMessage(R.string.error_private_channel_unsubscribe_failed)
        } finally {
            isRemovingChannel = false
        }
    }

    fun saveDecryptionConfig() {
        viewModelScope.launch {
            isSavingDecryption = true
            try {
                val trimmed = decryptionKeyInput.trim()
                if (trimmed.isEmpty()) {
                    settingsRepository.setNotificationKeyBytes(null)
                    decryptionUpdatedAt = null
                    isDecryptionConfigured = false
                    decryptionKeyInput = ""
                    successMessage = ResMessage(R.string.message_decryption_saved)
                    return@launch
                }
                val normalized = NotificationKeyValidator.normalizedKeyBytes(
                    input = trimmed,
                    encoding = keyEncoding,
                )
                settingsRepository.setNotificationKeyBytes(normalized)
                settingsRepository.setKeyEncoding(keyEncoding)
                decryptionUpdatedAt = settingsRepository.getNotificationKeyUpdatedAt() ?: Instant.now()
                isDecryptionConfigured = true
                decryptionKeyInput = ""
                successMessage = ResMessage(R.string.message_decryption_saved)
            } catch (ex: NotificationKeyValidationException) {
                errorMessage = when (ex) {
                    is NotificationKeyValidationException.InvalidBase64 -> ResMessage(R.string.error_invalid_base64)
                    is NotificationKeyValidationException.InvalidHex -> ResMessage(R.string.error_invalid_hex)
                    is NotificationKeyValidationException.InvalidLength -> ResMessage(R.string.error_invalid_key_length)
                }
            } catch (ex: Exception) {
                errorMessage = ex.toUiErrorMessage(R.string.error_request_failed)
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

    suspend fun loadAllMessages() = messageRepository.getAll()

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

    private fun Throwable.toUiErrorMessage(@StringRes fallbackResId: Int): UiMessage {
        val detail = message?.trim().takeUnless { it.isNullOrEmpty() }
            ?: localizedMessage?.trim().takeUnless { it.isNullOrEmpty() }
        return if (detail.isNullOrEmpty()) {
            ResMessage(fallbackResId)
        } else {
            TextMessage(detail)
        }
    }
}
