package io.ethan.pushgo.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.R
import io.ethan.pushgo.data.db.PendingLocalDeletionDao
import io.ethan.pushgo.data.db.PendingLocalDeletionEntity
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.ui.PendingLocalDeletionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@Database(
    entities = [PendingLocalDeletionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PendingLocalDeletionTestDatabase : RoomDatabase() {
    abstract fun pendingLocalDeletionDao(): PendingLocalDeletionDao
}

@RunWith(AndroidJUnit4::class)
class PendingLocalDeletionRoomDeviceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        (TARGET_NOTIFICATION_IDS + CONTROL_NOTIFICATION_ID).forEach(notificationManager()::cancel)
        notificationManager().deleteNotificationChannel(TEST_NOTIFICATION_CHANNEL)
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun pendingAndCommittingRowsSurviveCloseAndRecover() = runBlocking {
        var database = openDatabase()
        var repository = RoomPendingLocalDeletionRepository(
            database = database,
            dao = database.pendingLocalDeletionDao(),
        )
        val record = repository.enqueue(
            summary = "message",
            operation = PendingLocalDeletionOperation.messages(setOf("m1", "m2")),
            requestedAtEpochMillis = 1_000L,
            undoWindowMillis = 5_000L,
        )
        repository.claim(record.id, nowEpochMillis = 2_000L, force = true)
        database.close()

        database = openDatabase()
        repository = RoomPendingLocalDeletionRepository(
            database = database,
            dao = database.pendingLocalDeletionDao(),
        )
        val reopened = repository.loadActive().single()
        assertEquals(PendingLocalDeletionState.COMMITTING, reopened.state)
        assertEquals(setOf("m1", "m2"), reopened.operation.targetIds)

        assertEquals(1, repository.recoverInterruptedClaims(nowEpochMillis = 3_000L))
        val recovered = repository.loadActive().single()
        assertEquals(PendingLocalDeletionState.PENDING, recovered.state)
        assertEquals(3_000L, recovered.nextAttemptAtEpochMillis)
        database.close()
    }

    @Test
    fun cancelIsDurableAcrossCloseAndReopen() = runBlocking {
        var database = openDatabase()
        var repository = RoomPendingLocalDeletionRepository(
            database = database,
            dao = database.pendingLocalDeletionDao(),
        )
        val record = repository.enqueue(
            summary = "event",
            operation = PendingLocalDeletionOperation.events(setOf("e1")),
            requestedAtEpochMillis = 1_000L,
            undoWindowMillis = 5_000L,
        )
        assertTrue(repository.cancelPending(record.id))
        database.close()

        database = openDatabase()
        repository = RoomPendingLocalDeletionRepository(
            database = database,
            dao = database.pendingLocalDeletionDao(),
        )
        assertTrue(repository.loadActive().isEmpty())
        database.close()
    }

    @Test
    fun retryPendingCannotBeCancelledAfterFirstClaim() = runBlocking {
        val database = openDatabase()
        val repository = RoomPendingLocalDeletionRepository(
            database = database,
            dao = database.pendingLocalDeletionDao(),
        )
        val record = repository.enqueue(
            summary = "event",
            operation = PendingLocalDeletionOperation.events(setOf("event-1")),
            requestedAtEpochMillis = 1_000L,
            undoWindowMillis = 5_000L,
        )
        repository.claim(record.id, nowEpochMillis = 1_500L, force = true)
        assertTrue(repository.retryClaimed(record.id, 2_000L, 10_000L, "offline"))

        assertFalse(repository.cancelPending(record.id))
        assertEquals(1, repository.loadActive().single().attemptCount)
        database.close()
    }

    @Test
    fun permanentFailureIsNotActiveAfterReopenAndDoesNotBlockNextIntent() = runBlocking {
        var database = openDatabase()
        var repository = RoomPendingLocalDeletionRepository(
            database = database,
            dao = database.pendingLocalDeletionDao(),
        )
        val failed = repository.enqueue(
            summary = "conflicting channel",
            operation = PendingLocalDeletionOperation.channel(
                id = TARGET_CHANNEL_ID,
                expectedGatewayUrl = "https://gateway.example",
                expectedUpdatedAt = 1L,
                expectedUseProvider = true,
            ),
            requestedAtEpochMillis = 1_000L,
            undoWindowMillis = 5_000L,
        )
        repository.claim(failed.id, nowEpochMillis = 2_000L, force = true)
        assertTrue(repository.failClaimed(failed.id, 3_000L, "newer subscription version"))
        database.close()

        database = openDatabase()
        repository = RoomPendingLocalDeletionRepository(
            database = database,
            dao = database.pendingLocalDeletionDao(),
        )
        assertTrue(repository.loadActive().isEmpty())
        val followUp = repository.enqueue(
            summary = "message",
            operation = PendingLocalDeletionOperation.messages(setOf("m1")),
            requestedAtEpochMillis = 4_000L,
            undoWindowMillis = 5_000L,
        )
        assertEquals(followUp.id, repository.loadActive().single().id)
        database.close()
    }

    @Test
    fun channelNotificationRetryUsesPersistedOperationAfterDatabaseReopen() = runBlocking {
        grantNotificationPermissionIfNeeded()
        val operation = PendingLocalDeletionOperation.channel(
            id = TARGET_CHANNEL_ID,
            expectedGatewayUrl = "https://gateway.example",
            expectedUpdatedAt = 42L,
            expectedUseProvider = true,
        )
        var database = openDatabase()
        var repository = RoomPendingLocalDeletionRepository(database, database.pendingLocalDeletionDao())
        val record = repository.enqueue(
            summary = "channel",
            operation = operation,
            requestedAtEpochMillis = 1_000L,
            undoWindowMillis = 5_000L,
        )
        repository.claim(record.id, nowEpochMillis = 2_000L, force = true)
        assertTrue(
            repository.retryClaimed(
                id = record.id,
                nowEpochMillis = 3_000L,
                nextAttemptAtEpochMillis = 3_000L,
                lastError = "notification reconciliation failed after local commit",
            )
        )
        database.close()

        // These system notifications survive independently of the Room data that has committed.
        postChannelNotifications()
        val postedIds = awaitActiveNotificationIds { ids ->
            TARGET_NOTIFICATION_IDS.all(ids::contains) && CONTROL_NOTIFICATION_ID in ids
        }
        assertTrue(TARGET_NOTIFICATION_IDS.all(postedIds::contains))
        assertTrue(CONTROL_NOTIFICATION_ID in postedIds)

        database = openDatabase()
        repository = RoomPendingLocalDeletionRepository(database, database.pendingLocalDeletionDao())
        val restartedBackend = AlreadyDeletedNotificationReplayBackend(context)
        val restartedJob = SupervisorJob()
        val restartedScope = CoroutineScope(restartedJob + Dispatchers.Default)
        try {
            val restartedCoordinator = PendingLocalDeletionCoordinator(
                appScope = restartedScope,
                repository = repository,
                operationExecutor = PendingLocalDeletionExecutor { persistedOperation ->
                    DurablePendingChannelDeletionExecutor(restartedBackend).execute(persistedOperation)
                },
                wallClockEpochMillis = { 6_000L },
                elapsedRealtimeMillis = { 6_000L },
                completionDispatcher = Dispatchers.Unconfined,
            )
            assertFalse(restartedCoordinator.drainRecoverable(force = true))
        } finally {
            restartedJob.cancelAndJoin()
        }

        val activeIds = awaitActiveNotificationIds { ids ->
            TARGET_NOTIFICATION_IDS.none(ids::contains) && CONTROL_NOTIFICATION_ID in ids
        }
        assertTrue(TARGET_NOTIFICATION_IDS.none(activeIds::contains))
        assertTrue(CONTROL_NOTIFICATION_ID in activeIds)
        assertEquals(1, restartedBackend.cleanupCredentialCount)
        assertTrue(repository.loadActive().isEmpty())
        database.close()
    }

    @Test
    fun entityNotificationReplayUsesActiveGroupMetadataAfterRoomRowsAreGone() {
        grantNotificationPermissionIfNeeded()
        val manager = notificationManager()
        manager.createNotificationChannel(
            NotificationChannel(TEST_NOTIFICATION_CHANNEL, "Deletion replay", NotificationManager.IMPORTANCE_LOW)
        )
        val prefix = "io.ethan.pushgo.notifications.groups."
        val groups = listOf(
            "${prefix}event|channel=$TARGET_CHANNEL_ID|event=event-1",
            "${prefix}thing|channel=$TARGET_CHANNEL_ID|event=event-1|thing=thing-1",
            "${prefix}thing|channel=$TARGET_CHANNEL_ID|event=event-2|thing=thing-1",
            "${prefix}event|channel=$TARGET_CHANNEL_ID|event=event-2",
        )
        TARGET_NOTIFICATION_IDS.zip(groups).forEach { (id, group) ->
            manager.notify(
                id,
                Notification.Builder(context, TEST_NOTIFICATION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_pushgo)
                    .setContentTitle("target-$id")
                    .setGroup(group)
                    .build(),
            )
        }
        val postedIds = awaitActiveNotificationIds { activeIds -> TARGET_NOTIFICATION_IDS.all(activeIds::contains) }
        assertTrue(TARGET_NOTIFICATION_IDS.all(postedIds::contains))

        NotificationHelper.cancelEventNotifications(context, setOf("event-1"))
        var activeIds = awaitActiveNotificationIds { ids ->
            TARGET_NOTIFICATION_IDS[0] !in ids &&
                TARGET_NOTIFICATION_IDS[1] !in ids &&
                TARGET_NOTIFICATION_IDS[2] in ids &&
                TARGET_NOTIFICATION_IDS[3] in ids
        }
        assertFalse(TARGET_NOTIFICATION_IDS[0] in activeIds)
        assertFalse(TARGET_NOTIFICATION_IDS[1] in activeIds)
        assertTrue(TARGET_NOTIFICATION_IDS[2] in activeIds)
        assertTrue(TARGET_NOTIFICATION_IDS[3] in activeIds)

        NotificationHelper.cancelThingNotifications(context, setOf("thing-1"))
        activeIds = awaitActiveNotificationIds { ids ->
            TARGET_NOTIFICATION_IDS[2] !in ids && TARGET_NOTIFICATION_IDS[3] in ids
        }
        assertFalse(TARGET_NOTIFICATION_IDS[2] in activeIds)
        assertTrue(TARGET_NOTIFICATION_IDS[3] in activeIds)
    }

    private fun openDatabase(): PendingLocalDeletionTestDatabase = Room.databaseBuilder(
        context,
        PendingLocalDeletionTestDatabase::class.java,
        DATABASE_NAME,
    ).build()

    private fun grantNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            .close()
    }

    private fun postChannelNotifications() {
        val manager = notificationManager()
        manager.createNotificationChannel(
            NotificationChannel(TEST_NOTIFICATION_CHANNEL, "Deletion replay", NotificationManager.IMPORTANCE_LOW)
        )
        val prefix = "io.ethan.pushgo.notifications.groups."
        val targetGroups = listOf(
            "${prefix}message|channel=$TARGET_CHANNEL_ID",
            "${prefix}event|channel=$TARGET_CHANNEL_ID|event=e1",
            "${prefix}thing|channel=$TARGET_CHANNEL_ID|event=e1|thing=t1",
            "${prefix}message|channel=$TARGET_CHANNEL_ID",
        )
        TARGET_NOTIFICATION_IDS.zip(targetGroups).forEachIndexed { index, (id, group) ->
            val builder = Notification.Builder(context, TEST_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_pushgo)
                .setContentTitle("target-$id")
                .setGroup(group)
            if (index == targetGroups.lastIndex) builder.setGroupSummary(true)
            manager.notify(id, builder.build())
        }
        manager.notify(
            CONTROL_NOTIFICATION_ID,
            Notification.Builder(context, TEST_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_pushgo)
                .setContentTitle("control")
                .setGroup("${prefix}message|channel=$CONTROL_CHANNEL_ID")
                .build(),
        )
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun awaitActiveNotificationIds(
        timeoutMillis: Long = 2_000L,
        predicate: (Set<Int>) -> Boolean,
    ): Set<Int> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var activeIds = emptySet<Int>()
        do {
            activeIds = notificationManager().activeNotifications.mapTo(mutableSetOf()) { it.id }
            if (predicate(activeIds)) return activeIds
            SystemClock.sleep(20L)
        } while (SystemClock.elapsedRealtime() < deadline)
        return activeIds
    }

    private class AlreadyDeletedNotificationReplayBackend(
        private val context: Context,
    ) : PendingChannelDeletionBackend {
        var cleanupCredentialCount = 0

        override suspend fun inspect(operation: PendingLocalDeletionOperation) =
            PendingChannelDeletionTargetState.ALREADY_DELETED

        override suspend fun reconcileDeletedChannelNotifications(operation: PendingLocalDeletionOperation) {
            NotificationHelper.cancelChannelNotifications(context, operation.targetIds.single())
        }

        override suspend fun cleanupCredential(operation: PendingLocalDeletionOperation) {
            cleanupCredentialCount += 1
        }

        override fun currentlyUsesProvider() = error("unexpected remote replay")
        override suspend fun existingProviderToken(): String? = error("unexpected remote replay")
        override suspend fun syncProviderToken(token: String, operation: PendingLocalDeletionOperation) =
            error("unexpected remote replay")
        override suspend fun unsubscribeProvider(token: String, operation: PendingLocalDeletionOperation) =
            error("unexpected remote replay")
        override suspend fun unsubscribePrivate(operation: PendingLocalDeletionOperation) =
            error("unexpected remote replay")
        override suspend fun deleteLocal(operation: PendingLocalDeletionOperation) =
            error("unexpected local replay")
        override suspend fun currentCredential(operation: PendingLocalDeletionOperation): String? =
            error("unexpected compensation")
        override suspend fun restoreProvider(
            token: String,
            credential: String,
            operation: PendingLocalDeletionOperation,
        ) = error("unexpected compensation")
        override suspend fun restorePrivate(credential: String, operation: PendingLocalDeletionOperation) =
            error("unexpected compensation")
    }

    private companion object {
        const val DATABASE_NAME = "pending-local-deletion-test.db"
        const val TEST_NOTIFICATION_CHANNEL = "pending-local-deletion-replay-test"
        const val TARGET_CHANNEL_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        const val CONTROL_CHANNEL_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        const val CONTROL_NOTIFICATION_ID = 8_105
        val TARGET_NOTIFICATION_IDS = listOf(8_101, 8_102, 8_103, 8_104)
    }
}
