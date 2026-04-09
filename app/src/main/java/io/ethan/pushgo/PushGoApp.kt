package io.ethan.pushgo

import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.notifications.KeepaliveState
import io.ethan.pushgo.notifications.AlertPlaybackController
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.notifications.PrivateChannelServiceManager
import io.ethan.pushgo.update.UpdateCheckScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PushGoApp : Application(), ImageLoaderFactory {
    companion object {
        private const val TAG = "PushGoApp"
        private const val FCM_TOKEN_MAX_ATTEMPTS = 3
        private const val FCM_TOKEN_RETRY_BASE_DELAY_MS = 1_500L
    }

    @Volatile
    private var initializedContainer: AppContainer? = null
    @Volatile
    private var startupStorageError: String? = null
    @Volatile
    private var startupSyncScheduled: Boolean = false
    @Volatile
    private var cachedUseFcmChannel: Boolean = true

    val container: AppContainer
        get() = initializedContainer
            ?: error(startupStorageError ?: "Local persistent storage is unavailable.")

    fun containerOrNull(): AppContainer? = initializedContainer

    fun startupStorageErrorMessage(): String? = startupStorageError
    fun cachedUseFcmChannel(): Boolean = cachedUseFcmChannel
    fun isAppVisible(): Boolean = startedActivities > 0

    fun shouldRunPrivateChannelForegroundService(): Boolean {
        if (PushGoAutomation.isSessionConfigured() || isEffectiveFcmModeEnabled()) {
            return false
        }
        val container = containerOrNull() ?: return false
        val snapshot = container.privateChannelClient.readConnectionSnapshot()
        return startedActivities > 0 || snapshot.keepaliveState != KeepaliveState.FGS_LOST
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startedActivities: Int = 0

    override fun onCreate() {
        super.onCreate()
        val container = runCatching { AppContainer(this) }
            .onFailure { error ->
                val reason = error.message.orEmpty().trim()
                startupStorageError = "Local persistent storage init failed: $reason".trim()
                io.ethan.pushgo.util.SilentSink.e(TAG, "AppContainer init failed", error)
                PushGoAutomation.recordRuntimeError(
                    source = "app.container.init",
                    error = error,
                    category = "storage",
                )
            }
            .getOrNull()
        initializedContainer = container
        if (container == null) {
            NotificationHelper.cleanupObsoleteChannels(this)
            NotificationHelper.ensureManagedChannels(this)
            return
        }
        cachedUseFcmChannel = container.settingsRepository.getCachedUseFcmChannel()
        appScope.launch {
            container.settingsRepository.useFcmChannelFlow.collect { useFcmChannel ->
                cachedUseFcmChannel = useFcmChannel
                PrivateChannelServiceManager.refreshForMode(
                    this@PushGoApp,
                    effectiveFcmModeForSelection(useFcmChannel),
                )
            }
        }
        appScope.launch {
            container.settingsRepository.updateAutoCheckEnabledFlow.collect {
                UpdateCheckScheduler.refreshSchedule(this@PushGoApp)
            }
        }
        initializePushRuntime()
        UpdateCheckScheduler.refreshSchedule(this)
        scheduleStartupSyncIfNeeded()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities += 1
                AlertPlaybackController.stopAll(this@PushGoApp)
                val automationSession = PushGoAutomation.isSessionConfigured()
                container.privateChannelClient.setForeground(startedActivities > 0 && !automationSession)
                PrivateChannelServiceManager.refreshForMode(this@PushGoApp, isEffectiveFcmModeEnabled())
                if (!automationSession) {
                    scheduleStartupSyncIfNeeded()
                }
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                container.privateChannelClient.setForeground(startedActivities > 0)
                PrivateChannelServiceManager.refreshForMode(this@PushGoApp, isEffectiveFcmModeEnabled())
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        appScope.launch {
            container.messageRepository.observeUnreadCount().collect {
                NotificationHelper.reconcileActiveNotificationGroups(this@PushGoApp)
            }
        }
        NotificationHelper.cleanupObsoleteChannels(this)
        NotificationHelper.ensureManagedChannels(this)
    }

    private fun scheduleStartupSyncIfNeeded() {
        val container = containerOrNull() ?: return
        if (PushGoAutomation.isSessionConfigured()) {
            return
        }
        if (startupSyncScheduled) return
        synchronized(this) {
            if (startupSyncScheduled) return
            startupSyncScheduled = true
        }
        appScope.launch {
            applyAutomationGatewayOverrideIfNeeded(container)
            syncSubscriptionsOnLaunch()
        }
    }

    private suspend fun applyAutomationGatewayOverrideIfNeeded(container: AppContainer) {
        val overrideBaseUrl = PushGoAutomation.startupGatewayBaseUrl()
            ?.trim()
            ?.ifEmpty { null }
        val overrideToken = PushGoAutomation.startupGatewayToken()
            ?.trim()
            ?.ifEmpty { null }
        if (overrideBaseUrl == null && overrideToken == null) {
            return
        }
        runCatching {
            container.automationController.setGatewayServer(
                baseUrl = overrideBaseUrl,
                token = overrideToken,
            )
        }.onFailure { error ->
            io.ethan.pushgo.util.SilentSink.w(TAG, "applyAutomationGatewayOverrideIfNeeded failed: ${error.message}", error)
            PushGoAutomation.recordRuntimeError(
                source = "gateway.startup_override",
                error = error,
                category = "automation",
            )
        }
    }

    private fun initializePushRuntime() {
        val container = containerOrNull() ?: return
        appScope.launch {
            val useFcmChannel = runCatching {
                container.settingsRepository.getUseFcmChannel()
            }.getOrDefault(true)
            cachedUseFcmChannel = useFcmChannel
            val effectiveFcmMode = effectiveFcmModeForSelection(useFcmChannel)
            val cachedToken = if (effectiveFcmMode) {
                runCatching {
                    container.settingsRepository.getFcmToken()?.trim()?.ifEmpty { null }
                }.getOrNull()
            } else {
                null
            }
            container.privateChannelClient.setRuntime(
                fcmAvailable = effectiveFcmMode,
                systemToken = cachedToken
            )
            PrivateChannelServiceManager.refreshForMode(this@PushGoApp, effectiveFcmMode)
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    fun handlePushTokenUpdate(deviceToken: String) {
        val container = containerOrNull()
        if (container == null) {
            io.ethan.pushgo.util.SilentSink.w(TAG, "handlePushTokenUpdate ignored: storage unavailable")
            return
        }
        appScope.launch {
            val normalizedToken = deviceToken.trim().ifEmpty { return@launch }
            val useFcmChannel = runCatching { container.settingsRepository.getUseFcmChannel() }.getOrDefault(true)
            cachedUseFcmChannel = useFcmChannel
            val effectiveFcmMode = effectiveFcmModeForSelection(useFcmChannel)
            if (effectiveFcmMode) {
                runCatching {
                    container.channelRepository.syncProviderDeviceToken(normalizedToken)
                }.onFailure { error ->
                    io.ethan.pushgo.util.SilentSink.w(TAG, "syncProviderDeviceToken failed: ${error.message}", error)
                    PushGoAutomation.recordRuntimeError(
                        source = "provider.sync_device_token",
                        error = error,
                        category = "provider",
                    )
                }
                runCatching {
                    container.channelRepository.syncSubscriptionsIfNeeded(normalizedToken)
                }.onFailure { error ->
                    io.ethan.pushgo.util.SilentSink.w(TAG, "syncSubscriptionsIfNeeded after token update failed: ${error.message}", error)
                    PushGoAutomation.recordRuntimeError(
                        source = "channel.sync.after_token_update",
                        error = error,
                        category = "subscription",
                    )
                }
            } else {
                runCatching { container.handlePushTokenUpdate(normalizedToken) }
            }
            container.privateChannelClient.setRuntime(
                fcmAvailable = effectiveFcmMode,
                systemToken = if (effectiveFcmMode) normalizedToken else null
            )
            PrivateChannelServiceManager.refreshForMode(this@PushGoApp, effectiveFcmMode)
        }
    }

    private suspend fun syncSubscriptionsOnLaunch() {
        val container = containerOrNull() ?: return
        val useFcmChannel = runCatching { container.settingsRepository.getUseFcmChannel() }
            .getOrDefault(true)
        cachedUseFcmChannel = useFcmChannel
        val effectiveFcmMode = effectiveFcmModeForSelection(useFcmChannel)
        if (!effectiveFcmMode) {
            container.privateChannelClient.setRuntime(
                fcmAvailable = false,
                systemToken = null
            )
            PrivateChannelServiceManager.refreshForMode(this@PushGoApp, false)
            container.privateChannelClient.triggerProviderWakeupRecovery()
            return
        }
        val cachedToken = runCatching {
            container.settingsRepository.getFcmToken()?.trim()?.ifEmpty { null }
        }.getOrNull()
        container.privateChannelClient.setRuntime(
            fcmAvailable = true,
            systemToken = cachedToken
        )
        PrivateChannelServiceManager.refreshForMode(this@PushGoApp, true)
        if (!cachedToken.isNullOrBlank()) {
            handlePushTokenUpdate(cachedToken)
        }
        appScope.launch {
            runCatching { requestFcmTokenWithRetry() }
                .onSuccess { token ->
                    io.ethan.pushgo.util.SilentSink.i(TAG, "startup FCM token fetch succeeded")
                    handlePushTokenUpdate(token)
                }
                .onFailure { error ->
                    io.ethan.pushgo.util.SilentSink.w(TAG, "startup FCM token fetch failed: ${error.message}", error)
                    PushGoAutomation.recordRuntimeError(
                        source = "provider.fcm_token.startup",
                        error = error,
                        category = "provider",
                    )
                    // Keep provider mode enabled; token fetch may recover on next retry/update.
                }
        }
    }

    private suspend fun requestFcmTokenWithRetry(): String {
        var lastError: Throwable? = null
        repeat(FCM_TOKEN_MAX_ATTEMPTS) { attempt ->
            try {
                return requestFcmTokenOnce()
            } catch (error: Throwable) {
                lastError = error
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "requestFcmToken attempt=${attempt + 1}/$FCM_TOKEN_MAX_ATTEMPTS failed: ${error.message}",
                    error
                )
                if (!isRetriableFcmTokenError(error) || attempt == FCM_TOKEN_MAX_ATTEMPTS - 1) {
                    throw error
                }
                delay((attempt + 1) * FCM_TOKEN_RETRY_BASE_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Unable to get FCM token")
    }

    private fun isRetriableFcmTokenError(error: Throwable): Boolean {
        val message = buildString {
            append(error.message.orEmpty())
            val cause = error.cause
            if (cause != null) {
                append(" ")
                append(cause.message.orEmpty())
            }
        }.uppercase()
        return message.contains("SERVICE_NOT_AVAILABLE")
            || message.contains("INTERNAL_SERVER_ERROR")
            || message.contains("TIMEOUT")
    }

    private suspend fun requestFcmTokenOnce(): String = withTimeout(io.ethan.pushgo.data.AppConstants.fcmTokenTimeoutMs) {
        suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (cont.isActive) {
                        cont.resume(token)
                    }
                }
                .addOnFailureListener { error ->
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("Unable to get FCM token", error))
                    }
                }
                .addOnCanceledListener {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("FCM token task cancelled"))
                    }
                }
        }
    }

    private fun isFcmSupported(): Boolean {
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        return status == ConnectionResult.SUCCESS
    }

    private fun effectiveFcmModeForSelection(useFcmChannel: Boolean): Boolean {
        return useFcmChannel && isFcmSupported()
    }

    private fun isEffectiveFcmModeEnabled(): Boolean {
        return effectiveFcmModeForSelection(cachedUseFcmChannel)
    }
}
