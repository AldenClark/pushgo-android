package io.ethan.pushgo.data.db

import android.Manifest
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.MainActivity
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.model.KeyEncoding
import io.ethan.pushgo.testing.QualityRuntime
import io.ethan.pushgo.testing.QualityUiFailureDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushGoDatabaseMigrationDeviceTest {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scenario: ActivityScenario<MainActivity>? = null

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        (context.applicationContext as PushGoApp).releaseStorageForInstrumentationTest()
        cleanupDatabaseFamily("pushgo.db")
        cleanupDatabaseFamily("pushgo-v21.db")
        cleanupDatabaseFamily("pushgo-v22.db")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        (context.applicationContext as PushGoApp).releaseStorageForInstrumentationTest()
        cleanupDatabaseFamily("pushgo.db")
        cleanupDatabaseFamily("pushgo-v21.db")
        cleanupDatabaseFamily("pushgo-v22.db")
    }

    @Test
    fun appContainer_bootstrapsFromLegacyV21AndPreservesBusinessData() = runBlocking {
        seedLegacyV21Database()
        QualityUiFailureDiagnostics.logText(
            "legacy V21 bootstrap boundary",
            "qualityDatabase=${QualityRuntime.currentSession()?.databaseName}, " +
                "targetExists=${context.getDatabasePath("pushgo.db").exists()}, " +
                "legacyBytes=${context.getDatabasePath("pushgo-v21.db").length()}, " +
                "legacyWalBytes=${context.getDatabasePath("pushgo-v21.db-wal").length()}",
        )

        val container = AppContainer(context, appScope)
        val subscriptions = container.channelStore.loadSubscriptions(GATEWAY_URL)
        val messages = container.messageRepository.loadAllForExport()

        val restoredGateway = container.settingsRepository.getServerAddress()
        QualityUiFailureDiagnostics.logText(
            "legacy V21 bootstrap result",
            "database=${container.database.openHelper.databaseName}, gateway=$restoredGateway, " +
                "subscriptions=${subscriptions.size}, messages=${messages.size}",
        )
        assertEquals(GATEWAY_URL, restoredGateway)
        assertEquals(true, container.settingsRepository.getUpdateAutoCheckEnabled())
        assertEquals(false, container.settingsRepository.getUpdateBetaChannelEnabled())
        assertEquals(1, subscriptions.size)
        assertEquals(CHANNEL_ID, subscriptions.single().channelId)
        assertEquals(1, messages.size)
        assertEquals(MESSAGE_ID, messages.single().messageId)
        assertEquals(31, readUserVersion(context.getDatabasePath("pushgo.db")))
        assertEquals(1, container.messageRepository.totalCount())
        assertEquals(1, container.messageRepository.unreadCount())
        assertTrue(context.getDatabasePath("pushgo.db").exists())
        assertTrue(context.getDatabasePath("pushgo-v21.db").exists())

        container.database.close()

        scenario = ActivityScenario.launch(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithText("Legacy title").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Legacy title").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("field.message.detail.body")
            .assertIsDisplayed()
            .assertTextContains("Legacy body")
        Unit
    }

    @Test
    fun appContainer_prefersDataRichV21WhenEmptyV22Exists() = runBlocking {
        seedLegacyV21Database()
        seedEmptyLegacyV22Database()

        val container = AppContainer(context, appScope)
        val messages = container.messageRepository.loadAllForExport()

        assertEquals(1, messages.size)
        assertEquals(MESSAGE_ID, messages.single().messageId)
        assertEquals(GATEWAY_URL, container.settingsRepository.getServerAddress())

        container.database.close()
    }

    @Test
    fun appContainer_migratesCurrentV23InPlaceAndBuildsPerformanceState() = runBlocking {
        seedLegacyV23Database()

        val container = AppContainer(context, appScope)
        val messages = container.messageRepository.loadAllForExport()

        assertEquals(1, messages.size)
        assertEquals(MESSAGE_ID, messages.single().messageId)
        assertEquals(1, container.messageRepository.totalCount())
        assertEquals(1, container.messageRepository.unreadCount())
        assertEquals(31, readUserVersion(context.getDatabasePath("pushgo.db")))
        val sqlite = container.database.openHelper.writableDatabase
        val revision = sqlite.query(
            "SELECT revision FROM message_store_revision WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
        assertEquals(0L, revision)
        val summaryState = sqlite.query(
            "SELECT status FROM message_derived_state WHERE component = 'message_summary_projection'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals("stale", summaryState)
        sqlite.execSQL(
            """
            INSERT INTO messages(
                id, message_id, title, body, channel, url, is_read, received_at, raw_payload_json,
                status, decryption_state, notification_id, server_id, body_preview, entity_type,
                entity_id, event_id, thing_id, event_state, event_time_epoch, occurred_at_epoch,
                list_payload_json
            ) VALUES('trigger-probe', 'trigger-probe', 'Probe', 'Probe', 'beta', NULL, 0,
                1710000300000, '{}', 'NORMAL', NULL, NULL, NULL, 'Probe', '', NULL, NULL,
                NULL, NULL, NULL, NULL, '{}')
            """.trimIndent()
        )
        sqlite.execSQL("UPDATE messages SET is_read = 1 WHERE id = 'msg-local-v23'")
        sqlite.execSQL("DELETE FROM messages WHERE id = 'trigger-probe'")
        val triggerFacts = sqlite.query(
            "SELECT total_count, unread_count FROM message_global_stats WHERE id = 1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0) to cursor.getInt(1)
        }
        assertEquals(1 to 0, triggerFacts)
        val updatedRevision = sqlite.query(
            "SELECT revision FROM message_store_revision WHERE id = 1"
        ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
        assertEquals(3L, updatedRevision)

        container.database.close()
    }

    @Test
    fun appContainer_migratesV24AckOutboxWithoutGuessingGatewayOwnership() = runBlocking {
        val legacy = Room.databaseBuilder(
            context,
            LegacyPushGoV24Database::class.java,
            "pushgo.db",
        ).build()
        val sqlite = legacy.openHelper.writableDatabase
        sqlite.execSQL(
            """
            INSERT INTO inbound_delivery_ledger(
                delivery_id, channel_id, entity_type, entity_id, op_id,
                applied_at, ack_state, acked_at
            ) VALUES('legacy-ack-1', NULL, 'message', 'message-1', NULL,
                1710000000000, 'pending', NULL)
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO inbound_delivery_ack_outbox(
                delivery_id, source, enqueued_at, updated_at
            ) VALUES('legacy-ack-1', 'provider_pull', 1710000000000, 1710000000000)
            """.trimIndent()
        )
        legacy.close()

        val database = PushGoDatabase.build(context)
        val migrated = database.openHelper.writableDatabase
        val pendingOutbox = migrated.query(
            "SELECT COUNT(*) FROM inbound_delivery_ack_outbox"
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        val retainedLedger = migrated.query(
            "SELECT COUNT(*) FROM inbound_delivery_ledger WHERE delivery_id = 'legacy-ack-1'"
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        val columns = migrated.query("PRAGMA table_info(inbound_delivery_ack_outbox)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        }

        val retainedLedgerScope = migrated.query(
            "SELECT gateway_url, device_key FROM inbound_delivery_ledger " +
                "WHERE delivery_id = 'legacy-ack-1'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0) to cursor.getString(1)
        }

        assertEquals(31, readUserVersion(context.getDatabasePath("pushgo.db")))
        assertEquals(0, pendingOutbox)
        assertEquals(1, retainedLedger)
        assertEquals("" to "", retainedLedgerScope)
        assertTrue(
            columns.containsAll(
                setOf(
                    "gateway_url",
                    "device_key",
                    "ack_contract",
                    "attempt_count",
                    "last_attempt_uncertain",
                )
            )
        )
        database.close()
    }

    @Test
    fun appContainer_migratesV25LedgerAndAckRetryStateWithoutCrossGatewayGuessing() = runBlocking {
        val legacy = Room.databaseBuilder(
            context,
            LegacyPushGoV25Database::class.java,
            "pushgo.db",
        ).build()
        val sqlite = legacy.openHelper.writableDatabase
        sqlite.execSQL(
            """
            INSERT INTO inbound_delivery_ledger(
                delivery_id, channel_id, entity_type, entity_id, op_id,
                applied_at, ack_state, acked_at
            ) VALUES('legacy-v25-delivery', 'alpha', 'event', 'event-1', 'op-1',
                1710000000000, 'pending', NULL)
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO inbound_delivery_ack_outbox(
                delivery_id, gateway_url, device_key, ack_contract, source,
                enqueued_at, updated_at
            ) VALUES('scoped-v25-delivery', 'https://gateway-a.example', 'device-a',
                'legacy_single', 'provider_direct', 1710000000000, 1710000000001)
            """.trimIndent()
        )
        legacy.close()

        val database = PushGoDatabase.build(context)
        val migrated = database.openHelper.writableDatabase
        val legacyLedger = migrated.query(
            """
            SELECT gateway_url, device_key, ack_state
            FROM inbound_delivery_ledger
            WHERE delivery_id = 'legacy-v25-delivery'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
        }
        val scopedOutbox = migrated.query(
            """
            SELECT gateway_url, device_key, ack_contract, attempt_count
            FROM inbound_delivery_ack_outbox
            WHERE delivery_id = 'scoped-v25-delivery'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3))
        }
        val pendingDeliveryIndexUnique = migrated.query(
            "PRAGMA index_list(pending_thing_events)"
        ).use { cursor ->
            var unique: Int? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) ==
                    "index_pending_thing_events_delivery_id"
                ) {
                    unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                }
            }
            unique
        }
        val ackTombstoneIndexExists = migrated.query(
            "PRAGMA index_list(inbound_delivery_ledger)"
        ).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) ==
                    "index_inbound_delivery_ledger_ack_state_acked_at"
                ) {
                    found = true
                }
            }
            found
        }

        assertEquals(31, readUserVersion(context.getDatabasePath("pushgo.db")))
        assertEquals(Triple("", "", "pending"), legacyLedger)
        assertEquals(
            listOf("https://gateway-a.example", "device-a", "legacy_single", 0),
            scopedOutbox,
        )
        assertEquals(0, pendingDeliveryIndexUnique)
        assertTrue(ackTombstoneIndexExists)
        database.close()
    }

    @Test
    fun productionDatabase_migratesV27LegacyIngressWithoutLosingQueuedPayloadAndReopens() {
        seedDatabaseFromExportedSchema(27)
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("pushgo.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL(
                """
                INSERT INTO legacy_provider_ingress(
                    gateway_url, device_key, delivery_id, payload_json, enqueued_at
                ) VALUES(?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    LEGACY_INGRESS_GATEWAY,
                    LEGACY_INGRESS_DEVICE,
                    LEGACY_INGRESS_DELIVERY,
                    LEGACY_INGRESS_PAYLOAD,
                    LEGACY_INGRESS_ENQUEUED_AT,
                ),
            )
        }

        val migrated = PushGoDatabase.build(context)
        try {
            assertLegacyIngressBusinessState(migrated)
            assertEquals(31, readUserVersion(context.getDatabasePath("pushgo.db")))
            runBlocking {
                migrated.transportTransitionDao().insert(
                    TransportTransitionEntity(
                    operationId = "migration-transition-operation",
                    gatewayUrl = GATEWAY_URL,
                    deviceKey = "migration-transition-device",
                    targetChannelType = "private",
                    transitionId = null,
                    baseRevision = 7,
                    committedRevision = null,
                    phase = "LOCAL_INTENT",
                    candidateTokenFingerprint = null,
                    createdAt = 1_710_000_400_000L,
                    updatedAt = 1_710_000_400_000L,
                    lastError = null,
                    )
                )
            }
        } finally {
            migrated.close()
        }
        val reopened = PushGoDatabase.build(context)
        try {
            assertLegacyIngressBusinessState(reopened)
            val transition = runBlocking { reopened.transportTransitionDao().getPending() }
            assertEquals("migration-transition-operation", transition?.operationId)
            assertEquals("private", transition?.targetChannelType)
            assertEquals(7L, transition?.baseRevision)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun productionDatabase_migratesV30ToV31AndPersistsTransportTransitionAcrossReopen() {
        seedDatabaseFromExportedSchema(30)

        val migrated = PushGoDatabase.build(context)
        try {
            migrated.openHelper.writableDatabase
            assertEquals(31, readUserVersion(context.getDatabasePath("pushgo.db")))
            runBlocking {
                migrated.transportTransitionDao().insert(
                    TransportTransitionEntity(
                        operationId = "v30-transition-operation",
                        gatewayUrl = GATEWAY_URL,
                        deviceKey = "v30-transition-device",
                        targetChannelType = "private",
                        transitionId = "gateway-transition-id",
                        baseRevision = 11,
                        committedRevision = 12,
                        phase = "REMOTE_COMMITTED",
                        candidateTokenFingerprint = "candidate-token-fingerprint",
                        createdAt = 1_710_000_500_000L,
                        updatedAt = 1_710_000_500_100L,
                        lastError = null,
                    )
                )
            }
        } finally {
            migrated.close()
        }

        val reopened = PushGoDatabase.build(context)
        try {
            val transition = runBlocking { reopened.transportTransitionDao().getPending() }
            assertEquals("v30-transition-operation", transition?.operationId)
            assertEquals("gateway-transition-id", transition?.transitionId)
            assertEquals("REMOTE_COMMITTED", transition?.phase)
            assertEquals(12L, transition?.committedRevision)
            assertEquals("candidate-token-fingerprint", transition?.candidateTokenFingerprint)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun productionDatabase_migratesV28PendingDeletionWithoutLosingIntentAndReopens() {
        seedDatabaseFromExportedSchema(28)
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("pushgo.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL(
                """
                INSERT INTO pending_local_deletions(
                    id, summary, operation_kind, target_ids_json,
                    expected_gateway_url, expected_updated_at, expected_use_provider,
                    requested_at_epoch_ms, undo_deadline_epoch_ms, state, attempt_count,
                    next_attempt_at_epoch_ms, updated_at_epoch_ms, last_error
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    PENDING_DELETION_ID,
                    PENDING_DELETION_SUMMARY,
                    PENDING_DELETION_KIND,
                    PENDING_DELETION_TARGETS,
                    PENDING_DELETION_GATEWAY,
                    PENDING_DELETION_EXPECTED_UPDATED_AT,
                    1,
                    PENDING_DELETION_REQUESTED_AT,
                    PENDING_DELETION_UNDO_DEADLINE,
                    PENDING_DELETION_STATE,
                    PENDING_DELETION_ATTEMPT_COUNT,
                    PENDING_DELETION_NEXT_ATTEMPT_AT,
                    PENDING_DELETION_UPDATED_AT,
                    PENDING_DELETION_LAST_ERROR,
                ),
            )
        }

        val migrated = PushGoDatabase.build(context)
        try {
            assertPendingDeletionBusinessState(migrated)
            assertEquals(31, readUserVersion(context.getDatabasePath("pushgo.db")))
        } finally {
            migrated.close()
        }
        val reopened = PushGoDatabase.build(context)
        try {
            assertPendingDeletionBusinessState(reopened)
        } finally {
            reopened.close()
        }
    }

    private fun seedLegacyV21Database() {
        val db = Room.databaseBuilder(context, LegacyPushGoV21Database::class.java, "pushgo-v21.db")
            .build()
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL(
            """
            INSERT INTO app_settings(
                id, server_address, token, notification_key_updated_at, key_encoding, fcm_token,
                use_fcm_channel, is_message_page_enabled, is_event_page_enabled, is_thing_page_enabled
            ) VALUES(1, ?, NULL, NULL, ?, NULL, 1, 1, 1, 1)
            """.trimIndent(),
            arrayOf<Any>(GATEWAY_URL, KeyEncoding.BASE64.name),
        )
        sqlite.execSQL(
            """
            INSERT INTO channel_subscriptions(
                gateway_url, channel_id, display_name, updated_at, last_synced_at, is_deleted, deleted_at
            ) VALUES(?, ?, ?, ?, ?, 0, NULL)
            """.trimIndent(),
            arrayOf<Any>(GATEWAY_URL, CHANNEL_ID, "Alpha", 1_710_000_000_000L, 1_710_000_100_000L),
        )
        sqlite.execSQL(
            """
            INSERT INTO messages(
                id, message_id, title, body, channel, url, is_read, received_at, raw_payload_json,
                status, decryption_state, notification_id, server_id, body_preview, entity_type,
                entity_id, event_id, thing_id, event_state, event_time_epoch, occurred_at_epoch
            ) VALUES(?, ?, ?, ?, ?, NULL, 0, ?, ?, ?, NULL, NULL, NULL, ?, '', NULL, NULL, NULL, NULL, NULL, NULL)
            """.trimIndent(),
            arrayOf<Any>(
                "msg-local-1",
                MESSAGE_ID,
                "Legacy title",
                "Legacy body",
                CHANNEL_ID,
                1_710_000_200_000L,
                """{"entity_type":"message"}""",
                "NORMAL",
                "Legacy body",
            ),
        )
        db.close()
    }

    private fun seedEmptyLegacyV22Database() {
        val file = context.getDatabasePath("pushgo-v22.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA user_version = 22")
        }
    }

    private fun seedLegacyV23Database() {
        val db = Room.databaseBuilder(context, LegacyPushGoV23Database::class.java, "pushgo.db")
            .build()
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL(
            """
            INSERT INTO messages(
                id, message_id, title, body, channel, url, is_read, received_at, raw_payload_json,
                status, decryption_state, notification_id, server_id, body_preview, entity_type,
                entity_id, event_id, thing_id, event_state, event_time_epoch, occurred_at_epoch
            ) VALUES(?, ?, ?, ?, ?, NULL, 0, ?, ?, ?, NULL, NULL, NULL, ?, '', NULL, NULL, NULL, NULL, NULL, NULL)
            """.trimIndent(),
            arrayOf<Any>(
                "msg-local-v23",
                MESSAGE_ID,
                "Current title",
                "Current body",
                CHANNEL_ID,
                1_710_000_200_000L,
                """{"entity_type":"message","tags":["upgrade"]}""",
                "NORMAL",
                "Current body",
            ),
        )
        sqlite.execSQL(
            """
            INSERT INTO message_channel_counts(channel, total_count, unread_count, latest_received_at)
            VALUES(?, 1, 1, ?)
            """.trimIndent(),
            arrayOf<Any>(CHANNEL_ID, 1_710_000_200_000L),
        )
        db.close()
    }

    private fun seedDatabaseFromExportedSchema(version: Int) {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("$version.json")
            .bufferedReader()
            .use { JSONObject(it.readText()).getJSONObject("database") }
        assertEquals(version, schema.getInt("version"))

        val file = context.getDatabasePath("pushgo.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.beginTransaction()
            try {
                val entities = schema.getJSONArray("entities")
                for (entityIndex in 0 until entities.length()) {
                    val entity = entities.getJSONObject(entityIndex)
                    val tableName = entity.getString("tableName")
                    database.execSQL(resolveSchemaTable(entity.getString("createSql"), tableName))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (index in 0 until indices.length()) {
                        database.execSQL(
                            resolveSchemaTable(indices.getJSONObject(index).getString("createSql"), tableName)
                        )
                    }
                }
                val setupQueries = schema.getJSONArray("setupQueries")
                for (index in 0 until setupQueries.length()) {
                    database.execSQL(setupQueries.getString(index))
                }
                database.execSQL("PRAGMA user_version = $version")
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun resolveSchemaTable(sql: String, tableName: String): String =
        sql.replace("\${TABLE_NAME}", tableName)

    private fun assertLegacyIngressBusinessState(database: PushGoDatabase) {
        val state = database.openHelper.writableDatabase.query(
            """
            SELECT gateway_url, device_key, delivery_id, payload_json, enqueued_at
            FROM legacy_provider_ingress
            WHERE gateway_url = ? AND device_key = ? AND delivery_id = ?
            """.trimIndent(),
            arrayOf(LEGACY_INGRESS_GATEWAY, LEGACY_INGRESS_DEVICE, LEGACY_INGRESS_DELIVERY),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(
                cursor.getString(0),
                cursor.getString(1),
                cursor.getString(2),
                cursor.getString(3),
                cursor.getLong(4),
            )
        }
        assertEquals(
            listOf(
                LEGACY_INGRESS_GATEWAY,
                LEGACY_INGRESS_DEVICE,
                LEGACY_INGRESS_DELIVERY,
                LEGACY_INGRESS_PAYLOAD,
                LEGACY_INGRESS_ENQUEUED_AT,
            ),
            state,
        )
    }

    private fun assertPendingDeletionBusinessState(database: PushGoDatabase) {
        val state = database.openHelper.writableDatabase.query(
            """
            SELECT id, summary, operation_kind, target_ids_json,
                expected_gateway_url, expected_updated_at, expected_use_provider,
                requested_at_epoch_ms, undo_deadline_epoch_ms, state, attempt_count,
                next_attempt_at_epoch_ms, updated_at_epoch_ms, last_error
            FROM pending_local_deletions
            WHERE id = ?
            """.trimIndent(),
            arrayOf(PENDING_DELETION_ID.toString()),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(
                cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                cursor.getString(4), cursor.getLong(5), cursor.getInt(6), cursor.getLong(7),
                cursor.getLong(8), cursor.getString(9), cursor.getInt(10), cursor.getLong(11),
                cursor.getLong(12), cursor.getString(13),
            )
        }
        assertEquals(
            listOf(
                PENDING_DELETION_ID,
                PENDING_DELETION_SUMMARY,
                PENDING_DELETION_KIND,
                PENDING_DELETION_TARGETS,
                PENDING_DELETION_GATEWAY,
                PENDING_DELETION_EXPECTED_UPDATED_AT,
                1,
                PENDING_DELETION_REQUESTED_AT,
                PENDING_DELETION_UNDO_DEADLINE,
                PENDING_DELETION_STATE,
                PENDING_DELETION_ATTEMPT_COUNT,
                PENDING_DELETION_NEXT_ATTEMPT_AT,
                PENDING_DELETION_UPDATED_AT,
                PENDING_DELETION_LAST_ERROR,
            ),
            state,
        )
    }

    private fun cleanupDatabaseFamily(name: String) {
        context.deleteDatabase(name)
        context.getDatabasePath(name).delete()
        context.getDatabasePath("$name-wal").delete()
        context.getDatabasePath("$name-shm").delete()
    }

    private fun readUserVersion(file: java.io.File): Int {
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }
    }

    companion object {
        private const val GATEWAY_URL = "https://gateway.pushgo.cn"
        private const val CHANNEL_ID = "alpha-channel"
        private const val MESSAGE_ID = "legacy-message-001"
        private const val LEGACY_INGRESS_GATEWAY = "https://migration-gateway.example"
        private const val LEGACY_INGRESS_DEVICE = "migration-device"
        private const val LEGACY_INGRESS_DELIVERY = "delivery-before-v28"
        private const val LEGACY_INGRESS_PAYLOAD =
            "{\"entity_type\":\"message\",\"title\":\"queued-before-upgrade\"}"
        private const val LEGACY_INGRESS_ENQUEUED_AT = 1_710_000_300_001L
        private const val PENDING_DELETION_ID = 28L
        private const val PENDING_DELETION_SUMMARY = "Delete stale channel after undo window"
        private const val PENDING_DELETION_KIND = "channel"
        private const val PENDING_DELETION_TARGETS = "[\"stale-channel\"]"
        private const val PENDING_DELETION_GATEWAY = "https://migration-gateway.example"
        private const val PENDING_DELETION_EXPECTED_UPDATED_AT = 1_710_000_300_002L
        private const val PENDING_DELETION_REQUESTED_AT = 1_710_000_300_003L
        private const val PENDING_DELETION_UNDO_DEADLINE = 1_710_000_330_003L
        private const val PENDING_DELETION_STATE = "pending"
        private const val PENDING_DELETION_ATTEMPT_COUNT = 2
        private const val PENDING_DELETION_NEXT_ATTEMPT_AT = 1_710_000_340_003L
        private const val PENDING_DELETION_UPDATED_AT = 1_710_000_305_003L
        private const val PENDING_DELETION_LAST_ERROR = "temporary gateway timeout"
    }
}

@Entity(tableName = "app_settings")
data class LegacyAppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "server_address")
    val serverAddress: String?,
    val token: String?,
    @ColumnInfo(name = "notification_key_updated_at")
    val notificationKeyUpdatedAt: Long?,
    @ColumnInfo(name = "key_encoding")
    val keyEncoding: String = KeyEncoding.BASE64.name,
    @ColumnInfo(name = "fcm_token")
    val fcmToken: String?,
    @ColumnInfo(name = "use_fcm_channel")
    val useFcmChannel: Boolean = true,
    @ColumnInfo(name = "is_message_page_enabled")
    val isMessagePageEnabled: Boolean = true,
    @ColumnInfo(name = "is_event_page_enabled")
    val isEventPageEnabled: Boolean = true,
    @ColumnInfo(name = "is_thing_page_enabled")
    val isThingPageEnabled: Boolean = true,
)

