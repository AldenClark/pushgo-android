package io.ethan.pushgo.notifications

import org.json.JSONObject

object WarpLinkNativeBridge {
    private const val TAG = "WarpLinkNativeBridge"
    const val ABI_VERSION: Int = 2

    sealed interface SessionPollResult {
        data class Event(val eventJson: String) : SessionPollResult
        data object Timeout : SessionPollResult
        data object Closed : SessionPollResult
        data class Error(val code: String) : SessionPollResult
    }

    internal interface SessionRuntime {
        fun isAvailable(): Boolean
        fun sessionStart(configJson: String): Long
        fun sessionPollEvent(handle: Long, timeoutMs: Int): SessionPollResult
        fun sessionStop(handle: Long)
        fun sessionReplaceAuthToken(handle: Long, authToken: String?): Boolean
        fun sessionResolveMessage(handle: Long, ackId: Long, status: Int): Boolean
        fun sessionSetPowerHint(handle: Long, appState: String?, powerTier: String?): Boolean
        fun sessionRequestProbe(handle: Long): Boolean
        fun sessionForceReconnect(handle: Long): Boolean
        fun sessionPinTransport(handle: Long, transport: String, ttlMs: Long): Boolean
        fun sessionClearPin(handle: Long): Boolean
    }

    @Volatile
    private var loadedAbiVersion: Int = 0
    @Volatile
    private var loaded: Boolean = load()
    @Volatile
    private var testRuntime: SessionRuntime? = null

    private fun load(): Boolean {
        return try {
            System.loadLibrary("pushgo_quinn_jni")
            val version = nativeAbiVersion()
            loadedAbiVersion = version
            if (version != ABI_VERSION) {
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "native ABI mismatch expected=$ABI_VERSION actual=$version",
                )
                false
            } else {
                true
            }
        } catch (error: LinkageError) {
            io.ethan.pushgo.util.SilentSink.w(TAG, "load native library failed: ${error.message}")
            false
        } catch (error: SecurityException) {
            io.ethan.pushgo.util.SilentSink.w(TAG, "load native library denied: ${error.message}")
            false
        }
    }

    fun isAvailable(): Boolean = testRuntime?.isAvailable() ?: loaded

    fun abiVersion(): Int = if (testRuntime != null) ABI_VERSION else loadedAbiVersion

    internal fun installTestRuntime(runtime: SessionRuntime?) {
        testRuntime = runtime
    }

    fun sessionStart(configJson: String): Long {
        testRuntime?.let { return it.sessionStart(configJson) }
        if (!loaded) return 0L
        return runCatching {
            nativeSessionStart(configJson)
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session start failed: ${it.message}")
            0L
        }
    }

    fun sessionPollEvent(handle: Long, timeoutMs: Int): SessionPollResult {
        testRuntime?.let { return it.sessionPollEvent(handle, timeoutMs) }
        if (!loaded) return SessionPollResult.Error("native_unavailable")
        if (handle == 0L) return SessionPollResult.Error("invalid_handle")
        return runCatching {
            decodePollResult(nativeSessionPollEventV2(handle, timeoutMs))
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session poll failed: ${it.message}")
            SessionPollResult.Error("jni_call_failed")
        }
    }

    internal fun decodePollResult(raw: String?): SessionPollResult {
        if (raw == null) return SessionPollResult.Error("jni_string_allocation_failed")
        return runCatching {
            val envelope = JSONObject(raw)
            when (envelope.optString("status")) {
                "event" -> {
                    val event = envelope.optJSONObject("event")
                        ?: return@runCatching SessionPollResult.Error("malformed_event")
                    SessionPollResult.Event(event.toString())
                }
                "timeout" -> SessionPollResult.Timeout
                "closed" -> SessionPollResult.Closed
                "error" -> SessionPollResult.Error(
                    envelope.optString("code").ifBlank { "native_error" }
                )
                else -> SessionPollResult.Error("unknown_poll_status")
            }
        }.getOrElse {
            SessionPollResult.Error("malformed_poll_envelope")
        }
    }

    fun sessionStop(handle: Long) {
        testRuntime?.let {
            it.sessionStop(handle)
            return
        }
        if (!loaded || handle == 0L) return
        runCatching {
            nativeSessionStop(handle)
        }.onFailure {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session stop failed: ${it.message}")
        }
    }

    fun sessionReplaceAuthToken(handle: Long, authToken: String?): Boolean {
        testRuntime?.let { return it.sessionReplaceAuthToken(handle, authToken) }
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
        testRuntime?.let { return it.sessionResolveMessage(handle, ackId, status) }
        if (!loaded || handle == 0L || ackId <= 0L) return false
        return runCatching {
            nativeSessionResolveMessage(handle, ackId, status) == 1
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session resolve message failed: ${it.message}")
            false
        }
    }

    fun sessionSetPowerHint(handle: Long, appState: String?, powerTier: String?): Boolean {
        testRuntime?.let { return it.sessionSetPowerHint(handle, appState, powerTier) }
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
        testRuntime?.let { return it.sessionRequestProbe(handle) }
        if (!loaded || handle == 0L) return false
        return runCatching {
            nativeSessionRequestProbe(handle) == 1
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session request probe failed: ${it.message}")
            false
        }
    }

    fun sessionForceReconnect(handle: Long): Boolean {
        testRuntime?.let { return it.sessionForceReconnect(handle) }
        if (!loaded || handle == 0L) return false
        return runCatching {
            nativeSessionForceReconnect(handle) == 1
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session force reconnect failed: ${it.message}")
            false
        }
    }

    fun sessionPinTransport(handle: Long, transport: String, ttlMs: Long): Boolean {
        testRuntime?.let { return it.sessionPinTransport(handle, transport, ttlMs) }
        if (!loaded || handle == 0L) return false
        val normalizedTransport = transport.trim()
        if (normalizedTransport.isEmpty()) return false
        return runCatching {
            nativeSessionPinTransport(handle, normalizedTransport, ttlMs) == 1
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session pin transport failed: ${it.message}")
            false
        }
    }

    fun sessionClearPin(handle: Long): Boolean {
        testRuntime?.let { return it.sessionClearPin(handle) }
        if (!loaded || handle == 0L) return false
        return runCatching {
            nativeSessionClearPin(handle) == 1
        }.getOrElse {
            io.ethan.pushgo.util.SilentSink.w(TAG, "native session clear pin failed: ${it.message}")
            false
        }
    }

    @JvmStatic
    private external fun nativeAbiVersion(): Int

    @JvmStatic
    private external fun nativeSessionStart(configJson: String): Long

    @JvmStatic
    private external fun nativeSessionPollEventV2(handle: Long, timeoutMs: Int): String?

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

    @JvmStatic
    private external fun nativeSessionForceReconnect(handle: Long): Int

    @JvmStatic
    private external fun nativeSessionPinTransport(handle: Long, transport: String, ttlMs: Long): Int

    @JvmStatic
    private external fun nativeSessionClearPin(handle: Long): Int
}
