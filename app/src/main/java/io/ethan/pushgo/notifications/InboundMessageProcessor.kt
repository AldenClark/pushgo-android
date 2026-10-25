package io.ethan.pushgo.notifications

import android.content.Context
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
        expectedGatewayUrl: String? = null,
        expectedDeviceKey: String? = null,
        strictConfiguredTarget: Boolean = true,
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

    internal class InboundRetryableException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

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
                    expectedGatewayUrl = route.advertisedGatewayUrl,
                    expectedDeviceKey = route.advertisedDeviceKey,
                    strictConfiguredTarget = route.strictConfiguredTarget,
                )
                return
            }

            is InboundIngressRoute.Invalid -> throw io.ethan.pushgo.data.ChannelSubscriptionException.local(
                message = route.reason, code = "invalid_provider_hint", category = io.ethan.pushgo.data.GatewayErrorCategory.VALIDATION)

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
        if (outcome.status == InboundPersistenceStatus.FAILED) {
            throw InboundRetryableException("inbound canonical persistence incomplete")
        }
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
        expectedGatewayUrl: String?,
        expectedDeviceKey: String?,
        strictConfiguredTarget: Boolean,
    ) {
        val defaultRuntime = runtime.asDefaultRuntime()
        ProviderIngressCoordinator.pullPersistAndDrainAcks(
            context = context,
            channelRepository = defaultRuntime.channelRepository,
            messageRepository = defaultRuntime.messageRepository,
            entityRepository = defaultRuntime.entityRepository,
            inboundDeliveryLedgerRepository = defaultRuntime.inboundDeliveryLedgerRepository,
            settingsRepository = defaultRuntime.settingsRepository,
            deliveryId = deliveryId,
            expectedGatewayUrl = expectedGatewayUrl,
            expectedDeviceKey = expectedDeviceKey,
            strictConfiguredTarget = strictConfiguredTarget,
        )
    }

    // Retain the internal hook surface for focused contract tests, without a
    // second production parser/persist/ACK path or an inline fallback on failure.
    override suspend fun parseDirect(
        runtime: InboundProcessorRuntime,
        messageData: Map<String, String>,
        transportMessageId: String?,
    ): InboundPersistenceRequest? = throw InboundMessageProcessor.InboundRetryableException(
        "Inline provider content requires authenticated Pull")

    override suspend fun persistDirect(
        runtime: InboundProcessorRuntime,
        parsed: InboundPersistenceRequest,
    ): InboundPersistenceOutcome = throw InboundMessageProcessor.InboundRetryableException(
        "Inline provider content requires authenticated Pull")

    override suspend fun ackDirect(
        runtime: InboundProcessorRuntime,
        parsed: InboundPersistenceRequest,
        outcome: InboundPersistenceOutcome,
    ): Unit = throw InboundMessageProcessor.InboundRetryableException(
        "Inline provider content requires authenticated Pull")



}

private fun InboundProcessorRuntime.asDefaultRuntime(): DefaultInboundProcessorRuntime {
    return this as? DefaultInboundProcessorRuntime
        ?: error("Unsupported inbound runtime: ${this::class.qualifiedName}")
}
