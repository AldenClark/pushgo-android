package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class InboundMessageProcessorTest {
    private object FakeRuntime : InboundProcessorRuntime

    @Test
    fun processWithRuntime_providerWakeup_onlyCallsProviderHandler() = runBlocking {
        val hooks = RecordingHooks(
            route = InboundIngressRoute.ProviderWakeupPull("delivery-1"),
        )
        val processor = InboundMessageProcessor()

        processor.processWithRuntime(
            runtime = FakeRuntime,
            hooks = hooks,
            messageData = mapOf("provider_wakeup" to "1"),
            transportMessageId = "transport-1",
        )

        assertEquals(1, hooks.providerPullCalls)
        assertFalse(hooks.parseCalled)
        assertFalse(hooks.persistCalled)
        assertFalse(hooks.ackCalled)
    }

    @Test
    fun processWithRuntime_dropRoute_shortCircuitsHandlers() = runBlocking {
        val hooks = RecordingHooks(
            route = InboundIngressRoute.Drop("bad payload"),
        )
        val processor = InboundMessageProcessor()

        processor.processWithRuntime(
            runtime = FakeRuntime,
            hooks = hooks,
            messageData = emptyMap(),
            transportMessageId = null,
        )

        assertEquals(0, hooks.providerPullCalls)
        assertFalse(hooks.parseCalled)
        assertFalse(hooks.persistCalled)
        assertFalse(hooks.ackCalled)
    }

    @Test
    fun processWithRuntime_directRoute_parseNull_skipsPersistAndAck() = runBlocking {
        val hooks = RecordingHooks(
            route = InboundIngressRoute.Direct,
            parsedRequest = null,
        )
        val processor = InboundMessageProcessor()

        processor.processWithRuntime(
            runtime = FakeRuntime,
            hooks = hooks,
            messageData = mapOf("entity_type" to "message"),
            transportMessageId = "transport-2",
        )

        assertTrue(hooks.parseCalled)
        assertFalse(hooks.persistCalled)
        assertFalse(hooks.ackCalled)
    }

    @Test
    fun processWithRuntime_directRoute_withParsedRequest_callsPersistAndAck() = runBlocking {
        val request = sampleMessageRequest()
        val outcome = InboundPersistenceOutcome(
            status = InboundPersistenceStatus.PERSISTED_MAIN,
            notified = true,
            shouldAck = true,
        )
        val hooks = RecordingHooks(
            route = InboundIngressRoute.Direct,
            parsedRequest = request,
            persistOutcome = outcome,
        )
        val processor = InboundMessageProcessor()

        processor.processWithRuntime(
            runtime = FakeRuntime,
            hooks = hooks,
            messageData = mapOf("entity_type" to "message"),
            transportMessageId = "transport-3",
        )

        assertTrue(hooks.parseCalled)
        assertTrue(hooks.persistCalled)
        assertTrue(hooks.ackCalled)
        assertEquals(request, hooks.persistInput)
        assertEquals(request, hooks.ackInput)
        assertEquals(outcome, hooks.ackOutcome)
    }

    private fun sampleMessageRequest(): InboundPersistenceRequest.Message {
        val message = PushMessage(
            id = UUID.randomUUID().toString(),
            messageId = "message-1",
            title = "title",
            body = "body",
            channel = "channel-1",
            url = null,
            isRead = false,
            receivedAt = Instant.ofEpochMilli(1_700_000_000_000),
            rawPayloadJson = """{"delivery_id":"delivery-1"}""",
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = "transport-3",
            serverId = null,
        )
        return InboundPersistenceRequest.Message(
            message = message,
            level = "high",
            imageUrl = null,
            shouldNotify = true,
        )
    }

    private class RecordingHooks(
        private val route: InboundIngressRoute,
        private val parsedRequest: InboundPersistenceRequest? = sampleFallbackRequest(),
        private val persistOutcome: InboundPersistenceOutcome = InboundPersistenceOutcome(
            status = InboundPersistenceStatus.DUPLICATE,
            notified = false,
            shouldAck = false,
        ),
    ) : InboundProcessorHooks {
        var providerPullCalls: Int = 0
            private set
        var parseCalled: Boolean = false
            private set
        var persistCalled: Boolean = false
            private set
        var ackCalled: Boolean = false
            private set
        var persistInput: InboundPersistenceRequest? = null
            private set
        var ackInput: InboundPersistenceRequest? = null
            private set
        var ackOutcome: InboundPersistenceOutcome? = null
            private set

        override fun resolveRoute(messageData: Map<String, String>): InboundIngressRoute = route

        override suspend fun handleProviderWakeupPull(
            runtime: InboundProcessorRuntime,
            deliveryId: String,
        ) {
            providerPullCalls += 1
        }

        override suspend fun parseDirect(
            runtime: InboundProcessorRuntime,
            messageData: Map<String, String>,
            transportMessageId: String?,
        ): InboundPersistenceRequest? {
            parseCalled = true
            return parsedRequest
        }

        override suspend fun persistDirect(
            runtime: InboundProcessorRuntime,
            parsed: InboundPersistenceRequest,
        ): InboundPersistenceOutcome {
            persistCalled = true
            persistInput = parsed
            return persistOutcome
        }

        override suspend fun ackDirect(
            runtime: InboundProcessorRuntime,
            parsed: InboundPersistenceRequest,
            outcome: InboundPersistenceOutcome,
        ) {
            ackCalled = true
            ackInput = parsed
            ackOutcome = outcome
        }

        companion object {
            private fun sampleFallbackRequest(): InboundPersistenceRequest.Message {
                val message = PushMessage(
                    id = "m-fallback",
                    messageId = "m-fallback",
                    title = "fallback",
                    body = "fallback",
                    channel = null,
                    url = null,
                    isRead = false,
                    receivedAt = Instant.ofEpochMilli(1_700_000_000_000),
                    rawPayloadJson = "{}",
                    status = MessageStatus.NORMAL,
                    decryptionState = null,
                    notificationId = null,
                    serverId = null,
                )
                return InboundPersistenceRequest.Message(
                    message = message,
                    level = null,
                    imageUrl = null,
                    shouldNotify = false,
                )
            }
        }
    }
}
