package io.ethan.pushgo.data

import android.content.Context
import androidx.room.withTransaction
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.NotificationHelper

class RepositoryPendingLocalDeletionExecutor(
    context: Context,
    private val messageStateCoordinator: MessageStateCoordinator,
    private val entityRepository: EntityRepository,
    private val channelExecutor: PendingChannelDeletionExecutor,
    private val database: PushGoDatabase,
    private val messageRepository: MessageRepository,
    private val notificationCleanup: DeletionNotificationCleanup,
) : PendingLocalDeletionExecutor {
    private val appContext = context.applicationContext
    private val confirmation = OrdinaryDeletionConfirmation(database)

    override suspend fun captureConfirmation(operation: PendingLocalDeletionOperation) =
        confirmation.capture(operation)

    override suspend fun executeConfirmed(
        operation: PendingLocalDeletionOperation,
        identity: OrdinaryDeletionConfirmation.Identity?,
    ) {
        database.withTransaction {
            confirmation.validate(requireNotNull(identity), operation)
            notificationCleanup.captureAndRecord(operation)
            when (operation.kind) {
                PendingLocalDeletionKind.MESSAGES -> messageRepository.deleteByIds(operation.targetIds)
                PendingLocalDeletionKind.EVENTS -> entityRepository.deleteEvents(operation.targetIds)
                PendingLocalDeletionKind.THINGS -> entityRepository.deleteThings(operation.targetIds)
                else -> error("Foreground confirmation requires an ordinary local deletion")
            }
        }
        io.ethan.pushgo.testing.QualityRuntime.afterOrdinaryDeletionCommit()
        notificationCleanup.recover()
    }

    override suspend fun execute(operation: PendingLocalDeletionOperation) {
        when (operation.kind) {
            PendingLocalDeletionKind.MESSAGES -> {
                messageStateCoordinator.deleteMessages(operation.targetIds)
            }

            PendingLocalDeletionKind.EVENTS -> {
                entityRepository.deleteEvents(operation.targetIds)
                NotificationHelper.cancelEventNotifications(appContext, operation.targetIds)
            }

            PendingLocalDeletionKind.THINGS -> {
                entityRepository.deleteThings(operation.targetIds)
                NotificationHelper.cancelThingNotifications(appContext, operation.targetIds)
            }

            PendingLocalDeletionKind.CHANNEL -> channelExecutor.execute(operation)
            PendingLocalDeletionKind.RUNTIME_COMPATIBILITY -> error(
                "Runtime-only deletion cannot be recovered after process death"
            )
        }
    }
}
