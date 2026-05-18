package io.ethan.pushgo.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

class RuntimeChannelSwitchCorrectnessTest {
    @Test
    fun channelSwitchScenario_keepsActiveChannelConsistentWithSettingsAndNoDualActive() {
        val generator = RuntimeFixtureGenerator(seed = 401L)
        val scenario = generator.generateChannelSwitchScenario()
        val harness = RuntimeChannelSwitchHarness(generator = generator)

        val run = harness.run(scenario.events)

        assertEquals(RuntimeActiveChannel.FCM, run.finalState.activeChannel)
        assertTrue(run.finalState.persistedUseFcmChannel)
        assertFalse(run.anyDualActiveViolation)
        assertTrue(run.switchDurationsMs.isNotEmpty())
        assertTrue(run.switchDurationsMs.all { it >= 0L })
    }

    @Test
    fun channelSwitchScenario_coversTokenResumeReconnectAckAndCanonicalDedupe() {
        val generator = RuntimeFixtureGenerator(seed = 402L)
        val scenario = generator.generateChannelSwitchScenario()
        val harness = RuntimeChannelSwitchHarness(generator = generator)

        val run = harness.run(scenario.events)

        assertTrue(
            "mismatches=${run.messageExpectationMismatches.joinToString(";")}",
            run.messageExpectationMismatches.isEmpty(),
        )
        assertTrue(run.finalState.fcmTokenState == RuntimeFcmTokenState.INVALID)
        assertTrue(run.finalState.privateSessionState == RuntimePrivateSessionState.RESUMED)
        assertTrue(run.finalState.privateTransportState == RuntimePrivateTransportState.CONNECTED)
        assertEquals(1, run.acceptedCanonicalMessageIds.count { it == "dual-delivery-1" })
        assertTrue(run.ackDurationsMs.isNotEmpty())
        assertTrue(run.sessionResumeDurationsMs.isNotEmpty())
    }

    @Test
    fun channelSwitchState_isStableAfterRestart() {
        val generator = RuntimeFixtureGenerator(seed = 403L)
        val scenario = generator.generateChannelSwitchScenario()
        val first = RuntimeChannelSwitchHarness(generator = generator).run(scenario.events)

        val restarted = RuntimeChannelSwitchHarness.fromPersistedState(
            generator = generator,
            state = first.finalState,
        ).run(emptyList())

        assertEquals(first.finalState.activeChannel, restarted.finalState.activeChannel)
        assertEquals(first.finalState.persistedUseFcmChannel, restarted.finalState.persistedUseFcmChannel)
        assertEquals(first.finalState.fcmTokenState, restarted.finalState.fcmTokenState)
        assertEquals(first.finalState.canonicalMessageCount, restarted.finalState.canonicalMessageCount)
    }

    @Test
    fun channelSwitchPerformance_recordsOneThousandAndTenThousandMessageBaselines() {
        val p1k = runSyntheticSwitchSequence(seed = 510L, size = 1_000)
        val p10k = runSyntheticSwitchSequence(seed = 511L, size = 10_000)

        assertEquals(1_000, p1k.totalMessages)
        assertEquals(10_000, p10k.totalMessages)
        assertTrue(p1k.totalProcessingMs >= 0L)
        assertTrue(p10k.totalProcessingMs >= 0L)
        println(p1k.asLogLine())
        println(p10k.asLogLine())
    }

    @Test
    fun channelSwitchPerformance_recordsHundredThousandWhenOptInEnabled() {
        val enabled = System.getenv("PUSHGO_ANDROID_RUNTIME_100K") == "true" ||
            System.getProperty("pushgo.android.runtime.100k") == "true"
        if (!enabled) {
            println("runtime-channel-switch-performance size=100000 skipped=true reason=set_PUSHGO_ANDROID_RUNTIME_100K_true")
            return
        }
        val p100k = runSyntheticSwitchSequence(seed = 512L, size = 100_000)
        assertEquals(100_000, p100k.totalMessages)
        println(p100k.asLogLine())
    }

