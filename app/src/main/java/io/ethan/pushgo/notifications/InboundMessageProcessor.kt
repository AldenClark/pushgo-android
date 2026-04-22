package io.ethan.pushgo.notifications

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.data.ChannelSubscriptionRepository
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.SettingsRepository

internal object DefaultInboundMessageProcessor {
    private val delegate = InboundMessageProcessor()

    suspend fun process(
        context: Context,
        messageData: Map<String, String>,
        transportMessageId: String?,
    ) {
        delegate.process(context, messageData, transportMessageId)
    }
}

internal interface InboundProcessorRuntime

internal data class DefaultInboundProcessorRuntime(
    val channelRepository: ChannelSubscriptionRepository,
    val messageRepository: MessageRepository,
    val entityRepository: EntityRepository,
    val inboundDeliveryLedgerRepository: io.ethan.pushgo.data.InboundDeliveryLedgerRepository,
    val settingsRepository: SettingsRepository,
) : InboundProcessorRuntime

internal fun interface InboundProcessorRuntimeResolver {
    fun resolve(context: Context): InboundProcessorRuntime?
}

internal interface InboundProcessorHooks {
    fun resolveRoute(messageData: Map<String, String>): InboundIngressRoute
    suspend fun handleProviderWakeupPull(
        runtime: InboundProcessorRuntime,
        deliveryId: String,
    )

    suspend fun parseDirect(
        runtime: InboundProcessorRuntime,
        messageData: Map<String, String>,
        transportMessageId: String?,
    ): InboundPersistenceRequest?

    suspend fun persistDirect(
        runtime: InboundProcessorRuntime,
        parsed: InboundPersistenceRequest,
    ): InboundPersistenceOutcome

    suspend fun ackDirect(
        runtime: InboundProcessorRuntime,
        parsed: InboundPersistenceRequest,
        outcome: InboundPersistenceOutcome,
    )
}

internal fun interface InboundProcessorHooksFactory {
    fun create(context: Context): InboundProcessorHooks
}

internal class InboundMessageProcessor(
    private val runtimeResolver: InboundProcessorRuntimeResolver = DefaultInboundProcessorRuntimeResolver,
    private val hooksFactory: InboundProcessorHooksFactory = DefaultInboundProcessorHooksFactory,
) {
    companion object {
        private const val TAG = "InboundMessageProcessor"
    }

    internal class InboundRuntimeUnavailableException(
        message: String,
    ) : IllegalStateException(message)

    suspend fun process(
        context: Context,
        messageData: Map<String, String>,
        transportMessageId: String?,
    ) {
        val runtime = runtimeResolver.resolve(context)
            ?: throw InboundRuntimeUnavailableException("local storage unavailable")
        val hooks = hooksFactory.create(context)
        processWithRuntime(
            runtime = runtime,
            hooks = hooks,
            messageData = messageData,
            transportMessageId = transportMessageId,
        )
    }

    internal suspend fun processWithRuntime(
        runtime: InboundProcessorRuntime,
        hooks: InboundProcessorHooks,
        messageData: Map<String, String>,
        transportMessageId: String?,
    ) {
        when (val route = hooks.resolveRoute(messageData)) {
            is InboundIngressRoute.ProviderWakeupPull -> {
                hooks.handleProviderWakeupPull(
                    runtime = runtime,
                    deliveryId = route.deliveryId,
                )
                return
            }

            is InboundIngressRoute.Drop -> {
                io.ethan.pushgo.util.SilentSink.w(TAG, route.reason)
                return
            }

            InboundIngressRoute.Direct -> Unit
        }

        val parsed = hooks.parseDirect(
            runtime = runtime,
            messageData = messageData,
            transportMessageId = transportMessageId,
        ) ?: return
        val outcome = hooks.persistDirect(
            runtime = runtime,
            parsed = parsed,
        )
        hooks.ackDirect(
            runtime = runtime,
            parsed = parsed,
            outcome = outcome,
        )
    }
}

private object DefaultInboundProcessorRuntimeResolver : InboundProcessorRuntimeResolver {
    override fun resolve(context: Context): InboundProcessorRuntime? {
        val app = context.applicationContext as? PushGoApp ?: return null
        val container = app.containerOrNull() ?: return null
        return DefaultInboundProcessorRuntime(
            channelRepository = container.channelRepository,
            messageRepository = container.messageRepository,
            entityRepository = container.entityRepository,
            inboundDeliveryLedgerRepository = container.inboundDeliveryLedgerRepository,
            settingsRepository = container.settingsRepository,
        )
    }
}

