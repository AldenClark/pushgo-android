package io.ethan.pushgo.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.PendingLocalDeletionOperation
import io.ethan.pushgo.data.RoomPendingLocalDeletionRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingLocalDeletionWorkBoundaryDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app: PushGoApp
        get() = context.applicationContext as PushGoApp

    @Before
    fun setUp() {
        cancelDeletionWork()
        app.releaseStorageForInstrumentationTest()
        cleanupDatabaseFamily()
    }

    @After
    fun tearDown() {
        // Constructing the production scheduler registers a periodic safety net whose
        // first run may start immediately. Stop and await every tagged worker before
        // closing/deleting its Room database; otherwise teardown itself can race a
        // legitimate worker opening or observing the production database.
        cancelDeletionWork()
        app.releaseStorageForInstrumentationTest()
        cleanupDatabaseFamily()
    }

    @Test
    fun oldEnqueueBeforeUndoCannotDeleteContentThroughRealWorkerAfterReopen() = runBlocking {
        val firstContainer = app.container
        val firstRepository = RoomPendingLocalDeletionRepository(firstContainer.database, firstContainer.database.pendingLocalDeletionDao())
        val id = java.util.UUID.randomUUID().toString()
        val message = io.ethan.pushgo.data.model.PushMessage(id, id, "Worker must preserve", "Original content", null, null,
            false, java.time.Instant.now(), "{}", io.ethan.pushgo.data.model.MessageStatus.NORMAL, null, null, null)
        firstContainer.messageRepository.insert(message)
        val row = firstRepository.enqueue("old Undo not yet shown", PendingLocalDeletionOperation.messages(setOf(id)), 1L, 5000L)
        val original = firstContainer.database.pendingLocalDeletionDao().findById(row.id)
        assertTrue(firstRepository.loadActive().isEmpty())
        val workManager = WorkManager.getInstance(context)
        val before = workManager.getWorkInfosByTag(WorkManagerPendingLocalDeletionDrainScheduler.WORK_TAG).get(10, TimeUnit.SECONDS).map { it.id }.toSet()
        app.releaseStorageForInstrumentationTest()
        WorkManagerPendingLocalDeletionDrainScheduler(context).scheduleImmediate()
        withTimeout(15_000L) {
            while (workManager.getWorkInfosByTag(WorkManagerPendingLocalDeletionDrainScheduler.WORK_TAG).get(10, TimeUnit.SECONDS)
                    .none { it.id !in before && it.state == WorkInfo.State.SUCCEEDED }) delay(50L)
        }
        val reopened = app.container
        assertTrue(reopened.messageRepository.getById(id)?.body == message.body)
        assertTrue(reopened.database.pendingLocalDeletionDao().findById(row.id) == original)
        val recovered = RoomPendingLocalDeletionRepository(reopened.database, reopened.database.pendingLocalDeletionDao())
        assertTrue(recovered.loadFailures().single().id == row.id)
    }

    @Test
    fun explicitlyConfirmedChannelCleanupContinuesThroughRealWorkerAfterReopen() = runBlocking {
        val first = app.container
        val gateway = "https://captured.example"
        val target = io.ethan.pushgo.data.db.ChannelSubscriptionEntity(gateway, "worker-confirmed-channel", "captured target", 42L, null, true, 100L)
        val control = target.copy(channelId = "worker-control", isDeleted = false, deletedAt = null)
        first.database.channelSubscriptionDao().insert(target)
        first.database.channelSubscriptionDao().insert(control)
        val repository = RoomPendingLocalDeletionRepository(first.database, first.database.pendingLocalDeletionDao())
        // Explicit fixture: the current dialog confirmed this exact context, local
        // deletion committed, and process exit interrupted its remaining cleanup.
        val accepted = repository.enqueue("confirmed cleanup", PendingLocalDeletionOperation.channel(target.channelId, gateway, 42L, false),
            100L, 0L, confirmationProven = true)
        assertTrue(repository.loadActive().any { it.id == accepted.id })
        val workManager = WorkManager.getInstance(context)
        val before = workManager.getWorkInfosByTag(WorkManagerPendingLocalDeletionDrainScheduler.WORK_TAG).get(10, TimeUnit.SECONDS).map { it.id }.toSet()
        app.releaseStorageForInstrumentationTest()
        WorkManagerPendingLocalDeletionDrainScheduler(context).scheduleImmediate()
        withTimeout(15_000L) {
            while (workManager.getWorkInfosByTag(WorkManagerPendingLocalDeletionDrainScheduler.WORK_TAG).get(10, TimeUnit.SECONDS)
                    .none { it.id !in before && it.state == WorkInfo.State.SUCCEEDED }) delay(50L)
        }
        val reopened = app.container
        assertTrue(reopened.database.pendingLocalDeletionDao().findById(accepted.id) == null)
        assertTrue(reopened.database.channelSubscriptionDao().getById(gateway, target.channelId)?.isDeleted == true)
        assertTrue(reopened.database.channelSubscriptionDao().getById(gateway, control.channelId) == control)
    }

    @Test
    fun schedulerRegistersIndependentDelayedWakeupWithoutReplacingRunningWork() {
        val workManager = WorkManager.getInstance(context)
        val scheduler = WorkManagerPendingLocalDeletionDrainScheduler(context, clock = { 1_000L })
        val beforeIds = workManager
            .getWorkInfosByTag(WorkManagerPendingLocalDeletionDrainScheduler.WORK_TAG)
            .get(10, TimeUnit.SECONDS)
            .mapTo(mutableSetOf()) { it.id }

        scheduler.scheduleAt(epochMillis = 61_000L)
        scheduler.scheduleAt(epochMillis = 62_000L)

        val work = workManager
            .getWorkInfosByTag(WorkManagerPendingLocalDeletionDrainScheduler.WORK_TAG)
            .get(10, TimeUnit.SECONDS)
        val newlyRegistered = work.filter { it.id !in beforeIds }
        assertTrue(newlyRegistered.size == 2)
        assertTrue(newlyRegistered.all { it.state == WorkInfo.State.ENQUEUED })
    }

    private fun cleanupDatabaseFamily() {
        listOf("pushgo.db", "pushgo.db-wal", "pushgo.db-shm").forEach { name ->
            context.getDatabasePath(name).delete()
        }
    }

    private fun cancelDeletionWork() {
        WorkManager.getInstance(context).cancelAllWorkByTag(
            WorkManagerPendingLocalDeletionDrainScheduler.WORK_TAG,
        ).result.get(10, TimeUnit.SECONDS)
        runBlocking {
            withTimeout(10_000L) {
                while (PendingLocalDeletionDrainWorker.activeWorkerCountForTesting() != 0) {
                    delay(10L)
                }
            }
        }
    }
}
