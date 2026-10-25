package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
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
    fun processWithRuntime_fullInlineAdvertisementOnlyPullsAndForwardsUntrustedConstraints() = runBlocking {
        val payload = mapOf("entity_type" to "message", "delivery_id" to "delivery-full",
            "title" to "Inline must not persist", "body" to "Untrusted body",
            "base_url" to "https://advertised.invalid", "provider_device_key" to "advertised-device")
        val hooks = RecordingHooks(route = InboundIngressRouteResolver.resolve(payload))
        InboundMessageProcessor().processWithRuntime(FakeRuntime, hooks, payload, "untrusted-transport-id")

        assertEquals(1, hooks.providerPullCalls)
        assertEquals("delivery-full", hooks.providerPullDeliveryId)
        assertEquals("https://advertised.invalid", hooks.expectedGatewayUrl)
        assertEquals("advertised-device", hooks.expectedDeviceKey)
        assertTrue(hooks.strictConfiguredTarget)
        assertFalse(hooks.parseCalled)
        assertFalse(hooks.persistCalled)
        assertFalse(hooks.ackCalled)
    }

    @Test
    fun processWithRuntime_failedTargetedPullThrowsWithoutInlineFallbackOrEarlyAck() = runBlocking {
        val payload = mapOf("delivery_id" to "delivery-pull-fails", "title" to "Inline fallback forbidden", "body" to "retained by Worker")
        val originalFailure = InboundMessageProcessor.InboundRetryableException("synthetic targeted Pull unavailable")
        val hooks = RecordingHooks(route = InboundIngressRouteResolver.resolve(payload), providerFailure = originalFailure)
        val failure = runCatching {
            InboundMessageProcessor().processWithRuntime(FakeRuntime, hooks, payload, "untrusted-transport-id")
        }.exceptionOrNull()

        assertSame(originalFailure, failure)
        assertEquals(1, hooks.providerPullCalls)
        assertEquals("delivery-pull-fails", hooks.providerPullDeliveryId)
        assertEquals(null, hooks.expectedGatewayUrl)
        assertEquals(null, hooks.expectedDeviceKey)
        assertTrue(hooks.strictConfiguredTarget)
        assertFalse(hooks.parseCalled)
        assertFalse(hooks.persistCalled)
        assertFalse(hooks.ackCalled)
    }

    @Test
    fun processWithRuntime_missingDeliveryIsUnsupportedWithoutParsingOrAcknowledgingInlineContent() = runBlocking {
        val payload = mapOf("title" to "Default Message without entity_type", "body" to "Must not become an inline receipt")
        val hooks = RecordingHooks(route = InboundIngressRouteResolver.resolve(payload))
        val failure = runCatching {
            InboundMessageProcessor().processWithRuntime(FakeRuntime, hooks, payload, "transport-is-not-a-delivery")
        }.exceptionOrNull()

        assertTrue(failure is io.ethan.pushgo.data.ChannelSubscriptionException)
        assertFalse(shouldRetryInboundFailure(requireNotNull(failure)))
        assertEquals(0, hooks.providerPullCalls)
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

    // Explicit fake-hook Direct routes retain internal compatibility; the real
    // resolver no longer sends FCM content through these inline branches.
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

    @Test
    fun processWithRuntime_failedPersistenceThrowsAndNeverAcks() = runBlocking {
        val hooks = RecordingHooks(
            route = InboundIngressRoute.Direct,
            parsedRequest = sampleMessageRequest(),
            persistOutcome = InboundPersistenceOutcome(
                status = InboundPersistenceStatus.FAILED,
                notified = false,
                shouldAck = false,
            ),
        )

        val failure = runCatching {
            InboundMessageProcessor().processWithRuntime(
                runtime = FakeRuntime,
                hooks = hooks,
                messageData = mapOf("entity_type" to "message"),
                transportMessageId = "transport-failed",
            )
        }.exceptionOrNull()

        assertTrue(failure is InboundMessageProcessor.InboundRetryableException)
        assertTrue(hooks.persistCalled)
        assertFalse(hooks.ackCalled)
    }

    @Test
    fun duplicateReplay_resolvesCanonicalLocalRecordByStableMessageId() = runBlocking {
        val replay = sampleMessageRequest().message.copy(id = "transient-random-id")
        val canonical = replay.copy(id = "canonical-local-id", title = "stored title")
        var loadedStableId: String? = null

        val resolved = resolveCanonicalMessageForReplay(replay) { stableId ->
            loadedStableId = stableId
            canonical
        }

        assertEquals("message-1", loadedStableId)
        assertSame(canonical, resolved)
        assertEquals("canonical-local-id", resolved?.id)
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
        private val providerFailure: Throwable? = null,
    ) : InboundProcessorHooks {
        var providerPullCalls: Int = 0
            private set
        var providerPullDeliveryId: String? = null
            private set
        var expectedGatewayUrl: String? = null
            private set
        var expectedDeviceKey: String? = null
            private set
        var strictConfiguredTarget: Boolean = false
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
            expectedGatewayUrl: String?,
            expectedDeviceKey: String?,
            strictConfiguredTarget: Boolean,
        ) {
            providerPullCalls += 1
            providerPullDeliveryId = deliveryId
            this.expectedGatewayUrl = expectedGatewayUrl
            this.expectedDeviceKey = expectedDeviceKey
            this.strictConfiguredTarget = strictConfiguredTarget
            providerFailure?.let { throw it }
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
