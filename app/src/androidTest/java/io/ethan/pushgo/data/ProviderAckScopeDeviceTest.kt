package io.ethan.pushgo.data

import android.content.ContentValues
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.PushGoDatabase
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderAckScopeDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: PushGoDatabase
    private lateinit var entityRepository: EntityRepository
    private lateinit var ledgerRepository: InboundDeliveryLedgerRepository

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        database = PushGoDatabase.buildForTest(context, DATABASE_NAME)
        entityRepository = EntityRepository(
            database = database,
            inboundDeliveryLedgerDao = database.inboundDeliveryLedgerDao(),
            operationLedgerDao = database.operationLedgerDao(),
            eventChangeLogDao = database.eventChangeLogDao(),
            thingChangeLogDao = database.thingChangeLogDao(),
            thingSubEventDao = database.thingSubEventDao(),
            topLevelEventHeadDao = database.topLevelEventHeadDao(),
            thingHeadDao = database.thingHeadDao(),
            thingSubMessageDao = database.thingSubMessageDao(),
            pendingThingEventDao = database.pendingThingEventDao(),
        )
        ledgerRepository = InboundDeliveryLedgerRepository(
            database = database,
            inboundDeliveryLedgerDao = database.inboundDeliveryLedgerDao(),
            inboundDeliveryAckOutboxDao = database.inboundDeliveryAckOutboxDao(),
            legacyProviderIngressDao = database.legacyProviderIngressDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun sameDeliveryIdFromTwoGatewaysPersistsAndAcksInIndependentScopes() = runBlocking {
        val identityA = identity("https://gateway-a.example", "device-a")
        val identityB = identity("https://gateway-b.example", "device-b")
        val deliveryId = "shared-delivery"

        assertTrue(entityRepository.insertIncoming(event(deliveryId, "event-a"), identityA))
        assertTrue(entityRepository.insertIncoming(event(deliveryId, "event-b"), identityB))
        ledgerRepository.enqueueAcks(listOf(deliveryId), identityA)
        ledgerRepository.enqueueAcks(listOf(deliveryId), identityB)

        val sqlite = database.openHelper.writableDatabase
        val eventRows = sqlite.query(
            "SELECT id FROM event_change_logs WHERE delivery_id = ? ORDER BY id",
            arrayOf(deliveryId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val ledgerScopes = sqlite.query(
            """
            SELECT gateway_url, device_key, ack_state
            FROM inbound_delivery_ledger
            WHERE delivery_id = ?
            ORDER BY gateway_url
            """.trimIndent(),
            arrayOf(deliveryId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
        val pending = ledgerRepository.loadPendingAcks()

        assertEquals(2, eventRows.size)
        assertNotEquals(eventRows[0], eventRows[1])
        assertEquals(
            listOf(
                Triple("https://gateway-a.example", "device-a", "pending"),
                Triple("https://gateway-b.example", "device-b", "pending"),
            ),
            ledgerScopes,
        )
        assertEquals(2, pending.size)

        val freshRecordA = pending.single { it.gatewayUrl == identityA.gatewayUrl }
        assertEquals(0, freshRecordA.attemptCount)
        assertEquals(false, freshRecordA.lastAttemptUncertain)
        val attemptedRecordA = ledgerRepository.beginAckAttempt(listOf(freshRecordA)).single()
        assertEquals(false, attemptedRecordA.lastAttemptUncertain)
        val crashRecoveryRecordA = ledgerRepository.loadPendingAcks()
            .single { it.gatewayUrl == identityA.gatewayUrl }
        assertEquals(true, crashRecoveryRecordA.lastAttemptUncertain)
        ledgerRepository.deferFailedAcks(
            listOf(attemptedRecordA),
            lastAttemptUncertain = true,
        )
        val retriedRecordA = ledgerRepository.loadPendingAcks()
            .single { it.gatewayUrl == identityA.gatewayUrl }
        assertEquals(1, retriedRecordA.attemptCount)

        // A duplicate enqueue must refresh the marker without erasing proof that an
        // earlier legacy ACK attempt may have succeeded before its response was lost.
        ledgerRepository.enqueueAcks(listOf(deliveryId), identityA)
        val refreshedRecordA = ledgerRepository.loadPendingAcks()
            .single { it.gatewayUrl == identityA.gatewayUrl }
        assertEquals(1, refreshedRecordA.attemptCount)
        ledgerRepository.markAckRecordsAcked(listOf(refreshedRecordA))

        assertEquals(false, ledgerRepository.shouldAck(deliveryId, identityA))
        assertEquals(true, ledgerRepository.shouldAck(deliveryId, identityB))
        assertEquals(listOf(identityB.gatewayUrl), ledgerRepository.loadPendingAcks().map { it.gatewayUrl })
    }

    @Test
    fun definitiveLegacyFailureClearsThePreSendCrashMarker() = runBlocking {
        val identity = identity(GATEWAY_URL, DEVICE_KEY)
        ledgerRepository.enqueueAcks(listOf("legacy-definitive-false"), identity)
        val fresh = ledgerRepository.loadPendingAcks().single()

        val claimed = ledgerRepository.beginAckAttempt(listOf(fresh)).single()
        assertEquals(false, claimed.lastAttemptUncertain)
        assertEquals(true, ledgerRepository.loadPendingAcks().single().lastAttemptUncertain)

        ledgerRepository.deferFailedAcks(
            listOf(claimed),
            lastAttemptUncertain = false,
        )
        assertEquals(false, ledgerRepository.loadPendingAcks().single().lastAttemptUncertain)
    }

    @Test
    fun legacyDestructivePullIsDurableBeforeCanonicalPersistence() = runBlocking {
        val destination = ProviderAckDestination("https://gateway-a.example", "device-a")
        ledgerRepository.stageLegacyPull(
            destination = destination,
            items = listOf(
                PullItem(
                    deliveryId = "legacy-delivery",
                    payload = mapOf("title" to "durable", "body" to "payload"),
                )
            ),
        )

        database.close()
        database = PushGoDatabase.buildForTest(context, DATABASE_NAME)
        ledgerRepository = InboundDeliveryLedgerRepository(
            database = database,
            inboundDeliveryLedgerDao = database.inboundDeliveryLedgerDao(),
            inboundDeliveryAckOutboxDao = database.inboundDeliveryAckOutboxDao(),
            legacyProviderIngressDao = database.legacyProviderIngressDao(),
        )

        val recovered = ledgerRepository.loadPendingLegacyPull()
        assertEquals(1, recovered.size)
        assertEquals("legacy-delivery", recovered.single().deliveryId)
        assertTrue(recovered.single().payloadJson.contains("durable"))

        ledgerRepository.deleteLegacyPull(recovered.single())
        assertTrue(ledgerRepository.loadPendingLegacyPull().isEmpty())
    }

    @Test
    fun completedLegacyPullAtomicallyRemovesStagingAndMarksLedgerTerminal() = runBlocking {
        val destination = ProviderAckDestination("https://gateway-a.example", "device-a")
        val identity = ProviderAckIdentity.create(
            destination = destination,
            contract = ProviderAckContract.LEGACY_SINGLE,
            source = "legacy_provider_staging",
        )!!
        val deliveryId = "legacy-complete"
        ledgerRepository.stageLegacyPull(
            destination = destination,
            items = listOf(
                PullItem(
                    deliveryId = deliveryId,
                    payload = mapOf("title" to "durable", "body" to "payload"),
                )
            ),
        )
        assertTrue(entityRepository.insertIncoming(event(deliveryId, "legacy-event"), identity))
        assertTrue(ledgerRepository.shouldAck(deliveryId, identity))

        ledgerRepository.completeLegacyPull(ledgerRepository.loadPendingLegacyPull().single())

        assertTrue(ledgerRepository.loadPendingLegacyPull().isEmpty())
        assertEquals(false, ledgerRepository.shouldAck(deliveryId, identity))
    }

    @Test
    fun ackedTombstonePrune_outlivesGatewayReplayWindowAndRemainsBatchedAndReferenceSafe() = runBlocking {
        val now = TimeUnit.DAYS.toMillis(60)
        val old = now - TimeUnit.DAYS.toMillis(37)
        val retentionBoundary = now - TimeUnit.DAYS.toMillis(36)
        val insideGatewayWindow = now - TimeUnit.DAYS.toMillis(35)

        insertLedgerRow("eligible-oldest", "acked", old - 2, old)
        insertLedgerRow("eligible-old", "acked", old - 1, old + 1)
        insertLedgerRow("eligible-boundary", "acked", retentionBoundary, retentionBoundary)
        insertLedgerRow("inside-window", "acked", insideGatewayWindow, insideGatewayWindow)
        insertLedgerRow("pending-old", "pending", old, null)
        insertLedgerRow("outbox-reference", "acked", old, old)
        insertLedgerRow("staging-reference", "acked", old, old)

        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO inbound_delivery_ack_outbox(
                delivery_id, gateway_url, device_key, ack_contract, source,
                enqueued_at, updated_at, attempt_count
            ) VALUES(?, ?, ?, 'v2_batch', 'test', ?, ?, 0)
            """.trimIndent(),
            arrayOf<Any>("outbox-reference", GATEWAY_URL, DEVICE_KEY, old, old),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO legacy_provider_ingress(
                gateway_url, device_key, delivery_id, payload_json, enqueued_at
            ) VALUES(?, ?, ?, '{}', ?)
            """.trimIndent(),
            arrayOf<Any>(GATEWAY_URL, DEVICE_KEY, "staging-reference", old),
        )

        assertEquals(2, ledgerRepository.pruneAckedTombstones(nowEpochMs = now, limit = 2))
        assertEquals(1, countLedgerRows("eligible-%"))
        assertEquals(1, ledgerRepository.pruneAckedTombstones(nowEpochMs = now, limit = 2))
        assertEquals(0, countLedgerRows("eligible-%"))
        assertEquals(1, countLedgerRows("inside-window"))
        assertEquals(1, countLedgerRows("pending-old"))
        assertEquals(1, countLedgerRows("outbox-reference"))
        assertEquals(1, countLedgerRows("staging-reference"))
    }

    @Test
    fun ackDrainKickSignal_onlyFiresOnEmptyToNonEmptyTransition() = runBlocking {
        val identity = identity(GATEWAY_URL, DEVICE_KEY)

        assertTrue(ledgerRepository.enqueueAcks(listOf("delivery-first"), identity))
        repeat(100) { index ->
            assertEquals(
                false,
                ledgerRepository.enqueueAcks(listOf("delivery-burst-$index"), identity),
            )
        }

        val pending = ledgerRepository.loadPendingAcks(limit = 200)
        ledgerRepository.markAckRecordsAcked(pending)
        assertTrue(ledgerRepository.loadPendingAcks().isEmpty())

        // Models the producer racing immediately after a worker's final empty read:
        // the committed empty -> nonempty edge must schedule a replacement worker.
        assertTrue(ledgerRepository.enqueueAcks(listOf("delivery-after-empty-read"), identity))
        assertEquals(
            false,
            ledgerRepository.enqueueAcks(listOf("delivery-after-edge"), identity),
        )
    }

    @Test
    fun fairAckLoad_reservesCapacityForASecondGatewayBehindLargeBacklog() = runBlocking {
        val identityA = identity("https://gateway-a.example", "device-a")
        val identityB = identity("https://gateway-b.example", "device-b")
        ledgerRepository.enqueueAcks(
            deliveryIds = (0 until 250).map { index -> "gateway-a-$index" },
            identity = identityA,
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE inbound_delivery_ack_outbox SET enqueued_at = 1, updated_at = 1 " +
                "WHERE gateway_url = ?",
            arrayOf(identityA.gatewayUrl),
        )
        ledgerRepository.enqueueAcks(listOf("gateway-b-current"), identityB)

        val selected = ledgerRepository.loadFairPendingAcks(limit = 201)

        assertEquals(201, selected.size)
        assertTrue(selected.any { it.gatewayUrl == identityB.gatewayUrl })
        assertEquals(200, selected.count { it.gatewayUrl == identityA.gatewayUrl })
    }

    private fun insertLedgerRow(
        deliveryId: String,
        ackState: String,
        appliedAt: Long,
        ackedAt: Long?,
    ) {
        database.openHelper.writableDatabase.insert(
            "inbound_delivery_ledger",
            android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,
            ContentValues().apply {
                put("gateway_url", GATEWAY_URL)
                put("device_key", DEVICE_KEY)
                put("delivery_id", deliveryId)
                put("channel_id", "channel-test")
                put("entity_type", "message")
                put("entity_id", deliveryId)
                putNull("op_id")
                put("applied_at", appliedAt)
                put("ack_state", ackState)
                if (ackedAt == null) putNull("acked_at") else put("acked_at", ackedAt)
            },
        )
    }

    private fun countLedgerRows(deliveryIdPattern: String): Int {
        return database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM inbound_delivery_ledger WHERE delivery_id LIKE ?",
            arrayOf(deliveryIdPattern),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun identity(baseUrl: String, deviceKey: String): ProviderAckIdentity =
        requireNotNull(
            ProviderAckIdentity.create(
                destination = ProviderAckDestination(baseUrl, deviceKey),
                contract = ProviderAckContract.LEGACY_SINGLE,
                source = "provider_direct",
            )
        )

    private fun event(deliveryId: String, eventId: String) = IncomingEntityRecord(
        entityType = "event",
        entityId = eventId,
        channel = "alpha",
        title = eventId,
        body = "body-$eventId",
        rawPayloadJson = "{}",
        receivedAt = Instant.ofEpochMilli(1_710_000_000_000L),
        opId = "shared-operation",
        deliveryId = deliveryId,
        serverId = null,
        eventId = eventId,
        thingId = null,
        eventState = "open",
        eventTimeEpoch = 1_710_000_000_000L,
        observedTimeEpoch = null,
    )

    private companion object {
        const val DATABASE_NAME = "provider-ack-scope-test.db"
        const val GATEWAY_URL = "https://gateway.example"
        const val DEVICE_KEY = "device-key"
    }
}
