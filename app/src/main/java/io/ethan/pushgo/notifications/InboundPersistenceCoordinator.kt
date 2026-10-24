package io.ethan.pushgo.notifications

import android.content.Context
import android.util.Log
import io.ethan.pushgo.automation.PushGoAutomation
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.InboundDeliveryScope
import io.ethan.pushgo.data.InboundDeliveryLedgerRepository
import io.ethan.pushgo.data.IncomingEntityRecord
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.ProviderAckIdentity
import io.ethan.pushgo.data.SettingsRepository
import io.ethan.pushgo.data.ThingWriteTicket
import io.ethan.pushgo.data.ThingWriteInvalidatedException
import io.ethan.pushgo.data.hasSameEncryptedRecoveryProvenance
import io.ethan.pushgo.data.inboundDeliveryScope
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.data.model.MessageSeverity
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.CancellationException
import io.ethan.pushgo.data.POST_PROCESS_MEDIA
import io.ethan.pushgo.data.POST_PROCESS_NOTIFICATION
import io.ethan.pushgo.data.finishPostProcessIfCurrent
import io.ethan.pushgo.data.markPostProcessPending
import io.ethan.pushgo.data.postProcessLocator
import io.ethan.pushgo.data.scopedDeliveryStorageKey
import io.ethan.pushgo.data.db.EventChangeLogEntity
import io.ethan.pushgo.data.db.ThingChangeLogEntity
import io.ethan.pushgo.data.db.ThingSubEventEntity

sealed interface InboundPersistenceRequest {
    data class Message(
        val message: PushMessage,
        val level: String?,
        val imageUrl: String?,
        val shouldNotify: Boolean,
        val providerAckIdentity: ProviderAckIdentity? = null,
        val securityDisposition: InboundSecurityDisposition = InboundSecurityDisposition.ACCEPTED,
    ) : InboundPersistenceRequest

    data class Entity(
        val record: IncomingEntityRecord,
        val level: String?,
        val notificationTitle: String,
        val notificationBody: String,
        val shouldNotify: Boolean,
        val hasExplicitTitle: Boolean = true,
        val providerAckIdentity: ProviderAckIdentity? = null,
        val securityDisposition: InboundSecurityDisposition = InboundSecurityDisposition.ACCEPTED,
    ) : InboundPersistenceRequest
}

/**
 * Trust result carried from authenticated payload parsing to persistence side effects.
 * A quarantined payload may be retained by canonical identity for a later key correction,
 * but it must never cross the notification or successful-ack boundaries.
 */
enum class InboundSecurityDisposition(
    val allowsNotification: Boolean,
    val allowsSuccessfulAck: Boolean,
) {
    ACCEPTED(allowsNotification = true, allowsSuccessfulAck = true),
    ENCRYPTED_UNAVAILABLE(allowsNotification = false, allowsSuccessfulAck = true),
    AUTHENTICATION_FAILED(allowsNotification = false, allowsSuccessfulAck = false),
}

enum class InboundPersistenceStatus {
    PERSISTED_MAIN,
    PERSISTED_PENDING,
    DUPLICATE,
    REJECTED,
    FAILED,
}

data class InboundPersistenceOutcome(
    val status: InboundPersistenceStatus,
    val notified: Boolean,
    val shouldAck: Boolean,
)

object InboundPersistenceCoordinator {
    private const val TAG = "InboundPersistence"

