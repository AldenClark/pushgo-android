package io.ethan.pushgo

import android.app.Application
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.google.firebase.messaging.FirebaseMessaging
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.ImageCacheCleanupScheduler
import io.ethan.pushgo.data.MessageImageStore
import io.ethan.pushgo.data.MessageImageStoreFetcher
import io.ethan.pushgo.notifications.KeepaliveState
import io.ethan.pushgo.notifications.AlertPlaybackController
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.notifications.PrivateChannelServiceManager
import io.ethan.pushgo.notifications.ProviderIngressCoordinator
import io.ethan.pushgo.testing.InstrumentationRuntime
import io.ethan.pushgo.ui.PendingLocalDeletionDrainScheduler
import io.ethan.pushgo.update.UpdateCheckScheduler
import io.ethan.pushgo.util.FcmSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Path.Companion.toOkioPath
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PushGoApp : Application(), SingletonImageLoader.Factory {
    companion object {
        private const val TAG = "PushGoApp"
        private const val FCM_TOKEN_MAX_ATTEMPTS = 3
        private const val FCM_TOKEN_RETRY_BASE_DELAY_MS = 1_500L
    }

    @Volatile
    private var initializedContainer: AppContainer? = null
    private var containerJob: Job? = null
    @Volatile
    private var startupStorageError: String? = null
    @Volatile
    private var startupSyncScheduled: Boolean = false
    @Volatile
    private var startupSyncCompleted: Boolean = false
    @Volatile
    private var cachedUseFcmChannel: Boolean = true

    val container: AppContainer
        get() = containerOrNull()
            ?: error(startupStorageError ?: "Local persistent storage is unavailable.")

    fun containerOrNull(): AppContainer? {
        initializedContainer?.let { return it }
        if (!InstrumentationRuntime.isUnderInstrumentationTest()) return null
        return synchronized(this) {
            initializedContainer ?: runCatching { createContainer() }
                .onFailure { error ->
                    startupStorageError = error.message.orEmpty().trim()
                        .ifEmpty { "Local persistent storage init failed." }
                }
                .getOrNull()
                .also { initializedContainer = it }
        }
    }

    /**
     * Releases the target application's database handle before a migration test
     * replaces database files. The next test-owned access lazily opens a fresh
     * container. This is deliberately unavailable outside instrumentation.
     */
    fun releaseStorageForInstrumentationTest() {
        check(InstrumentationRuntime.isUnderInstrumentationTest())
        val (job, database) = synchronized(this) {
            val currentJob = containerJob
            val currentDatabase = initializedContainer?.database
            containerJob = null
            initializedContainer = null
            startupStorageError = null
            currentJob to currentDatabase
        }
        runBlocking { job?.cancelAndJoin() }
        database?.close()
    }

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
    private val imageStoreForCoil by lazy { MessageImageStore(this) }
    private var startedActivities: Int = 0
    private var pendingDeletionLifecycleGeneration: Long = 0L

    override fun onCreate() {
        super.onCreate()
        if (InstrumentationRuntime.isUnderInstrumentationTest()) {
            // Test fixtures own their database lifecycle. Opening the production
            // container here would race migration tests that replace pushgo.db.
            NotificationHelper.cleanupObsoleteChannels(this)
            NotificationHelper.ensureManagedChannels(this)
            return
        }
        val container = runCatching { createContainer() }
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
        container.pendingLocalDeletionCoordinator.start()
        cachedUseFcmChannel = container.settingsRepository.getCachedUseFcmChannel()
        appScope.launch {
            runCatching {
                container.messageRepository.backfillTagMetadataIndexIfNeeded(this@PushGoApp)
            }.onFailure { error ->
                PushGoAutomation.recordRuntimeError(
                    source = "storage.message_tag_metadata_backfill",
                    error = error,
                    category = "storage",
                )
            }
        }
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
        ImageCacheCleanupScheduler.refreshSchedule(this)
        scheduleStartupSyncIfNeeded()
        val lifecycleHandler = Handler(Looper.getMainLooper())
        val deletionInteractionBoundary = StartedActivityInteractionBoundary(
            scheduleDelayed = { delayMillis, block ->
                lifecycleHandler.postDelayed(block, delayMillis)
            },
            onInteractionChanged = { interactionActive ->
                val generation = ++pendingDeletionLifecycleGeneration
                appScope.launch {
                    container.pendingLocalDeletionCoordinator.setInteractionActive(
                        interactionActive,
                        generation,
                    )
                }
            },
        )
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities += 1
                deletionInteractionBoundary.onActivityStarted()
                val becameForeground = startedActivities == 1
                AlertPlaybackController.stopAll(this@PushGoApp)
                val automationSession = PushGoAutomation.isSessionConfigured()
                container.privateChannelClient.setForeground(startedActivities > 0 && !automationSession)
                PrivateChannelServiceManager.refreshForMode(this@PushGoApp, isEffectiveFcmModeEnabled())
                if (!automationSession) {
                    scheduleStartupSyncIfNeeded()
                }
                if (becameForeground && startupSyncCompleted) {
                    scheduleProviderIngressSync(reason = "app_foreground")
                }
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                deletionInteractionBoundary.onActivityStopped(activity.isChangingConfigurations)
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

    private fun createContainer(): AppContainer {
        val job = SupervisorJob(appScope.coroutineContext[Job])
        return runCatching {
            AppContainer(
                context = this,
                appScope = CoroutineScope(job + Dispatchers.IO),
                pendingLocalDeletionDrainScheduler = if (
                    InstrumentationRuntime.isUnderInstrumentationTest()
                ) {
                    PendingLocalDeletionDrainScheduler.None
                } else {
                    io.ethan.pushgo.ui.WorkManagerPendingLocalDeletionDrainScheduler(this)
                },
            )
        }.onSuccess {
            containerJob = job
        }.onFailure {
            job.cancel()
        }.getOrThrow()
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
            try {
                applyAutomationGatewayOverrideIfNeeded(container)
                syncSubscriptionsOnLaunch()
            } finally {
                startupSyncCompleted = true
            }
        }
    }

    private fun scheduleProviderIngressSync(reason: String) {
        val container = containerOrNull() ?: return
        appScope.launch {
            runCatching {
                ProviderIngressCoordinator.pullPersistAndDrainAcks(
                    context = this@PushGoApp,
                    channelRepository = container.channelRepository,
                    messageRepository = container.messageRepository,
                    entityRepository = container.entityRepository,
                    inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
                    settingsRepository = container.settingsRepository,
                )
            }
        }
    }

    private suspend fun processPushTokenUpdate(
        container: AppContainer,
        normalizedToken: String,
        triggerPull: Boolean,
    ) {
        val useFcmChannel = runCatching { container.settingsRepository.getUseFcmChannel() }
            .getOrDefault(true)
        cachedUseFcmChannel = useFcmChannel
        val effectiveFcmMode = effectiveFcmModeForSelection(useFcmChannel)
        if (effectiveFcmMode) {
            runCatching {
                container.channelRepository.syncProviderDeviceToken(normalizedToken)
            }.onFailure { error ->
                PushGoAutomation.recordRuntimeError(
                    source = "provider.sync_device_token",
                    error = error,
                    category = "provider",
                )
            }
            runCatching {
                container.channelRepository.syncSubscriptionsIfNeeded(normalizedToken)
            }.onFailure { error ->
                PushGoAutomation.recordRuntimeError(
                    source = "channel.sync.after_token_update",
                    error = error,
                    category = "subscription",
                )
            }
        } else {
            runCatching { container.handlePushTokenUpdate(normalizedToken) }
        }
        if (effectiveFcmMode && triggerPull) {
            scheduleProviderIngressSync(reason = "token_update")
        }
        container.privateChannelClient.setRuntime(
            fcmAvailable = effectiveFcmMode,
            systemToken = if (effectiveFcmMode) normalizedToken else null,
        )
        PrivateChannelServiceManager.refreshForMode(this@PushGoApp, effectiveFcmMode)
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

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(MessageImageStoreFetcher.Factory(imageStoreForCoil))
                add(AnimatedImageDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
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
            processPushTokenUpdate(
                container = container,
                normalizedToken = normalizedToken,
                triggerPull = true,
            )
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
            scheduleProviderIngressSync(reason = "startup_sync_private_mode")
            return
        }
        val cachedToken = runCatching {
            container.settingsRepository.getFcmToken()?.trim()?.ifEmpty { null }
        }.getOrNull()
        container.privateChannelClient.setRuntime(
            fcmAvailable = true,
            systemToken = cachedToken,
        )
        PrivateChannelServiceManager.refreshForMode(this@PushGoApp, true)
        if (!cachedToken.isNullOrBlank()) {
            processPushTokenUpdate(
                container = container,
                normalizedToken = cachedToken,
                triggerPull = false,
            )
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
        scheduleProviderIngressSync(reason = "startup_sync_provider_mode")
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

    @Suppress("DEPRECATION")
    private fun firebaseTokenTask() = FirebaseMessaging.getInstance().token

    private suspend fun requestFcmTokenOnce(): String = withTimeout(io.ethan.pushgo.data.AppConstants.fcmTokenTimeoutMs) {
        suspendCancellableCoroutine { cont ->
            firebaseTokenTask()
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
        return FcmSupport.isAvailable(this)
    }

    private fun effectiveFcmModeForSelection(useFcmChannel: Boolean): Boolean {
        return useFcmChannel && isFcmSupported()
    }

    private fun isEffectiveFcmModeEnabled(): Boolean {
        return effectiveFcmModeForSelection(cachedUseFcmChannel)
    }
}

/** Mirrors ProcessLifecycleOwner's started boundary without treating pause/configuration as background. */
internal class StartedActivityInteractionBoundary(
    private val scheduleDelayed: (delayMillis: Long, block: Runnable) -> Unit,
    private val onInteractionChanged: (Boolean) -> Unit,
) {
    private var startedCount = 0
    private var generation = 0L
    private var interactionActive = false

    fun onActivityStarted() {
        startedCount += 1
        generation += 1
        if (!interactionActive) {
            interactionActive = true
            onInteractionChanged(true)
        }
    }

    fun onActivityStopped(isChangingConfigurations: Boolean = false) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount != 0 || isChangingConfigurations) return
        val scheduledGeneration = ++generation
        scheduleDelayed(PROCESS_STOP_DEBOUNCE_MILLIS, Runnable {
            if (startedCount == 0 && generation == scheduledGeneration) {
                interactionActive = false
                onInteractionChanged(false)
            }
        })
    }

    companion object {
        internal const val PROCESS_STOP_DEBOUNCE_MILLIS = 700L
    }
}
