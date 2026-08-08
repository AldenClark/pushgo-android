package io.ethan.pushgo.notifications

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarpLinkNativeBridgeInstrumentedTest {
    private var handle: Long = 0L

    @Before
    fun useRealNativeRuntime() {
        WarpLinkNativeBridge.installTestRuntime(null)
    }

    @After
    fun stopNativeSession() {
        if (handle > 0L) {
            WarpLinkNativeBridge.sessionStop(handle)
            handle = 0L
        }
    }

    @Test
    fun realJni_abiStartPollAndStop_areConsistent() {
        assertTrue("Rust JNI library must load", WarpLinkNativeBridge.isAvailable())
        assertEquals(WarpLinkNativeBridge.ABI_VERSION, WarpLinkNativeBridge.abiVersion())

        handle = WarpLinkNativeBridge.sessionStart(
            JSONObject().apply {
                put("session_generation", 42L)
                put("host", "127.0.0.1")
                put("quic_enabled", false)
                put("tcp_enabled", false)
                put("wss_enabled", false)
                put("identity", "jni-contract-test")
                put("app_state", "foreground")
                put("perf_tier", "balanced")
            }.toString(),
        )
        assertTrue("native start must return an owned handle", handle > 0L)

        val first = WarpLinkNativeBridge.sessionPollEvent(handle, 2_000)
        assertTrue(
            "first native event must cross the real JNI boundary, got $first",
            first is WarpLinkNativeBridge.SessionPollResult.Event,
        )
        val event = JSONObject((first as WarpLinkNativeBridge.SessionPollResult.Event).eventJson)
        assertEquals("session_profile", event.getString("type"))
        assertEquals(42L, event.getLong("session_generation"))

        WarpLinkNativeBridge.sessionStop(handle)
        val stoppedHandle = handle
        handle = 0L
        assertEquals(
            WarpLinkNativeBridge.SessionPollResult.Error("invalid_handle"),
            WarpLinkNativeBridge.sessionPollEvent(stoppedHandle, 0),
        )
    }
}
