package io.ethan.pushgo.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.FirebasePushTokenProvider
import io.ethan.pushgo.notifications.ProviderIngressCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProviderGatewayIntegrationDeviceTest {
    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var baseUrl: String
    private lateinit var token: String
    private lateinit var deviceToken: String
    private var enabled: Boolean = false
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        container = AppContainer(context, appScope)
        val args = InstrumentationRegistry.getArguments()
        enabled = args.getString("pushgo.runtime.providerIntegration")?.trim()?.toBooleanStrictOrNull() == true
        assumeTrue(
            "provider gateway integration is disabled by default; pass -e pushgo.runtime.providerIntegration true",
            enabled,
        )
        baseUrl = args.getString("pushgoGatewayBaseUrl")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "https://sandbox.pushgo.dev"
        token = args.getString("pushgoGatewayToken")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "integration-token"
        check(!baseUrl.contains("gateway.pushgo.cn")) { "production gateway is forbidden for provider integration tests: $baseUrl" }
        deviceToken = args.getString("pushgoProviderFcmToken")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: FirebasePushTokenProvider().fetchToken(timeoutMs = 30_000L)
            ?: error("timed out waiting for a real Firebase FCM token")

        val health = request(
            method = "GET",
            path = "/healthz",
            body = null,
        )
        check(health.code == 200) {
            "gateway is not reachable from device baseUrl=$baseUrl code=${health.code} body=${health.body}"
        }

        container.settingsRepository.setServerAddress(baseUrl)
        container.settingsRepository.setGatewayToken(token)
        container.settingsRepository.setUseFcmChannel(true)
        container.settingsRepository.setFcmToken(deviceToken)
        container.messageRepository.deleteAll()
        container.entityRepository.deleteAll()
        container.inboundDeliveryLedgerRepository.clearAll()
    }

    @After
    fun tearDown() {
        appScope.cancel()
    }

    @Test
    fun pull_with_and_without_deliveryId_matches_gateway_contract() = runBlocking {
        // An invalid provider registration prevents background FCM ingestion from
        // racing the explicit-pull assertions while Gateway still materializes the
        // durable provider-pull queue and exercises terminal delivery failure.
        val pullOnlyRegistration = UUID.randomUUID().toString()
        val deviceKey = container.channelRepository.syncProviderDeviceToken(pullOnlyRegistration)
        val password = "benchmark-123"
        val alias = "it-provider-${System.currentTimeMillis()}"
        val opSuffix = UUID.randomUUID().toString().replace("-", "")
        val subscription = container.channelRepository.createChannel(alias, password, pullOnlyRegistration)
        val channelId = subscription.channelId

        sendMessage(channelId, password, "it-op-msg-1-$opSuffix")
        sendMessage(channelId, password, "it-op-msg-2-$opSuffix")

        val queued = waitForPullItems(expected = 2)
        val first = queued.first().deliveryId

        val persistedSingle = ProviderIngressCoordinator.pullPersistAndDrainAcks(
            context = context,
            channelRepository = container.channelRepository,
            messageRepository = container.messageRepository,
            entityRepository = container.entityRepository,
            inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
            settingsRepository = container.settingsRepository,
            deliveryId = first,
        )
        assertEquals(1, persistedSingle)
        assertEquals(1, container.messageRepository.totalCount())

        val persistedRest = ProviderIngressCoordinator.pullPersistAndDrainAcks(
            context = context,
            channelRepository = container.channelRepository,
            messageRepository = container.messageRepository,
            entityRepository = container.entityRepository,
            inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
            settingsRepository = container.settingsRepository,
            deliveryId = null,
        )
        assertEquals(1, persistedRest)
        assertEquals(2, container.messageRepository.totalCount())

        val emptyPull = container.channelRepository.pullMessages(null)
        assertTrue(emptyPull.items.isEmpty())

        val ackRemoved = container.channelRepository.ackMessage(first)
        assertFalse("ack should be idempotent and non-failing when already removed", ackRemoved)

        val resolvedAgain = container.channelRepository.syncProviderDeviceToken(deviceToken)
        assertEquals(deviceKey, resolvedAgain)
    }

    @Test
    fun pull_persists_message_event_and_thing_projections() = runBlocking {
        container.channelRepository.syncProviderDeviceToken(deviceToken)
        val password = "benchmark-123"
        val alias = "it-entity-${System.currentTimeMillis()}"
        val opSuffix = UUID.randomUUID().toString().replace("-", "")
        val subscription = container.channelRepository.createChannel(alias, password, deviceToken)
        val channelId = subscription.channelId

        val (thingId, thingOpId) = sendThingCreate(
            channelId,
            password,
            "it-op-thing-create-$opSuffix",
        )
        val topEventOpId = sendEventCreate(
            channelId,
            password,
            "it-op-event-top-$opSuffix",
            thingId = null,
        )
        val subEventOpId = sendEventCreate(
            channelId,
            password,
            "it-op-event-sub-$opSuffix",
            thingId = thingId,
        )
        val messageOpId = sendMessage(channelId, password, "it-op-msg-entity-flow-$opSuffix")

        listOf(thingOpId, topEventOpId, subEventOpId, messageOpId).forEach { opId ->
            waitForAcceptedSendStatus(opId)
        }
        waitForProjectionCounts(messages = 1, events = 1, things = 1)
        assertEquals(1, container.messageRepository.totalCount())
        assertEquals(1, container.entityRepository.eventCount())
        assertEquals(1, container.entityRepository.thingCount())
        waitForPullQueueToDrain()
    }

    private fun sendMessage(channelId: String, password: String, marker: String): String {
        val body = JSONObject()
            .put("channel_id", channelId)
            .put("password", password)
            .put("title", "msg-$marker")
            .put("body", "message body $marker")
        val response = request("POST", "/message", body)
        check(response.code == 200) {
            "send message failed code=${response.code} body=${response.body}"
        }
        return response.requiredDataId("op_id")
    }

    private fun sendThingCreate(
        channelId: String,
        password: String,
        marker: String,
    ): Pair<String, String> {
        val now = Instant.now().epochSecond
        val body = JSONObject()
            .put("channel_id", channelId)
            .put("password", password)
            .put("title", "thing-$marker")
            .put("description", "thing desc")
            .put("observed_at", now)
        val response = request("POST", "/thing/create", body)
        check(response.code == 200) {
            "thing/create failed code=${response.code} body=${response.body}"
        }
        return response.requiredDataId("thing_id") to response.requiredDataId("op_id")
    }

    private fun sendEventCreate(
        channelId: String,
        password: String,
        marker: String,
        thingId: String?,
    ): String {
        val now = Instant.now().epochSecond
        val body = JSONObject()
            .put("channel_id", channelId)
            .put("password", password)
            .put("event_time", now)
            .put("title", "event-$marker")
            .put("message", "event message")
            .put("status", "open")
            .put("severity", "high")
        if (!thingId.isNullOrBlank()) {
            body.put("thing_id", thingId)
        }
        val response = request("POST", "/event/create", body)
        check(response.code == 200) {
            "event/create failed code=${response.code} body=${response.body}"
        }
        return response.requiredDataId("op_id")
    }

    private suspend fun waitForPullItems(expected: Int): List<io.ethan.pushgo.data.PullItem> {
        val deadline = System.currentTimeMillis() + 20_000L
        var latest = emptyList<io.ethan.pushgo.data.PullItem>()
        while (System.currentTimeMillis() < deadline) {
            latest = container.channelRepository.pullMessages(null).items
            if (latest.size >= expected) return latest
            delay(150)
        }
        error("timed out waiting for $expected provider-pull items; latest=${latest.size}")
    }

    private suspend fun waitForAcceptedSendStatus(opId: String) {
        val deadline = System.currentTimeMillis() + 20_000L
        var latest = "missing"
        while (System.currentTimeMillis() < deadline) {
            val response = request("GET", "/send_status/$opId", null)
            if (response.code == 200) {
                latest = JSONObject(response.body)
                    .optJSONObject("data")
                    ?.optString("status")
                    ?.trim()
                    .orEmpty()
                if (latest == "provider_queued" || latest == "sent") return
            }
            delay(150)
        }
        error("send status did not reach an accepted state opId=$opId latest=$latest")
    }

    private suspend fun waitForProjectionCounts(messages: Int, events: Int, things: Int) {
        val deadline = System.currentTimeMillis() + 60_000L
        var latest = Triple(0, 0, 0)
        while (System.currentTimeMillis() < deadline) {
            latest = Triple(
                container.messageRepository.totalCount(),
                container.entityRepository.eventCount(),
                container.entityRepository.thingCount(),
            )
            if (latest == Triple(messages, events, things)) return
            delay(200)
        }
        error("timed out waiting for projections expected=($messages,$events,$things) latest=$latest")
    }

    private suspend fun waitForPullQueueToDrain() {
        val deadline = System.currentTimeMillis() + 20_000L
        var latest = -1
        while (System.currentTimeMillis() < deadline) {
            latest = container.channelRepository.pullMessages(null).items.size
            if (latest == 0) return
            delay(150)
        }
        error("provider-pull queue did not drain after persistence; latest=$latest")
    }

    private fun request(method: String, path: String, body: JSONObject?): HttpResult {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
        }
        val code = conn.responseCode
        val raw = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream
        }
        val text = raw?.use { stream ->
            BufferedReader(stream.reader(StandardCharsets.UTF_8)).readText()
        } ?: ""
        conn.disconnect()
        return HttpResult(code = code, body = text)
    }

    private fun HttpResult.requiredDataId(name: String): String {
        val value = JSONObject(body)
            .optJSONObject("data")
            ?.optString(name)
            ?.trim()
            .orEmpty()
        check(value.isNotEmpty()) { "response missing $name body=$body" }
        return value
    }

    private data class HttpResult(
        val code: Int,
        val body: String,
    )
}