    suspend fun persistAndNotify(
        context: Context,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        inbound: InboundPersistenceRequest,
        deliveryScope: InboundDeliveryScope? = null,
        postProcessRecovery: ((String) -> Unit)? = null,
        notificationPublisher: (Context, InboundPersistenceRequest, Boolean) -> Boolean = ::publishInboundNotification,
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit = { _, _ -> },
    ): InboundPersistenceOutcome {
        // Synchronously determine the target before registration; all production ingress paths
        // (Direct, Pull and Private) converge here before asynchronous repository lookups.
        val thingId = when (inbound) {
            is InboundPersistenceRequest.Message -> inbound.message.thingId
            is InboundPersistenceRequest.Entity -> inbound.record.thingId?.trim()?.takeIf { it.isNotEmpty() }
                ?: inbound.record.entityId.takeIf { inbound.record.entityType.trim().lowercase() == "thing" }
        }
        val channel = when (inbound) {
            is InboundPersistenceRequest.Message -> inbound.message.channel
            is InboundPersistenceRequest.Entity -> inbound.record.channel
        }
        val thingWriteTicket = entityRepository.beginThingWrite(thingId, channel)
        var canonicalMessageId: String? = null
        return try {
            persistRegisteredAndNotify(
                context, messageRepository, entityRepository, inboundDeliveryLedgerRepository,
                settingsRepository, inbound, deliveryScope, thingWriteTicket, notificationPublisher,
                beforeMessageNotify = { message, imageUrl ->
                    canonicalMessageId = message.id
                    beforeMessageNotify(message, imageUrl)
                },
            )
        } finally {
            thingWriteTicket?.close()
            try {
                    val locator = when (inbound) {
                        is InboundPersistenceRequest.Message -> messageRepository.postProcessLocator(
                            canonicalMessageId ?: inbound.message.id,
                            deliveryScope ?: inbound.providerAckIdentity.inboundDeliveryScope(),
                        )
                        is InboundPersistenceRequest.Entity -> {
                            val scope = deliveryScope ?: inbound.providerAckIdentity.inboundDeliveryScope()
                            val (table, id) = entityPostProcessIdentity(inbound.record, scope)
                            messageRepository.postProcessDatabase.postProcessLocator(table, id)
                        }
                    }
                    // Empty/completed ingress does not register a worker or scan history.
                    if (locator != null) {
                        if (postProcessRecovery != null) postProcessRecovery(locator.generation)
                        else MessagePostProcessWorker.enqueueLocator(context.applicationContext, locator)
                    }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // The canonical pending bits survive a scheduler failure and the existing
                // startup/cleanup worker will retry registration. No payload/URL is logged.
                io.ethan.pushgo.util.SilentSink.w(TAG, "post-process recovery scheduling failed type=${error.javaClass.simpleName}")
            }
        }
    }

    /** Continue the same operation after its short registration transaction, without registering anew. */
    internal suspend fun persistRegisteredAndNotify(
        context: Context,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        inbound: InboundPersistenceRequest,
        deliveryScope: InboundDeliveryScope?,
        thingWriteTicket: ThingWriteTicket?,
        notificationPublisher: (Context, InboundPersistenceRequest, Boolean) -> Boolean = ::publishInboundNotification,
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit = { _, _ -> },
    ): InboundPersistenceOutcome {
        return when (inbound) {
            is InboundPersistenceRequest.Message -> persistMessage(
                context = context,
                messageRepository = messageRepository,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                settingsRepository = settingsRepository,
                inbound = inbound,
                deliveryScope = deliveryScope ?: inbound.providerAckIdentity.inboundDeliveryScope(),
                beforeMessageNotify = beforeMessageNotify,
                thingWriteTicket = thingWriteTicket,
                notificationPublisher = notificationPublisher,
            )

            is InboundPersistenceRequest.Entity -> persistEntity(
                context = context,
                messageRepository = messageRepository,
                entityRepository = entityRepository,
                inboundDeliveryLedgerRepository = inboundDeliveryLedgerRepository,
                settingsRepository = settingsRepository,
                inbound = inbound,
                deliveryScope = deliveryScope ?: inbound.providerAckIdentity.inboundDeliveryScope(),
                thingWriteTicket = thingWriteTicket,
                notificationPublisher = notificationPublisher,
            )
        }
    }