@Entity(tableName = "messages")
data class LegacyMessageV21Entity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id") val messageId: String?,
    val title: String,
    val body: String,
    val channel: String?,
    val url: String?,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "raw_payload_json") val rawPayloadJson: String,
    val status: String,
    @ColumnInfo(name = "decryption_state") val decryptionState: String?,
    @ColumnInfo(name = "notification_id") val notificationId: String?,
    @ColumnInfo(name = "server_id") val serverId: String?,
    @ColumnInfo(name = "body_preview") val bodyPreview: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String?,
    @ColumnInfo(name = "event_id") val eventId: String?,
    @ColumnInfo(name = "thing_id") val thingId: String?,
    @ColumnInfo(name = "event_state") val eventState: String?,
    @ColumnInfo(name = "event_time_epoch") val eventTimeEpoch: Long?,
    @ColumnInfo(name = "occurred_at_epoch") val occurredAtEpoch: Long?,
)

@Entity(tableName = "message_channel_counts")
data class LegacyMessageChannelStatsV21Entity(
    @PrimaryKey val channel: String,
    @ColumnInfo(name = "total_count") val totalCount: Int,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "latest_received_at") val latestReceivedAt: Long,
)

@Fts4(contentEntity = LegacyMessageV21Entity::class)
@Entity(tableName = "message_fts")
data class LegacyMessageFtsV21(
    val title: String,
    val body: String,
    val channel: String?,
)