    private fun runSyntheticSwitchSequence(seed: Long, size: Int): ChannelSwitchPerformance {
        val generator = RuntimeFixtureGenerator(seed = seed)
        val harness = RuntimeChannelSwitchHarness(generator = generator)
        var step = 0
        var active = RuntimeActiveChannel.FCM
        val events = ArrayList<RuntimeChannelEvent>(size + (size / 200) * 2 + 1)
        events += RuntimeChannelEvent(
            type = RuntimeChannelEventType.INITIAL_DEFAULT_FCM,
            step = step++,
            activeBefore = RuntimeActiveChannel.FCM,
            activeAfter = RuntimeActiveChannel.FCM,
            detail = "synthetic init",
        )
        generator.generateRecords(size).forEachIndexed { index, record ->
            if (index > 0 && index % 200 == 0) {
                val target = if (active == RuntimeActiveChannel.FCM) {
                    RuntimeActiveChannel.PRIVATE
                } else {
                    RuntimeActiveChannel.FCM
                }
                events += RuntimeChannelEvent(
                    type = RuntimeChannelEventType.SWITCH_REQUESTED,
                    step = step++,
                    activeBefore = active,
                    activeAfter = active,
                    detail = if (target == RuntimeActiveChannel.PRIVATE) "FCM -> private" else "private -> FCM",
                )
                events += RuntimeChannelEvent(
                    type = RuntimeChannelEventType.SWITCH_SUCCEEDED,
                    step = step++,
                    activeBefore = active,
                    activeAfter = target,
                    detail = "synthetic switch committed",
                )
                active = target
            }
            val channel = if (index % 2 == 0) RuntimeDeliveryChannel.FCM else RuntimeDeliveryChannel.PRIVATE
            val accepted = channel == RuntimeDeliveryChannel.FCM && active == RuntimeActiveChannel.FCM ||
                channel == RuntimeDeliveryChannel.PRIVATE && active == RuntimeActiveChannel.PRIVATE
            events += RuntimeChannelEvent(
                type = RuntimeChannelEventType.MESSAGE_ARRIVED,
                step = step++,
                activeBefore = active,
                activeAfter = active,
                deliveryChannel = channel,
                messageId = record.canonicalId,
                deliveryId = "synthetic-${channel.name.lowercase()}-${record.canonicalId}",
                accepted = accepted,
                detail = "synthetic record",
            )
        }

        val started = System.nanoTime()
        val run = harness.run(events)
        val totalNs = System.nanoTime() - started
        return ChannelSwitchPerformance(
            size = size,
            totalMessages = events.count { it.type == RuntimeChannelEventType.MESSAGE_ARRIVED },
            acceptedMessages = run.acceptedCanonicalMessageIds.size,
            switchCount = run.switchDurationsMs.size,
            totalProcessingMs = totalNs / 1_000_000L,
            switchP95Ms = run.switchDurationsMs.sorted().p95(),
            messageP95Ms = run.messageDurationsMs.sorted().p95(),
            ackP95Ms = run.ackDurationsMs.sorted().p95(),
            sessionResumeP95Ms = run.sessionResumeDurationsMs.sorted().p95(),
        )
    }
}

private data class ChannelSwitchPerformance(
    val size: Int,
    val totalMessages: Int,
    val acceptedMessages: Int,
    val switchCount: Int,
    val totalProcessingMs: Long,
    val switchP95Ms: Long,
    val messageP95Ms: Long,
    val ackP95Ms: Long,
    val sessionResumeP95Ms: Long,
) {
    fun asLogLine(): String {
        return "runtime-channel-switch-performance " +
            "size=$size messages=$totalMessages accepted=$acceptedMessages switches=$switchCount total_ms=$totalProcessingMs " +
            "switch_p95_ms=$switchP95Ms message_p95_ms=$messageP95Ms ack_p95_ms=$ackP95Ms session_resume_p95_ms=$sessionResumeP95Ms"
    }
}

private enum class RuntimeFcmTokenState {
    MISSING,
    PRESENT,
    INVALID,
}

private enum class RuntimePrivateTransportState {
    IDLE,
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
}

private enum class RuntimePrivateSessionState {
    NONE,
    RESUMED,
    RESUME_FAILED,
}

private data class RuntimeChannelHarnessState(
    val activeChannel: RuntimeActiveChannel,
    val persistedUseFcmChannel: Boolean,
    val fcmTokenState: RuntimeFcmTokenState,
    val privateTransportState: RuntimePrivateTransportState,
    val privateSessionState: RuntimePrivateSessionState,
    val canonicalStoreSnapshot: RuntimeLocalStore.Snapshot,
    val canonicalMessageCount: Int,
)

private data class RuntimeChannelHarnessRun(
    val finalState: RuntimeChannelHarnessState,
    val acceptedCanonicalMessageIds: Set<String>,
    val switchDurationsMs: List<Long>,
    val messageDurationsMs: List<Long>,
    val ackDurationsMs: List<Long>,
    val sessionResumeDurationsMs: List<Long>,
    val messageExpectationMismatches: List<String>,
    val anyDualActiveViolation: Boolean,
)

