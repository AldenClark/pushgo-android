package io.ethan.pushgo.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.R
import io.ethan.pushgo.data.db.PendingLocalDeletionDao
import io.ethan.pushgo.data.db.PendingLocalDeletionEntity
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.testing.NotificationPermissionTestSupport
import io.ethan.pushgo.ui.PendingLocalDeletionCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@Database(
    entities = [PendingLocalDeletionEntity::class, io.ethan.pushgo.data.db.ChannelSubscriptionEntity::class],
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
    fun oldQueuedUndoRequestSurvivesReopenWithoutForceClaimOrInventedConsent() = runBlocking {
        var database = openDatabase()
        var repository = RoomPendingLocalDeletionRepository(database, database.pendingLocalDeletionDao())
        val id = "186df7cc-ae66-4e2a-8230-7e0e1dd799e1"
        val old = repository.enqueue("old", PendingLocalDeletionOperation.messages(setOf(id)), 1_000L, 120_000L)
        val original = database.pendingLocalDeletionDao().findById(old.id)
        assertEquals(PendingLocalDeletionState.NEEDS_CONFIRMATION, old.state)
        assertTrue(repository.loadActive().isEmpty())
        assertEquals(null, repository.claim(old.id, 2_000L, true))
        database.close()
        database = openDatabase()
        repository = RoomPendingLocalDeletionRepository(database, database.pendingLocalDeletionDao())
        assertEquals(null, repository.claim(old.id, 500_000L, true))
        assertEquals(0, repository.recoverInterruptedClaims(500_000L))
        assertFalse(repository.retryFailed(old.id, 500_000L))
        assertEquals(original, database.pendingLocalDeletionDao().findById(old.id))
        assertTrue(repository.cancelUnconfirmed(old.id))
        assertFalse(repository.cancelUnconfirmed(old.id))
        database.close()
    }

    @Test
    fun corruptAndInterruptedOldRowsRemainExactAndCannotCancelRetryOrClaim() = runBlocking {
        val database = openDatabase()
        val dao = database.pendingLocalDeletionDao()
        val repository = RoomPendingLocalDeletionRepository(database, dao)
        val seed = PendingLocalDeletionEntity(summary = "original", operationKind = "CHANNEL", targetIdsJson = "[\"c1\"]",
            expectedGatewayUrl = "https://original.example", expectedUpdatedAt = 42L, expectedUseProvider = false,
            requestedAtEpochMillis = 100L, undoDeadlineEpochMillis = 5100L, state = "PENDING", attemptCount = 0,
            nextAttemptAtEpochMillis = 100L, updatedAtEpochMillis = 100L, lastError = "original failure")
        for (bad in listOf(seed.copy(targetIdsJson = "not-json"), seed.copy(operationKind = "UNKNOWN"),
            seed.copy(state = "UNKNOWN"), seed.copy(state = "COMMITTING", attemptCount = 1),
            seed.copy(state = "FAILED", attemptCount = 2), seed.copy(confirmationProven = true, targetIdsJson = "corrupt"),
            seed.copy(confirmationProven = true, state = "COMMITTING", attemptCount = 1, targetIdsJson = "corrupt"))) {
            val id = dao.insert(bad)
            val original = dao.findById(id)
            assertFalse(repository.cancelUnconfirmed(id))
            assertFalse(repository.retryFailed(id, 9000L))
            assertEquals(null, repository.claim(id, 9000L, true))
            repository.recoverInterruptedClaims(9000L)
            assertEquals(original, dao.findById(id))
        }
        assertTrue(repository.loadActive().isEmpty())
        assertEquals(7, repository.loadFailures().size)
        assertTrue(repository.loadFailures().all { it.state == PendingLocalDeletionState.QUARANTINED })
        database.close()
    }

    @Test
    fun concurrentCancellationOfDecodedUnstartedRequestHasOneWinner() = runBlocking {
        val database = openDatabase()
        val repository = RoomPendingLocalDeletionRepository(database, database.pendingLocalDeletionDao())
        val old = repository.enqueue("event no incarnation", PendingLocalDeletionOperation.events(setOf("event-1")), 1000L, 5000L)
        coroutineScope {
            val cancelled = listOf(async(Dispatchers.Default) { repository.cancelUnconfirmed(old.id) },
                async(Dispatchers.Default) { repository.cancelUnconfirmed(old.id) }).map { it.await() }
            assertEquals(1, cancelled.count { it })
        }
        assertTrue(repository.loadFailures().isEmpty())
        database.close()
    }

    @Test
    fun concurrentCurrentlyConfirmedChannelClaimsHaveOneOwner() = runBlocking {
        val database = openDatabase()
        val repository = RoomPendingLocalDeletionRepository(database, database.pendingLocalDeletionDao())
        // Explicit current-confirmation fixture, distinct from every old Undo row above.
        val operation = PendingLocalDeletionOperation.channel("confirmed", "https://captured.example", 42L, false)
        val row = repository.enqueue("confirmed", operation, 1000L, 0L, confirmationProven = true)
        coroutineScope {
            val claims = listOf(async(Dispatchers.Default) { repository.claim(row.id, 1001L, true) },
                async(Dispatchers.Default) { repository.claim(row.id, 1001L, true) }).map { it.await() }
            assertEquals(1, claims.count { it != null })
            assertEquals(operation, claims.single { it != null }!!.operation)
        }
        assertTrue(repository.completeClaimed(row.id))
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
            confirmationProven = true, // Synthetic current channel confirmation, not old enqueue/Undo.
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
        assertTrue(repository.loadActive().isEmpty())
        assertTrue(repository.loadFailures().any { it.id == followUp.id })
        database.close()
    }

    @Test
    fun channelNotificationRetryUsesPersistedOperationAfterDatabaseReopen() = runBlocking {
        requireNotificationPermission()
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
            confirmationProven = true, // Synthetic current channel confirmation, not old enqueue/Undo.
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
                completionDispatcher = Dispatchers.Unconfined,
            )
            assertFalse(restartedCoordinator.drainRecoverable())
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
        requireNotificationPermission()
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

    private fun requireNotificationPermission() {
        NotificationPermissionTestSupport.requireEnabled(context)
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