@Database(
    entities = [
        LegacyMessageV21Entity::class,
        MessageMetadataIndexEntity::class,
        LegacyMessageFtsV21::class,
        LegacyMessageChannelStatsV21Entity::class,
        LegacyInboundDeliveryLedgerV25Entity::class,
        LegacyInboundDeliveryAckOutboxV24Entity::class,
        OperationLedgerEntity::class,
        EventChangeLogEntity::class,
        ThingChangeLogEntity::class,
        ThingSubEventEntity::class,
        TopLevelEventHeadEntity::class,
        ThingHeadEntity::class,
        ThingSubMessageEntity::class,
        PendingThingMessageEntity::class,
        PendingThingEventEntity::class,
        ChannelSubscriptionEntity::class,
        LegacyAppSettingsEntity::class,
    ],
    version = 21,
    exportSchema = false,
)
abstract class LegacyPushGoV21Database : RoomDatabase()

@Database(
    entities = [
        LegacyMessageV21Entity::class,
        MessageMetadataIndexEntity::class,
        LegacyMessageFtsV21::class,
        LegacyMessageChannelStatsV21Entity::class,
        LegacyInboundDeliveryLedgerV25Entity::class,
        LegacyInboundDeliveryAckOutboxV24Entity::class,
        OperationLedgerEntity::class,
        EventChangeLogEntity::class,
        ThingChangeLogEntity::class,
        ThingSubEventEntity::class,
        TopLevelEventHeadEntity::class,
        ThingHeadEntity::class,
        ThingSubMessageEntity::class,
        PendingThingMessageEntity::class,
        PendingThingEventEntity::class,
        ChannelSubscriptionEntity::class,
        AppSettingsEntity::class,
    ],
    version = 23,
    exportSchema = false,
)
abstract class LegacyPushGoV23Database : RoomDatabase()

