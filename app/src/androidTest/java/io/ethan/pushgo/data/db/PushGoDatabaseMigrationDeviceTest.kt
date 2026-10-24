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
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
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
import java.time.Instant

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
        assertEquals(38, readUserVersion(context.getDatabasePath("pushgo.db")))
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
        assertEquals(38, readUserVersion(context.getDatabasePath("pushgo.db")))
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

        assertEquals(38, readUserVersion(context.getDatabasePath("pushgo.db")))
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
        val pendingEventTableRetired = migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'pending_thing_events'"
        ).use { cursor -> !cursor.moveToFirst() }
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

        assertEquals(38, readUserVersion(context.getDatabasePath("pushgo.db")))
        assertEquals(Triple("", "", "pending"), legacyLedger)
        assertEquals(
            listOf("https://gateway-a.example", "device-a", "legacy_single", 0),
            scopedOutbox,
        )
        assertTrue(pendingEventTableRetired)
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
            assertEquals(38, readUserVersion(context.getDatabasePath("pushgo.db")))
        } finally {
            migrated.close()
        }
        val reopened = PushGoDatabase.build(context)
        try {
            assertLegacyIngressBusinessState(reopened)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun productionDatabase_migratesV31ToV32DropsOnlyTransportJournalAndReopens() {
        assertRouteJournalRemovalPreservesBusinessData(version = 31)
    }

    @Test
    fun productionDatabase_migratesV32AndRetiresLegacyFtsWithoutLosingSearchableMessages() = runBlocking {
        // User purpose: keep saved messages and metadata searchable after the v32 FTS retirement.
        // This detects native Room/SQLite virtual-table, trigger, and migration behavior that a JVM
        // fixture cannot reproduce. Expected cost is one short database upgrade/reopen (<5 seconds).
        seedDatabaseFromExportedSchema(32)
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("pushgo.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL(
                """
                INSERT INTO app_settings(
                    id, server_address, key_encoding, use_fcm_channel,
                    is_message_page_enabled, is_event_page_enabled, is_thing_page_enabled,
                    update_auto_check_enabled, update_beta_channel_enabled, update_prompt_dismiss_count
                ) VALUES(1, ?, 'BASE64', 1, 1, 1, 1, 1, 0, 0)
                """.trimIndent(),
                arrayOf<Any>(GATEWAY_URL),
            )
            database.execSQL(
                """
                INSERT INTO channel_subscriptions(
                    gateway_url, channel_id, display_name, updated_at, last_synced_at, is_deleted
                ) VALUES(?, ?, 'FTS retained channel', 1710000000000, 1710000100000, 0)
                """.trimIndent(),
                arrayOf<Any>(GATEWAY_URL, CHANNEL_ID),
            )
            database.execSQL(
                """
                INSERT INTO messages(
                    id, message_id, title, body, channel, url, is_read, received_at,
                    raw_payload_json, status, decryption_state, notification_id, server_id,
                    body_preview, entity_type, entity_id, event_id, thing_id, event_state,
                    event_time_epoch, occurred_at_epoch, list_payload_json
                ) VALUES('retained-v32-message', 'retained-v32-message', 'Migration keeps title',
                    'retained searchable body', ?, NULL, 1, 1710000200000,
                    '{"tags":["retained-tag"],"metadata":{"migration-key":"retained-value"}}',
                    'NORMAL', NULL, NULL, NULL, 'retained searchable body', 'message', NULL,
                    NULL, NULL, NULL, NULL, NULL, '{}')
                """.trimIndent(),
                arrayOf<Any>(CHANNEL_ID),
            )
            database.execSQL(
                """
                INSERT INTO message_metadata_index(message_id, key_name, value_norm, label, received_at)
                VALUES('retained-v32-message', 'tag', 'retained-tag', 'retained-tag', 1710000200000)
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO message_metadata_index(message_id, key_name, value_norm, label, received_at)
                VALUES('retained-v32-message', 'search_text', 'normalization_v1',
                    'migration keeps title retained searchable body migration-key retained-value retained-tag',
                    1710000200000)
                """.trimIndent(),
            )

            val existingFtsTriggers = database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'trigger' " +
                    "AND name LIKE 'room_fts_content_sync_message_fts_%' ORDER BY name",
                null,
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertEquals(
                listOf(
                    "room_fts_content_sync_message_fts_AFTER_INSERT",
                    "room_fts_content_sync_message_fts_AFTER_UPDATE",
                    "room_fts_content_sync_message_fts_BEFORE_DELETE",
                    "room_fts_content_sync_message_fts_BEFORE_UPDATE",
                ),
                existingFtsTriggers,
            )
            val ftsMatchCount = database.rawQuery(
                "SELECT COUNT(*) FROM message_fts WHERE message_fts MATCH 'searchable'",
                null,
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            assertEquals("The v32 FTS fixture is populated before migration", 1, ftsMatchCount)
        }

        val container = AppContainer(context, appScope)
        val sqlite = container.database.openHelper.writableDatabase
        try {
            assertEquals(38, readUserVersion(context.getDatabasePath("pushgo.db")))
            val migratedMessage = sqlite.query(
                "SELECT message_id, title, body, channel, is_read, raw_payload_json " +
                    "FROM messages WHERE id = 'retained-v32-message'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getInt(4).toString(), cursor.getString(5))
            }
            assertEquals(
                listOf(
                    "retained-v32-message", "Migration keeps title", "retained searchable body",
                    CHANNEL_ID, "1",
                    "{\"tags\":[\"retained-tag\"],\"metadata\":{\"migration-key\":\"retained-value\"}}",
                ),
                migratedMessage,
            )
            val retainedMetadata = sqlite.query(
                "SELECT key_name, value_norm, label, received_at FROM message_metadata_index " +
                    "WHERE message_id = 'retained-v32-message' ORDER BY key_name",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
                    }
                }
            }
            assertEquals(
                listOf(
                    listOf("search_text", "normalization_v1",
                        "migration keeps title retained searchable body migration-key retained-value retained-tag",
                        1710000200000L),
                    listOf("tag", "retained-tag", "retained-tag", 1710000200000L),
                ),
                retainedMetadata,
            )
            assertEquals(GATEWAY_URL, container.settingsRepository.getServerAddress())
            assertEquals(true, container.settingsRepository.getUpdateAutoCheckEnabled())
            assertEquals(false, container.settingsRepository.getUpdateBetaChannelEnabled())
            val retainedSubscription = container.channelStore.loadSubscriptions(GATEWAY_URL).single()
            assertEquals(CHANNEL_ID, retainedSubscription.channelId)
            assertEquals("FTS retained channel", retainedSubscription.displayName)
            assertEquals(
                listOf("retained-v32-message"),
                container.messageRepository.searchMessagesSnapshot(
                    "tag:retained-tag", unreadOnly = false, limit = 10,
                ).map { it.id },
            )
            assertEquals(
                listOf("retained-v32-message"),
                container.messageRepository.searchMessagesSnapshot(
                    "retained searchable", unreadOnly = false, limit = 10,
                ).map { it.id },
            )
            assertNoLegacyMessageFts(sqlite)
        } finally {
            container.database.close()
        }

        val reopened = AppContainer(context, appScope)
        try {
            val reopenedOriginal = reopened.database.openHelper.writableDatabase.query(
                "SELECT title, body, is_read FROM messages WHERE id = 'retained-v32-message'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                listOf(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
            }
            assertEquals(
                listOf("Migration keeps title", "retained searchable body", 1),
                reopenedOriginal,
            )
            assertEquals(
                listOf("retained-v32-message"),
                reopened.messageRepository.searchMessagesSnapshot(
                    "tag:retained-tag", unreadOnly = false, limit = 10,
                ).map { it.id },
            )
            val added = PushMessage(
                id = "repository-added-after-fts-retirement",
                messageId = "repository-added-after-fts-retirement",
                title = "Repository added title",
                body = "repository-added-token",
                channel = CHANNEL_ID,
                url = null,
                isRead = false,
                receivedAt = Instant.ofEpochMilli(1710000300000),
                rawPayloadJson = "{\"entity_type\":\"message\",\"tags\":[\"added-tag\"]}",
                status = MessageStatus.NORMAL,
                decryptionState = null,
                notificationId = null,
                serverId = null,
            )
            reopened.messageRepository.insert(added)
            val initialBodySearch = reopened.messageRepository.searchMessagesSnapshot(
                "repository-added-token", unreadOnly = false, limit = 10,
            )
            assertEquals(
                listOf(added.id),
                initialBodySearch.map { it.id },
            )
            assertEquals("Repository added title", initialBodySearch.single().title)
            reopened.messageRepository.insertAll(
                listOf(
                    added.copy(
                        title = "Repository updated title",
                        body = "repository-updated-token",
                        bodyPreview = "repository-updated-token",
                        receivedAt = added.receivedAt.plusMillis(1_000),
                    ),
                ),
            )
            val updatedBodySearch = reopened.messageRepository.searchMessagesSnapshot(
                "repository-updated-token", unreadOnly = false, limit = 10,
            )
            assertEquals(listOf(added.id), updatedBodySearch.map { it.id })
            assertEquals("Repository updated title", updatedBodySearch.single().title)
            assertEquals("repository-updated-token", updatedBodySearch.single().bodyPreview)
            assertEquals(
                listOf(added.id),
                reopened.messageRepository.searchMessagesSnapshot(
                    "Repository updated title", unreadOnly = false, limit = 10,
                ).map { it.id },
            )
            assertTrue(
                reopened.messageRepository.searchMessagesSnapshot(
                    "repository-added-token", unreadOnly = false, limit = 10,
                ).isEmpty(),
            )
            assertTrue(
                reopened.messageRepository.searchMessagesSnapshot(
                    "Repository added title", unreadOnly = false, limit = 10,
                ).isEmpty(),
            )
            reopened.messageRepository.markRead(added.id)
            val updatedSearch = reopened.messageRepository.searchMessagesSnapshot(
                "tag:added-tag", unreadOnly = false, limit = 10,
            )
            assertEquals(listOf(added.id), updatedSearch.map { it.id })
            assertTrue("The repository update persists the read state", updatedSearch.single().isRead)
            assertTrue(
                reopened.messageRepository.searchMessagesSnapshot(
                    "tag:added-tag", unreadOnly = true, limit = 10,
                ).isEmpty(),
            )
            reopened.messageRepository.deleteById(added.id)
            assertTrue(
                reopened.messageRepository.searchMessagesSnapshot(
                    "repository-updated-token", unreadOnly = false, limit = 10,
                ).isEmpty(),
            )
            val deletedTargetCount = reopened.database.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM messages WHERE id = ?",
                arrayOf(added.id),
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            assertEquals(0, deletedTargetCount)
            val retainedAfterDelete = reopened.database.openHelper.writableDatabase.query(
                "SELECT title, body, is_read FROM messages WHERE id = 'retained-v32-message'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                listOf(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
            }
            assertEquals(
                listOf("Migration keeps title", "retained searchable body", 1),
                retainedAfterDelete,
            )
            assertNoLegacyMessageFts(reopened.database.openHelper.writableDatabase)
        } finally {
            reopened.database.close()
        }
    }

    @Test
    fun productionDatabase_freshCreateSearchesThroughRepositoryWithoutLegacyFts() = runBlocking {
        val container = AppContainer(context, appScope)
        try {
            val sqlite = container.database.openHelper.writableDatabase
            assertEquals(38, readUserVersion(context.getDatabasePath("pushgo.db")))
            assertTrue(
                container.messageRepository.searchMessagesSnapshot(
                    "fresh-database-query", unreadOnly = false, limit = 10,
                ).isEmpty(),
            )
            val fresh = PushMessage(
                id = "fresh-repository-search-message",
                messageId = "fresh-repository-search-message",
                title = "Fresh create search title",
                body = "fresh-create-search-body",
                channel = CHANNEL_ID,
                url = null,
                isRead = false,
                receivedAt = Instant.ofEpochMilli(1710000400000),
                rawPayloadJson = "{\"entity_type\":\"message\",\"tags\":[\"fresh-create-tag\"]}",
                status = MessageStatus.NORMAL,
                decryptionState = null,
                notificationId = null,
                serverId = null,
                bodyPreview = "fresh-create-search-body",
            )
            container.messageRepository.insert(fresh)
            val bodyMatches = container.messageRepository.searchMessagesSnapshot(
                "fresh-create-search-body", unreadOnly = false, limit = 10,
            )
            assertEquals(listOf(fresh.id), bodyMatches.map { it.id })
            assertEquals("Fresh create search title", bodyMatches.single().title)
            assertEquals("fresh-create-search-body", bodyMatches.single().bodyPreview)
            val tagMatches = container.messageRepository.searchMessagesSnapshot(
                "tag:fresh-create-tag", unreadOnly = false, limit = 10,
            )
            assertEquals(listOf(fresh.id), tagMatches.map { it.id })
            assertEquals("Fresh create search title", tagMatches.single().title)
            assertEquals("fresh-create-search-body", tagMatches.single().bodyPreview)
            assertNoLegacyMessageFts(sqlite)
        } finally {
            container.database.close()
        }
    }

    private fun assertNoLegacyMessageFts(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        val leftovers = database.query(
            "SELECT name FROM sqlite_master WHERE name LIKE 'message_fts%' " +
                "OR name LIKE 'room_fts_content_sync_message_fts_%' ORDER BY name",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        assertTrue("Legacy FTS table, shadows, and sync triggers are removed: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun productionDatabase_migratesV33ChildEventDomainsWithoutLosingOverlappingContentAndReopens() {
        // One native upgrade/reopen proves saved fields, original domain membership and ACK
        // identity. Same-ID rows in the two old tables must survive independently.
        seedDatabaseFromExportedSchema(33)
        val expected = openLegacyDatabase().use { database ->
            seedLegacyChildEvent(database, "thing_sub_events", "different-content", "former-parent")
            seedLegacyChildEvent(database, "pending_thing_events", "different-content", "present-parent")
            database.execSQL(
                "UPDATE pending_thing_events SET title = 'Waiting title', body = 'Waiting body', " +
                    "channel = 'waiting-channel', raw_payload_json = '{\"original\":\"等待原文\"}'"
            )
            seedLegacyChildEvent(database, "thing_sub_events", "identical-content", "shared-thing")
            seedLegacyChildEvent(database, "pending_thing_events", "identical-content", "shared-thing")
            seedLegacyChildEvent(database, "pending_thing_events", "waiting-no-parent", "absent-parent")
            database.execSQL(
                """
                INSERT INTO thing_heads(thing_id, source_id, entity_id, title, body,
                    raw_payload_json, received_at, updated_at)
                VALUES('present-parent', 'parent-source', 'present-parent', 'Parent', 'Parent body',
                    '{"entity_type":"thing","thing_id":"present-parent"}', 1710000000123, 1710000000456)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO inbound_delivery_ledger(gateway_url, device_key, delivery_id, channel_id,
                    entity_type, entity_id, op_id, applied_at, ack_state, acked_at)
                VALUES('https://old-gateway.example', 'original-device', 'child-delivery',
                    'child-channel', 'event', 'child-event', 'original-op', 1710000000123, 'acked', 1710000000999)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO inbound_delivery_ack_outbox(gateway_url, device_key, delivery_id,
                    ack_contract, source, enqueued_at, updated_at, attempt_count, last_attempt_uncertain)
                VALUES('https://other-gateway.example', 'other-device', 'child-delivery',
                    'v2_batch', 'provider_pull', 1710000000111, 1710000000222, 3, 1)
                """.trimIndent()
            )
            buildMap<String, List<List<String?>>> {
                put("events", database.rawQuery(
                    "SELECT $childEventContentColumns, 1 AS activated FROM thing_sub_events UNION ALL " +
                        "SELECT $childEventContentColumns, 0 AS activated FROM pending_thing_events " +
                        "ORDER BY id, activated", null
                ).use(::readBusinessRows))
                childMigrationBusinessTables.forEach { table ->
                    put(table, database.rawQuery("SELECT * FROM $table", null).use(::readBusinessRows))
                }
            }
        }

        repeat(2) {
            val database = PushGoDatabase.build(context)
            try {
                val sqlite = database.openHelper.writableDatabase
                assertEquals(38, readUserVersion(context.getDatabasePath("pushgo.db")))
                assertEquals(expected.getValue("events"), sqlite.query(
                    "SELECT $childEventContentColumns, activated FROM thing_sub_events ORDER BY id, activated"
                ).use(::readBusinessRows))
                childMigrationBusinessTables.forEach { table ->
                    assertEquals("Retain $table", expected.getValue(table),
                        sqlite.query("SELECT * FROM $table").use(::readBusinessRows))
                }
                val visibleIds = sqlite.query(
                    "SELECT id FROM thing_sub_events WHERE activated = 1 ORDER BY id"
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                assertEquals(listOf("different-content", "identical-content"), visibleIds)
                assertTrue(sqlite.query(
                    "SELECT 1 FROM sqlite_master WHERE name = 'pending_thing_events' " +
                        "OR tbl_name = 'pending_thing_events'"
                ).use { cursor -> !cursor.moveToFirst() })
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun productionDatabase_v33ChildEventMigrationRollsBackCopiedDroppedAndRenamedTablesOnIndexFailure() {
        // This fixture reserves a new index name on another table. The ordinary migration
        // reaches copy/drop/rename before CREATE INDEX fails; no production test switch is used.
        seedDatabaseFromExportedSchema(33)
        val indexName = "index_thing_sub_events_channel_activated"
        val expected = openLegacyDatabase().use { database ->
            seedLegacyChildEvent(database, "thing_sub_events", "overlapping-child", "active-thing")
            seedLegacyChildEvent(database, "pending_thing_events", "overlapping-child", "waiting-thing")
            database.execSQL("CREATE TABLE migration_index_fault_fixture(value TEXT)")
            database.execSQL("CREATE INDEX $indexName ON migration_index_fault_fixture(value)")
            buildMap<String, List<List<String?>>> {
                listOf("thing_sub_events", "pending_thing_events").forEach { table ->
                    put(table, database.rawQuery("SELECT * FROM $table", null).use(::readBusinessRows))
                }
                put("schema", database.rawQuery(
                    "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name", null
                ).use(::readBusinessRows))
            }
        }
        val database = PushGoDatabase.build(context)
        val failure = try {
            runCatching { database.openHelper.writableDatabase }.exceptionOrNull()
        } finally {
            database.close()
        }
        assertTrue("Injected index creation failure must block upgrade", failure != null)
        assertTrue("Failure must reach the post-copy index creation", generateSequence(failure) { it.cause }
            .any { it.message?.contains(indexName) == true })
        openLegacyDatabase().use { original ->
            assertEquals(33, original.version)
            listOf("thing_sub_events", "pending_thing_events").forEach { table ->
                assertEquals("Restore original $table", expected.getValue(table),
                    original.rawQuery("SELECT * FROM $table", null).use(::readBusinessRows))
            }
            assertEquals("Restore original tables, indexes and Room identity schema", expected.getValue("schema"),
                original.rawQuery("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name", null)
                    .use(::readBusinessRows))
        }
    }

    private val childMigrationBusinessTables =
        listOf("thing_heads", "inbound_delivery_ledger", "inbound_delivery_ack_outbox")

    private val childEventContentColumns =
        "id, entity_id, channel, title, body, raw_payload_json, received_at, op_id, " +
            "delivery_id, server_id, event_id, thing_id, event_state, event_time_epoch"

    private fun openLegacyDatabase(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath("pushgo.db").path, null, SQLiteDatabase.OPEN_READWRITE
    )

    private fun seedLegacyChildEvent(database: SQLiteDatabase, table: String, id: String, thingId: String) {
        check(table == "thing_sub_events" || table == "pending_thing_events")
        database.execSQL(
            "INSERT INTO $table($childEventContentColumns) " +
                "VALUES(?, ?, 'child-channel', 'Original child title', 'Original child body', " +
                "?, 1710000000123, 'original-op', 'child-delivery', 'original-server', " +
                "'child-event', ?, 'active', 1709999999876)",
            arrayOf<Any>(id, "entity-$id", "{\"event_id\":\"child-event\",\"thing_id\":\"$thingId\",\"original\":\"保留\"}", thingId),
        )
    }

    @Test
    fun productionDatabase_migratesV30ToV32PreservesBusinessDataAndReopens() {
        assertRouteJournalRemovalPreservesBusinessData(version = 30)
    }

    private fun assertRouteJournalRemovalPreservesBusinessData(version: Int) {
        seedDatabaseFromExportedSchema(version)
        val originalColumns = linkedMapOf<String, List<String>>()
        val expected = SQLiteDatabase.openDatabase(
            context.getDatabasePath("pushgo.db").path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            database.execSQL(
                """
                INSERT INTO app_settings(
                    id, server_address, key_encoding, use_fcm_channel,
                    is_message_page_enabled, is_event_page_enabled, is_thing_page_enabled,
                    update_auto_check_enabled, update_beta_channel_enabled, update_prompt_dismiss_count
                ) VALUES(1, ?, 'BASE64', 1, 1, 1, 1, 1, 0, 2)
                """.trimIndent(),
                arrayOf<Any>(GATEWAY_URL),
            )
            database.execSQL(
                """
                INSERT INTO channel_subscriptions(
                    gateway_url, channel_id, display_name, updated_at, last_synced_at, is_deleted
                ) VALUES(?, ?, 'Retained channel', 1710000000000, 1710000100000, 0)
                """.trimIndent(),
                arrayOf<Any>(GATEWAY_URL, CHANNEL_ID),
            )
            database.execSQL(
                """
                INSERT INTO messages(
                    id, message_id, title, body, channel, is_read, received_at, raw_payload_json,
                    status, body_preview, entity_type, list_payload_json
                ) VALUES('retained-local-message', ?, 'Retained title', 'Retained body', ?,
                    1, 1710000200000, '{}', 'NORMAL', 'Retained body', 'message', '{}')
                """.trimIndent(),
                arrayOf<Any>(MESSAGE_ID, CHANNEL_ID),
            )
            database.execSQL(
                """
                INSERT INTO inbound_delivery_ledger(
                    gateway_url, device_key, delivery_id, channel_id, entity_type, entity_id,
                    applied_at, ack_state
                ) VALUES(?, 'retained-device', 'retained-delivery', ?, 'message', ?,
                    1710000200000, 'pending')
                """.trimIndent(),
                arrayOf<Any>(GATEWAY_URL, CHANNEL_ID, MESSAGE_ID),
            )
            database.execSQL(
                """
                INSERT INTO inbound_delivery_ack_outbox(
                    delivery_id, gateway_url, device_key, ack_contract, source,
                    enqueued_at, updated_at, attempt_count, last_attempt_uncertain
                ) VALUES('retained-delivery', ?, 'retained-device', 'v2_batch', 'private',
                    1710000200000, 1710000200000, 1, 1)
                """.trimIndent(),
                arrayOf<Any>(GATEWAY_URL),
            )
            if (version == 31) {
                database.execSQL(
                    """
                    INSERT INTO transport_transitions(
                        operation_id, gateway_url, device_key, target_channel_type, transition_id,
                        base_revision, committed_revision, phase, candidate_token_fingerprint,
                        created_at, updated_at
                    ) VALUES('obsolete-operation', ?, 'retained-device', 'private', 'obsolete-transition',
                        11, 12, 'REMOTE_COMMITTED', 'obsolete-fingerprint', 1710000200000, 1710000200100)
                    """.trimIndent(),
                    arrayOf<Any>(GATEWAY_URL),
                )
            }
            retainedRouteCleanupTables.associateWith { table ->
                database.rawQuery("SELECT * FROM $table", null).use { cursor ->
                    originalColumns[table] = cursor.columnNames.toList()
                    readBusinessRows(cursor)
                }
            }
        }

        fun assertBusinessState(database: PushGoDatabase) {
            val sqlite = database.openHelper.writableDatabase
            assertEquals(38, readUserVersion(context.getDatabasePath("pushgo.db")))
            retainedRouteCleanupTables.forEach { table ->
                // Compare every original business column, even as a later migration adds
                // an internal recovery field. Nothing from the source row is omitted.
                val columns = originalColumns.getValue(table).joinToString(",") { "`$it`" }
                val actual = sqlite.query("SELECT $columns FROM $table").use(::readBusinessRows)
                assertEquals("Preserve $table", expected[table], actual)
                assertEquals("Seeded $table is retained", 1, actual.size)
            }
            assertEquals(
                "The retained historical message must not acquire a new notification obligation",
                listOf(listOf("0")),
                sqlite.query("SELECT post_process_pending FROM messages").use(::readBusinessRows),
            )
            val removed = sqlite.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'transport_transitions'"
            ).use { cursor -> !cursor.moveToFirst() }
            assertTrue("Obsolete journal table is removed", removed)
        }

        val migrated = PushGoDatabase.build(context)
        try {
            assertBusinessState(migrated)
        } finally {
            migrated.close()
        }
        val reopened = PushGoDatabase.build(context)
        try {
            assertBusinessState(reopened)
        } finally {
            reopened.close()
        }
    }

    private val retainedRouteCleanupTables = listOf(
        "messages", "app_settings", "channel_subscriptions",
        "inbound_delivery_ledger", "inbound_delivery_ack_outbox",
    )

    private fun readBusinessRows(cursor: android.database.Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) {
            add((0 until cursor.columnCount).map { column ->
                if (cursor.isNull(column)) null else cursor.getString(column)
            })
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
            assertEquals(38, readUserVersion(context.getDatabasePath("pushgo.db")))
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
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) {
                        for (index in 0 until indices.length()) {
                            database.execSQL(
                                resolveSchemaTable(indices.getJSONObject(index).getString("createSql"), tableName)
                            )
                        }
                    }
                    val contentSyncTriggers = entity.optJSONArray("contentSyncTriggers")
                    if (contentSyncTriggers != null) {
                        for (triggerIndex in 0 until contentSyncTriggers.length()) {
                            database.execSQL(contentSyncTriggers.getString(triggerIndex))
                        }
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

// Freeze the legacy fixture shape at the last pre-post-processing schema. These are
// schema-only test records, not aliases to evolving production entities: otherwise
// constructing a v21/v23/v24/v25 fixture would already contain the v37 column.
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["message_id"], unique = true, name = "index_messages_message_id_unique"),
        Index(value = ["channel", "received_at"]),
        Index(value = ["is_read", "received_at"]),
        Index(value = ["received_at"]),
        Index(value = ["entity_type", "event_time_epoch"]),
        Index(value = ["event_id", "event_time_epoch"]),
        Index(value = ["thing_id", "occurred_at_epoch", "event_time_epoch"]),
    ]
)
data class LegacyMessageV36ShapeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id")
    val messageId: String?,
    val title: String,
    val body: String,
    val channel: String?,
    val url: String?,
    @ColumnInfo(name = "is_read")
    val isRead: Boolean,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    val status: String,
    @ColumnInfo(name = "decryption_state")
    val decryptionState: String?,
    @ColumnInfo(name = "notification_id")
    val notificationId: String?,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "body_preview")
    val bodyPreview: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String?,
    @ColumnInfo(name = "thing_id")
    val thingId: String?,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
    @ColumnInfo(name = "occurred_at_epoch")
    val occurredAtEpoch: Long?,
    @ColumnInfo(name = "list_payload_json", defaultValue = "'{}'")
    val listPayloadJson: String = "{}",
)

@Entity(
    tableName = "event_change_logs",
    indices = [
        Index(value = ["delivery_id"]),
        Index(value = ["event_id", "event_time_epoch"]),
        Index(value = ["channel"]),
        Index(value = ["received_at"]),
    ],
)
data class LegacyEventChangeV36ShapeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "op_id")
    val opId: String?,
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String?,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "thing_id")
    val thingId: String?,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
)

