package io.ethan.pushgo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.*
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.notifications.InboundMessageProcessor
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.ProviderIngressCoordinator
import io.ethan.pushgo.notifications.ProviderAckDrainCoordinator
import io.ethan.pushgo.notifications.NotificationIngressParser
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native Room + HttpURLConnection receipts, without a UI launch or external Gateway.
 * Bounded journeys detect page ACK reordering, premature ACK after SQL failure,
 * retained independent failures, durable scan progress and frozen page identity.
 * The repository uses its default no-op continuation callback: cross-turn tests
 * explicitly invoke the next turn and do not claim the AppContainer Job ran.
 * Expected cost: a handful of loopback requests and database reopen, below a UI journey.
 */
@RunWith(AndroidJUnit4::class)
class ProviderV2PullOnlyDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PushGoDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var messages: MessageRepository
    private lateinit var entities: EntityRepository
    private lateinit var ledger: InboundDeliveryLedgerRepository
    private lateinit var channels: ChannelSubscriptionRepository
    private val secrets = MemorySecrets()
    private val servers = mutableListOf<Gateway>()

    @Before fun setUp() { context.deleteDatabase(DB_NAME); open() }
    @After fun tearDown() {
        servers.forEach { it.close() }
        db.close()
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences(DB_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun v2PagingAcknowledgesCommittedPageBeforeNextPull() = runBlocking {
        var pulls = 0
        val gateway = gateway { path, body ->
            when (path) {
                PULL -> {
                    pulls++
                    if (pulls == 2) assertEquals("acked", ackState("outer-1"))
                    200 to page("outer-$pulls", hasMore = pulls == 1)
                }
                ACK -> {
                    val id = body.getJSONArray("delivery_ids").getString(0)
                    assertEquals("device-a", body.getString("device_key"))
                    assertEquals(pulls.toLong(), runBlocking { messages.totalCount() }.toLong())
                    assertEquals("pending", ackState(id))
                    assertEquals(1, sqlCount("inbound_delivery_ack_outbox"))
                    200 to ackReceipt()
                }
                else -> error("Unexpected endpoint $path")
            }
        }
        configure(gateway)
        assertEquals(2, pull())
        assertEquals(listOf(PULL, ACK, PULL, ACK), gateway.paths())
        assertEquals(0, sqlCount("legacy_provider_ingress"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        assertEquals("acked", ackState("outer-2"))
        assertEquals(0, sqlCount("inbound_delivery_ledger", "delivery_id = 'payload-spoof'"))
        db.close(); open()
        assertEquals(2L, messages.totalCount().toLong())
        assertEquals("acked", ackState("outer-1"))
        assertCanonical("outer-1")
        assertCanonical("outer-2")
        gateway.assertHealthy()
    }

    @Test
    fun missingV2AckRoutePreservesCommittedReceiptAndStopsPaging() = runBlocking {
        val gateway = gateway { path, _ -> when (path) {
            PULL -> 200 to page("outer-unsupported-ack", hasMore = true)
            ACK -> {
                assertEquals(1, sqlCount("messages"))
                assertEquals(1, sqlCount("inbound_delivery_ack_outbox"))
                404 to JSONObject("""{"success":false,"error_code":"route_not_found","problem":{"code":"route_not_found","category":"not_found","status":404,"retryable":false}}""")
            }
            else -> error("Legacy fallback escaped: $path")
        } }
        configure(gateway)
        val failure = runCatching { pull() }.exceptionOrNull()
        assertTrue(failure is ChannelSubscriptionException)
        failure as ChannelSubscriptionException
        assertEquals("gateway_v2_required", failure.code)
        assertEquals(GatewayErrorCategory.FEATURE_DISABLED, failure.category)
        assertEquals("route_not_found", (failure.cause as ChannelSubscriptionException).code)
        assertEquals(listOf(PULL, ACK), gateway.paths())
        assertEquals("pending", ackState("outer-unsupported-ack"))
        db.close(); open()
        assertEquals(1, sqlCount("messages"))
        assertEquals(1, sqlCount("inbound_delivery_ack_outbox"))
        assertEquals("pending", ackState("outer-unsupported-ack"))
        assertCanonical("outer-unsupported-ack")
        assertEquals(0, sqlCount("legacy_provider_ingress"))
        gateway.assertHealthy()
    }

    @Test
    fun sqlFailureDoesNotAckAndRetryUsesCapturedGatewayAfterConfigSwitch() = runBlocking {
        val replacement = gateway { path, _ -> error("Old delivery escaped to replacement Gateway: $path") }
        val original = gateway { path, _ -> when (path) {
            PULL -> 200 to page("outer-retry", false)
            ACK -> {
                assertEquals(1, sqlCount("messages"))
                assertEquals("pending", ackState("outer-retry"))
                200 to ackReceipt()
            }
            else -> error("Unexpected endpoint $path")
        } }
        configure(original)
        rejectMessageWrites()
        assertTrue(runCatching { pull() }.exceptionOrNull() != null)
        assertEquals(listOf(PULL), original.paths())
        assertEquals(0, sqlCount("messages"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        assertEquals(0, sqlCount("inbound_delivery_ledger"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_provider_write")
        assertEquals(1, pull {
            // Configuration changes after canonical persistence, before ACK. The
            // outbox must still own the page's original destination and device key.
            settings.setServerAddress(replacement.baseUrl)
            settings.setDeviceKey("device-b")
        })
        assertEquals(listOf(PULL, PULL, ACK), original.paths())
        assertTrue(replacement.paths().isEmpty())
        assertEquals("acked", ackState("outer-retry"))
        configure(original)
        assertEquals(1, pull()) // replay is counted as complete, not another content row
        assertEquals(1, sqlCount("messages"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        assertCanonical("outer-retry")
        original.assertHealthy(); replacement.assertHealthy()
    }

    @Test
    fun v27AcceptedLegacyReceiptSurvivesMigrationFailureThenDrainsOnceWithoutLegacyHttp() = runBlocking {
        db.close(); context.deleteDatabase(DB_NAME)
        seedV27()
        open()
        val gateway = gateway { path, _ ->
            assertEquals(PULL, path)
            assertEquals(0, sqlCount("legacy_provider_ingress"))
            200 to JSONObject().put("success", true).put("data", JSONObject().put("items", JSONArray()).put("has_more", false))
        }
        configure(gateway)
        val originalPayload = ledger.loadPendingLegacyPull().single().payloadJson
        rejectMessageWrites()
        assertTrue(runCatching { pull() }.exceptionOrNull() != null)
        assertEquals(originalPayload, ledger.loadPendingLegacyPull().single().payloadJson)
        assertTrue(gateway.paths().isEmpty())
        db.close(); open()
        assertEquals(originalPayload, ledger.loadPendingLegacyPull().single().payloadJson)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_provider_write")
        assertEquals(1, pull())
        assertEquals(0, sqlCount("legacy_provider_ingress"))
        assertEquals(1, sqlCount("messages"))
        assertEquals("acked", ackState("old-accepted"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        db.close(); open()
        assertEquals(0, pull())
        assertEquals(1, sqlCount("messages"))
        assertEquals(listOf(PULL, PULL), gateway.paths())
        assertCanonical("old-accepted")
        val migratedScope = db.openHelper.readableDatabase.query(
            "SELECT gateway_url, device_key, ack_state FROM inbound_delivery_ledger WHERE delivery_id = ?",
            arrayOf("old-accepted"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2))
        }
        assertEquals(listOf("https://old-gateway.example", "old-device", "acked"), migratedScope)
        gateway.assertHealthy()
    }

    @Test
    fun independentInvalidFirstPageRetainsItsResponsibilityWhileSevenHealthyItemsSettle() = runBlocking {
        val healthy = (1..7).map { "healthy-after-invalid-$it" }
        val invalid = (1..200).map { "independent-invalid-$it" }
        val requestedTokens = Collections.synchronizedList(mutableListOf<String?>())
        val acknowledged = Collections.synchronizedList(mutableListOf<String>())
        val gateway = gateway { path, body -> when (path) {
            PULL -> {
                val token = body.optString("page_token").takeIf { body.has("page_token") }
                requestedTokens += token
                when (token) {
                    null -> progressPage(JSONArray(invalid.map { id ->
                        wireItem(id, JSONObject().put("entity_type", "message").put("message_id", id)
                            .put("title", "").put("body", ""))
                    }), nextToken = "after-independent-invalid", complete = false)
                    "after-independent-invalid" -> progressPage(JSONArray(healthy.map { wireItem(it) }), null, true)
                    else -> error("Unexpected synthetic scan position")
                }.let { 200 to it }
            }
            ACK -> {
                val ids = body.getJSONArray("delivery_ids").let { values ->
                    (0 until values.length()).map(values::getString)
                }
                assertEquals(healthy.toSet(), ids.toSet())
                assertEquals(7, sqlCount("messages"))
                assertNull("The completed scan checkpoint must precede HTTP ACK", runBlocking { db.providerPullFrontierDao().get() })
                acknowledged += ids
                200 to ackReceipt(ids.size)
            }
            else -> error("Unexpected endpoint $path")
        } }
        configure(gateway)
        repeat(2) {
            val failure = runCatching { pull() }.exceptionOrNull()
            assertTrue("The retained invalid deliveries must remain retryable", failure is InboundMessageProcessor.InboundRetryableException)
            assertEquals(7, sqlCount("messages"))
            assertEquals(7, sqlCount("inbound_delivery_ledger"))
            assertEquals(0, sqlCount("inbound_delivery_ledger", "delivery_id LIKE 'independent-invalid-%'"))
            assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
            assertNull(db.providerPullFrontierDao().get())
            healthy.forEach { id -> assertCanonical(id); assertEquals("acked", ackState(id)) }
        }
        assertEquals(listOf(null, "after-independent-invalid", null, "after-independent-invalid"), requestedTokens)
        assertEquals(healthy.toSet(), acknowledged.toSet())
        // A validated first ACK response already proved these exact deliveries
        // absent on the Gateway. Replaying an ACKed local fact creates neither a
        // new canonical row nor another HTTP ACK obligation.
        assertEquals(7, acknowledged.size)
        assertEquals(listOf(PULL, PULL, ACK, PULL, PULL), gateway.paths())
        gateway.assertHealthy()
    }

    @Test
    fun rejectedForeignParentAndChildRemainUnadoptedWhileIndependentHealthyItemsProgress() = runBlocking {
        val thing = "cross-gateway-parent"
        fun parentWire(delivery: String, body: String) = JSONObject().put("entity_type", "thing")
            .put("entity_id", thing).put("thing_id", thing).put("delivery_id", delivery)
            .put("op_id", "operation-$delivery").put("title", "Parent").put("body", body)
            .put("sent_at", "2026-01-01T00:00:00Z").put("ttl", "1")
        val old = parentWire("foreign-parent-original", "foreign parent retained")
        val parsed = NotificationIngressParser.parse(old.keys().asSequence().associateWith { old.get(it).toString() }, "foreign-parent-original", null)
            as InboundPersistenceRequest.Entity
        assertTrue(entities.insertIncoming(parsed.record, deliveryScope = requireNotNull(InboundDeliveryScope.create("https://foreign-parent.invalid", "foreign-device"))))
        val original = db.thingHeadDao().getByThingId(thing)
        val healthy = listOf("healthy-next-to-foreign-parent", "healthy-after-foreign-parent")
        val gateway = gateway { path, body -> when (path) {
            PULL -> 200 to if (!body.has("page_token")) {
                progressPage(JSONArray().put(wireItem("foreign-parent-new", parentWire("foreign-parent-new", "must not replace")))
                    .put(wireItem("foreign-parent-child", identityPayload("foreign-parent-child").put("thing_id", thing).put("occurred_at", "1767225600000")))
                    .put(wireItem(healthy[0])), "after-rejected-parent", false)
            } else {
                assertEquals("after-rejected-parent", body.getString("page_token"))
                progressPage(JSONArray().put(wireItem(healthy[1])), null, true)
            }
            ACK -> {
                val ids = body.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString) }
                assertTrue(ids.all { it in healthy }); 200 to ackReceipt(ids.size)
            }
            else -> error("Unexpected parent isolation endpoint")
        } }
        configure(gateway)
        assertTrue(runCatching { pull() }.exceptionOrNull() is InboundMessageProcessor.InboundRetryableException)
        assertEquals(listOf(PULL, ACK, PULL, ACK), gateway.paths())
        assertEquals(original, db.thingHeadDao().getByThingId(thing))
        assertEquals(0, sqlCount("thing_sub_messages")); assertEquals(0, sqlCount("pending_thing_messages"))
        for (id in listOf("foreign-parent-new", "foreign-parent-child")) {
            assertEquals(0, sqlCount("inbound_delivery_ledger", "gateway_url='${gateway.baseUrl}' AND delivery_id='$id'"))
            assertEquals(0, sqlCount("inbound_delivery_ack_outbox", "delivery_id='$id'"))
        }
        healthy.forEach { assertCanonical(it); assertEquals("acked", ackState(it)) }
        assertNull(db.providerPullFrontierDao().get()); gateway.assertHealthy()
    }

    @Test
    fun reusedOperationWithANewStableEntityIdentityDoesNotReuseTheOldLocalClaim() = runBlocking {
        val previous = "old-retained-identity"
        val fresh = "new-after-identity-retention"
        val sharedOperation = "normal-operation-reused-after-server-gc"
        val gateway = gateway { path, body -> when (path) {
            PULL -> 200 to progressPage(JSONArray()
                .put(wireItem(previous, identityPayload(previous).put("op_id", sharedOperation)))
                .put(wireItem(fresh, identityPayload(fresh).put("op_id", sharedOperation))), null, true)
            ACK -> {
                assertEquals(setOf(previous, fresh), body.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString).toSet() })
                200 to ackReceipt(2)
            }
            else -> error("Unexpected normal identity endpoint")
        } }
        configure(gateway)
        val wire = identityPayload(previous).put("op_id", sharedOperation)
        val parsed = NotificationIngressParser.parse(wire.keys().asSequence().associateWith { wire.get(it).toString() }, previous, null)
            as InboundPersistenceRequest.Message
        assertTrue(messages.insertIncoming(parsed.message, deliveryScope = requireNotNull(InboundDeliveryScope.create(gateway.baseUrl, "device-a"))))
        val original = canonicalOwnerSnapshot(previous)
        assertEquals(2, pull())
        assertEquals(2, sqlCount("messages")); assertEquals(2, sqlCount("operation_ledger", "op_id='$sharedOperation'"))
        assertEquals(original, canonicalOwnerSnapshot(previous))
        assertEquals("acked", ackState(previous)); assertEquals("acked", ackState(fresh))
        assertCanonical(fresh); assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        gateway.assertHealthy()
    }

    @Test
    fun knownForeignCanonicalMessagesRetainBothBodiesWhileModernHealthyPagesProgress() = runBlocking {
        val foreign = requireNotNull(InboundDeliveryScope.create("https://foreign-canonical.invalid", "foreign-device"))
        val bad = listOf("foreign-same-body", "foreign-different-body")
        bad.forEach { seedCanonicalIdentity(it, foreign) }
        val before = bad.associateWith(::canonicalOwnerSnapshot)
        val healthy = listOf("healthy-with-foreign-head", "healthy-after-foreign-head")
        val acknowledged = Collections.synchronizedList(mutableListOf<String>())
        val gateway = gateway { path, body -> when (path) {
            PULL -> {
                val token = body.optString("page_token").takeIf { body.has("page_token") }
                200 to when (token) {
                    null -> progressPage(JSONArray().put(wireItem(bad[0], identityPayload(bad[0])))
                        .put(wireItem(bad[1], identityPayload(bad[1]).put("body", "a new body cannot adopt a foreign identity")))
                        .put(wireItem(healthy[0])), "after-foreign-head", false)
                    "after-foreign-head" -> progressPage(JSONArray().put(wireItem(healthy[1])), null, true)
                    else -> error("Unexpected conflict scan token")
                }
            }
            ACK -> {
                val ids = body.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString) }
                assertTrue("foreign content must never be ACKed by this identity", ids.all { it in healthy })
                acknowledged += ids
                200 to ackReceipt(ids.size)
            }
            else -> error("Unexpected endpoint $path")
        } }
        configure(gateway)
        assertTrue(runCatching { pull() }.exceptionOrNull() is InboundMessageProcessor.InboundRetryableException)
        assertEquals(listOf(PULL, ACK, PULL, ACK), gateway.paths())
        assertEquals(healthy, acknowledged)
        assertNull(db.providerPullFrontierDao().get())
        assertEquals(4, sqlCount("messages"))
        bad.forEach { id ->
            assertEquals(before.getValue(id), canonicalOwnerSnapshot(id))
            assertEquals(0, sqlCount("inbound_delivery_ledger", "gateway_url='${gateway.baseUrl}' AND delivery_id='$id'"))
            assertEquals(0, sqlCount("inbound_delivery_ack_outbox", "delivery_id='$id'"))
            assertEquals("no current-scope operation claim", 1, sqlCount("operation_ledger", "op_id='operation-$id'"))
        }
        healthy.forEach { assertCanonical(it); assertEquals("acked", ackState(it)) }
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        gateway.assertHealthy()
    }

    @Test
    fun unknownHistoricalCanonicalOwnerRemainsUnchangedWithoutBlockingIndependentModernPages() = runBlocking {
        val bad = "historical-owner-unknown"
        seedCanonicalIdentity(bad, null) // Deliberate historical fixture: no proven origin is invented.
        val before = canonicalOwnerSnapshot(bad)
        assertNull(before.last())
        val healthy = "healthy-after-unknown-owner"
        val gateway = gateway { path, body -> when (path) {
            PULL -> 200 to if (!body.has("page_token")) {
                progressPage(JSONArray().put(wireItem(bad, identityPayload(bad).put("body", "unverified replacement"))), "after-unknown-owner", false)
            } else {
                assertEquals("after-unknown-owner", body.getString("page_token"))
                progressPage(JSONArray().put(wireItem(healthy)), null, true)
            }
            ACK -> {
                assertEquals(listOf(healthy), body.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString) })
                200 to ackReceipt()
            }
            else -> error("Unexpected endpoint $path")
        } }
        configure(gateway)
        assertTrue(runCatching { pull() }.exceptionOrNull() is InboundMessageProcessor.InboundRetryableException)
        assertEquals(listOf(PULL, PULL, ACK), gateway.paths())
        assertEquals(before, canonicalOwnerSnapshot(bad))
        assertEquals(0, sqlCount("inbound_delivery_ledger", "gateway_url='${gateway.baseUrl}' AND delivery_id='$bad'"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox", "delivery_id='$bad'"))
        assertEquals(1, sqlCount("operation_ledger", "op_id='operation-$bad'"))
        assertEquals(2, sqlCount("messages")); assertCanonical(healthy)
        assertEquals("acked", ackState(healthy)); assertNull(db.providerPullFrontierDao().get())
        gateway.assertHealthy()
    }

    @Test
    fun sameGatewayNewDeviceMayAckAnExactCanonicalReplayWithoutChangingItsOwner() = runBlocking {
        val id = "same-gateway-new-device-replay"
        val gateway = gateway { path, body -> when (path) {
            PULL -> 200 to progressPage(JSONArray().put(wireItem(id, identityPayload(id))), null, true)
            ACK -> {
                assertEquals("device-a", body.getString("device_key"))
                assertEquals(listOf(id), body.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString) })
                200 to ackReceipt()
            }
            else -> error("Unexpected endpoint $path")
        } }
        val oldScope = requireNotNull(InboundDeliveryScope.create(gateway.baseUrl, "old-device"))
        seedCanonicalIdentity(id, oldScope)
        val before = canonicalOwnerSnapshot(id)
        configure(gateway)
        assertEquals("same Gateway and exact business payload remains a normal duplicate", 1, pull())
        assertEquals(listOf(PULL, ACK), gateway.paths())
        assertEquals(before, canonicalOwnerSnapshot(id))
        assertEquals(1, sqlCount("messages"))
        assertEquals(1, sqlCount("inbound_delivery_ledger", "gateway_url='${gateway.baseUrl}' AND device_key='device-a' AND delivery_id='$id' AND ack_state='acked'"))
        assertEquals(1, sqlCount("inbound_delivery_ledger", "gateway_url='${gateway.baseUrl}' AND device_key='old-device' AND delivery_id='$id' AND ack_state='pending'"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox")); assertNull(db.providerPullFrontierDao().get())
        gateway.assertHealthy()
    }

    @Test
    fun sameGatewayNewDeviceCannotReplaceCanonicalContentButHealthyModernPagesContinue() = runBlocking {
        val bad = "same-gateway-changed-content"
        val healthy = "healthy-after-same-gateway-conflict"
        val gateway = gateway { path, body -> when (path) {
            PULL -> 200 to if (!body.has("page_token")) {
                progressPage(JSONArray().put(wireItem(bad, identityPayload(bad).put("body", "different business content"))), "after-same-gateway-conflict", false)
            } else {
                assertEquals("after-same-gateway-conflict", body.getString("page_token"))
                progressPage(JSONArray().put(wireItem(healthy)), null, true)
            }
            ACK -> {
                assertEquals("device-a", body.getString("device_key"))
                assertEquals(listOf(healthy), body.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString) })
                200 to ackReceipt()
            }
            else -> error("Unexpected endpoint $path")
        } }
        seedCanonicalIdentity(bad, requireNotNull(InboundDeliveryScope.create(gateway.baseUrl, "old-device")))
        val before = canonicalOwnerSnapshot(bad)
        configure(gateway)
        assertTrue(runCatching { pull() }.exceptionOrNull() is InboundMessageProcessor.InboundRetryableException)
        assertEquals(listOf(PULL, PULL, ACK), gateway.paths())
        assertEquals(before, canonicalOwnerSnapshot(bad))
        assertEquals(0, sqlCount("inbound_delivery_ledger", "gateway_url='${gateway.baseUrl}' AND device_key='device-a' AND delivery_id='$bad'"))
        assertEquals(1, sqlCount("operation_ledger", "op_id='operation-$bad'"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox")); assertNull(db.providerPullFrontierDao().get())
        assertEquals(2, sqlCount("messages")); assertCanonical(healthy); assertEquals("acked", ackState(healthy))
        gateway.assertHealthy()
    }

    @Test
    fun knownForeignMessageDependencyIsNotAdoptedWhileIndependentModernPagesProgress() = runBlocking {
        val foreign = requireNotNull(InboundDeliveryScope.create("https://foreign-dependent.invalid", "foreign-device"))
        // event_id retains the existing top-level messages domain. Do not turn a
        // top-level -> thing_sub_messages lifecycle transition into an identity conflict.
        for (field in listOf("event_id")) {
            val bad = "foreign-dependent-$field"
            seedCanonicalIdentity(bad, foreign)
            val before = canonicalOwnerSnapshot(bad)
            val healthy = "healthy-beside-rejected-dependent-$field"
            val gateway = gateway { path, body -> when (path) {
                PULL -> 200 to if (!body.has("page_token")) {
                    progressPage(JSONArray().put(wireItem(bad, identityPayload(bad).put(field, "parent-$field"))), "dependent-next", false)
                } else {
                    assertEquals("dependent-next", body.getString("page_token"))
                    progressPage(JSONArray().put(wireItem(healthy)), null, true)
                }
                ACK -> {
                    assertEquals(listOf(healthy), body.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString) })
                    200 to ackReceipt()
                }
                else -> error("Unexpected endpoint $path")
            } }
            configure(gateway)
            assertTrue(runCatching { pull() }.exceptionOrNull() is InboundMessageProcessor.InboundRetryableException)
            assertEquals(listOf(PULL, PULL, ACK), gateway.paths())
            assertEquals(before, canonicalOwnerSnapshot(bad))
            assertEquals(0, sqlCount("inbound_delivery_ledger", "gateway_url='${gateway.baseUrl}' AND delivery_id='$bad'"))
            assertEquals(0, sqlCount("inbound_delivery_ack_outbox", "delivery_id='$bad'"))
            assertEquals(1, sqlCount("operation_ledger", "op_id='operation-$bad'"))
            assertCanonical(healthy); assertEquals("acked", ackState(healthy))
            assertNull(db.providerPullFrontierDao().get()?.pageToken)
            gateway.assertHealthy()
        }
    }

    @Test
    fun provenDeletedDeliveryReplaySettlesWithoutTouchingAReplacementFromAnotherGateway() = runBlocking {
        val pending = "deleted-proven-pending"
        val acked = "deleted-proven-already-acked"
        val ids = listOf(pending, acked)
        val acknowledged = Collections.synchronizedList(mutableListOf<String>())
        val gateway = gateway { path, body -> when (path) {
            PULL -> 200 to progressPage(JSONArray(ids.map { wireItem(it, identityPayload(it)) }), null, true)
            ACK -> {
                val actual = body.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString) }
                assertEquals(listOf(pending), actual)
                acknowledged += actual
                200 to ackReceipt(actual.size)
            }
            else -> error("Unexpected processed replay endpoint")
        } }
        configure(gateway)
        val originalScope = requireNotNull(InboundDeliveryScope.create(gateway.baseUrl, "device-a"))
        val replacementScope = requireNotNull(InboundDeliveryScope.create("https://replacement-canonical.invalid", "replacement-device"))
        val fixedOldTime = System.currentTimeMillis() - 86_400_000L
        for (id in ids) {
            seedCanonicalIdentity(id, originalScope)
            val fact = requireNotNull(db.inboundDeliveryLedgerDao().get(originalScope.gatewayUrl, originalScope.deviceKey, id))
            assertEquals(true, fact.acceptanceProven)
            val localId = requireNotNull(messages.getByMessageId(id)).id
            messages.deleteById(localId)
            assertNull(messages.getByMessageId(id))
            db.openHelper.writableDatabase.execSQL(
                "UPDATE inbound_delivery_ledger SET applied_at=?,ack_state=?,acked_at=? WHERE gateway_url=? AND device_key=? AND delivery_id=?",
                arrayOf<Any?>(fixedOldTime, if (id == acked) "acked" else "pending", fixedOldTime.takeIf { id == acked },
                    originalScope.gatewayUrl, originalScope.deviceKey, id),
            )
            val replacement = identityPayload(id).put("delivery_id", "replacement-$id").put("op_id", "replacement-operation-$id")
                .put("body", "Replacement Gateway owns this separate content: $id")
            val parsed = NotificationIngressParser.parse(replacement.keys().asSequence().associateWith { replacement.get(it).toString() }, "replacement-$id", null)
                as InboundPersistenceRequest.Message
            assertTrue(messages.insertIncoming(parsed.message, deliveryScope = replacementScope, postProcessFlags = POST_PROCESS_NOTIFICATION))
        }
        val replacements = ids.associateWith(::canonicalOwnerSnapshot)
        val replacementWorkNames = ids.map { id ->
            val localId = requireNotNull(messages.getByMessageId(id)).id
            val locator = requireNotNull(messages.postProcessLocator(localId, replacementScope))
            "message-post-process:${locator.tableIndex}:${locator.generation}"
        }
        val oldAcked = requireNotNull(db.inboundDeliveryLedgerDao().get(originalScope.gatewayUrl, originalScope.deviceKey, acked))
        db.close(); open()
        var derivedCallbacks = 0
        assertEquals(2, pull { derivedCallbacks++ })
        assertEquals(0, derivedCallbacks)
        assertEquals(listOf(pending), acknowledged)
        val settled = requireNotNull(db.inboundDeliveryLedgerDao().get(originalScope.gatewayUrl, originalScope.deviceKey, pending))
        assertEquals(true, settled.acceptanceProven)
        assertEquals(fixedOldTime, settled.appliedAt)
        assertEquals("acked", settled.ackState)
        assertNotNull(settled.ackedAt)
        assertEquals(oldAcked, db.inboundDeliveryLedgerDao().get(originalScope.gatewayUrl, originalScope.deviceKey, acked))
        db.close(); open()
        assertEquals(2, pull { derivedCallbacks++ })
        assertEquals(0, derivedCallbacks)
        assertEquals(settled, db.inboundDeliveryLedgerDao().get(originalScope.gatewayUrl, originalScope.deviceKey, pending))
        assertEquals(oldAcked, db.inboundDeliveryLedgerDao().get(originalScope.gatewayUrl, originalScope.deviceKey, acked))
        ids.forEach { assertEquals(replacements.getValue(it), canonicalOwnerSnapshot(it)) }
        for (name in replacementWorkNames) assertTrue("Old-scope replay must not schedule replacement work",
            androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get(5, java.util.concurrent.TimeUnit.SECONDS).isEmpty())
        assertEquals(2, sqlCount("messages", "post_process_pending=$POST_PROCESS_NOTIFICATION"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        assertNull(db.providerPullFrontierDao().get())
        assertEquals(listOf(PULL, ACK, PULL), gateway.paths())
        gateway.assertHealthy()
    }

    @Test
    fun unprovenLegacyClaimsCannotAckForeignOrMissingContentWhileIndependentModernPagesContinue() = runBlocking {
        val foreignBody = "legacy-null-foreign-content"
        val missingBody = "legacy-null-missing-content"
        val bad = listOf(foreignBody, missingBody)
        val foreignScope = requireNotNull(InboundDeliveryScope.create("https://legacy-foreign.invalid", "foreign-device"))
        seedCanonicalIdentity(foreignBody, foreignScope)
        val original = canonicalOwnerSnapshot(foreignBody)
        val healthy = "healthy-after-unproven-legacy-claims"
        val acknowledged = Collections.synchronizedList(mutableListOf<String>())
        val gateway = gateway { path, body -> when (path) {
            PULL -> 200 to if (!body.has("page_token")) {
                progressPage(JSONArray(bad.map { wireItem(it, identityPayload(it)) }), "after-unproven-legacy", false)
            } else {
                assertEquals("after-unproven-legacy", body.getString("page_token"))
                progressPage(JSONArray().put(wireItem(healthy)), null, true)
            }
            ACK -> {
                val actual = body.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString) }
                assertEquals(listOf(healthy), actual)
                acknowledged += actual
                200 to ackReceipt()
            }
            else -> error("Unexpected unproven legacy endpoint")
        } }
        configure(gateway)
        val currentScope = requireNotNull(InboundDeliveryScope.create(gateway.baseUrl, "device-a"))
        val fixedOldTime = System.currentTimeMillis() - 86_400_000L
        // Deliberate old polluted claims, not authenticated acceptance. Both lack the new proof bit.
        for (id in bad) {
            assertTrue(claimInboundDelivery(db.inboundDeliveryLedgerDao(), "identity-lane", "message", id,
                id, "operation-$id", fixedOldTime, deliveryScope = currentScope))
            assertTrue(claimOperationScope(db.operationLedgerDao(), "identity-lane", "message", id,
                "operation-$id", id, fixedOldTime, deliveryScope = currentScope))
        }
        val legacyFacts = bad.associateWith { requireNotNull(db.inboundDeliveryLedgerDao().get(currentScope.gatewayUrl, currentScope.deviceKey, it)) }
        legacyFacts.values.forEach { assertNull(it.acceptanceProven) }
        fun legacyOperations() = db.openHelper.readableDatabase.query(
            "SELECT * FROM operation_ledger WHERE op_id IN (?,?) ORDER BY scope_key", arrayOf("operation-$foreignBody", "operation-$missingBody"),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) } }
        val operationFacts = legacyOperations()
        repeat(2) {
            assertTrue(runCatching { pull() }.exceptionOrNull() is InboundMessageProcessor.InboundRetryableException)
            assertEquals(original, canonicalOwnerSnapshot(foreignBody))
            assertNull(messages.getByMessageId(missingBody))
            bad.forEach { id ->
                assertEquals(legacyFacts.getValue(id), db.inboundDeliveryLedgerDao().get(currentScope.gatewayUrl, currentScope.deviceKey, id))
                assertEquals(0, sqlCount("inbound_delivery_ack_outbox", "delivery_id='$id'"))
            }
            assertEquals(operationFacts, legacyOperations())
            assertCanonical(healthy); assertEquals("acked", ackState(healthy))
            assertNull(db.providerPullFrontierDao().get())
            db.close(); open()
        }
        assertEquals(listOf(healthy), acknowledged)
        assertEquals(2, sqlCount("messages"))
        assertEquals(listOf(PULL, PULL, ACK, PULL, PULL), gateway.paths())
        gateway.assertHealthy()
    }

    @Test
    fun foreignCanonicalChildCannotBeAckedThroughACurrentParentOrConsumeOtherPendingRecovery() = runBlocking {
        val thing = "reused-parent-with-foreign-child"
        val bad = "foreign-existing-sub-message"
        val waiting = "foreign-pending-preserved"
        val healthy = "healthy-after-existing-sub-conflict"
        val foreign = requireNotNull(InboundDeliveryScope.create("https://old-child-origin.invalid", "foreign-child-device"))
        fun parent(delivery: String) = JSONObject().put("entity_type", "thing").put("entity_id", thing).put("thing_id", thing)
            .put("delivery_id", delivery).put("op_id", "operation-$delivery").put("channel_id", "old-parent-channel")
            .put("title", "Scoped parent").put("body", "Retained scoped parent").put("sent_at", "2026-01-01T00:00:00Z").put("ttl", "1")
        suspend fun seedParent(raw: JSONObject, scope: InboundDeliveryScope) {
            val parsed = NotificationIngressParser.parse(raw.keys().asSequence().associateWith { raw.get(it).toString() }, raw.getString("delivery_id"), null)
                as InboundPersistenceRequest.Entity
            assertTrue(entities.insertIncoming(parsed.record, deliveryScope = scope))
        }
        suspend fun seedChild(id: String, thingId: String): Boolean {
            val raw = identityPayload(id).put("thing_id", thingId).put("occurred_at", "1767225600000")
            val parsed = NotificationIngressParser.parse(raw.keys().asSequence().associateWith { raw.get(it).toString() }, id, null)
                as InboundPersistenceRequest.Message
            return messages.insertIncoming(parsed.message, deliveryScope = foreign)
        }
        assertFalse(seedChild(waiting, "still-missing-foreign-parent"))
        val oldPending = requireNotNull(db.pendingThingMessageDao().getByMessageId(waiting))
        val gateway = gateway { path, body -> when (path) {
            PULL -> 200 to if (!body.has("page_token")) {
                progressPage(JSONArray().put(wireItem(bad, identityPayload(bad).put("thing_id", thing)
                    .put("occurred_at", "1767225600000"))), "after-existing-sub-conflict", false)
            } else {
                assertEquals("after-existing-sub-conflict", body.getString("page_token"))
                progressPage(JSONArray().put(wireItem(healthy)), null, true)
            }
            ACK -> {
                assertEquals(listOf(healthy), body.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString) })
                200 to ackReceipt()
            }
            else -> error("Unexpected child acceptance endpoint")
        } }
        configure(gateway)
        val current = requireNotNull(InboundDeliveryScope.create(gateway.baseUrl, "device-a"))
        seedParent(parent("current-parent-for-child"), current)
        val currentHead = requireNotNull(db.thingHeadDao().getByThingId(thing))
        assertEquals(gateway.baseUrl, db.authenticatedSourceGateway(currentHead.sourceId))
        // Explicit historical fixture: an already-present foreign canonical child.
        // The current normal API correctly refuses to manufacture this mixed-owner
        // state, so seed its old facts directly rather than claiming normal acceptance.
        val oldRaw = identityPayload(bad).put("thing_id", thing).put("occurred_at", "1767225600000")
        val parsedOld = NotificationIngressParser.parse(oldRaw.keys().asSequence().associateWith { oldRaw.get(it).toString() }, bad, null)
            as InboundPersistenceRequest.Message
        db.withTransaction {
            db.thingSubMessageDao().insert(io.ethan.pushgo.data.db.ThingSubMessageEntity.fromModel(parsedOld.message))
            db.recordResourceOrigin(parsedOld.message.id, parsedOld.message.receivedAt.toEpochMilli(), foreign)
            assertTrue(claimInboundDelivery(db.inboundDeliveryLedgerDao(), "identity-lane", "message", bad,
                bad, "operation-$bad", parsedOld.message.receivedAt.toEpochMilli(), deliveryScope = foreign, acceptanceProven = true))
            assertTrue(claimOperationScope(db.operationLedgerDao(), "identity-lane", "message", bad,
                "operation-$bad", bad, parsedOld.message.receivedAt.toEpochMilli(), deliveryScope = foreign))
        }
        val oldSub = requireNotNull(db.thingSubMessageDao().getByMessageId(bad))
        val oldOwner = db.messageResourceDao().owners(oldSub.id)
        val oldSubFact = db.inboundDeliveryLedgerDao().get(foreign.gatewayUrl, foreign.deviceKey, bad)
        val oldPendingFact = db.inboundDeliveryLedgerDao().get(foreign.gatewayUrl, foreign.deviceKey, waiting)
        repeat(2) {
            assertTrue(runCatching { pull() }.exceptionOrNull() is InboundMessageProcessor.InboundRetryableException)
            assertEquals(currentHead, db.thingHeadDao().getByThingId(thing))
            assertEquals(oldSub, db.thingSubMessageDao().getByMessageId(bad))
            assertEquals(oldOwner, db.messageResourceDao().owners(oldSub.id))
            assertEquals(oldPending, db.pendingThingMessageDao().getByMessageId(waiting))
            assertEquals(oldSubFact, db.inboundDeliveryLedgerDao().get(foreign.gatewayUrl, foreign.deviceKey, bad))
            assertEquals(oldPendingFact, db.inboundDeliveryLedgerDao().get(foreign.gatewayUrl, foreign.deviceKey, waiting))
            assertEquals(0, sqlCount("inbound_delivery_ledger", "gateway_url='${gateway.baseUrl}' AND delivery_id='$bad'"))
            assertEquals(1, sqlCount("operation_ledger", "op_id='operation-$bad'"))
            assertEquals(0, sqlCount("inbound_delivery_ack_outbox", "delivery_id='$bad'"))
            assertEquals(1, sqlCount("thing_sub_messages")); assertEquals(1, sqlCount("pending_thing_messages"))
            assertCanonical(healthy); assertEquals("acked", ackState(healthy))
            assertNull(db.providerPullFrontierDao().get())
            db.close(); open()
        }
        assertEquals(listOf(PULL, PULL, ACK, PULL, PULL), gateway.paths())
        gateway.assertHealthy()
    }

    @Test
    fun foreignEncryptedQuarantineDoesNotOwnAnotherGatewaysIndependentChildDomain() = runBlocking {
        val stable = "foreign-encrypted-shared-id"
        val current = requireNotNull(InboundDeliveryScope.create("https://current-encrypted.invalid", "current-device"))
        val foreign = requireNotNull(InboundDeliveryScope.create("https://foreign-encrypted.invalid", "foreign-device"))
        fun parsed(raw: JSONObject) = NotificationIngressParser.parse(
            raw.keys().asSequence().associateWith { raw.get(it).toString() }, stable, null,
        ) as InboundPersistenceRequest.Message
        val foreignRaw = identityPayload(stable).put("ciphertext", "retained-foreign-envelope")
        val old = parsed(foreignRaw).message.copy(
            rawPayloadJson = foreignRaw.toString(),
            decryptionState = io.ethan.pushgo.data.model.DecryptionState.DECRYPT_FAILED,
        )
        assertTrue(messages.insertIncoming(old, deliveryScope = foreign, claimIngressIdentity = false))
        val previousTop = canonicalOwnerSnapshot(stable)
        val childRaw = identityPayload(stable).put("thing_id", "independent-current-parent")
            .put("occurred_at", "1767225600000")
            .put("ciphertext", "independently-authenticated-current-envelope")
        // This Room-level fixture starts after authenticated parsing; actual AES
        // promotion/rejection is owned by the Private ingress journey.
        val parsedChild = parsed(childRaw)
        val child = parsedChild.copy(
            message = parsedChild.message.copy(
                title = childRaw.getString("title"),
                body = childRaw.getString("body"),
                rawPayloadJson = childRaw.toString(),
                decryptionState = io.ethan.pushgo.data.model.DecryptionState.DECRYPT_OK,
            ),
            shouldNotify = false,
            securityDisposition = io.ethan.pushgo.notifications.InboundSecurityDisposition.ACCEPTED,
        )
        val outcome = io.ethan.pushgo.notifications.InboundPersistenceCoordinator.persistAndNotify(
            context, messages, entities, ledger, settings, child, deliveryScope = current,
            postProcessRecovery = {},
        )
        assertEquals(io.ethan.pushgo.notifications.InboundPersistenceStatus.PERSISTED_PENDING, outcome.status)
        assertTrue(outcome.shouldAck)
        assertEquals(previousTop, canonicalOwnerSnapshot(stable))
        val pending = requireNotNull(db.pendingThingMessageDao().getByMessageId(stable))
        assertEquals("independent-current-parent", pending.thingId)
        assertEquals(current.gatewayUrl, pending.authenticatedGatewayUrl)
        assertEquals(current.deviceKey, pending.authenticatedDeviceKey)
        assertEquals(true, db.inboundDeliveryLedgerDao().get(current.gatewayUrl, current.deviceKey, stable)?.acceptanceProven)
        assertNull(db.inboundDeliveryLedgerDao().get(foreign.gatewayUrl, foreign.deviceKey, stable))
    }

    @Test
    fun childReplayHandoffUsesItsCanonicalDomainInsteadOfAForeignTopLevelMessageWithTheSameStableId() = runBlocking {
        val stable = "cross-domain-shared-message-id"
        val thing = "cross-domain-current-parent"
        val current = requireNotNull(InboundDeliveryScope.create("https://cross-domain-current.invalid", "current-device"))
        val foreign = requireNotNull(InboundDeliveryScope.create("https://cross-domain-foreign.invalid", "foreign-device"))
        val parent = JSONObject().put("entity_type", "thing").put("entity_id", thing).put("thing_id", thing)
            .put("delivery_id", "cross-domain-parent-delivery").put("op_id", "cross-domain-parent-operation")
            .put("title", "Current parent").put("body", "Current parent body").put("ttl", "1")
        val parsedParent = NotificationIngressParser.parse(parent.keys().asSequence().associateWith { parent.get(it).toString() }, "cross-domain-parent-delivery", null)
            as InboundPersistenceRequest.Entity
        assertTrue(entities.insertIncoming(parsedParent.record, deliveryScope = current))
        val childRaw = identityPayload(stable).put("thing_id", thing).put("occurred_at", "1767225600000")
        fun parseChild() = NotificationIngressParser.parse(childRaw.keys().asSequence().associateWith { childRaw.get(it).toString() }, stable, null)
            as InboundPersistenceRequest.Message
        val child = parseChild()
        assertFalse(child.shouldNotify)
        assertTrue(messages.insertIncoming(child.message, deliveryScope = current))
        // The separate top/sub canonical domains may already share a stable ID.
        // Reproduce that historical state without forbidding its existing contract.
        val topRaw = identityPayload(stable).put("body", "Foreign top-level body must not become the child handoff")
        val top = NotificationIngressParser.parse(topRaw.keys().asSequence().associateWith { topRaw.get(it).toString() }, stable, null)
            as InboundPersistenceRequest.Message
        assertTrue(messages.insertIncoming(top.message, deliveryScope = foreign))
        val originalSub = requireNotNull(db.thingSubMessageDao().getByMessageId(stable))
        val originalTop = canonicalOwnerSnapshot(stable)
        val originalSubOwner = db.messageResourceDao().owners(originalSub.id)
        val originalFact = db.inboundDeliveryLedgerDao().get(current.gatewayUrl, current.deviceKey, stable)
        db.close(); open()
        val replay = parseChild()
        assertNotEquals(originalSub.id, replay.message.id)
        val handedOff = mutableListOf<Pair<String, String?>>()
        var unexpectedRecovery = 0
        var unexpectedNotification = 0
        val outcome = io.ethan.pushgo.notifications.InboundPersistenceCoordinator.persistAndNotify(
            context, messages, entities, ledger, settings, replay, deliveryScope = current,
            postProcessRecovery = { unexpectedRecovery++ },
            notificationPublisher = { _, _, _ -> unexpectedNotification++; false },
            beforeMessageNotify = { canonical, _ -> handedOff += canonical.id to canonical.thingId },
        )
        assertEquals(io.ethan.pushgo.notifications.InboundPersistenceStatus.DUPLICATE, outcome.status)
        assertTrue(outcome.shouldAck)
        assertFalse(outcome.notified)
        assertEquals(listOf(originalSub.id to thing), handedOff)
        assertEquals(0, unexpectedRecovery)
        assertEquals(0, unexpectedNotification)
        assertEquals(originalTop, canonicalOwnerSnapshot(stable))
        assertEquals(originalSub, db.thingSubMessageDao().getByMessageId(stable))
        assertEquals(originalSubOwner, db.messageResourceDao().owners(originalSub.id))
        assertEquals(originalFact, db.inboundDeliveryLedgerDao().get(current.gatewayUrl, current.deviceKey, stable))
        assertEquals(1, sqlCount("messages")); assertEquals(1, sqlCount("thing_sub_messages"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
    }

    @Test
    fun inlineHintCannotPersistOrAckBeforeItsAuthenticatedTargetedResponse() = runBlocking {
        val id = "inline-verified-target"
        var requests = 0
        val gateway = gateway { path, body -> when (path) {
            PULL -> {
                if (body.optString("delivery_id") == "inline-authenticated-empty") {
                    200 to progressPage(JSONArray(), null, true)
                } else {
                assertEquals(id, body.getString("delivery_id"))
                assertEquals("device-a", body.getString("device_key")); assertFalse(body.has("page_token"))
                if (++requests == 1) 503 to JSONObject().put("success", false).put("error_code", "fixture_unavailable")
                    .put("problem", JSONObject().put("code", "fixture_unavailable").put("status", 503).put("retryable", true).put("category", "upstream"))
                else 200 to progressPage(JSONArray().put(wireItem(id, identityPayload(id))), null, true)
                }
            }
            ACK -> { assertCanonical(id); 200 to ackReceipt() }
            else -> error("Inline hint escaped its authenticated V2 endpoint")
        } }
        configure(gateway); settings.setGatewayToken("synthetic-inline-configured")
        val advertised = identityPayload(id).put("title", "Untrusted inline title").put("body", "Untrusted inline body")
            .put("base_url", gateway.baseUrl).put("provider_device_key", "device-a")
        val data = advertised.keys().asSequence().associateWith { advertised.get(it).toString() }
        val first = runCatching { actualInboundProcessor().process(context, data, "inline-transport") }.exceptionOrNull()
        assertNotNull(first); assertTrue(io.ethan.pushgo.notifications.shouldRetryInboundFailure(requireNotNull(first)))
        assertEquals(listOf(PULL), gateway.paths())
        assertEquals(0, sqlCount("messages")); assertEquals(0, sqlCount("inbound_delivery_ledger")); assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        db.close(); open() // Same durable store, not process-death or an actual WorkManager retry.
        actualInboundProcessor().process(context, data, "inline-transport")
        assertCanonical(id); assertEquals("acked", ackState(id))
        assertEquals(listOf(PULL, PULL, ACK), gateway.paths())
        assertEquals(listOf("Bearer synthetic-inline-configured", "Bearer synthetic-inline-configured"), gateway.authorizations(PULL))
        assertEquals(1, sqlCount("inbound_delivery_ledger", "acceptance_proven=1")); assertNull(db.providerPullFrontierDao().get())
        val source = requireNotNull(messages.getByMessageId(id))
        assertEquals(gateway.baseUrl, db.messageResourceDao().owners(source.id).single().let { owner ->
            db.openHelper.readableDatabase.query("SELECT authenticated_gateway_url FROM message_resource_owners WHERE generation=?", arrayOf(owner.generation))
                .use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
        })
        val emptyHint = data + ("delivery_id" to "inline-authenticated-empty")
        actualInboundProcessor().process(context, emptyHint, "inline-empty-transport")
        assertEquals(listOf(PULL, PULL, ACK, PULL), gateway.paths())
        assertEquals(1, sqlCount("messages")); assertEquals(1, sqlCount("inbound_delivery_ledger"))
        assertEquals(0, sqlCount("inbound_delivery_ledger", "delivery_id='inline-authenticated-empty'"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        gateway.assertHealthy()
    }

    @Test
    fun inlineHintRejectsMissingTargetAndLateIdentityChangesWithoutInlineFallback() = runBlocking {
        val id = "inline-changing-target"
        // Read the actual context predicate without contacting this public address:
        // clearing an explicit default-valued URL must not revive it by fallback.
        settings.setServerAddress(AppConstants.defaultServerAddress)
        settings.setGatewayToken("synthetic-configured-default")
        settings.setDeviceKey("device-a")
        val explicitDefault = channels.preparePullContext(id, strictConfiguredTarget = true)
        assertTrue(channels.isCurrentPullContext(explicitDefault))
        settings.setServerAddress(null)
        assertFalse(channels.isCurrentPullContext(explicitDefault))
        assertEquals(0, sqlCount("messages")); assertEquals(0, sqlCount("inbound_delivery_ledger"))
        var requests = 0
        val gateway = gateway { path, body -> when (path) {
            PULL -> {
                assertEquals(id, body.getString("delivery_id")); assertEquals("device-a", body.getString("device_key"))
                if (++requests == 1) runBlocking { settings.setGatewayToken("synthetic-new-identity"); settings.setDeviceKey("device-b") }
                200 to progressPage(JSONArray().put(wireItem(id, identityPayload(id))), null, true)
            }
            ACK -> { assertCanonical(id); 200 to ackReceipt() }
            else -> error("Unverified inline hint escaped its targeted endpoint")
        } }
        configure(gateway)
        val advertised = identityPayload(id).put("body", "Inline must never become canonical")
            .put("base_url", gateway.baseUrl).put("provider_device_key", "device-a")
        fun data() = advertised.keys().asSequence().associateWith { advertised.get(it).toString() }
        suspend fun rejected(input: Map<String,String>, retryable: Boolean = true) {
            val error = runCatching { actualInboundProcessor().process(context, input, "changing-inline-transport") }.exceptionOrNull()
            assertNotNull(error); assertEquals(retryable, io.ethan.pushgo.notifications.shouldRetryInboundFailure(requireNotNull(error)))
            assertEquals(0, sqlCount("messages")); assertEquals(0, sqlCount("inbound_delivery_ledger")); assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        }
        rejected(mapOf("entity_type" to "message", "body" to "No delivery cannot be verified"), retryable = false)
        settings.setGatewayToken(null); rejected(data())
        settings.setGatewayToken("synthetic-original-identity"); settings.setDeviceKey(null); rejected(data())
        settings.setDeviceKey("device-a"); advertised.put("provider_device_key", "advertised-other-device"); rejected(data())
        advertised.put("provider_device_key", "device-a").put("base_url", "https://foreign-hint.invalid"); rejected(data())
        assertTrue(gateway.paths().isEmpty())
        advertised.put("base_url", gateway.baseUrl)
        rejected(data()) // The response loses its frozen context while HTTP is in flight.
        assertEquals(listOf(PULL), gateway.paths()); assertEquals("device-b", settings.getDeviceKey())
        settings.setGatewayToken("synthetic-original-identity"); settings.setDeviceKey("device-a")
        actualInboundProcessor().process(context, data(), "changing-inline-transport")
        assertCanonical(id); assertEquals("acked", ackState(id)); assertEquals(listOf(PULL, PULL, ACK), gateway.paths())
        assertEquals(listOf("Bearer synthetic-original-identity", "Bearer synthetic-original-identity"), gateway.authorizations(PULL))
        assertNull(db.providerPullFrontierDao().get()); gateway.assertHealthy()
    }

    private fun actualInboundProcessor() = io.ethan.pushgo.notifications.InboundMessageProcessor(
        runtimeResolver = io.ethan.pushgo.notifications.InboundProcessorRuntimeResolver {
            io.ethan.pushgo.notifications.DefaultInboundProcessorRuntime(channels, messages, entities, ledger, settings)
        },
    )

    private fun identityPayload(id: String) = payload(id).put("entity_id", id).put("channel_id", "identity-lane")
        .put("delivery_id", id).put("op_id", "operation-$id")

    private suspend fun seedCanonicalIdentity(id: String, origin: InboundDeliveryScope?) {
        val raw = identityPayload(id)
        val parsed = NotificationIngressParser.parse(raw.keys().asSequence().associateWith { raw.get(it).toString() }, id, null)
            as InboundPersistenceRequest.Message
        assertFalse(parsed.shouldNotify)
        assertTrue(messages.insertIncoming(parsed.message, deliveryScope = origin))
    }

    private fun canonicalOwnerSnapshot(id: String): List<String?> = db.openHelper.readableDatabase.query(
        "SELECT m.*,o.* FROM messages m JOIN message_resource_owners o ON o.source_table='messages' AND o.owner_id=m.id AND o.owner_slot=0 WHERE m.message_id=?",
        arrayOf(id),
    ).use { cursor ->
        assertTrue("the exact canonical owner must remain present", cursor.moveToFirst())
        (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }
    }

    @Test
    fun fourPageTurnPersistsItsFrontierAndReopenStartsAtTheFifthPage() = runBlocking {
        val requestedTokens = Collections.synchronizedList(mutableListOf<String?>())
        val generations = Collections.synchronizedList(mutableListOf<String>())
        val gateway = gateway { path, body -> when (path) {
            PULL -> {
                val token = body.optString("page_token").takeIf { body.has("page_token") }
                requestedTokens += token
                val pageNumber = when (token) {
                    null -> 1
                    "p1" -> 2
                    "p2" -> 3
                    "p3" -> 4
                    "p4" -> 5
                    else -> error("Unexpected synthetic scan position")
                }
                generations += checkNotNull(runBlocking { db.providerPullFrontierDao().get() }).requestGeneration
                200 to progressPage(JSONArray().put(wireItem("turn-page-$pageNumber")),
                    nextToken = "p$pageNumber".takeUnless { pageNumber == 5 }, complete = pageNumber == 5)
            }
            ACK -> {
                val values = body.getJSONArray("delivery_ids")
                val pageNumber = values.getString(0).substringAfterLast('-').toInt()
                val frontier = runBlocking { db.providerPullFrontierDao().get() }
                if (pageNumber == 5) assertNull(frontier) else assertEquals("p$pageNumber", frontier?.pageToken)
                200 to ackReceipt(values.length())
            }
            else -> error("Unexpected endpoint $path")
        } }
        configure(gateway)
        val started = SystemClock.elapsedRealtime()
        val firstTurn = pull()
        val elapsedMs = SystemClock.elapsedRealtime() - started
        assertEquals("Four small pages must fit the five-second soft turn budget; actual ${elapsedMs}ms", 4, firstTurn)
        val saved = checkNotNull(db.providerPullFrontierDao().get())
        assertEquals("p4", saved.pageToken)
        assertEquals(4, sqlCount("messages"))
        assertEquals(4, requestedTokens.size)
        assertEquals(1, generations.toSet().size)
        assertEquals(saved.requestGeneration, generations.first())
        db.close(); open()
        assertEquals(saved, db.providerPullFrontierDao().get())
        assertEquals(1, pull())
        assertEquals(listOf(null, "p1", "p2", "p3", "p4"), requestedTokens)
        assertNotEquals(saved.requestGeneration, generations.last())
        assertNull(db.providerPullFrontierDao().get())
        assertEquals(5, sqlCount("messages"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        for (number in 1..5) {
            assertCanonical("turn-page-$number")
            assertEquals("acked", ackState("turn-page-$number"))
        }
        assertEquals((1..5).flatMap { listOf(PULL, ACK) }, gateway.paths())
        gateway.assertHealthy()
    }

    @Test
    fun samePageKeepsItsOriginalScopeAndAckCredentialAfterLiveIdentityChanges() = runBlocking {
        val ids = listOf("frozen-page-first", "frozen-page-second")
        val oldCredential = "synthetic-frozen-page-old"
        val newCredential = "synthetic-frozen-page-new"
        val gateway = gateway { path, body -> when (path) {
            PULL -> {
                assertEquals("device-a", body.getString("device_key"))
                assertFalse(body.has("page_token"))
                200 to progressPage(JSONArray(ids.map { wireItem(it) }), "old-page-next", false)
            }
            ACK -> {
                assertEquals("device-a", body.getString("device_key"))
                assertEquals(ids.toSet(), body.getJSONArray("delivery_ids").let { values ->
                    (0 until values.length()).map(values::getString).toSet()
                })
                assertEquals(2, sqlCount("messages"))
                200 to ackReceipt(2)
            }
            else -> error("Unexpected endpoint $path")
        } }
        configure(gateway)
        settings.setGatewayToken(oldCredential)
        var notifyCalls = 0
        var replacement: ProviderPullContext? = null
        assertEquals(2, pull {
            if (++notifyCalls == 1) {
                settings.setGatewayToken(newCredential)
                settings.setDeviceKey("device-b")
                replacement = channels.preparePullContext(null)
            }
        })
        assertEquals(2, notifyCalls)
        val newFrontier = checkNotNull(replacement?.frontier)
        assertEquals(newFrontier, db.providerPullFrontierDao().get())
        assertNull(newFrontier.pageToken)
        assertEquals("device-b", settings.getDeviceKey())
        assertTrue("The ACK must retain the page's captured credential",
            gateway.authorizations(ACK) == listOf("Bearer $oldCredential"))
        assertTrue("The request must use the original synthetic credential",
            gateway.authorizations(PULL) == listOf("Bearer $oldCredential"))
        val persistedScopes = db.openHelper.readableDatabase.query(
            "SELECT gateway_url,device_key,ack_state FROM inbound_delivery_ledger ORDER BY delivery_id",
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
        } }
        assertEquals(List(2) { listOf(gateway.baseUrl, "device-a", "acked") }, persistedScopes)
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        ids.forEach(::assertCanonical)
        assertEquals(listOf(PULL, ACK), gateway.paths())
        gateway.assertHealthy()
    }

    @Test
    fun onlyExactTokenInvalidationResponsesClearTheCapturedFrontier() = runBlocking {
        data class Case(val status: Int, val topCode: String, val problemCode: String, val clears: Boolean)
        val cases = listOf(
            Case(400, "page_token_invalid", "page_token_invalid", true),
            Case(409, "page_token_scope_changed", "page_token_scope_changed", true),
            Case(503, "page_token_invalid", "page_token_invalid", false),
            Case(503, "page_token_scope_changed", "page_token_scope_changed", false),
            Case(400, "page_token_invalid", "upstream_unavailable", false),
            Case(409, "page_token_scope_changed", "page_token_invalid", false),
        )
        var request = 0
        val gateway = gateway { path, body ->
            assertEquals(PULL, path)
            assertEquals("synthetic-error-frontier", body.getString("page_token"))
            val case = cases[request++]
            case.status to JSONObject().put("success", false).put("error_code", case.topCode)
                .put("problem", JSONObject().put("code", case.problemCode).put("status", case.status)
                    .put("category", "validation").put("retryable", case.status == 503))
        }
        configure(gateway)
        for (case in cases) {
            val initial = channels.preparePullContext(null)
            db.providerPullFrontierDao().put(checkNotNull(initial.frontier).copy(pageToken = "synthetic-error-frontier"))
            val captured = channels.preparePullContext(null)
            val before = db.providerPullFrontierDao().get()
            val failure = runCatching { channels.pullMessages(captured, null) }.exceptionOrNull()
            if (case.clears) {
                assertTrue(failure is ProviderPullTurnEnded)
                assertNull(db.providerPullFrontierDao().get())
            } else {
                assertTrue(failure is ChannelSubscriptionException)
                assertEquals(before, db.providerPullFrontierDao().get())
            }
        }
        assertEquals(List(cases.size) { PULL }, gateway.paths())
        assertEquals(0, sqlCount("messages"))
        assertEquals(0, sqlCount("inbound_delivery_ledger"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        gateway.assertHealthy()
    }

    @Test
    fun backgroundDrainHoldsAnOldDeviceWithoutStarvingTheCurrentScopeAndRecoversWhenExactContextReturns() = runBlocking {
        val oldId = "background-old-device"
        val newId = "background-new-device"
        var expectedDevice = "device-b"
        val gateway = gateway { path, body ->
            assertEquals(ACK, path)
            assertEquals(expectedDevice, body.getString("device_key"))
            val expectedId = if (expectedDevice == "device-b") newId else oldId
            assertEquals(listOf(expectedId), body.getJSONArray("delivery_ids").let { values ->
                (0 until values.length()).map(values::getString)
            })
            200 to ackReceipt()
        }
        configure(gateway)
        settings.setGatewayToken("synthetic-old-device-token")
        suspend fun seed(id: String, device: String) {
            val identity = checkNotNull(ProviderAckIdentity.create(
                ProviderAckDestination(gateway.baseUrl, device), ProviderAckContract.V2_BATCH, "provider_pull"))
            val raw = payload(id).put("delivery_id", id)
            val parsed = checkNotNull(NotificationIngressParser.parse(
                buildMap { raw.keys().forEach { key -> put(key, raw.getString(key)) } }, id, null,
            ) as? InboundPersistenceRequest.Message)
            assertTrue(messages.insertIncoming(parsed.message, identity))
            ledger.enqueueAcks(listOf(id), identity)
        }
        channels.loadAckDestination() // Existing old-route credential behavior, no new storage.
        seed(oldId, "device-a")
        settings.setDeviceKey("device-b")
        settings.setGatewayToken("synthetic-new-device-token")
        channels.loadAckDestination()
        seed(newId, "device-b")
        db.close(); open()
        val first = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = ledger::loadFairPendingAcks, beginAttempt = ledger::beginAckAttempt,
            ackMessages = channels::ackMessages, markAcked = ledger::markAckRecordsAcked,
        )
        assertEquals(listOf(oldId), first.failedIds)
        assertEquals(listOf(newId), first.ackedIds)
        assertEquals("ack_context_unavailable", (first.failureCause as ChannelSubscriptionException).code)
        assertEquals(listOf(ACK), gateway.paths())
        assertEquals(listOf("Bearer synthetic-new-device-token"), gateway.authorizations(ACK))
        assertEquals("pending", ackState(oldId))
        assertEquals("acked", ackState(newId))
        assertEquals(1, sqlCount("inbound_delivery_ack_outbox"))
        settings.setDeviceKey("device-a")
        settings.setGatewayToken("synthetic-old-device-token")
        expectedDevice = "device-a"
        db.close(); open()
        val recovered = ProviderAckDrainCoordinator.drainPendingAcks(
            loadPendingAcks = ledger::loadFairPendingAcks, beginAttempt = ledger::beginAckAttempt,
            ackMessages = channels::ackMessages, markAcked = ledger::markAckRecordsAcked,
        )
        assertTrue(recovered.failedIds.isEmpty())
        assertEquals(listOf(oldId), recovered.ackedIds)
        assertEquals(listOf("Bearer synthetic-new-device-token", "Bearer synthetic-old-device-token"), gateway.authorizations(ACK))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        assertEquals("acked", ackState(oldId))
        assertEquals("acked", ackState(newId))
        assertCanonical(oldId); assertCanonical(newId)
        gateway.assertHealthy()
    }

    @Test
    fun realAppContainerOwnerContinuesBeyondFourPagesAndStopsAtScanCompletion() = runBlocking {
        val base = context
        val ownerId = java.util.UUID.randomUUID().toString()
        val ownerRoot = java.io.File(base.cacheDir, "provider-pull-owner-$ownerId").apply { mkdirs() }
        val ownerDbRoot = java.io.File(ownerRoot, "databases").apply { mkdirs() }
        val preferencePrefix = "provider_pull_owner_${ownerId}_"
        val ownedPreferenceNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val owned = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): java.io.File = java.io.File(ownerRoot, "files").apply { mkdirs() }
            override fun getCacheDir(): java.io.File = java.io.File(ownerRoot, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir(): java.io.File = java.io.File(ownerRoot, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): java.io.File {
                // ImageAssetMetadataStore passes its already-resolved absolute path.
                // Resolve that path only when it belongs to this same isolated root.
                val candidate = java.io.File(name)
                val result = if (candidate.isAbsolute) candidate else java.io.File(ownerDbRoot, name)
                check(result.canonicalPath.startsWith(ownerDbRoot.canonicalPath + java.io.File.separator))
                result.parentFile?.mkdirs()
                return result
            }
            override fun databaseList(): Array<String> = ownerDbRoot.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".db") }.map { it.name }.toTypedArray()
            override fun deleteDatabase(name: String): Boolean =
                android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolatedName = preferencePrefix + name
                ownedPreferenceNames += isolatedName
                return base.getSharedPreferences(isolatedName, mode)
            }
        }
        assertNull(io.ethan.pushgo.testing.QualityRuntime.currentSession())
        assertTrue(
            "The already-authorized LAN permission must be present; this test never grants it",
            io.ethan.pushgo.util.LocalNetworkPermission.isGranted(owned),
        )
        // Reuse the app's existing encryption key. Do not create, rotate or delete an
        // AndroidKeyStore alias to make this instrumentation scene executable.
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("The existing app encryption key is a required precondition",
            keyStore.containsAlias("pushgo.secure.secrets.v1"))

        val ownerJob = kotlinx.coroutines.SupervisorJob()
        val ownerScope = kotlinx.coroutines.CoroutineScope(ownerJob + kotlinx.coroutines.Dispatchers.IO)
        val routeReady = java.util.concurrent.atomic.AtomicReference(
            kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) })
        var container: AppContainer? = null
        val fifthRequested = java.util.concurrent.CountDownLatch(1)
        val releaseFifth = java.util.concurrent.CountDownLatch(1)
        val requestNumber = java.util.concurrent.atomic.AtomicInteger(0)
        val observedGenerations = java.util.Collections.synchronizedList(mutableListOf<String>())
        val observations = org.json.JSONArray()
        var originalFailure: Throwable? = null
        var firstTurnCount: Int? = null
        var firstTurnElapsedMs: Long? = null
        var success = false
        fun observe(stage: String, extra: org.json.JSONObject = org.json.JSONObject()) {
            synchronized(observations) {
                observations.put(extra.put("stage", stage)
                    .put("elapsed_realtime_ms", android.os.SystemClock.elapsedRealtime()))
            }
        }
        try {
            val actual = AppContainer(owned, ownerScope, awaitRouteSynchronization = { routeReady.get().await() })
            container = actual
            assertTrue(owned.getDatabasePath("pushgo.db").canonicalPath.startsWith(ownerDbRoot.canonicalPath))
            fun countOwner(table: String, condition: String = "1=1"): Int =
                actual.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE $condition")
                    .use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
            val gateway = gateway { path, body ->
                when (path) {
                    PULL -> {
                        val number = requestNumber.incrementAndGet()
                        check(number in 1..9) { "Completed or cancelled owner reopened the scan head" }
                        val cancelling = number > 5
                        val pageNumber = if (cancelling) number - 5 else number
                        val tokenPrefix = if (cancelling) "cancel-next-" else "owner-next-"
                        val expectedToken = if (pageNumber == 1) null else tokenPrefix + (pageNumber - 1)
                        val suppliedToken = body.optString("page_token").takeIf { body.has("page_token") }
                        assertEquals(expectedToken, suppliedToken)
                        assertEquals("synthetic-owner-device", body.getString("device_key"))
                        val row = checkNotNull(runBlocking { actual.database.providerPullFrontierDao().get() })
                        assertEquals(expectedToken, row.pageToken)
                        observedGenerations += row.requestGeneration
                        observe("pull", org.json.JSONObject().put("page_number", number))
                        if (number == 5) {
                            // The owner can start while the caller still finishes its
                            // post-turn maintenance. Hold only the response so assertions
                            // after the returned count do not depend on that timing.
                            fifthRequested.countDown()
                            check(releaseFifth.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                "The test did not release its own fifth-page response"
                            }
                        }
                        val id = if (cancelling) "owner-cancel-page-$pageNumber" else "owner-page-$pageNumber"
                        200 to progressPage(org.json.JSONArray().put(wireItem(id)),
                            nextToken = if (number == 5) null else tokenPrefix + pageNumber, complete = number == 5)
                    }
                    ACK -> {
                        val values = body.getJSONArray("delivery_ids")
                        assertEquals(1, values.length())
                        assertEquals("synthetic-owner-device", body.getString("device_key"))
                        val cancelling = values.getString(0).startsWith("owner-cancel-")
                        val number = values.getString(0).substringAfterLast('-').toInt()
                        val frontier = runBlocking { actual.database.providerPullFrontierDao().get() }
                        if (!cancelling && number == 5) assertNull(frontier)
                        else assertEquals((if (cancelling) "cancel-next-" else "owner-next-") + number, frontier?.pageToken)
                        assertEquals(if (cancelling) number + 5 else number, countOwner("messages"))
                        observe("ack", org.json.JSONObject().put("page_number", number))
                        200 to ackReceipt(1)
                    }
                    else -> error("Unexpected owner-loopback endpoint")
                }
            }
            actual.settingsRepository.setServerAddress(gateway.baseUrl)
            actual.settingsRepository.setDeviceKey("synthetic-owner-device")
            actual.settingsRepository.setGatewayToken("synthetic-owner-credential")
            actual.settingsRepository.setUseFcmChannel(false)
            // Do not call setRuntime(), transport synchronization or any native-start
            // entry. The preconfigured device and private-mode setting avoid FCM route
            // registration; the constructor's real network callbacks remain unchanged.
            val started = android.os.SystemClock.elapsedRealtime()
            firstTurnCount = io.ethan.pushgo.notifications.ProviderIngressCoordinator.pullPersistAndDrainAcks(
                context = owned,
                channelRepository = actual.channelRepository,
                messageRepository = actual.messageRepository,
                entityRepository = actual.entityRepository,
                inboundDeliveryLedgerRepository = actual.inboundDeliveryLedgerRepository,
                settingsRepository = actual.settingsRepository,
                reason = "actual_owner_positive",
            )
            firstTurnElapsedMs = android.os.SystemClock.elapsedRealtime() - started
            assertEquals("Four small pages must fit the five-second soft turn budget; actual ${firstTurnElapsedMs}ms",
                4, firstTurnCount)
            kotlinx.coroutines.withTimeout(10_000L) {
                while (fifthRequested.count != 0L) {
                    gateway.assertHealthy()
                    kotlinx.coroutines.delay(10L)
                }
            }
            // The fifth response is still held here. No manual second pull, callback,
            // private resolver or repository helper is used to drive continuation.
            assertEquals(4, countOwner("messages"))
            assertEquals(0, countOwner("inbound_delivery_ack_outbox"))
            assertEquals("owner-next-4", actual.database.providerPullFrontierDao().get()?.pageToken)
            observe("automatic_fifth_request_observed")
            releaseFifth.countDown()
            kotlinx.coroutines.withTimeout(10_000L) {
                while (countOwner("messages") != 5 || countOwner("inbound_delivery_ack_outbox") != 0 ||
                    countOwner("inbound_delivery_ledger", "ack_state='acked'") != 5 ||
                    actual.database.providerPullFrontierDao().get() != null) {
                    gateway.assertHealthy()
                    kotlinx.coroutines.delay(10L)
                }
            }
            for (number in 1..5) {
                val id = "owner-page-$number"
                val stored = checkNotNull(actual.messageRepository.getByMessageId(id))
                assertEquals("retained-$id", stored.title)
                assertEquals("canonical-body-$id", stored.body)
            }
            assertEquals(1, observedGenerations.take(4).toSet().size)
            assertNotEquals(observedGenerations.first(), observedGenerations.last())
            observe("all_five_canonical_and_acked")
            val completedPaths = gateway.paths()
            kotlinx.coroutines.delay(750L)
            assertEquals("Completed owner must remain idle instead of reopening head", completedPaths, gateway.paths())
            assertEquals((1..5).flatMap { listOf(PULL, ACK) }, gateway.paths())
            assertEquals(5, requestNumber.get())
            assertEquals(5, countOwner("messages"))
            assertEquals(0, countOwner("inbound_delivery_ack_outbox"))
            assertNull(actual.database.providerPullFrontierDao().get())
            gateway.assertHealthy()
            observe("idle_750ms_no_new_head")
            // Gate only the existing startup/readiness suspension point. A second
            // real turn commits four rows; close must cancel its queued continuation
            // before the gate is released, without losing its durable frontier.
            val pausedRoute = kotlinx.coroutines.CompletableDeferred<Unit>()
            routeReady.set(pausedRoute)
            val secondTurn = io.ethan.pushgo.notifications.ProviderIngressCoordinator.pullPersistAndDrainAcks(
                context = owned, channelRepository = actual.channelRepository,
                messageRepository = actual.messageRepository, entityRepository = actual.entityRepository,
                inboundDeliveryLedgerRepository = actual.inboundDeliveryLedgerRepository,
                settingsRepository = actual.settingsRepository, reason = "actual_owner_close",
            )
            assertEquals(4, secondTurn)
            assertEquals(9, requestNumber.get())
            assertEquals("cancel-next-4", actual.database.providerPullFrontierDao().get()?.pageToken)
            actual.close()
            pausedRoute.complete(Unit)
            kotlinx.coroutines.delay(750L) // finite no-request observation after releasing the closed owner's gate
            assertEquals("close must stop queued continuation without another HTTP request", 9, requestNumber.get())
            assertEquals(9, countOwner("messages"))
            assertEquals(9, countOwner("inbound_delivery_ledger", "ack_state='acked'"))
            assertEquals(0, countOwner("inbound_delivery_ack_outbox"))
            assertEquals("cancel-next-4", actual.database.providerPullFrontierDao().get()?.pageToken)
            observe("close_cancelled_queued_continuation_frontier_retained")
            success = true
        } catch (failure: Throwable) {
            originalFailure = failure
            observe("original_failure", org.json.JSONObject().put("type", failure.javaClass.name))
            throw failure
        } finally {
            // Release only this test's held response, then stop its actual owner before
            // closing only its isolated Room handle. Keep the first failure primary.
            releaseFifth.countDown()
            val cleanup = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    container?.close()
                    ownerJob.cancel()
                    ownerJob.join()
                    container?.messageImageStore?.close()
                    io.ethan.pushgo.data.ImageAssetMetadataStore.closeForTest(owned)
                    container?.database?.close()
                    // The shared AndroidKeyStore alias stays untouched. Only the exact
                    // synthetic secret preference namespace is cleared; all other
                    // prefixed preferences and the UUID DB family remain as evidence.
                    val syntheticSecrets = preferencePrefix + "pushgo_secure_secrets_v1"
                    check(syntheticSecrets.startsWith(preferencePrefix))
                    check(base.getSharedPreferences(syntheticSecrets, android.content.Context.MODE_PRIVATE)
                        .edit().clear().commit()) { "Owned synthetic secret cleanup failed" }
                    observe("owned_scope_cancelled_and_database_closed")
                }
            }
            val receipt = org.json.JSONObject().put("kind", "real-AppContainer-provider-pull-continuation-positive")
                .put("assertions_status", if (success && cleanup.isSuccess) "PASSED" else "FAILED")
                .put("native_execution_status", "Use the fresh instrumentation JUnit result; output delivery may still fail")
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put("isolated_root", ownerRoot.absolutePath)
                .put("isolated_database", owned.getDatabasePath("pushgo.db").absolutePath)
                .put("first_turn_count", firstTurnCount ?: org.json.JSONObject.NULL)
                .put("first_turn_elapsed_ms", firstTurnElapsedMs ?: org.json.JSONObject.NULL)
                .put("pull_requests", requestNumber.get()).put("observations", observations)
                .put("owned_pref_count", ownedPreferenceNames.size)
                .put("permission_granted_read_only", io.ethan.pushgo.util.LocalNetworkPermission.isGranted(owned))
                .put("limits", "API37 synthetic loopback and isolated real AppContainer; no permission mutation, OS notification publication, private native session, external Gateway, process death or physical-performance claim; 750ms finite idle observation")
            cleanup.exceptionOrNull()?.let { receipt.put("cleanup_failure_type", it.javaClass.name) }
            val outputResult = runCatching {
                val text = receipt.toString(2)
                java.io.File(ownerRoot, "actual-owner-result.json").writeText(text)
                androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?.let { java.io.File(it).apply { mkdirs() } }
                    ?.let { java.io.File(it, "actual-owner-$ownerId.json").writeText(text) }
            }
            val trailingFailure = cleanup.exceptionOrNull() ?: outputResult.exceptionOrNull()
            val primary = originalFailure
            if (primary != null) trailingFailure?.let { primary.addSuppressed(it) }
            else trailingFailure?.let { throw it }
        }
    }

    /** Explicit opt-in composition with the normal Gateway binary; no replacement service. */
    internal suspend fun verifyNormalGateway(contextData: JSONObject): JSONObject {
        check(contextData.getBoolean("synthetic_only") && contextData.getBoolean("normal_product"))
        val origin = contextData.getString("base_url")
        check(origin.startsWith("http://127.0.0.1:"))
        settings.setServerAddress(origin)
        settings.setDeviceKey(contextData.getString("device_key"))
        settings.setGatewayToken(contextData.getString("token"))
        settings.setGatewayAckToken(origin, contextData.getString("token"))
        settings.setUseFcmChannel(false)
        val submitted = JSONArray()
        val payloads = mutableListOf<JSONObject>()
        for ((index, size) in listOf(0 to 0, 1 to 49_152)) {
            val value = JSONObject().put("channel_id", contextData.getString("channel_id"))
                .put("password", contextData.getString("channel_password"))
                .put("op_id", java.util.UUID.randomUUID().toString()).put("title", "synthetic-android-$index")
                .put("body", "retained-body-$index").put("ttl", System.currentTimeMillis()+3_600_000L)
            if (size > 0) {
                value.put("images", JSONArray().put("https://synthetic-resource.invalid/image.png"))
                val current = value.toString().toByteArray(Charsets.UTF_8).size
                value.put("body", value.getString("body")+"x".repeat(size-current))
                assertEquals(size, value.toString().toByteArray(Charsets.UTF_8).size)
            }
            val accepted = actualGatewayPost(origin+"/message", contextData.getString("token"), value)
            assertTrue(accepted.getBoolean("success"))
            payloads += value
            submitted.put(JSONObject().put("op_id", value.getString("op_id"))
                .put("message_id", accepted.getJSONObject("data").getString("message_id"))
                .put("request_bytes", value.toString().toByteArray(Charsets.UTF_8).size))
        }
        // A genuine scheduling exception occurs after the repository commit. The real
        // V2 HTTP ACK must still settle; canonical pending bits retain optional recovery.
        assertEquals(2, pull { error("synthetic optional scheduling failure") })
        assertEquals(2, sqlCount("messages"))
        assertEquals(0, sqlCount("inbound_delivery_ack_outbox"))
        assertEquals(2, sqlCount("inbound_delivery_ledger", "ack_state='acked'"))
        val rows = db.messageDao().getByMessageIds((0 until submitted.length()).map { submitted.getJSONObject(it).getString("message_id") })
        val expectedBodies = payloads.indices.associate { index -> submitted.getJSONObject(index).getString("message_id") to payloads[index].getString("body") }
        for (row in rows) assertEquals(expectedBodies[row.messageId], row.body)
        assertTrue(rows.any { JSONObject(it.rawPayloadJson).optString("images").contains("synthetic-resource.invalid") })
        db.close(); open()
        assertEquals(2, sqlCount("messages"))
        assertTrue(ledger.loadPendingAcks().isEmpty())
        for (index in payloads.indices) {
            val replay = actualGatewayPost(origin+"/message", contextData.getString("token"), payloads[index])
            assertEquals(submitted.getJSONObject(index).getString("message_id"), replay.getJSONObject("data").getString("message_id"))
        }
        assertEquals(0, pull())
        assertEquals(2, sqlCount("messages"))
        return JSONObject().put("kind", "normal-Gateway-to-production-Android-ProviderIngress-Room-HTTP-ACK")
            .put("submitted", submitted).put("canonical_rows", 2).put("acked_ledger", 2).put("pending_ack", 0)
            .put("limits", "API37 Debug production modules under instrumentation, HTTP adb reverse loopback; not normal Release/TLS/Private wire or OS FCM proof; body release separately reconciled by Gateway owner")
    }

    private fun actualGatewayPost(endpoint: String, access: String, value: JSONObject): JSONObject {
        val connection = URL(endpoint).openConnection() as java.net.HttpURLConnection
        return try {
            connection.requestMethod = "POST"; connection.connectTimeout=5_000; connection.readTimeout=10_000
            connection.setRequestProperty("Authorization", "Bearer "+access)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput=true
            connection.outputStream.use { it.write(value.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val errorCode = if (code == 200) "" else runCatching {
                val error = JSONObject(connection.errorStream.bufferedReader().use { it.readText() })
                error.optString("error_code").ifBlank { error.optJSONObject("problem")?.optString("code").orEmpty() }
            }.getOrDefault("unparseable_error")
            assertEquals("normal Gateway accept must commit error_code=$errorCode", 200, code)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private suspend fun pull(beforeNotify: suspend () -> Unit = {}): Int =
        ProviderIngressCoordinator.pullAndPersist(context, channels, messages, entities, ledger, settings,
            beforeMessageNotify = { _, _ -> beforeNotify() })

    private suspend fun configure(gateway: Gateway) {
        settings.setServerAddress(gateway.baseUrl)
        settings.setUseFcmChannel(false)
        settings.setDeviceKey("device-a")
    }

    private fun open() {
        db = PushGoDatabase.buildForTest(context, DB_NAME)
        settings = SettingsRepository(db.appSettingsDao(), secrets,
            context.getSharedPreferences(DB_NAME, Context.MODE_PRIVATE))
        messages = MessageRepository(db, db.messageDao(), db.messageChannelStatsDao(), db.messageMetadataIndexDao(),
            db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.thingHeadDao(), db.thingSubMessageDao(), db.pendingThingMessageDao())
        entities = EntityRepository(db, db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.eventChangeLogDao(),
            db.thingChangeLogDao(), db.thingSubEventDao(), db.topLevelEventHeadDao(), db.thingHeadDao(), db.thingSubMessageDao())
        ledger = InboundDeliveryLedgerRepository(db, db.inboundDeliveryLedgerDao(), db.inboundDeliveryAckOutboxDao(), db.legacyProviderIngressDao())
        channels = ChannelSubscriptionRepository(ChannelSubscriptionStore(db.channelSubscriptionDao(), secrets), settings,
            MessageStateCoordinator(context, messages), messages, entities, db,
            object : PushTokenProvider { override suspend fun fetchToken(timeoutMs: Long): String? = null })
    }

    private fun rejectMessageWrites() = db.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER reject_provider_write BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'provider canonical store failure'); END")

    private fun sqlCount(table: String, condition: String = "1 = 1"): Int = db.openHelper.readableDatabase
        .query("SELECT count(*) FROM $table WHERE $condition").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun assertCanonical(id: String) {
        val content = db.openHelper.readableDatabase.query(
            "SELECT title, body FROM messages WHERE message_id = ?", arrayOf(id),
        ).use { cursor ->
            assertTrue("Canonical message $id missing", cursor.moveToFirst())
            listOf(cursor.getString(0), cursor.getString(1))
        }
        assertEquals(listOf("retained-$id", "canonical-body-$id"), content)
    }

    private fun ackState(id: String): String? = db.openHelper.readableDatabase
        .query("SELECT ack_state FROM inbound_delivery_ledger WHERE delivery_id = ?", arrayOf(id))
        .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun payload(id: String) = JSONObject().put("entity_type", "message").put("message_id", id)
        .put("title", "retained-$id").put("body", "canonical-body-$id").put("delivery_id", "payload-spoof")
        .put("ttl", "1") // Already expired alerts still persist and settle, without OS notification dependence.

    private fun page(id: String, hasMore: Boolean) = JSONObject().put("success", true).put("data", JSONObject()
        .put("items", JSONArray().put(JSONObject().put("delivery_id", id).put("payload", payload(id))))
        .put("has_more", hasMore))

    private fun wireItem(id: String, value: JSONObject = payload(id)) =
        JSONObject().put("delivery_id", id).put("payload", value)

    private fun progressPage(items: JSONArray, nextToken: String?, complete: Boolean) =
        JSONObject().put("success", true).put("data", JSONObject().put("items", items)
            .put("has_more", false).put("scan_complete", complete)
            .put("next_page_token", nextToken ?: JSONObject.NULL))

    private fun ackReceipt(count: Int = 1) = JSONObject().put("success", true).put("data", JSONObject()
        .put("requested_count", count).put("removed_count", count))

    private fun seedV27() {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("27.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            sqlite.beginTransaction()
            try {
                val tables = schema.getJSONArray("entities")
                for (i in 0 until tables.length()) {
                    val table = tables.getJSONObject(i)
                    val name = table.getString("tableName")
                    sqlite.execSQL(table.getString("createSql").replace("\${TABLE_NAME}", name))
                    table.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", name))
                    }
                    table.optJSONArray("contentSyncTriggers")?.let { triggers ->
                        for (j in 0 until triggers.length()) sqlite.execSQL(triggers.getString(j))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO legacy_provider_ingress(gateway_url,device_key,delivery_id,payload_json,enqueued_at) VALUES(?,?,?,?,?)",
                    arrayOf<Any>("https://old-gateway.example", "old-device", "old-accepted", payload("old-accepted").toString(), 1L))
                sqlite.execSQL("PRAGMA user_version = 27")
                sqlite.setTransactionSuccessful()
            } finally { sqlite.endTransaction() }
        }
    }

    private fun gateway(handler: (String, JSONObject) -> Pair<Int, JSONObject>): Gateway = Gateway(handler).also { servers += it }

    internal class Gateway(val handler: (String, JSONObject) -> Pair<Int, JSONObject>) : AutoCloseable {
        private val socket = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        private val paths = Collections.synchronizedList(mutableListOf<String>())
        private val requestSummaries = Collections.synchronizedList(mutableListOf<String>())
        private val authorizations = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(name = "provider-v2-native-gateway", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { connection ->
                        connection.soTimeout = 5_000
                        val reader = connection.getInputStream().bufferedReader()
                        val request = reader.readLine().split(' ')
                        val method = request[0]
                        val path = request[1]
                        var length = 0
                        var authorization: String? = null
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                            if (header.startsWith("Authorization:", true)) authorization = header.substringAfter(':').trim()
                        }
                        val content = CharArray(length)
                        var read = 0
                        while (read < length) { val n = reader.read(content, read, length - read); check(n >= 0); read += n }
                        paths += path
                        requestSummaries += "$method ${runCatching { java.net.URI(path).path }.getOrNull() ?: "<invalid-path>"}"
                        authorizations += path to authorization
                        val response = try { handler(path, if (content.isEmpty()) JSONObject() else JSONObject(String(content))) } catch (error: Throwable) {
                            failure.compareAndSet(null, error)
                            500 to JSONObject().put("success", false).put("error", "test request oracle failed")
                        }
                        val bytes = response.second.toString().toByteArray()
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 ${response.first} Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (error: Throwable) { if (!socket.isClosed && error !is SocketException) failure.compareAndSet(null, error) }
            }
        }
        fun paths(): List<String> = synchronized(paths) { paths.toList() }
        fun requestSummaries(): List<String> = synchronized(requestSummaries) { requestSummaries.toList() }
        fun authorizations(path: String): List<String?> = synchronized(authorizations) {
            authorizations.filter { it.first == path }.map { it.second }
        }
        fun assertHealthy() { failure.get()?.let { throw AssertionError("Gateway request oracle failed; requests=${requestSummaries()}", it) } }
        override fun close() { socket.close(); worker.join(1_000); assertHealthy() }
    }

    private class MemorySecrets : SecureSecretStore {
        private val values = mutableMapOf<String, String?>()
        private var key: ByteArray? = null
        override fun gatewayToken() = values["gateway"]
        override fun setGatewayToken(token: String?) { values["gateway"] = token }
        override fun gatewayAckToken(gatewayUrl: String) = values["ack:$gatewayUrl"]
        override fun setGatewayAckToken(gatewayUrl: String, token: String?) { values["ack:$gatewayUrl"] = token }
        override fun fcmToken() = values["fcm"]
        override fun setFcmToken(token: String?) { values["fcm"] = token }
        override fun gatewayTransitionJournal() = values["journal"]
        override fun setGatewayTransitionJournal(journal: String?) { values["journal"] = journal }
        override fun deviceKey() = values["device"]
        override fun setDeviceKey(deviceKey: String?) { values["device"] = deviceKey }
        override fun notificationKeyBytes() = key
        override fun setNotificationKeyBytes(value: ByteArray?) { key = value }
        override fun channelPassword(gatewayUrl: String, channelId: String) = values["channel:$gatewayUrl:$channelId"]
        override fun setChannelPassword(gatewayUrl: String, channelId: String, password: String?) { values["channel:$gatewayUrl:$channelId"] = password }
        override fun removeChannelPassword(gatewayUrl: String, channelId: String) { values.remove("channel:$gatewayUrl:$channelId") }
        override fun clearAll() { values.clear(); key = null }
    }

    companion object {
        private const val DB_NAME = "provider_v2_native.db"
        private const val PULL = "/v2/messages/pull"
        private const val ACK = "/v2/messages/ack"
    }
}
