package io.ethan.pushgo.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.util.SilentSink
import java.io.File

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
        AppSettingsEntity::class,
    ],
    version = 22,
    exportSchema = false,
)
abstract class PushGoDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun messageChannelStatsDao(): MessageChannelStatsDao
    abstract fun messageMetadataIndexDao(): MessageMetadataIndexDao
    abstract fun inboundDeliveryLedgerDao(): InboundDeliveryLedgerDao
    abstract fun inboundDeliveryAckOutboxDao(): InboundDeliveryAckOutboxDao
    abstract fun operationLedgerDao(): OperationLedgerDao
    abstract fun eventChangeLogDao(): EventChangeLogDao
    abstract fun thingChangeLogDao(): ThingChangeLogDao
    abstract fun thingSubEventDao(): ThingSubEventDao
    abstract fun topLevelEventHeadDao(): TopLevelEventHeadDao
    abstract fun thingHeadDao(): ThingHeadDao
    abstract fun thingSubMessageDao(): ThingSubMessageDao
    abstract fun pendingThingMessageDao(): PendingThingMessageDao
    abstract fun pendingThingEventDao(): PendingThingEventDao
    abstract fun channelSubscriptionDao(): ChannelSubscriptionDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        private const val TAG = "PushGoDatabase"
        private const val DATABASE_NAME = "pushgo.db"
        private val LEGACY_DATABASE_NAMES = listOf("pushgo-v22.db", "pushgo-v21.db")

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE app_settings
                    ADD COLUMN update_auto_check_enabled INTEGER NOT NULL DEFAULT 1
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE app_settings
                    ADD COLUMN update_beta_channel_enabled INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE app_settings
                    ADD COLUMN update_skipped_version_code INTEGER
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE app_settings
                    ADD COLUMN update_last_prompted_version_code INTEGER
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE app_settings
                    ADD COLUMN update_prompt_cooldown_until INTEGER
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE app_settings
                    ADD COLUMN update_prompt_dismiss_count INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    ALTER TABLE app_settings
                    ADD COLUMN update_last_check_at INTEGER
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context): PushGoDatabase {
            return runCatching { newBuilder(context).build() }.getOrElse { error ->
                PushGoAutomation.recordRuntimeError(
                    source = "storage.database.open",
                    error = error,
                    category = "storage",
                )
                throw error
            }
        }

        private fun newBuilder(context: Context): RoomDatabase.Builder<PushGoDatabase> {
            prepareDatabaseFile(context)
            return Room.databaseBuilder(context, PushGoDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_21_22)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // Enforce messageId as a required business key at DB boundary.
                        db.execSQL(
                            """
                            CREATE UNIQUE INDEX IF NOT EXISTS index_messages_message_id_unique
                            ON messages(message_id)
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE UNIQUE INDEX IF NOT EXISTS index_thing_sub_messages_message_id_unique
                            ON thing_sub_messages(message_id)
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TRIGGER IF NOT EXISTS messages_message_id_required_on_insert
                            BEFORE INSERT ON messages
                            WHEN NEW.message_id IS NULL OR LENGTH(TRIM(NEW.message_id)) = 0
                            BEGIN
                                SELECT RAISE(ABORT, 'messages.message_id is required');
                            END
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TRIGGER IF NOT EXISTS messages_message_id_required_on_update
                            BEFORE UPDATE OF message_id ON messages
                            WHEN NEW.message_id IS NULL OR LENGTH(TRIM(NEW.message_id)) = 0
                            BEGIN
                                SELECT RAISE(ABORT, 'messages.message_id is required');
                            END
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TRIGGER IF NOT EXISTS thing_sub_messages_message_id_required_on_insert
                            BEFORE INSERT ON thing_sub_messages
                            WHEN NEW.message_id IS NULL OR LENGTH(TRIM(NEW.message_id)) = 0
                            BEGIN
                                SELECT RAISE(ABORT, 'thing_sub_messages.message_id is required');
                            END
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TRIGGER IF NOT EXISTS thing_sub_messages_message_id_required_on_update
                            BEFORE UPDATE OF message_id ON thing_sub_messages
                            WHEN NEW.message_id IS NULL OR LENGTH(TRIM(NEW.message_id)) = 0
                            BEGIN
                                SELECT RAISE(ABORT, 'thing_sub_messages.message_id is required');
                            END
                            """.trimIndent()
                        )
                        db.query("PRAGMA foreign_keys=ON").close()
                        db.query("PRAGMA journal_mode=WAL").close()
                        db.query("PRAGMA synchronous=NORMAL").close()
                        db.query("PRAGMA busy_timeout=5000").close()
                        // Let SQLite update planner statistics and opportunistically optimize indices.
                        db.query("PRAGMA optimize").close()
                    }
                })
        }

        private fun prepareDatabaseFile(context: Context) {
            val target = context.getDatabasePath(DATABASE_NAME)
            if (target.exists()) {
                return
            }
            val source = selectLegacyDatabase(context) ?: return
            copyDatabaseFamily(source, target)
        }

        private fun selectLegacyDatabase(context: Context): File? {
            val candidates = LEGACY_DATABASE_NAMES
                .map(context::getDatabasePath)
                .filter(File::exists)
            if (candidates.isEmpty()) {
                return null
            }
            val selected = LegacyDatabaseBootstrapPolicy.pickBest(
                candidates.map { file ->
                    LegacyDatabaseBootstrapPolicy.Candidate(
                        file = file,
                        contentScore = databaseContentScore(file),
                        priority = legacyPriority(file.name),
                    )
                }
            )?.file
            if (selected != null) {
                SilentSink.i(TAG, "bootstrap database from legacy file=${selected.name}")
            }
            return selected
        }

        private fun legacyPriority(name: String): Int {
            return LEGACY_DATABASE_NAMES.size - LEGACY_DATABASE_NAMES.indexOf(name)
        }

        private fun databaseContentScore(file: File): Int {
            return runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    listOf(
                        "messages",
                        "top_level_event_heads",
                        "thing_heads",
                        "thing_sub_messages",
                        "thing_sub_events",
                        "channel_subscriptions",
                        "app_settings",
                    ).sumOf { table -> countRows(db, table) }
                }
            }.getOrElse { error ->
                SilentSink.w(TAG, "inspect legacy database failed: ${file.name}: ${error.message}", error)
                0
            }
        }

        private fun countRows(db: SQLiteDatabase, table: String): Int {
            return db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }

        private fun copyDatabaseFamily(source: File, target: File) {
            target.parentFile?.mkdirs()
            copyIfExists(source, target)
            copyIfExists(sidecarFile(source, "-wal"), sidecarFile(target, "-wal"))
            copyIfExists(sidecarFile(source, "-shm"), sidecarFile(target, "-shm"))
        }

        private fun copyIfExists(source: File, target: File) {
            if (!source.exists()) {
                return
            }
            source.copyTo(target, overwrite = false)
        }

        private fun sidecarFile(base: File, suffix: String): File {
            return File(base.parentFile, base.name + suffix)
        }

    }
}