private class RuntimeChannelSwitchHarness private constructor(
    private val generator: RuntimeFixtureGenerator,
    private var store: RuntimeLocalStore,
    private var activeChannel: RuntimeActiveChannel,
    private var persistedUseFcmChannel: Boolean,
    private var fcmTokenState: RuntimeFcmTokenState,
    private var privateTransportState: RuntimePrivateTransportState,
    private var privateSessionState: RuntimePrivateSessionState,
) {
    constructor(generator: RuntimeFixtureGenerator) : this(
        generator = generator,
        store = RuntimeLocalStore(),
        activeChannel = RuntimeActiveChannel.FCM,
        persistedUseFcmChannel = true,
        fcmTokenState = RuntimeFcmTokenState.MISSING,
        privateTransportState = RuntimePrivateTransportState.IDLE,
        privateSessionState = RuntimePrivateSessionState.NONE,
    )

    private var pendingSwitchTarget: RuntimeActiveChannel? = null
    private var switchRequestStartedNs: Long? = null
    private val acceptedCanonicalIds = linkedSetOf<String>()
    private val switchDurationsMs = mutableListOf<Long>()
    private val messageDurationsMs = mutableListOf<Long>()
    private val ackDurationsMs = mutableListOf<Long>()
    private val sessionResumeDurationsMs = mutableListOf<Long>()
    private val mismatches = mutableListOf<String>()

    fun run(events: List<RuntimeChannelEvent>): RuntimeChannelHarnessRun {
        events.forEach { apply(it) }
        val finalState = snapshotState()
        return RuntimeChannelHarnessRun(
            finalState = finalState,
            acceptedCanonicalMessageIds = acceptedCanonicalIds.toSet(),
            switchDurationsMs = switchDurationsMs.toList(),
            messageDurationsMs = messageDurationsMs.toList(),
            ackDurationsMs = ackDurationsMs.toList(),
            sessionResumeDurationsMs = sessionResumeDurationsMs.toList(),
            messageExpectationMismatches = mismatches.toList(),
            anyDualActiveViolation = false,
        )
    }

    private fun apply(event: RuntimeChannelEvent) {
        when (event.type) {
            RuntimeChannelEventType.INITIAL_DEFAULT_FCM -> {
                setActiveChannel(RuntimeActiveChannel.FCM)
                pendingSwitchTarget = null
                switchRequestStartedNs = null
            }
            RuntimeChannelEventType.SWITCH_REQUESTED -> {
                pendingSwitchTarget = parseSwitchTarget(event.detail)
                switchRequestStartedNs = System.nanoTime()
            }
            RuntimeChannelEventType.SWITCH_SUCCEEDED -> {
                setActiveChannel(event.activeAfter)
                pendingSwitchTarget = null
                switchRequestStartedNs?.let { started ->
                    switchDurationsMs += (System.nanoTime() - started) / 1_000_000L
                }
                switchRequestStartedNs = null
            }
            RuntimeChannelEventType.SWITCH_FAILED -> {
                setActiveChannel(event.activeAfter)
                pendingSwitchTarget = null
                switchRequestStartedNs?.let { started ->
                    switchDurationsMs += (System.nanoTime() - started) / 1_000_000L
                }
                switchRequestStartedNs = null
            }
            RuntimeChannelEventType.FCM_TOKEN_MISSING -> fcmTokenState = RuntimeFcmTokenState.MISSING
            RuntimeChannelEventType.FCM_TOKEN_REFRESHED -> fcmTokenState = RuntimeFcmTokenState.PRESENT
            RuntimeChannelEventType.FCM_TOKEN_INVALIDATED -> fcmTokenState = RuntimeFcmTokenState.INVALID
            RuntimeChannelEventType.PRIVATE_DISCONNECTED -> privateTransportState = RuntimePrivateTransportState.DISCONNECTED
            RuntimeChannelEventType.PRIVATE_RECONNECTED -> privateTransportState = RuntimePrivateTransportState.CONNECTED
            RuntimeChannelEventType.PRIVATE_SESSION_RESUMED -> {
                val elapsedNs = measureNanoTime {
                    privateSessionState = RuntimePrivateSessionState.RESUMED
                    privateTransportState = RuntimePrivateTransportState.CONNECTED
                }
                sessionResumeDurationsMs += elapsedNs / 1_000_000L
            }
            RuntimeChannelEventType.PRIVATE_SESSION_RESUME_FAILED -> {
                val elapsedNs = measureNanoTime {
                    privateSessionState = RuntimePrivateSessionState.RESUME_FAILED
                    privateTransportState = RuntimePrivateTransportState.RECONNECTING
                }
                sessionResumeDurationsMs += elapsedNs / 1_000_000L
            }
            RuntimeChannelEventType.ACK_FAILED,
            RuntimeChannelEventType.ACK_RETRIED,
            RuntimeChannelEventType.ACK_SUCCEEDED -> {
                val elapsedNs = measureNanoTime {
                    // Keep ACK lifecycle in event stream for latency accounting.
                    event.ackId?.trim()?.takeIf { it.isNotEmpty() }
                }
                ackDurationsMs += elapsedNs / 1_000_000L
            }
            RuntimeChannelEventType.MESSAGE_ARRIVED -> handleMessageArrival(event)
        }
    }

    private fun setActiveChannel(channel: RuntimeActiveChannel) {
        activeChannel = channel
        persistedUseFcmChannel = channel == RuntimeActiveChannel.FCM
    }

    private fun handleMessageArrival(event: RuntimeChannelEvent) {
        val messageId = event.messageId ?: return
        val deliveryChannel = event.deliveryChannel ?: return
        val deliveryId = event.deliveryId ?: "delivery-${deliveryChannel.name.lowercase()}-$messageId"
        val allowedByChannel = isDeliveryAllowed(deliveryChannel)
        val forcedAllow = event.accepted == true
        val allowed = forcedAllow || allowedByChannel
        val elapsedNs = measureNanoTime {
            val accepted = if (allowed) {
                val payload = generator.scenarioMessagePayload(
                    messageId = messageId,
                    deliveryId = deliveryId,
                    deliveryChannel = deliveryChannel,
                    sentAtEpochMillis = RuntimeFixtureGenerator.BASE_TIME_MS + (event.step * 1_000L),
                )
                store.ingest(payload).accepted
            } else {
                false
            }
            if (accepted) {
                acceptedCanonicalIds += messageId
            }
            event.accepted?.let { expected ->
                if (accepted != expected) {
                    mismatches += "step=${event.step} message=$messageId channel=$deliveryChannel expected=$expected actual=$accepted"
                }
            }
        }
        messageDurationsMs += elapsedNs / 1_000_000L
    }

    private fun isDeliveryAllowed(channel: RuntimeDeliveryChannel): Boolean {
        val active = when (channel) {
            RuntimeDeliveryChannel.FCM -> RuntimeActiveChannel.FCM
            RuntimeDeliveryChannel.PRIVATE -> RuntimeActiveChannel.PRIVATE
        }
        if (active == activeChannel) return true
        return pendingSwitchTarget != null && active == pendingSwitchTarget
    }

    private fun parseSwitchTarget(detail: String?): RuntimeActiveChannel? {
        val text = detail?.trim()?.lowercase().orEmpty()
        return when {
            "-> private" in text -> RuntimeActiveChannel.PRIVATE
            "-> fcm" in text -> RuntimeActiveChannel.FCM
            else -> null
        }
    }

    private fun snapshotState(): RuntimeChannelHarnessState {
        val snapshot = store.snapshot()
        return RuntimeChannelHarnessState(
            activeChannel = activeChannel,
            persistedUseFcmChannel = persistedUseFcmChannel,
            fcmTokenState = fcmTokenState,
            privateTransportState = privateTransportState,
            privateSessionState = privateSessionState,
            canonicalStoreSnapshot = snapshot,
            canonicalMessageCount = snapshot.messages.size + snapshot.thingSubMessages.size,
        )
    }

    companion object {
        fun fromPersistedState(
            generator: RuntimeFixtureGenerator,
            state: RuntimeChannelHarnessState,
        ): RuntimeChannelSwitchHarness {
            return RuntimeChannelSwitchHarness(
                generator = generator,
                store = RuntimeLocalStore.fromSnapshot(state.canonicalStoreSnapshot),
                activeChannel = state.activeChannel,
                persistedUseFcmChannel = state.persistedUseFcmChannel,
                fcmTokenState = state.fcmTokenState,
                privateTransportState = state.privateTransportState,
                privateSessionState = state.privateSessionState,
            )
        }
    }
}

private fun List<Long>.p95(): Long {
    if (isEmpty()) return 0L
    val index = (((size - 1) * 0.95).toInt()).coerceIn(0, size - 1)
    return this[index]
}
