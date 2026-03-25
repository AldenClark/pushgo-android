package io.ethan.pushgo.notifications

object WarpLinkNativeBridge {
    private const val TAG = "WarpLinkNativeBridge"

    @Volatile
    private var loaded: Boolean = load()

    private fun load(): Boolean {
        return try {
            System.loadLibrary("pushgo_quinn_jni")
            true
        } catch (error: UnsatisfiedLinkError) {
            io.ethan.pushgo.util.SilentSink.w(TAG, "load native library failed: ${error.message}")
            false
        }
    }

    fun isAvailable(): Boolean = loaded

    fun sessionStart(configJson: String): Long {
        if (!loaded) return 0L
        return runCatching {
            nativeSessionStart(configJson)
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session start failed: ${it.message}")
            0L
        }
    }

    fun sessionPollEvent(handle: Long, timeoutMs: Int): String? {
        if (!loaded || handle == 0L) return null
        return runCatching {
            nativeSessionPollEvent(handle, timeoutMs)
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session poll failed: ${it.message}")
            null
        }
    }

    fun sessionStop(handle: Long) {
        if (!loaded || handle == 0L) return
        runCatching {
            nativeSessionStop(handle)
        }.onFailure {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session stop failed: ${it.message}")
        }
    }

    fun sessionReplaceAuthToken(handle: Long, authToken: String?): Boolean {
        if (!loaded || handle == 0L) return false
        val token = authToken?.trim().orEmpty()
        return runCatching {
            nativeSessionReplaceAuthToken(handle, token) == 1
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session replace auth token failed: ${it.message}")
            false
        }
    }

    fun sessionResolveMessage(handle: Long, ackId: Long, status: Int): Boolean {
        if (!loaded || handle == 0L || ackId <= 0L) return false
        return runCatching {
            nativeSessionResolveMessage(handle, ackId, status) == 1
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session resolve message failed: ${it.message}")
            false
        }
    }

    fun sessionSetPowerHint(handle: Long, appState: String?, powerTier: String?): Boolean {
        if (!loaded || handle == 0L) return false
        val state = appState?.trim().orEmpty()
        val tier = powerTier?.trim().orEmpty()
        return runCatching {
            nativeSessionSetPowerHint(handle, state, tier) == 1
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session set power hint failed: ${it.message}")
            false
        }
    }

    fun sessionRequestProbe(handle: Long): Boolean {
        if (!loaded || handle == 0L) return false
        return runCatching {
            nativeSessionRequestProbe(handle) == 1
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session request probe failed: ${it.message}")
            false
        }
    }

    @JvmStatic
    private external fun nativeSessionStart(configJson: String): Long

    @JvmStatic
    private external fun nativeSessionPollEvent(handle: Long, timeoutMs: Int): String?

    @JvmStatic
    private external fun nativeSessionStop(handle: Long)

    @JvmStatic
    private external fun nativeSessionReplaceAuthToken(handle: Long, authToken: String): Int

    @JvmStatic
    private external fun nativeSessionResolveMessage(handle: Long, ackId: Long, status: Int): Int

    @JvmStatic
    private external fun nativeSessionSetPowerHint(handle: Long, appState: String, powerTier: String): Int

    @JvmStatic
    private external fun nativeSessionRequestProbe(handle: Long): Int
}
