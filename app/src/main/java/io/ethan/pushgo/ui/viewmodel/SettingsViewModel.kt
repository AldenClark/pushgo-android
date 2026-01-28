package io.ethan.pushgo.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import io.ethan.pushgo.R
import io.ethan.pushgo.data.AppConstants
import io.ethan.pushgo.data.ChannelIdException
import io.ethan.pushgo.data.ChannelNameException
import io.ethan.pushgo.data.ChannelPasswordException
import io.ethan.pushgo.data.ChannelSubscriptionException
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.data.model.KeyLength
import io.ethan.pushgo.data.model.ThemeMode
import io.ethan.pushgo.notifications.RingtoneCatalog
import io.ethan.pushgo.notifications.MessageStateCoordinator
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import io.ethan.pushgo.notifications.CustomRingtone
import io.ethan.pushgo.notifications.CustomRingtoneManager
import android.net.Uri

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
) : ViewModel() {
    var gatewayAddress by mutableStateOf("")
        private set
    var gatewayToken by mutableStateOf("")
        private set

    var deviceToken by mutableStateOf<String?>(null)
        private set

    var customRingtones by mutableStateOf<List<CustomRingtone>>(emptyList())
        private set

    var decryptionKeyInput by mutableStateOf("")
        private set
    var keyEncoding by mutableStateOf(KeyEncoding.BASE64)
        private set
    var keyLength by mutableStateOf(KeyLength.BITS_256)
        private set
    var decryptionUpdatedAt by mutableStateOf<Instant?>(null)
        private set
    var isDecryptionConfigured by mutableStateOf(false)
        private set

    var ringtoneId by mutableStateOf(RingtoneCatalog.DEFAULT_ID)
        private set
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    var autoCleanupEnabled by mutableStateOf(true)
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
    var errorMessage by mutableStateOf<UiMessage?>(null)
        private set
    var successMessage by mutableStateOf<UiMessage?>(null)
        private set

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
            settingsRepository.ringtoneIdFlow.collect { value ->
                ringtoneId = value ?: RingtoneCatalog.DEFAULT_ID
            }
        }
        viewModelScope.launch {
            settingsRepository.themeModeFlow.collect { value ->
                themeMode = value
            }
        }
        viewModelScope.launch {
            settingsRepository.autoCleanupEnabledFlow.collect { value ->
                autoCleanupEnabled = value
            }
        }

        viewModelScope.launch {
            gatewayToken = settingsRepository.getGatewayToken() ?: ""
            deviceToken = settingsRepository.getFcmToken()
            val currentKey = settingsRepository.getNotificationKeyBase64()
            isDecryptionConfigured = !currentKey.isNullOrBlank()
            decryptionUpdatedAt = settingsRepository.getNotificationKeyUpdatedAt()
            keyEncoding = settingsRepository.getKeyEncoding()
            keyLength = settingsRepository.getKeyLength()
            autoCleanupEnabled = settingsRepository.getAutoCleanupEnabled()
        }

        viewModelScope.launch {
            refreshChannelSubscriptions()
        }
    }

    fun refreshCustomRingtones(context: android.content.Context) {
        customRingtones = CustomRingtoneManager.getCustomRingtones(context.applicationContext)
    }

    fun addCustomRingtone(context: android.content.Context, uri: Uri) {
        viewModelScope.launch {
            val result = CustomRingtoneManager.addRingtone(context.applicationContext, uri)
            when (result) {
                is CustomRingtoneManager.AddResult.Success -> {
                    refreshCustomRingtones(context)
                    updateRingtoneId(result.ringtone.id)
                    successMessage = ResMessage(R.string.message_ringtone_added)
                }
                is CustomRingtoneManager.AddResult.Error -> {
                    errorMessage = TextMessage(result.message)
                }
            }
        }
    }

    fun deleteCustomRingtone(context: android.content.Context, id: String) {
        CustomRingtoneManager.deleteRingtone(context.applicationContext, id)
        refreshCustomRingtones(context)
        if (ringtoneId == id) {
            updateRingtoneId(RingtoneCatalog.DEFAULT_ID)
        }
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

    fun updateKeyLength(value: KeyLength) {
        keyLength = value
    }

    fun updateRingtoneId(value: String) {
        ringtoneId = value
        viewModelScope.launch {
            settingsRepository.setRingtoneId(value)
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun updateAutoCleanupEnabled(enabled: Boolean) {
        autoCleanupEnabled = enabled
        viewModelScope.launch {
            settingsRepository.setAutoCleanupEnabled(enabled)
        }
    }

    fun saveGatewayConfig(context: Context) {
        viewModelScope.launch {
            isSavingGateway = true
            try {
                val trimmed = gatewayAddress.trim().ifBlank { AppConstants.defaultServerAddress }
                if (!isValidHttpsUrl(trimmed)) {
                    errorMessage = ResMessage(R.string.error_invalid_server_address)
                    return@launch
                }
                val token = gatewayToken.trim().ifBlank { null }
                val fcmToken = requireFcmToken(context) ?: return@launch
                settingsRepository.setServerAddress(trimmed)
                settingsRepository.setGatewayToken(token)
                channelRepository.handleTokenUpdate(fcmToken)
                refreshChannelSubscriptions()
                successMessage = ResMessage(R.string.message_gateway_saved)
            } catch (ex: Exception) {
                errorMessage = TextMessage(ex.localizedMessage ?: "")
            } finally {
                isSavingGateway = false
            }
        }
    }

    suspend fun refreshChannelSubscriptions() {
        channelSubscriptions = channelRepository.loadSubscriptions()
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
            errorMessage = TextMessage(ex.localizedMessage ?: "")
        } catch (ex: Exception) {
            errorMessage = TextMessage(ex.localizedMessage ?: "")
        } finally {
            isCheckingChannel = false
        }
    }

    suspend fun createChannel(context: Context, alias: String, password: String): Boolean {
        if (isSavingChannel) return false
        isSavingChannel = true
        return try {
            val token = requireFcmToken(context) ?: return false
            val result = channelRepository.createChannel(
                rawAlias = alias,
                password = password,
                deviceToken = token,
            )
            channelExists = true
            channelExistsName = result.channelName
            refreshChannelSubscriptions()
            val messageRes = if (result.created) {
                R.string.message_channel_created_and_subscribed
            } else {
                R.string.message_channel_subscribed
            }
            successMessage = ResMessage(messageRes)
            true
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
            errorMessage = TextMessage(ex.localizedMessage ?: "")
            false
        } catch (ex: Exception) {
            errorMessage = TextMessage(ex.localizedMessage ?: "")
            false
        } finally {
            isSavingChannel = false
        }
    }

    suspend fun subscribeChannel(context: Context, channelId: String, password: String): Boolean {
        if (isSavingChannel) return false
        isSavingChannel = true
        return try {
            val token = requireFcmToken(context) ?: return false
            val result = channelRepository.subscribeChannel(
                rawChannelId = channelId,
                password = password,
                deviceToken = token,
            )
            channelExists = result.channelId.isNotBlank()
            channelExistsName = result.channelName
            refreshChannelSubscriptions()
            successMessage = ResMessage(R.string.message_channel_subscribed)
            true
        } catch (ex: ChannelIdException) {
            errorMessage = ResMessage(ex.resId)
            false
        } catch (ex: ChannelPasswordException) {
            errorMessage = ResMessage(ex.resId)
            false
        } catch (ex: ChannelSubscriptionException) {
            errorMessage = TextMessage(ex.localizedMessage ?: "")
            false
        } catch (ex: Exception) {
            errorMessage = TextMessage(ex.localizedMessage ?: "")
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
            errorMessage = TextMessage(ex.localizedMessage ?: "")
        } catch (ex: Exception) {
            errorMessage = TextMessage(ex.localizedMessage ?: "")
        } finally {
            isRenamingChannel = false
        }
    }

    suspend fun updateChannelAutoCleanupEnabled(channelId: String, enabled: Boolean) {
        try {
            channelRepository.setChannelAutoCleanupEnabled(channelId, enabled)
            refreshChannelSubscriptions()
        } catch (ex: ChannelIdException) {
            errorMessage = ResMessage(ex.resId)
        } catch (ex: ChannelSubscriptionException) {
            errorMessage = TextMessage(ex.localizedMessage ?: "")
        } catch (ex: Exception) {
            errorMessage = TextMessage(ex.localizedMessage ?: "")
        }
    }

    suspend fun unsubscribeChannel(context: Context, channelId: String, deleteLocalMessages: Boolean) {
        if (isRemovingChannel) return
        isRemovingChannel = true
        try {
            val token = requireFcmToken(context) ?: return
            val removedCount = channelRepository.unsubscribeChannel(
                rawChannelId = channelId,
                deviceToken = token,
                deleteLocalMessages = deleteLocalMessages,
            )
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
            errorMessage = TextMessage(ex.localizedMessage ?: "")
        } catch (ex: Exception) {
            errorMessage = TextMessage(ex.localizedMessage ?: "")
        } finally {
            isRemovingChannel = false
        }
    }

    fun saveDecryptionConfig() {
        viewModelScope.launch {
            if (decryptionKeyInput.isBlank()) {
                errorMessage = if (isDecryptionConfigured) {
                    ResMessage(R.string.error_key_already_saved)
                } else {
                    ResMessage(R.string.error_key_required)
                }
                return@launch
            }
            isSavingDecryption = true
            try {
                val normalized = normalizeKeyBase64(
                    input = decryptionKeyInput.trim(),
                    encoding = keyEncoding,
                    keyLength = keyLength,
                )
                settingsRepository.setNotificationKeyBase64(normalized)
                settingsRepository.setKeyEncoding(keyEncoding)
                settingsRepository.setKeyLength(keyLength)
                decryptionUpdatedAt = settingsRepository.getNotificationKeyUpdatedAt() ?: Instant.now()
                isDecryptionConfigured = true
                decryptionKeyInput = ""
                successMessage = ResMessage(R.string.message_decryption_saved)
            } catch (ex: KeyValidationError) {
                errorMessage = when (ex) {
                    is KeyValidationError.InvalidBase64 -> ResMessage(R.string.error_invalid_base64)
                    is KeyValidationError.InvalidHex -> ResMessage(R.string.error_invalid_hex)
                    is KeyValidationError.InvalidLength -> PluralResMessage(
                        R.plurals.error_key_length_mismatch,
                        ex.expectedBytes,
                        listOf(ex.expectedBytes, ex.expectedBytes * 8, ex.actualBytes),
                    )
                }
            } catch (ex: Exception) {
                errorMessage = TextMessage(ex.localizedMessage ?: "")
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
                errorMessage = TextMessage(ex.localizedMessage ?: "")
            } finally {
                isClearing = false
            }
        }
    }

    suspend fun loadAllMessages() = messageRepository.getAll()

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

    private fun normalizeKeyBase64(
        input: String,
        encoding: KeyEncoding,
        keyLength: KeyLength,
    ): String {
        val data = when (encoding) {
            KeyEncoding.PLAINTEXT -> input.toByteArray()
            KeyEncoding.BASE64 -> runCatching { java.util.Base64.getDecoder().decode(input) }
                .getOrElse { throw KeyValidationError.InvalidBase64 }
            KeyEncoding.HEX -> {
                val clean = input.filterNot { it.isWhitespace() }
                if (clean.length % 2 != 0) throw KeyValidationError.InvalidHex
                val bytes = ByteArray(clean.length / 2)
                var index = 0
                while (index < clean.length) {
                    val byteString = clean.substring(index, index + 2)
                    val value = byteString.toInt(16)
                    bytes[index / 2] = value.toByte()
                    index += 2
                }
                bytes
            }
        }
        if (data.size != keyLength.bytes) {
            throw KeyValidationError.InvalidLength(keyLength.bytes, data.size)
        }
        return java.util.Base64.getEncoder().encodeToString(data)
    }

    private fun isValidHttpsUrl(raw: String): Boolean {
        return try {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()
            scheme == "https" && !uri.host.isNullOrBlank()
        } catch (ex: Exception) {
            false
        }
    }

    fun consumeError() {
        errorMessage = null
    }

    fun consumeSuccess() {
        successMessage = null
    }

    fun notifyIfFcmUnsupported(context: Context) {
        if (!isFcmSupported(context)) {
            errorMessage = ResMessage(R.string.error_fcm_not_supported)
        }
    }

    private suspend fun requireFcmToken(context: Context): String? {
        if (!isFcmSupported(context)) {
            errorMessage = ResMessage(R.string.error_fcm_not_supported)
            return null
        }
        return try {
            fetchFcmToken()
        } catch (ex: TimeoutCancellationException) {
            errorMessage = ResMessage(R.string.error_fcm_token_timeout)
            null
        } catch (ex: Exception) {
            errorMessage = ResMessage(R.string.error_unable_to_get_fcm_token)
            null
        }
    }

    private fun isFcmSupported(context: Context): Boolean {
        val availability = GoogleApiAvailability.getInstance()
        return availability.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private suspend fun fetchFcmToken(): String = withTimeout(AppConstants.fcmTokenTimeoutMs) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (cont.isActive) {
                        cont.resume(token)
                    }
                }
                .addOnFailureListener { ex ->
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Unable to get FCM token", ex))
                    }
                }
        }
    }
}

private sealed class KeyValidationError : Exception() {
    object InvalidBase64 : KeyValidationError()
    object InvalidHex : KeyValidationError()
    data class InvalidLength(val expectedBytes: Int, val actualBytes: Int) : KeyValidationError()
}