@Entity(
    tableName = "thing_change_logs",
    indices = [
        Index(value = ["delivery_id"]),
        Index(value = ["thing_id", "observed_time_epoch", "event_time_epoch"]),
        Index(value = ["channel"]),
        Index(value = ["received_at"]),
    ],
)
data class LegacyThingChangeV36ShapeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "op_id")
    val opId: String?,
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String?,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String?,
    @ColumnInfo(name = "thing_id")
    val thingId: String,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
    @ColumnInfo(name = "observed_time_epoch")
    val observedTimeEpoch: Long?,
)

@Entity(
    tableName = "thing_sub_messages",
    indices = [
        Index(value = ["message_id"], unique = true, name = "index_thing_sub_messages_message_id_unique"),
        Index(value = ["thing_id", "occurred_at_epoch", "event_time_epoch"]),
        Index(value = ["channel"]),
        Index(value = ["received_at"]),
    ],
)
data class LegacyThingSubMessageV36ShapeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id")
    val messageId: String?,
    val title: String,
    val body: String,
    val channel: String?,
    val url: String?,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    val status: String,
    @ColumnInfo(name = "decryption_state")
    val decryptionState: String?,
    @ColumnInfo(name = "notification_id")
    val notificationId: String?,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "body_preview")
    val bodyPreview: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String?,
    @ColumnInfo(name = "thing_id")
    val thingId: String?,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
    @ColumnInfo(name = "occurred_at_epoch")
    val occurredAtEpoch: Long?,
)

