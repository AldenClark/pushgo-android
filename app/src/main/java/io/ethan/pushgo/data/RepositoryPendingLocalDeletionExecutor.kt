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

    override suspend fun captureConfirmation(operation: PendingLocalDeletionOperation): OrdinaryDeletionConfirmation.Identity =
        if (operation.kind == PendingLocalDeletionKind.CHANNEL) captureChannel(operation) else confirmation.capture(operation)

    private suspend fun captureChannel(operation: PendingLocalDeletionOperation): OrdinaryDeletionConfirmation.Identity = database.withTransaction {
        val gateway = requireNotNull(operation.expectedGatewayUrl)
        val channel = database.channelSubscriptionDao().getById(gateway, operation.targetIds.single())
            ?: throw OrdinaryDeletionTargetChangedException()
        val settings = database.appSettingsDao().get()
        val fcmAvailable = io.ethan.pushgo.util.FcmSupport.isAvailable(appContext)
        validateLegacyChannelContext(operation, settings?.serverAddress, channel.updatedAt, channel.isDeleted,
            settings?.useFcmChannel ?: true, fcmAvailable)
        val material = listOf(gateway, channel.channelId, channel.updatedAt, channel.displayName,
            settings?.serverAddress, settings?.useFcmChannel, fcmAvailable, settings?.notificationKeyUpdatedAt, settings?.keyEncoding).joinToString("\u0000")
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
        OrdinaryDeletionConfirmation.Identity(fingerprint, channel.displayName,
            listOf(channel.displayName, channel.channelId, gateway, if (operation.expectedUseProvider == true) "FCM" else "Private").joinToString("\n"))
    }

    override suspend fun executeReconfirmed(record: PendingLocalDeletionRecord, identity: OrdinaryDeletionConfirmation.Identity?) {
        database.withTransaction {
            val dao = database.pendingLocalDeletionDao()
            val current = dao.findById(record.id) ?: throw OrdinaryDeletionTargetChangedException()
            if (current.confirmationProven || current.state != "PENDING" || current.attemptCount != 0 ||
                current.decodedOperation() != record.operation) throw OrdinaryDeletionTargetChangedException()
            if (record.operation.kind == PendingLocalDeletionKind.CHANNEL) {
                if (captureChannel(record.operation).fingerprint != requireNotNull(identity).fingerprint) throw OrdinaryDeletionTargetChangedException()
                // The complete captured destination/version/mode is already durable. It
                // receives proof only after this new explicit UI decision and validation.
                check(dao.confirmUnstartedChannel(record.id, System.currentTimeMillis()) == 1)
            } else {
                require(record.operation.kind == PendingLocalDeletionKind.MESSAGES)
                confirmation.validate(requireNotNull(identity), record.operation)
                check(dao.cancelUnconfirmed(record.id) == 1)
                notificationCleanup.captureAndRecord(record.operation)
                messageRepository.deleteByIds(record.operation.targetIds)
            }
        }
        if (record.operation.kind == PendingLocalDeletionKind.MESSAGES) notificationCleanup.recover()
    }

    override suspend fun recoverCommittedCleanup(record: PendingLocalDeletionRecord) {
        if (record.operation.kind != PendingLocalDeletionKind.CHANNEL || record.confirmationProven) return
        try {
            channelExecutor.cleanupCommittedCredentialOnly(record.operation)
            // A same-version committed tombstone proves credential ownership only. Old OS
            // groups lack gateway identity, so retain the old request and its exact fields
            // as isolated responsibility. Independent exact notification receipts still recover.
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // A cleanup failure also leaves the original responsibility untouched.
        }
    }

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
