package io.ethan.pushgo.data

import android.content.Context
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.notifications.NotificationHelper

class RepositoryPendingLocalDeletionExecutor(
    context: Context,
    private val messageStateCoordinator: MessageStateCoordinator,
    private val entityRepository: EntityRepository,
    private val channelExecutor: PendingChannelDeletionExecutor,
) : PendingLocalDeletionExecutor {
    private val appContext = context.applicationContext

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