@Fts4(contentEntity = LegacyMessageV21Entity::class)
@Entity(tableName = "message_fts")
data class LegacyMessageFtsV21(
    val title: String,
    val body: String,
    val channel: String?,
)

@Fts4(contentEntity = LegacyMessageV36ShapeEntity::class)
@Entity(tableName = "message_fts")
data class LegacyMessageFtsV24(
    val title: String,
    val body: String,
    val channel: String?,
)

@Entity(
    tableName = "pending_thing_events",
    indices = [
        Index(value = ["thing_id", "event_time_epoch", "received_at"]),
        Index(value = ["delivery_id"]),
    ],
)
data class LegacyPendingThingEventV33Entity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "op_id")
    val opId: String?,
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String?,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "thing_id")
    val thingId: String,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
)

@Entity(
    tableName = "thing_sub_events",
    indices = [
        Index(value = ["delivery_id"]),
        Index(value = ["thing_id", "event_time_epoch"]),
        Index(value = ["event_id", "event_time_epoch"]),
        Index(value = ["channel"]),
        Index(value = ["received_at"]),
    ],
)
data class LegacyThingSubEventV33Entity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    val channel: String?,
    val title: String,
    val body: String,
    @ColumnInfo(name = "raw_payload_json")
    val rawPayloadJson: String,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "op_id")
    val opId: String?,
    @ColumnInfo(name = "delivery_id")
    val deliveryId: String?,
    @ColumnInfo(name = "server_id")
    val serverId: String?,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "thing_id")
    val thingId: String,
    @ColumnInfo(name = "event_state")
    val eventState: String?,
    @ColumnInfo(name = "event_time_epoch")
    val eventTimeEpoch: Long?,
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
        LegacyEventChangeV36ShapeEntity::class,
        LegacyThingChangeV36ShapeEntity::class,
        LegacyThingSubEventV33Entity::class,
        TopLevelEventHeadEntity::class,
        ThingHeadEntity::class,
        LegacyThingSubMessageV36ShapeEntity::class,
        PendingThingMessageEntity::class,
        LegacyPendingThingEventV33Entity::class,
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
        LegacyEventChangeV36ShapeEntity::class,
        LegacyThingChangeV36ShapeEntity::class,
        LegacyThingSubEventV33Entity::class,
        TopLevelEventHeadEntity::class,
        ThingHeadEntity::class,
        LegacyThingSubMessageV36ShapeEntity::class,
        PendingThingMessageEntity::class,
        LegacyPendingThingEventV33Entity::class,
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
        LegacyMessageV36ShapeEntity::class,
        MessageMetadataIndexEntity::class,
        LegacyMessageFtsV24::class,
        MessageChannelStatsEntity::class,
        MessageGlobalStatsEntity::class,
        MessageStoreRevisionEntity::class,
        MessageDerivedStateEntity::class,
        LegacyInboundDeliveryLedgerV25Entity::class,
        LegacyInboundDeliveryAckOutboxV24Entity::class,
        OperationLedgerEntity::class,
        LegacyEventChangeV36ShapeEntity::class,
        LegacyThingChangeV36ShapeEntity::class,
        LegacyThingSubEventV33Entity::class,
        TopLevelEventHeadEntity::class,
        ThingHeadEntity::class,
        LegacyThingSubMessageV36ShapeEntity::class,
        PendingThingMessageEntity::class,
        LegacyPendingThingEventV33Entity::class,
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
        LegacyMessageV36ShapeEntity::class,
        MessageMetadataIndexEntity::class,
        LegacyMessageFtsV24::class,
        MessageChannelStatsEntity::class,
        MessageGlobalStatsEntity::class,
        MessageStoreRevisionEntity::class,
        MessageDerivedStateEntity::class,
        LegacyInboundDeliveryLedgerV25Entity::class,
        LegacyInboundDeliveryAckOutboxV25Entity::class,
        OperationLedgerEntity::class,
        LegacyEventChangeV36ShapeEntity::class,
        LegacyThingChangeV36ShapeEntity::class,
        LegacyThingSubEventV33Entity::class,
        TopLevelEventHeadEntity::class,
        ThingHeadEntity::class,
        LegacyThingSubMessageV36ShapeEntity::class,
        PendingThingMessageEntity::class,
        LegacyPendingThingEventV25Entity::class,
        ChannelSubscriptionEntity::class,
        AppSettingsEntity::class,
    ],
    version = 25,
    exportSchema = false,
)
abstract class LegacyPushGoV25Database : RoomDatabase()
