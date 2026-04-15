package io.ethan.pushgo.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.notifications.ProviderIngressCoordinator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProviderGatewayIntegrationDeviceTest {
    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var baseUrl: String
    private lateinit var token: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        container = AppContainer(context)
        val args = InstrumentationRegistry.getArguments()
        baseUrl = args.getString("pushgoGatewayBaseUrl")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "http://127.0.0.1:7780"
        token = args.getString("pushgoGatewayToken")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "integration-token"

        val health = request(
            method = "GET",
            path = "/healthz",
            body = null,
        )
        check(health.code == 200) {
            "gateway is not reachable from device baseUrl=$baseUrl code=${health.code} body=${health.body}"
        }

        runBlocking {
            container.settingsRepository.setServerAddress(baseUrl)
            container.settingsRepository.setGatewayToken(token)
            container.settingsRepository.setUseFcmChannel(true)
            container.settingsRepository.setFcmToken("android-it-fcm-${UUID.randomUUID()}")
            container.messageRepository.deleteAll()
            container.entityRepository.deleteAll()
            container.inboundDeliveryLedgerRepository.clearAll()
        }
    }

    @Test
    fun pull_with_and_without_deliveryId_matches_gateway_contract() = runBlocking {
        val deviceToken = "android-it-fcm-${UUID.randomUUID()}"
        val deviceKey = container.channelRepository.syncProviderDeviceToken(deviceToken)
        val password = "benchmark-123"
        val alias = "it-provider-${System.currentTimeMillis()}"
        val subscription = container.channelRepository.createChannel(alias, password, deviceToken)
        val channelId = subscription.channelId

        val knownBefore = diagnosticsDeliveryIds(channelId).toMutableSet()
        sendMessage(channelId, password, "it-op-msg-1")
        sendMessage(channelId, password, "it-op-msg-2")

        val created = waitForNewDeliveryIds(channelId, knownBefore, expected = 2)
        val first = created.first()

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
        assertTrue(emptyPull.isEmpty())

        val ackRemoved = container.channelRepository.ackMessage(first)
        assertFalse("ack should be idempotent and non-failing when already removed", ackRemoved)

        val resolvedAgain = container.channelRepository.syncProviderDeviceToken(deviceToken)
        assertEquals(deviceKey, resolvedAgain)
    }

    @Test
    fun pull_persists_message_event_and_thing_projections() = runBlocking {
        val deviceToken = "android-it-fcm-${UUID.randomUUID()}"
        container.channelRepository.syncProviderDeviceToken(deviceToken)
        val password = "benchmark-123"
        val alias = "it-entity-${System.currentTimeMillis()}"
        val subscription = container.channelRepository.createChannel(alias, password, deviceToken)
        val channelId = subscription.channelId

        val knownBefore = diagnosticsDeliveryIds(channelId).toMutableSet()
        val thingId = sendThingCreate(channelId, password, "it-op-thing-create")

        sendEventCreate(channelId, password, "it-op-event-top", thingId = null)
        sendEventCreate(channelId, password, "it-op-event-sub", thingId = thingId)
        sendMessage(channelId, password, "it-op-msg-entity-flow")

        waitForNewDeliveryIds(channelId, knownBefore, expected = 4)

        val persisted = ProviderIngressCoordinator.pullPersistAndDrainAcks(
            context = context,
            channelRepository = container.channelRepository,
            messageRepository = container.messageRepository,
            entityRepository = container.entityRepository,
            inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
            settingsRepository = container.settingsRepository,
            deliveryId = null,
        )
        assertTrue("expected at least one persisted record", persisted >= 1)
        assertTrue(container.channelRepository.pullMessages(null).isEmpty())
    }

    private fun sendMessage(channelId: String, password: String, opId: String) {
        val body = JSONObject()
            .put("channel_id", channelId)
            .put("password", password)
            .put("op_id", opId)
            .put("title", "msg-$opId")
            .put("body", "message body $opId")
        val response = request("POST", "/message", body)
        check(response.code == 200 || response.code == 503) {
            "send message failed code=${response.code} body=${response.body}"
        }
    }

    private fun sendThingCreate(channelId: String, password: String, opId: String): String {
        val now = Instant.now().epochSecond
        val body = JSONObject()
            .put("channel_id", channelId)
            .put("password", password)
            .put("op_id", opId)
            .put("title", "thing-$opId")
            .put("description", "thing desc")
            .put("observed_at", now)
        val response = request("POST", "/thing/create", body)
        check(response.code == 200) {
            "thing/create failed code=${response.code} body=${response.body}"
        }
        val thingId = JSONObject(response.body)
            .optJSONObject("data")
            ?.optString("thing_id")
            ?.trim()
            .orEmpty()
        check(thingId.isNotEmpty()) {
            "thing/create response missing thing_id body=${response.body}"
        }
        return thingId
    }

    private fun sendEventCreate(channelId: String, password: String, opId: String, thingId: String?) {
        val now = Instant.now().epochSecond
        val body = JSONObject()
            .put("channel_id", channelId)
            .put("password", password)
            .put("op_id", opId)
            .put("event_time", now)
            .put("title", "event-$opId")
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
    }

    private fun diagnosticsDeliveryIds(channelId: String): List<String> {
        val response = request(
            method = "GET",
            path = "/diagnostics/dispatch?limit=200&channel_id=${urlEncode(channelId)}",
            body = null,
        )
        if (response.code != 200) return emptyList()
        val entries = JSONObject(response.body)
            .optJSONObject("data")
            ?.optJSONArray("entries")
            ?: JSONArray()
        val ids = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            val id = entries.optJSONObject(i)?.optString("delivery_id")?.trim().orEmpty()
            if (id.isNotEmpty()) ids += id
        }
        return ids
    }

    private fun waitForNewDeliveryIds(
        channelId: String,
        knownBefore: MutableSet<String>,
        expected: Int,
    ): List<String> {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            val current = diagnosticsDeliveryIds(channelId)
            val newIds = current.filter { !knownBefore.contains(it) }
            if (newIds.size >= expected) {
                knownBefore += newIds
                return newIds
            }
            Thread.sleep(150)
        }
        error("timed out waiting for $expected new delivery ids for channel=$channelId")
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

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private data class HttpResult(
        val code: Int,
        val body: String,
    )
}
