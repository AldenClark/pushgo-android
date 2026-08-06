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
import org.junit.Assert.fail
import org.junit.Test

class ChannelSubscriptionServiceIngressContractTest {

    @Test
    fun registerDevice_postsIdentityOnlyContract() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"device_key":"device-001"}}"""
        ).use { server ->
            val service = ChannelSubscriptionService()
            val result = service.registerDevice(
                baseUrl = server.baseUrl,
                token = "token-001",
                platform = "android",
                deviceKey = "device-001",
            )
            assertEquals("device-001", result.deviceKey)

            val request = server.firstRequest()
            assertEquals("POST", request.method)
            assertEquals("/device/register", request.path)
            val body = JSONObject(request.body)
            assertEquals("device-001", body.getString("device_key"))
            assertEquals("android", body.getString("platform"))
            assertFalse(body.has("channel_type"))
            assertFalse(body.has("provider_token"))
        }
    }

    @Test
    fun upsertDeviceChannel_postsRouteContract() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"device_key":"device-001"}}"""
        ).use { server ->
            val service = ChannelSubscriptionService()
            val result = service.upsertDeviceChannel(
                baseUrl = server.baseUrl,
                token = "token-001",
                deviceKey = "device-001",
                platform = "android",
                channelType = "fcm",
                providerToken = "provider-token-001",
            )
            assertEquals("device-001", result.deviceKey)

            val request = server.firstRequest()
            assertEquals("POST", request.method)
            assertEquals("/channel/device", request.path)
            val body = JSONObject(request.body)
            assertEquals("device-001", body.getString("device_key"))
            assertEquals("android", body.getString("platform"))
            assertEquals("fcm", body.getString("channel_type"))
            assertEquals("provider-token-001", body.getString("provider_token"))
        }
    }

    @Test
    fun registerDevice_rejectsMissingDeviceKeyInGatewayResponse() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"device_key":""}}"""
        ).use { server ->
            val service = ChannelSubscriptionService()
            try {
                service.registerDevice(
                    baseUrl = server.baseUrl,
                    token = "token-001",
                    platform = "android",
                    deviceKey = "device-001",
                )
                fail("expected missing device_key response to throw")
            } catch (error: ChannelSubscriptionException) {
                assertEquals("Request failed", error.message)
                assertEquals("gateway_response_missing_device_key", error.code)
            }
        }
    }

    @Test
    fun upsertDeviceChannel_rejectsMissingDeviceKeyInGatewayResponse() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"device_key":""}}"""
        ).use { server ->
            val service = ChannelSubscriptionService()
            try {
                service.upsertDeviceChannel(
                    baseUrl = server.baseUrl,
                    token = "token-001",
                    deviceKey = "device-001",
                    platform = "android",
                    channelType = "fcm",
                    providerToken = "provider-token-001",
                )
                fail("expected missing device_key response to throw")
            } catch (error: ChannelSubscriptionException) {
                assertEquals("Request failed", error.message)
                assertEquals("gateway_response_missing_device_key", error.code)
            }
        }
    }

    @Test
    fun registerDevice_preservesStructuredGatewayProblem() = runBlocking {
        CapturingGatewayServer(
            responseBody = """
                {
                  "success": false,
                  "error": "device_key not found",
                  "error_code": "device_key_not_found",
                  "problem": {
                    "code": "device_key_not_found",
                    "category": "not_found",
                    "status": 400,
                    "title": "Resource not found",
                    "detail": "device_key not found",
                    "localized_message": "当前设备注册已失效，请重试。",
                    "locale": "zh-CN",
                    "retryable": false
                  }
                }
            """.trimIndent(),
            responseCode = 400,
        ).use { server ->
            val service = ChannelSubscriptionService()
            try {
                service.registerDevice(
                    baseUrl = server.baseUrl,
                    token = "token-001",
                    platform = "android",
                    deviceKey = "device-001",
                )
                fail("expected gateway problem to throw")
            } catch (error: ChannelSubscriptionException) {
                assertEquals("device_key_not_found", error.code)
                assertEquals(GatewayErrorCategory.NOT_FOUND, error.category)
                assertEquals("当前设备注册已失效，请重试。", error.message)
                assertEquals(400, error.httpStatus)
            }
        }
    }

    @Test
    fun pullMessages_omitsDeliveryIdWhenNull() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"items":[]}}"""
        ).use { server ->
            val service = ChannelSubscriptionService()
            val page = service.pullMessages(
                baseUrl = server.baseUrl,
                token = "token-001",
                deviceKey = "device-001",
                deliveryId = null,
            )
            assertTrue(page.items.isEmpty())
            assertEquals(ProviderPullContract.V2, page.contract)

            val request = server.firstRequest()
            assertEquals("POST", request.method)
            assertEquals("/v2/messages/pull", request.path)
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
            val page = service.pullMessages(
                baseUrl = server.baseUrl,
                token = "token-001",
                deviceKey = "device-001",
                deliveryId = "delivery-123",
            )
            assertEquals(1, page.items.size)
            assertEquals("delivery-123", page.items[0].deliveryId)
            assertEquals("ok", page.items[0].payload["title"])

            val request = server.firstRequest()
            assertEquals("/v2/messages/pull", request.path)
            val body = JSONObject(request.body)
            assertEquals("device-001", body.getString("device_key"))
            assertEquals("delivery-123", body.getString("delivery_id"))
        }
    }

    @Test
    fun pullMessages_preservesHasMoreAndRequiresOuterDeliveryId() = runBlocking {
        CapturingGatewayServer(
            responseBody = """
                {"success":true,"data":{"has_more":true,"items":[
                  {"payload":{"delivery_id":"inner-only","title":"drop"}},
                  {"delivery_id":"outer","payload":{"delivery_id":"inner-conflict","title":"keep"}}
                ]}}
            """.trimIndent()
        ).use { server ->
            val page = ChannelSubscriptionService().pullMessages(
                baseUrl = server.baseUrl,
                token = null,
                deviceKey = "device-001",
            )

            assertTrue(page.hasMore)
            assertEquals(ProviderPullContract.V2, page.contract)
            assertEquals(listOf("outer"), page.items.map { it.deliveryId })
        }
    }

    @Test
    fun pullMessages_fallsBackOnlyForStructuredV2RouteNotFound() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":false,"error_code":"route_not_found","problem":{"code":"route_not_found","category":"not_found","status":404,"retryable":false}}""",
            responseCode = 404,
            subsequentResponses = listOf(
                200 to """{"success":true,"data":{"items":[]}}""",
            ),
        ).use { server ->
            val page = ChannelSubscriptionService().pullMessages(
                baseUrl = server.baseUrl,
                token = null,
                deviceKey = "device-001",
            )
            assertTrue(page.items.isEmpty())
            assertEquals(ProviderPullContract.LEGACY, page.contract)
            assertFalse(page.hasMore)
            assertEquals(
                listOf("/v2/messages/pull", "/messages/pull"),
                server.allRequests().map { it.path },
            )
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

    @Test
    fun ackMessages_postsAtomicDeliveryIdBatch() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"removed":true,"requested_count":2,"removed_count":2}}"""
        ).use { server ->
            val service = ChannelSubscriptionService()
            val result = service.ackMessages(
                baseUrl = server.baseUrl,
                token = "token-001",
                deviceKey = "device-001",
                deliveryIds = listOf("delivery-002", "delivery-001", "delivery-001"),
            )

            assertEquals(2, result.requestedCount)
            assertEquals(2, result.removedCount)

            val request = server.firstRequest()
            assertEquals("POST", request.method)
            assertEquals("/v2/messages/ack", request.path)
            val body = JSONObject(request.body)
            assertEquals("device-001", body.getString("device_key"))
            assertFalse(body.has("delivery_id"))
            assertEquals(
                listOf("delivery-001", "delivery-002"),
                buildList {
                    val ids = body.getJSONArray("delivery_ids")
                    for (index in 0 until ids.length()) add(ids.getString(index))
                },
            )
        }
    }

    @Test
    fun ackMessages_rejectsRequestedCountMismatch() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"requested_count":1,"removed_count":1}}"""
        ).use { server ->
            try {
                ChannelSubscriptionService().ackMessages(
                    baseUrl = server.baseUrl,
                    token = null,
                    deviceKey = "device-001",
                    deliveryIds = listOf("one", "two"),
                )
                fail("expected mismatched ACK count to throw")
            } catch (error: ChannelSubscriptionException) {
                assertEquals("gateway_ack_count_mismatch", error.code)
            }
        }
    }

    @Test
    fun ackMessages_rejectsStringEncodedCounts() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"requested_count":"1","removed_count":"1"}}"""
        ).use { server ->
            try {
                ChannelSubscriptionService().ackMessages(
                    baseUrl = server.baseUrl,
                    token = null,
                    deviceKey = "device-001",
                    deliveryIds = listOf("one"),
                )
                fail("expected non-numeric ACK fields to throw")
            } catch (error: ChannelSubscriptionException) {
                assertEquals("gateway_ack_count_mismatch", error.code)
            }
        }
    }

    @Test
    fun ackMessages_acceptsZeroRemovedForIdempotentRetry() = runBlocking {
        CapturingGatewayServer(
            responseBody = """{"success":true,"data":{"requested_count":1,"removed_count":0}}"""
        ).use { server ->
            val result = ChannelSubscriptionService().ackMessages(
                baseUrl = server.baseUrl,
                token = null,
                deviceKey = "device-001",
                deliveryIds = listOf("already-removed"),
            )

            assertEquals(1, result.requestedCount)
            assertEquals(0, result.removedCount)
        }
    }
}

private class CapturingGatewayServer(
    private val responseBody: String,
    private val responseCode: Int = 200,
    private val subsequentResponses: List<Pair<Int, String>> = emptyList(),
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

    fun allRequests(): List<RecordedRequest> = requests.toList()

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
        val response = subsequentResponses.getOrNull(requests.size - 2)
        val effectiveCode = response?.first ?: responseCode
        val effectiveBody = response?.second ?: responseBody
        val bytes = effectiveBody.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(effectiveCode, bytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(bytes)
        }
    }
}
