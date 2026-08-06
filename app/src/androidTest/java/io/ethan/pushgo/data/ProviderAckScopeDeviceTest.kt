package io.ethan.pushgo.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.PushGoDatabase
import java.time.Instant
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
        ledgerRepository.deferFailedAcks(listOf(freshRecordA))
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
    }
}
