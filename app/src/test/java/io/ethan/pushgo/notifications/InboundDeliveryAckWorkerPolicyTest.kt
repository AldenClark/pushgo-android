package io.ethan.pushgo.notifications

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

class InboundDeliveryAckWorkerPolicyTest {
    @Test
    fun drainSignal_replacesTheSingleUniqueWorkInsteadOfAppending() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            InboundDeliveryAckWorker.ACK_DRAIN_WORK_POLICY,
        )
    }

    @Test
    fun duplicateRecovery_keepsAnAlreadyRunningDrain() {
        assertEquals(
            ExistingWorkPolicy.KEEP,
            InboundDeliveryAckWorker.ACK_DRAIN_RECOVERY_WORK_POLICY,
        )
    }

    @Test
    fun directAckScheduling_repairsOnlyTheCommittedDuplicateHandoffGap() {
        assertEquals(
            DirectAckDrainSchedule.PRIMARY,
            directAckDrainSchedule(true, InboundPersistenceStatus.PERSISTED_MAIN),
        )
        assertEquals(
            DirectAckDrainSchedule.RECOVERY,
            directAckDrainSchedule(false, InboundPersistenceStatus.DUPLICATE),
        )
        assertEquals(
            DirectAckDrainSchedule.NONE,
            directAckDrainSchedule(false, InboundPersistenceStatus.PERSISTED_MAIN),
        )
    }

    @Test
    fun tombstoneMaintenance_drainsEveryFullBatchUntilTheFirstPartialBatch() = runBlocking {
        val observedLimits = mutableListOf<Int>()
        val results = ArrayDeque(listOf(200, 200, 17))

        val total = pruneAckedTombstonesInBatches { limit ->
            observedLimits += limit
            results.removeFirst()
        }

        assertEquals(417, total)
        assertEquals(listOf(200, 200, 200), observedLimits)
    }
}
