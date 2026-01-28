package io.ethan.pushgo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        MessageFts::class,
        ChannelSubscriptionEntity::class,
        AppSettingsEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class PushGoDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun channelSubscriptionDao(): ChannelSubscriptionDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        fun build(context: Context): PushGoDatabase {
            return Room.databaseBuilder(context, PushGoDatabase::class.java, "pushgo.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
