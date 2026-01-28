package io.ethan.pushgo

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.google.firebase.messaging.FirebaseMessaging
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.notifications.RingtoneCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class PushGoApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            syncSubscriptionsOnLaunch()
        }
        appScope.launch {
            container.messageRepository.observeUnreadCount().collect { count ->
                NotificationHelper.updateActiveNotificationNumbers(this@PushGoApp, count)
            }
        }
        NotificationHelper.ensureChannel(this, RingtoneCatalog.DEFAULT_ID, null, null)
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
        appScope.launch {
            runCatching { container.handlePushTokenUpdate(deviceToken) }
        }
    }

    private fun syncSubscriptionsOnLaunch() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                handlePushTokenUpdate(token)
            }
    }
}