@Entity(tableName = "inbound_delivery_ledger")
data class LegacyInboundDeliveryLedgerV25Entity(
    @ColumnInfo(name = "delivery_id")
    @PrimaryKey val deliveryId: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String?,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String?,
    @ColumnInfo(name = "op_id")
    val opId: String?,
    @ColumnInfo(name = "applied_at")
    val appliedAt: Long,
    @ColumnInfo(name = "ack_state")
    val ackState: String,
    @ColumnInfo(name = "acked_at")
    val ackedAt: Long?,
)

@Entity(tableName = "inbound_delivery_ack_outbox")
data class LegacyInboundDeliveryAckOutboxV24Entity(
    @ColumnInfo(name = "delivery_id")
    @PrimaryKey val deliveryId: String,
    val source: String,
    @ColumnInfo(name = "enqueued_at")
    val enqueuedAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Database(
    entities = [
        MessageEntity::class,
        MessageMetadataIndexEntity::class,
        MessageFts::class,
        MessageChannelStatsEntity::class,
        MessageGlobalStatsEntity::class,
        MessageStoreRevisionEntity::class,
        MessageDerivedStateEntity::class,
        LegacyInboundDeliveryLedgerV25Entity::class,
        LegacyInboundDeliveryAckOutboxV24Entity::class,
        OperationLedgerEntity::class,
        EventChangeLogEntity::class,
        ThingChangeLogEntity::class,
        ThingSubEventEntity::class,
        TopLevelEventHeadEntity::class,
        ThingHeadEntity::class,
        ThingSubMessageEntity::class,
        PendingThingMessageEntity::class,
        PendingThingEventEntity::class,
        ChannelSubscriptionEntity::class,
        AppSettingsEntity::class,
    ],
    version = 24,
    exportSchema = false,
)
abstract class LegacyPushGoV24Database : RoomDatabase()

@Entity(
    tableName = "inbound_delivery_ack_outbox",
    primaryKeys = ["gateway_url", "device_key", "delivery_id"],
)
data class LegacyInboundDeliveryAckOutboxV25Entity(
    @ColumnInfo(name = "delivery_id") val deliveryId: String,
    @ColumnInfo(name = "gateway_url") val gatewayUrl: String,
    @ColumnInfo(name = "device_key") val deviceKey: String,
    @ColumnInfo(name = "ack_contract") val ackContract: String,
    val source: String,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "pending_thing_events",
    indices = [
        Index(value = ["thing_id", "event_time_epoch", "received_at"]),
        Index(value = ["delivery_id"], unique = true),
    ],
)
data class LegacyPendingThingEventV25Entity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_payload_json") val rawPayloadJson: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "op_id") val opId: String?,
    @ColumnInfo(name = "delivery_id") val deliveryId: String?,
    @ColumnInfo(name = "server_id") val serverId: String?,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "thing_id") val thingId: String,
    @ColumnInfo(name = "event_state") val eventState: String?,
    @ColumnInfo(name = "event_time_epoch") val eventTimeEpoch: Long?,
)

@Database(
    entities = [
        MessageEntity::class,
        MessageMetadataIndexEntity::class,
        MessageFts::class,
        MessageChannelStatsEntity::class,
        MessageGlobalStatsEntity::class,
        MessageStoreRevisionEntity::class,
        MessageDerivedStateEntity::class,
        LegacyInboundDeliveryLedgerV25Entity::class,
        LegacyInboundDeliveryAckOutboxV25Entity::class,
        OperationLedgerEntity::class,
        EventChangeLogEntity::class,
        ThingChangeLogEntity::class,
        ThingSubEventEntity::class,
        TopLevelEventHeadEntity::class,
        ThingHeadEntity::class,
        ThingSubMessageEntity::class,
        PendingThingMessageEntity::class,
        LegacyPendingThingEventV25Entity::class,
        ChannelSubscriptionEntity::class,
        AppSettingsEntity::class,
    ],
    version = 25,
    exportSchema = false,
)
abstract class LegacyPushGoV25Database : RoomDatabase()
