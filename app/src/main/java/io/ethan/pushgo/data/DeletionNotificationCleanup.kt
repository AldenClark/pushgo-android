package io.ethan.pushgo.data

import android.content.Context
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.notifications.NotificationHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DeletionNotificationCleanup(context: Context, private val database: PushGoDatabase) {
    private val appContext = context.applicationContext
    private val dao = database.deletionNotificationCleanupDao()
    private val recoveryMutex = Mutex()

    /** Call inside the same Room transaction that removes the captured deletion targets. */
    suspend fun captureAndRecord(operation: PendingLocalDeletionOperation) {
        check(database.inTransaction()) { "Notification cleanup receipts must share the deletion transaction" }
        val entityKeys = linkedSetOf<Pair<String, String>>()
        val associatedMessageIds = linkedSetOf<String>()
        when (operation.kind) {
            PendingLocalDeletionKind.EVENTS -> operation.targetIds.forEach { eventId ->
                database.eventChangeLogDao().getByEventId(eventId).forEach { entityKeys += "event" to it.entityId }
                database.topLevelEventHeadDao().getByEventId(eventId)?.let { entityKeys += "event" to it.entityId }
                database.thingSubEventDao().getByEventId(eventId).forEach { entityKeys += "event" to it.entityId }
            }
            PendingLocalDeletionKind.THINGS -> operation.targetIds.forEach { thingId ->
                database.thingChangeLogDao().getByThingId(thingId).forEach { entityKeys += "thing" to it.entityId }
                database.thingHeadDao().getByThingId(thingId)?.let { entityKeys += "thing" to it.entityId }
                database.thingSubMessageDao().getByThingId(thingId).forEach { associatedMessageIds += it.id }
            }
            else -> Unit
        }
        val receipts = NotificationHelper.captureDeletionNotifications(
            appContext, operation, entityKeys, associatedMessageIds,
        )
        if (receipts.isNotEmpty()) dao.insertAll(receipts)
    }

    /** Replays only OS cleanup. A replaced or already-removed instance also completes its receipt. */
    suspend fun recover() = recoveryMutex.withLock {
        val receipts = dao.loadAll()
        if (receipts.isEmpty()) return@withLock
        NotificationHelper.reconcileDeletionNotifications(appContext, receipts)
        receipts.forEach { dao.deleteById(it.id) }
    }
}
