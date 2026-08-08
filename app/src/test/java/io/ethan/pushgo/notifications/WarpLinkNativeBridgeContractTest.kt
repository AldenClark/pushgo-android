package io.ethan.pushgo.notifications

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpLinkNativeBridgeContractTest {
    @Test
    fun cancelledStart_stopsHandleReturnedAfterCancellation() = runBlocking {
        val startEntered = CountDownLatch(1)
        val allowStartToReturn = CountDownLatch(1)
        val stoppedHandle = AtomicLong(0L)
        val runtime = BlockingStartRuntime(startEntered, allowStartToReturn, stoppedHandle)
        WarpLinkNativeBridge.installTestRuntime(runtime)
        try {
            val job = launch(Dispatchers.Default) {
                startNativeSessionCancellationSafe("{}")
            }
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))
            job.cancel()
            allowStartToReturn.countDown()
            job.cancelAndJoin()
            assertEquals(77L, stoppedHandle.get())
        } finally {
            allowStartToReturn.countDown()
            WarpLinkNativeBridge.installTestRuntime(null)
        }
    }

    @Test
    fun pollEnvelope_decodesEveryTerminalStatusWithoutNullConflation() {
        assertEquals(
            WarpLinkNativeBridge.SessionPollResult.Timeout,
            WarpLinkNativeBridge.decodePollResult("{\"status\":\"timeout\"}"),
        )
        assertEquals(
            WarpLinkNativeBridge.SessionPollResult.Closed,
            WarpLinkNativeBridge.decodePollResult("{\"status\":\"closed\"}"),
        )
        assertEquals(
            WarpLinkNativeBridge.SessionPollResult.Error("invalid_handle"),
            WarpLinkNativeBridge.decodePollResult(
                "{\"status\":\"error\",\"code\":\"invalid_handle\"}",
            ),
        )
        assertEquals(
            WarpLinkNativeBridge.SessionPollResult.Event("{\"type\":\"welcome\"}"),
            WarpLinkNativeBridge.decodePollResult(
                "{\"status\":\"event\",\"event\":{\"type\":\"welcome\"}}",
            ),
        )
    }

    @Test
    fun nativeDeclarations_matchVersionedContract() {
        val nativeMethods = WarpLinkNativeBridge::class.java.declaredMethods
            .filter { method -> method.name.startsWith("native") }
            .associateBy { method -> method.name }

        assertEquals(Int::class.javaPrimitiveType, nativeMethods.getValue("nativeAbiVersion").returnType)
        assertEquals(
            listOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            nativeMethods.getValue("nativeSessionPollEventV2").parameterTypes.toList(),
        )
        assertEquals(
            String::class.java,
            nativeMethods.getValue("nativeSessionPollEventV2").returnType,
        )
        assertTrue(nativeMethods.keys.none { method -> method == "nativeSessionPollEvent" })
    }
}

private class BlockingStartRuntime(
    private val startEntered: CountDownLatch,
    private val allowStartToReturn: CountDownLatch,
    private val stoppedHandle: AtomicLong,
) : WarpLinkNativeBridge.SessionRuntime {
    override fun isAvailable(): Boolean = true

    override fun sessionStart(configJson: String): Long {
        startEntered.countDown()
        check(allowStartToReturn.await(5, TimeUnit.SECONDS))
        return 77L
    }

    override fun sessionPollEvent(
        handle: Long,
        timeoutMs: Int,
    ): WarpLinkNativeBridge.SessionPollResult = WarpLinkNativeBridge.SessionPollResult.Timeout

    override fun sessionStop(handle: Long) {
        stoppedHandle.set(handle)
    }

    override fun sessionReplaceAuthToken(handle: Long, authToken: String?): Boolean = true
    override fun sessionResolveMessage(handle: Long, ackId: Long, status: Int): Boolean = true
    override fun sessionSetPowerHint(handle: Long, appState: String?, powerTier: String?): Boolean = true
    override fun sessionRequestProbe(handle: Long): Boolean = true
    override fun sessionForceReconnect(handle: Long): Boolean = true
    override fun sessionPinTransport(handle: Long, transport: String, ttlMs: Long): Boolean = true
    override fun sessionClearPin(handle: Long): Boolean = true
}
