package io.ethan.pushgo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.ethan.pushgo.automation.PushGoAutomation

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
        // No backward-compatibility for old local data: always use current store file.
        private const val DATABASE_NAME = "pushgo-v22.db"

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
            return Room.databaseBuilder(context, PushGoDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
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

    }
}