    private suspend fun persistMessage(
        context: Context,
        messageRepository: MessageRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        inbound: InboundPersistenceRequest.Message,
        deliveryScope: InboundDeliveryScope?,
        beforeMessageNotify: suspend (PushMessage, String?) -> Unit,
        thingWriteTicket: ThingWriteTicket?,
        notificationPublisher: (Context, InboundPersistenceRequest, Boolean) -> Boolean,
    ): InboundPersistenceOutcome {
        val allowsSuccessfulAck = inbound.securityDisposition.allowsSuccessfulAck
        val providerAckIdentity = inbound.providerAckIdentity.takeIf { allowsSuccessfulAck }
        val effectiveDeliveryScope = deliveryScope.takeIf { allowsSuccessfulAck }
        val stableMessageId = inbound.message.messageId?.trim()?.takeIf { it.isNotEmpty() }
        val existingBeforePersist = stableMessageId?.let { messageRepository.getByMessageId(it) }
        if (
            inbound.message.decryptionState == DecryptionState.DECRYPT_OK &&
            existingBeforePersist != null &&
            !hasSameEncryptedRecoveryProvenance(existingBeforePersist, inbound.message)
        ) {
            io.ethan.pushgo.util.SilentSink.w(
                TAG,
                "encrypted replay provenance mismatch messageId=$stableMessageId",
            )
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.REJECTED,
                notified = false,
                shouldAck = false,
            )
        }
        val inserted = runCatching {
            messageRepository.insertIncoming(
                message = inbound.message,
                providerAckIdentity = providerAckIdentity,
                deliveryScope = effectiveDeliveryScope,
                claimIngressIdentity = allowsSuccessfulAck,
                thingWriteTicket = thingWriteTicket,
                postProcessFlags = (if (inbound.imageUrl != null) POST_PROCESS_MEDIA else 0) or
                    (if (inbound.shouldNotify && inbound.securityDisposition.allowsNotification) POST_PROCESS_NOTIFICATION else 0),
                beforeAcceptanceCommit = { settingsRepository.reenableInboundPageInTransaction("message") },
            )
        }
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (error is ThingWriteInvalidatedException) {
                    io.ethan.pushgo.util.SilentSink.i(TAG, "Thing deletion cancelled old message persistence; no ACK")
                    return@onFailure
                }
                io.ethan.pushgo.util.SilentSink.e(TAG, "message persist failed", error)
                PushGoAutomation.recordRuntimeError(
                    source = "inbound.persist.message",
                    error = error,
                    category = "storage",
                )
            }
            .getOrNull()
        if (inserted == null) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.FAILED,
                notified = false,
                shouldAck = false,
            )
        }
        refreshPageCacheAfterCommit(settingsRepository)
        if (!inserted) {
            val pending = messageRepository.wouldPersistAsPending(inbound.message)
            val shouldAck = allowsSuccessfulAck &&
                inboundDeliveryLedgerRepository.shouldAckDelivery(
                    deliveryId = inbound.message.deliveryId,
                    scope = effectiveDeliveryScope,
                )
            if (shouldAck && !pending) {
                val existingBeforeReplay = stableMessageId?.let { messageRepository.getByMessageId(it) }
                if (
                    inbound.message.decryptionState == DecryptionState.DECRYPT_OK &&
                    existingBeforeReplay != null &&
                    existingBeforeReplay.decryptionState != DecryptionState.DECRYPT_OK
                ) {
                    messageRepository.replaceEncryptedRecoveryCandidate(
                        existingId = existingBeforeReplay.id,
                        reparsed = inbound.message,
                    )
                    val recovered = messageRepository.getByMessageId(stableMessageId)
                    if (recovered?.decryptionState != DecryptionState.DECRYPT_OK) {
                        io.ethan.pushgo.util.SilentSink.w(
                            TAG,
                            "authenticated replay failed to replace quarantined messageId=$stableMessageId",
                        )
                        return InboundPersistenceOutcome(
                            status = InboundPersistenceStatus.FAILED,
                            notified = false,
                            shouldAck = false,
                        )
                    }
                }
                val canonicalMessage = resolveCanonicalMessageForReplay(inbound.message) { messageId ->
                    messageRepository.getByMessageId(messageId)
                }
                if (canonicalMessage == null) {
                    io.ethan.pushgo.util.SilentSink.w(
                        TAG,
                        "duplicate message replay skipped: canonical row missing messageId=$stableMessageId",
                    )
                    return InboundPersistenceOutcome(
                        status = InboundPersistenceStatus.DUPLICATE,
                        notified = false,
                        shouldAck = shouldAck,
                    )
                }
                // Resolve media from the canonical payload too; a replay payload may differ.
                handOffMessagePostProcess(beforeMessageNotify, canonicalMessage, null)
                if (inbound.shouldNotify && inbound.securityDisposition.allowsNotification) {
                    publishMessageAfterCommit(context, messageRepository, inbound.copy(message = canonicalMessage), true, notificationPublisher)
                }
            }
            return InboundPersistenceOutcome(
                status = if (pending) {
                    InboundPersistenceStatus.PERSISTED_PENDING
                } else {
                    InboundPersistenceStatus.DUPLICATE
                },
                notified = false,
                shouldAck = shouldAck,
            )
        }

        handOffMessagePostProcess(beforeMessageNotify, inbound.message, inbound.imageUrl)
        if (!inbound.shouldNotify || !inbound.securityDisposition.allowsNotification) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.PERSISTED_MAIN,
                notified = false,
                shouldAck = allowsSuccessfulAck &&
                    inboundDeliveryLedgerRepository.shouldAckDelivery(
                        deliveryId = inbound.message.deliveryId,
                        scope = effectiveDeliveryScope,
                    ),
            )
        }

        val published = publishMessageAfterCommit(context, messageRepository, inbound, false, notificationPublisher)
        return InboundPersistenceOutcome(
            status = InboundPersistenceStatus.PERSISTED_MAIN,
            notified = published == true,
            shouldAck = allowsSuccessfulAck &&
                inboundDeliveryLedgerRepository.shouldAckDelivery(
                    deliveryId = inbound.message.deliveryId,
                    scope = effectiveDeliveryScope,
                ),
        )
    }

    private suspend fun persistEntity(
        context: Context,
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inboundDeliveryLedgerRepository: InboundDeliveryLedgerRepository,
        settingsRepository: SettingsRepository,
        inbound: InboundPersistenceRequest.Entity,
        deliveryScope: InboundDeliveryScope?,
        thingWriteTicket: ThingWriteTicket?,
        notificationPublisher: (Context, InboundPersistenceRequest, Boolean) -> Boolean,
    ): InboundPersistenceOutcome {
        if (!shouldPersistEntityProjection(inbound)) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.REJECTED,
                notified = false,
                shouldAck = false,
            )
        }
        val allowsSuccessfulAck = inbound.securityDisposition.allowsSuccessfulAck
        val providerAckIdentity = inbound.providerAckIdentity.takeIf { allowsSuccessfulAck }
        val effectiveDeliveryScope = deliveryScope.takeIf { allowsSuccessfulAck }
        val eventFallbackTitle = if (
            inbound.record.entityType == "event" &&
                !inbound.hasExplicitTitle
        ) {
            val fallbackEventId = inbound.record.eventId?.trim()?.takeIf { it.isNotEmpty() }
                ?: inbound.record.entityId
            runCatching { entityRepository.resolveStoredEventTitle(fallbackEventId) }
                .onFailure { error ->
                    io.ethan.pushgo.util.SilentSink.w(
                        TAG,
                        "event title fallback lookup failed eventId=$fallbackEventId",
                        error,
                    )
                }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val resolvedTitle = eventFallbackTitle ?: inbound.notificationTitle
        val resolvedInbound = if (resolvedTitle == inbound.notificationTitle) {
            inbound
        } else {
            inbound.copy(
                record = inbound.record.copy(title = resolvedTitle),
                notificationTitle = resolvedTitle,
            )
        }
        val inserted = runCatching {
            entityRepository.insertIncoming(
                resolvedInbound.record,
                providerAckIdentity,
                effectiveDeliveryScope,
                thingWriteTicket,
                postProcessFlags = if (resolvedInbound.shouldNotify && resolvedInbound.securityDisposition.allowsNotification) POST_PROCESS_NOTIFICATION else 0,
                beforeAcceptanceCommit = { settingsRepository.reenableInboundPageInTransaction(resolvedInbound.record.entityType) },
            )
        }
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (error is ThingWriteInvalidatedException) {
                    io.ethan.pushgo.util.SilentSink.i(TAG, "Thing deletion cancelled old entity persistence; no ACK")
                    return@onFailure
                }
                io.ethan.pushgo.util.SilentSink.e(
                    TAG,
                    "entity persist failed type=${resolvedInbound.record.entityType} id=${resolvedInbound.record.entityId}",
                    error,
                )
                PushGoAutomation.recordRuntimeError(
                    source = "inbound.persist.${resolvedInbound.record.entityType}",
                    error = error,
                    category = "storage",
                )
            }
            .getOrNull()
        if (inserted == null) {
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.FAILED,
                notified = false,
                shouldAck = false,
            )
        }
        val displayInbound = resolveEntityNotificationDisplayAfterPersist(
            entityRepository = entityRepository,
            inbound = resolvedInbound,
        )
        refreshPageCacheAfterCommit(settingsRepository)
        if (!inserted) {
            val pending = entityRepository.wouldPersistAsPending(displayInbound.record)
            val shouldAck = allowsSuccessfulAck &&
                inboundDeliveryLedgerRepository.shouldAckDelivery(
                    deliveryId = displayInbound.record.deliveryId,
                    scope = effectiveDeliveryScope,
                )
            if (
                shouldAck && !pending && displayInbound.shouldNotify &&
                displayInbound.securityDisposition.allowsNotification
            ) {
                publishEntityAfterCommit(context, messageRepository, entityRepository, displayInbound,
                    effectiveDeliveryScope, true, notificationPublisher)
            }
            if (shouldAck && !pending && displayInbound.record.entityType == "thing") {
                if (!replayPendingThingChildren(
                        messageRepository = messageRepository,
                        entityRepository = entityRepository,
                        inbound = displayInbound,
                        thingWriteTicket = thingWriteTicket,
                    )) return lifecycleChangedOutcome()
            }
            return InboundPersistenceOutcome(
                status = if (pending) {
                    InboundPersistenceStatus.PERSISTED_PENDING
                } else {
                    InboundPersistenceStatus.DUPLICATE
                },
                notified = false,
                shouldAck = shouldAck,
            )
        }
        if (!displayInbound.shouldNotify || !displayInbound.securityDisposition.allowsNotification) {
            if (displayInbound.record.entityType == "thing") {
                if (!replayPendingThingChildren(messageRepository, entityRepository, displayInbound, thingWriteTicket)) {
                    return lifecycleChangedOutcome()
                }
            }
            return InboundPersistenceOutcome(
                status = InboundPersistenceStatus.PERSISTED_MAIN,
                notified = false,
                shouldAck = allowsSuccessfulAck &&
                    inboundDeliveryLedgerRepository.shouldAckDelivery(
                        deliveryId = displayInbound.record.deliveryId,
                        scope = effectiveDeliveryScope,
                    ),
            )
        }

        val published = publishEntityAfterCommit(context, messageRepository, entityRepository, displayInbound,
            effectiveDeliveryScope, false, notificationPublisher)
        if (displayInbound.record.entityType == "thing") {
            if (!replayPendingThingChildren(messageRepository, entityRepository, displayInbound, thingWriteTicket)) {
                return lifecycleChangedOutcome()
            }
        }
        return InboundPersistenceOutcome(
            status = InboundPersistenceStatus.PERSISTED_MAIN,
            notified = published == true,
            shouldAck = allowsSuccessfulAck &&
                inboundDeliveryLedgerRepository.shouldAckDelivery(
                    deliveryId = displayInbound.record.deliveryId,
                    scope = effectiveDeliveryScope,
                ),
        )
    }

    private suspend fun handOffMessagePostProcess(
        handoff: suspend (PushMessage, String?) -> Unit,
        message: PushMessage,
        imageUrl: String?,
    ) {
        try { handoff(message, imageUrl) } catch (error: Exception) {
            if (error is CancellationException) throw error
            io.ethan.pushgo.util.SilentSink.w(TAG, "media scheduling deferred type=${error.javaClass.simpleName}")
        }
    }

    private suspend fun refreshPageCacheAfterCommit(settings: SettingsRepository) {
        try { settings.refreshInboundPageCacheAfterCommit() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            // The canonical Room row and its page visibility are already committed;
            // a preferences projection can be repaired by the next normal read.
            io.ethan.pushgo.util.SilentSink.w(TAG, "page cache refresh deferred type=${error.javaClass.simpleName}")
        }
    }

    private suspend fun publishMessageAfterCommit(
        context: Context,
        repository: MessageRepository,
        inbound: InboundPersistenceRequest.Message,
        replay: Boolean,
        publisher: (Context, InboundPersistenceRequest, Boolean) -> Boolean,
    ): Boolean {
        val db = repository.postProcessDatabase
        if (replay) repository.publishNotificationIfCurrent(inbound.message) {
            val table = if (inbound.message.thingId.isNullOrBlank()) "messages" else "thing_sub_messages"
            db.markPostProcessPending(table, inbound.message.id, POST_PROCESS_NOTIFICATION)
            true
        }
        val locator = repository.postProcessLocator(inbound.message.id) ?: return false
        val published = try {
            db.finishPostProcessIfCurrent(locator, POST_PROCESS_NOTIFICATION) { snapshot ->
                publisher(context, inbound.copy(message = snapshot.message,
                    level = canonicalNotificationLevel(snapshot.message, inbound.level)), replay)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            io.ethan.pushgo.util.SilentSink.w(TAG, "notification deferred type=${error.javaClass.simpleName}")
            false
        }
        return published
    }

    private suspend fun publishEntityAfterCommit(
        context: Context,
        repository: MessageRepository,
        entities: EntityRepository,
        inbound: InboundPersistenceRequest.Entity,
        scope: InboundDeliveryScope?,
        replay: Boolean,
        publisher: (Context, InboundPersistenceRequest, Boolean) -> Boolean,
    ): Boolean {
        val record = inbound.record
        val (table, id) = entityPostProcessIdentity(record, scope)
        val db = repository.postProcessDatabase
        if (replay) entities.publishNotificationIfCurrent(record, scope, true) {
            db.markPostProcessPending(table, id, POST_PROCESS_NOTIFICATION)
            true
        }
        val locator = db.postProcessLocator(table, id) ?: return false
        val published = try { db.finishPostProcessIfCurrent(locator, POST_PROCESS_NOTIFICATION) { publisher(context, inbound, replay) } }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            io.ethan.pushgo.util.SilentSink.w(TAG, "notification deferred type=${error.javaClass.simpleName}")
            false
        }
        return published
    }

    private fun entityPostProcessIdentity(record: IncomingEntityRecord, scope: InboundDeliveryScope?): Pair<String, String> {
        val scoped = record.copy(localDeliveryKey = record.deliveryId?.trim()?.takeIf(String::isNotEmpty)?.let { scope.scopedDeliveryStorageKey(it) })
        return when {
            record.entityType == "thing" -> "thing_change_logs" to ThingChangeLogEntity.fromIncoming(scoped).id
            record.thingId.isNullOrBlank() -> "event_change_logs" to EventChangeLogEntity.fromIncoming(scoped).id
            else -> "thing_sub_events" to ThingSubEventEntity.fromIncoming(scoped).id
        }
    }


    private fun showEntityNotification(
        context: Context,
        inbound: InboundPersistenceRequest.Entity,
    ): Boolean = NotificationHelper.showEntityNotification(
        context = context,
        entityType = inbound.record.entityType,
        entityId = inbound.record.entityId,
        groupChannel = inbound.record.channel,
        eventId = inbound.record.eventId,
        thingId = inbound.record.thingId,
        title = inbound.notificationTitle,
        body = inbound.notificationBody,
        level = inbound.level,
    )

    private fun showEntityReplayNotificationSilently(
        context: Context,
        inbound: InboundPersistenceRequest.Entity,
    ): Boolean = NotificationHelper.showEntityReplayNotificationSilently(
        context = context,
        entityType = inbound.record.entityType,
        entityId = inbound.record.entityId,
        groupChannel = inbound.record.channel,
        eventId = inbound.record.eventId,
        thingId = inbound.record.thingId,
        title = inbound.notificationTitle,
        body = inbound.notificationBody,
        level = inbound.level,
    )

    internal suspend fun replayPendingThingChildren(
        messageRepository: MessageRepository,
        entityRepository: EntityRepository,
        inbound: InboundPersistenceRequest.Entity,
        thingWriteTicket: ThingWriteTicket?,
    ): Boolean {
        val thingId = inbound.record.thingId?.trim()?.takeIf { it.isNotEmpty() }
            ?: inbound.record.entityId
        return try {
            messageRepository.replayPendingForThing(thingId, thingWriteTicket)
            entityRepository.activateStoredEventsForThing(thingId, thingWriteTicket)
            true
        } catch (_: ThingWriteInvalidatedException) {
            // Invalidation is deliberately conservative even if an outer deletion rolls back.
            // Do not ACK this interrupted invocation: the existing durable parent/ledger lets a
            // later normal retry finish surviving pending work, without a permanent tombstone.
            false
        }
    }

    private fun lifecycleChangedOutcome() = InboundPersistenceOutcome(
        status = InboundPersistenceStatus.FAILED,
        notified = false,
        shouldAck = false,
    )

    private suspend fun resolveEntityNotificationDisplayAfterPersist(
        entityRepository: EntityRepository,
        inbound: InboundPersistenceRequest.Entity,
    ): InboundPersistenceRequest.Entity {
        if (inbound.record.entityType != "thing") {
            return inbound
        }
        val thingId = inbound.record.thingId?.trim()?.takeIf { it.isNotEmpty() }
            ?: inbound.record.entityId
        val storedTitle = runCatching { entityRepository.resolveStoredThingTitle(thingId) }
            .onFailure { error ->
                io.ethan.pushgo.util.SilentSink.w(
                    TAG,
                    "thing title snapshot lookup failed thingId=$thingId",
                    error,
                )
            }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return inbound
        if (storedTitle == inbound.notificationTitle) {
            return inbound
        }
        return inbound.copy(
            record = inbound.record.copy(title = storedTitle),
            notificationTitle = storedTitle,
        )
    }
}

