package io.ethan.pushgo.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.MessagePostProcessWorker
import io.ethan.pushgo.testing.QualityRuntime
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual WorkManager recovery scanner and locator Worker, ordinary container/no QA session.
 * A deliberately expired alert responsibility suppresses OS publication without permission changes.
 * Container recreation is not a claim of process-death or normal external startup recovery. */
@RunWith(AndroidJUnit4::class)
class PostProcessWorkManagerDeviceTest {
    @Test
    fun existingScannerAndActualWorkerRecoverCommittedCanonicalPendingAfterContainerRecreation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PushGoApp>()
        assertNull(QualityRuntime.currentSession())
        app.releaseStorageForInstrumentationTest()
        val container = app.container
        // This task-owned APK is installed on an empty synthetic AVD profile by UTP.
        // Never reset or silently adopt a database containing unrelated content.
        assertEquals(0L, container.messageRepository.totalCount().toLong())
        val id = "worker-canonical-synthetic"
        val raw = JSONObject().put("entity_type", "message").put("entity_id", id).put("message_id", id)
            .put("delivery_id", id).put("op_id", id).put("ttl", 1)
        val message = PushMessage(id, id, "Expired alert, durable body", "This body must remain after alert suppression",
            "synthetic", null, false, Instant.now(), raw.toString(), MessageStatus.NORMAL, null, null, "synthetic")
        // Explicit delayed-work fixture: a once-accepted alert is now past its absolute TTL.
        assertTrue(container.messageRepository.insertIncoming(message, postProcessFlags = POST_PROCESS_NOTIFICATION))
        assertEquals(POST_PROCESS_NOTIFICATION, container.database.messageDao().getById(id)?.postProcessPending)
        app.releaseStorageForInstrumentationTest()
        val reopened = app.container
        assertEquals(message.body, reopened.messageRepository.getById(id)?.body)
        assertEquals(POST_PROCESS_NOTIFICATION, reopened.database.messageDao().getById(id)?.postProcessPending)
        MessagePostProcessWorker.enqueueRecovery(app)
        withTimeout(15_000) {
            while (reopened.database.messageDao().getById(id)?.postProcessPending != 0) delay(25)
        }
        assertEquals(message.body, reopened.messageRepository.getById(id)?.body)
        assertTrue(reopened.inboundDeliveryLedgerRepository.shouldAck(id, null))
        assertTrue(reopened.database.loadPendingPostProcess(0).isEmpty())
        app.releaseStorageForInstrumentationTest()
    }
}
