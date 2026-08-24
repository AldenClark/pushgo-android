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
    fun durableRoomRow_isRecoveredThroughTheRealWorkerAfterStorageReopen() = runBlocking {
        val firstContainer = app.container
        val firstRepository = RoomPendingLocalDeletionRepository(
            firstContainer.database,
            firstContainer.database.pendingLocalDeletionDao(),
        )
        firstRepository.enqueue(
            summary = "worker boundary",
            operation = PendingLocalDeletionOperation.messages(setOf("already-absent-message")),
            requestedAtEpochMillis = 1L,
            undoWindowMillis = 0L,
        )
        assertTrue(firstRepository.loadActive().isNotEmpty())

        app.releaseStorageForInstrumentationTest()
        WorkManagerPendingLocalDeletionDrainScheduler(context).scheduleImmediate()

        var remainingCount = Int.MAX_VALUE
        withTimeout(15_000L) {
            do {
                delay(50L)
                val reopened = app.container
                val reopenedRepository = RoomPendingLocalDeletionRepository(
                    reopened.database,
                    reopened.database.pendingLocalDeletionDao(),
                )
                remainingCount = reopenedRepository.loadActive().size
            } while (remainingCount != 0)
        }
        assertTrue(
            "storageError=${app.startupStorageErrorMessage()} remainingCount=$remainingCount",
            remainingCount == 0,
        )
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
