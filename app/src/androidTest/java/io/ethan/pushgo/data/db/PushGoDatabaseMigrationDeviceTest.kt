package io.ethan.pushgo.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.AppContainer
import io.ethan.pushgo.data.model.KeyEncoding
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushGoDatabaseMigrationDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        cleanupDatabaseFamily("pushgo.db")
        cleanupDatabaseFamily("pushgo-v21.db")
        cleanupDatabaseFamily("pushgo-v22.db")
    }

    @After
    fun tearDown() {
        cleanupDatabaseFamily("pushgo.db")
        cleanupDatabaseFamily("pushgo-v21.db")
        cleanupDatabaseFamily("pushgo-v22.db")
    }

    @Test
    fun appContainer_bootstrapsFromLegacyV21AndPreservesBusinessData() = runBlocking {
        seedLegacyV21Database()

        val container = AppContainer(context)
        val subscriptions = container.channelStore.loadSubscriptions(GATEWAY_URL)
        val messages = container.messageRepository.getAll()

        assertEquals(GATEWAY_URL, container.settingsRepository.getServerAddress())
        assertEquals(true, container.settingsRepository.getUpdateAutoCheckEnabled())
        assertEquals(false, container.settingsRepository.getUpdateBetaChannelEnabled())
        assertEquals(1, subscriptions.size)
        assertEquals(CHANNEL_ID, subscriptions.single().channelId)
        assertEquals(1, messages.size)
        assertEquals(MESSAGE_ID, messages.single().messageId)
        assertEquals(22, readUserVersion(context.getDatabasePath("pushgo.db")))
        assertTrue(context.getDatabasePath("pushgo.db").exists())
        assertTrue(context.getDatabasePath("pushgo-v21.db").exists())

        container.database.close()
    }

    @Test
    fun appContainer_prefersDataRichV21WhenEmptyV22Exists() = runBlocking {
        seedLegacyV21Database()
        seedEmptyLegacyV22Database()

        val container = AppContainer(context)
        val messages = container.messageRepository.getAll()

        assertEquals(1, messages.size)
        assertEquals(MESSAGE_ID, messages.single().messageId)
        assertEquals(GATEWAY_URL, container.settingsRepository.getServerAddress())

        container.database.close()
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

@Database(
    entities = [
        MessageEntity::class,
        MessageMetadataIndexEntity::class,
        MessageFts::class,
        MessageChannelStatsEntity::class,
        InboundDeliveryLedgerEntity::class,
        InboundDeliveryAckOutboxEntity::class,
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