private object DefaultInboundProcessorHooksFactory : InboundProcessorHooksFactory {
    override fun create(context: Context): InboundProcessorHooks {
        return DefaultInboundProcessorHooks(context)
    }
}

private class DefaultInboundProcessorHooks(
    private val context: Context,
) : InboundProcessorHooks {
    override fun resolveRoute(messageData: Map<String, String>): InboundIngressRoute {
        return InboundIngressRouteResolver.resolve(messageData)
    }

    override suspend fun handleProviderWakeupPull(
        runtime: InboundProcessorRuntime,
        deliveryId: String,
    ) {
        val defaultRuntime = runtime.asDefaultRuntime()
        runCatching {
            ProviderIngressCoordinator.pullPersistAndDrainAcks(
                context = context,
                channelRepository = defaultRuntime.channelRepository,
                messageRepository = defaultRuntime.messageRepository,
                entityRepository = defaultRuntime.entityRepository,
                inboundDeliveryLedgerRepository = defaultRuntime.inboundDeliveryLedgerRepository,
                settingsRepository = defaultRuntime.settingsRepository,
                deliveryId = deliveryId,
            ) { message, imageUrl ->
                enqueuePostProcess(
                    context = context,
                    messageId = message.id,
                    imageUrl = imageUrl,
                )
            }
        }.onFailure { error ->
            io.ethan.pushgo.util.SilentSink.w(
                "InboundMessageProcessor",
                "provider wakeup pull failed deliveryId=$deliveryId",
                error,
            )
        }
    }

    override suspend fun parseDirect(
        runtime: InboundProcessorRuntime,
        messageData: Map<String, String>,
        transportMessageId: String?,
    ): InboundPersistenceRequest? {
        val defaultRuntime = runtime.asDefaultRuntime()
        val keyBytes = defaultRuntime.settingsRepository.getNotificationKeyBytes()
        return NotificationIngressParser.parse(
            data = messageData,
            transportMessageId = transportMessageId,
            keyBytes = keyBytes,
            textLocalizer = NotificationIngressParser.NotificationTextLocalizer.fromContext(
                context
            ),
        )
    }

    override suspend fun persistDirect(
        runtime: InboundProcessorRuntime,
        parsed: InboundPersistenceRequest,
    ): InboundPersistenceOutcome {
        val defaultRuntime = runtime.asDefaultRuntime()
        return InboundPersistenceCoordinator.persistAndNotify(
            context = context,
            messageRepository = defaultRuntime.messageRepository,
            entityRepository = defaultRuntime.entityRepository,
            inboundDeliveryLedgerRepository = defaultRuntime.inboundDeliveryLedgerRepository,
            settingsRepository = defaultRuntime.settingsRepository,
            inbound = parsed,
        ) { message, imageUrl ->
            enqueuePostProcess(
                context = context,
                messageId = message.id,
                imageUrl = imageUrl,
            )
        }
    }

    override suspend fun ackDirect(
        runtime: InboundProcessorRuntime,
        parsed: InboundPersistenceRequest,
        outcome: InboundPersistenceOutcome,
    ) {
        val defaultRuntime = runtime.asDefaultRuntime()
        ProviderIngressCoordinator.ackDirectDeliveryIfNeeded(
            context = context,
            inboundDeliveryLedgerRepository = defaultRuntime.inboundDeliveryLedgerRepository,
            inbound = parsed,
            outcome = outcome,
        )
    }

    private fun enqueuePostProcess(
        context: Context,
        messageId: String,
        imageUrl: String?,
    ) {
        val input = workDataOf(
            MessagePostProcessWorker.KEY_MESSAGE_ID to messageId,
            MessagePostProcessWorker.KEY_IMAGE_URL to imageUrl,
        )
        val request = OneTimeWorkRequestBuilder<MessagePostProcessWorker>()
            .setInputData(input)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }
}

private fun InboundProcessorRuntime.asDefaultRuntime(): DefaultInboundProcessorRuntime {
    return this as? DefaultInboundProcessorRuntime
        ?: error("Unsupported inbound runtime: ${this::class.qualifiedName}")
}