internal fun shouldPersistEntityProjection(
    inbound: InboundPersistenceRequest.Entity,
): Boolean = inbound.securityDisposition == InboundSecurityDisposition.ACCEPTED

private fun canonicalNotificationLevel(message: PushMessage, fallback: String?): String? {
    return when (message.severity) {
        MessageSeverity.LOW -> "low"
        MessageSeverity.MEDIUM -> "normal"
        MessageSeverity.HIGH -> "high"
        MessageSeverity.CRITICAL -> "critical"
        null -> fallback
    }
}

internal suspend fun resolveCanonicalMessageForReplay(
    replay: PushMessage,
    loadByMessageId: suspend (String) -> PushMessage?,
): PushMessage? {
    val stableMessageId = replay.messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return loadByMessageId(stableMessageId)
}

internal fun publishInboundNotification(context: Context, inbound: InboundPersistenceRequest, replay: Boolean): Boolean = when (inbound) {
    is InboundPersistenceRequest.Message -> if (replay) NotificationHelper.showMessageReplayNotificationSilently(context, inbound.message, inbound.level)
        else NotificationHelper.showMessageNotification(context, inbound.message, inbound.level)
    is InboundPersistenceRequest.Entity -> if (replay) NotificationHelper.showEntityReplayNotificationSilently(context,
        inbound.record.entityType, inbound.record.entityId, inbound.record.channel, inbound.record.eventId, inbound.record.thingId,
        inbound.notificationTitle, inbound.notificationBody, inbound.level)
        else NotificationHelper.showEntityNotification(context,
            inbound.record.entityType, inbound.record.entityId, inbound.record.channel, inbound.record.eventId, inbound.record.thingId,
            inbound.notificationTitle, inbound.notificationBody, inbound.level)
}
