package io.ethan.pushgo.ui.viewmodel

import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.update.UpdateCandidate
import java.time.Instant

data class SettingsUiState(
    val gatewayAddress: String = "",
    val gatewayToken: String = "",
    val deviceToken: String? = null,
    val useFcmChannel: Boolean = true,
    val isFcmSupported: Boolean = true,
    val gatewayPrivateChannelEnabled: Boolean? = null,
    val isChannelModeLoaded: Boolean = false,
    val privateTransportStatus: String = "未连接",
    val decryptionKeyInput: String = "",
    val keyEncoding: KeyEncoding = KeyEncoding.BASE64,
    val decryptionUpdatedAt: Instant? = null,
    val isDecryptionConfigured: Boolean = false,
    val isMessagePageEnabled: Boolean = true,
    val isEventPageEnabled: Boolean = true,
    val isThingPageEnabled: Boolean = true,
    val updateAutoCheckEnabled: Boolean = true,
    val updateBetaChannelEnabled: Boolean = false,
    val availableUpdate: UpdateCandidate? = null,
    val updateSuppressedBySkip: Boolean = false,
    val updateSuppressedByCooldown: Boolean = false,
    val isSavingGateway: Boolean = false,
    val isSavingDecryption: Boolean = false,
    val isClearing: Boolean = false,
    val isCheckingUpdates: Boolean = false,
    val isInstallingUpdate: Boolean = false,
    val updateInstallProgressMessage: UiMessage? = null,
    val shouldShowInstallPermissionDialog: Boolean = false,
    val pendingManualInstallApkPath: String? = null,
    val shouldShowInstallBlockedDialog: Boolean = false,
    val installBlockedDetail: String? = null,
    val blockedInstallApkPath: String? = null,
    val errorMessage: UiMessage? = null,
    val successMessage: UiMessage? = null,
    val shouldShowPrivateChannelWhitelistDialog: Boolean = false,
)
