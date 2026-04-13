package io.ethan.pushgo.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSubscriptionServiceIngressContractTest {

    @Test
    fun pullMessages_omitsDeliveryIdWhenNull() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"items":[]}}"""
        ).use { server ->
            val service = ChannelSubscriptionService()
            val items = service.pullMessages(
                baseUrl = server.baseUrl,
                token = "token-001",
                deviceKey = "device-001",
                deliveryId = null,
            )
            assertTrue(items.isEmpty())

            val request = server.firstRequest()
            assertEquals("POST", request.method)
            assertEquals("/messages/pull", request.path)
            val body = JSONObject(request.body)
            assertEquals("device-001", body.getString("device_key"))
            assertFalse(body.has("delivery_id"))
        }
    }

    @Test
    fun pullMessages_includesDeliveryIdWhenProvided() = runBlocking {
        CapturingGatewayServer(
            responseBody = """
                {"success":true,"data":{"items":[{"delivery_id":"delivery-123","payload":{"title":"ok"}}]}}
            """.trimIndent()
        ).use { server ->
            val service = ChannelSubscriptionService()
            val items = service.pullMessages(
                baseUrl = server.baseUrl,
                token = "token-001",
                deviceKey = "device-001",
                deliveryId = "delivery-123",
            )
            assertEquals(1, items.size)
            assertEquals("delivery-123", items[0].deliveryId)
            assertEquals("ok", items[0].payload["title"])

            val request = server.firstRequest()
            assertEquals("/messages/pull", request.path)
            val body = JSONObject(request.body)
            assertEquals("device-001", body.getString("device_key"))
            assertEquals("delivery-123", body.getString("delivery_id"))
        }
    }

    @Test
    fun ackMessage_postsDeviceKeyAndDeliveryId() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"removed":true}}"""
        ).use { server ->
            val service = ChannelSubscriptionService()
            val removed = service.ackMessage(
                baseUrl = server.baseUrl,
                token = "token-001",
                deviceKey = "device-001",
                deliveryId = "delivery-ack-001",
            )
            assertTrue(removed)

            val request = server.firstRequest()
            assertEquals("POST", request.method)
            assertEquals("/messages/ack", request.path)
            val body = JSONObject(request.body)
            assertEquals("device-001", body.getString("device_key"))
            assertEquals("delivery-ack-001", body.getString("delivery_id"))
        }
    }
}

private class CapturingGatewayServer(
    private val responseBody: String,
) : Closeable {
    data class RecordedRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            handle(exchange)
        }
        start()
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    fun firstRequest(): RecordedRequest {
        return requests.first()
    }

    override fun close() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
        requests += RecordedRequest(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            body = body,
        )
        val bytes = responseBody.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }
}
