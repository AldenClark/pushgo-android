package io.ethan.pushgo.data

import android.content.Context
import io.ethan.pushgo.notifications.NotificationHelper
import io.ethan.pushgo.notifications.PrivateChannelClient
import io.ethan.pushgo.util.FcmSupport

class RepositoryPendingChannelDeletionBackend(
    context: Context,
    private val store: ChannelSubscriptionStore,
    private val settingsRepository: SettingsRepository,
    private val channelRepository: ChannelSubscriptionRepository,
    private val privateChannelClient: PrivateChannelClient,
) : PendingChannelDeletionBackend {
    private val appContext = context.applicationContext

    override suspend fun <T> withChannelMutationLock(
        operation: PendingLocalDeletionOperation,
        block: suspend () -> T,
    ): T = store.withChannelMutation(
        gatewayUrl = requireNotNull(operation.expectedGatewayUrl),
        channelId = operation.targetIds.single(),
        block = block,
    )

    override suspend fun inspect(
        operation: PendingLocalDeletionOperation,
    ): PendingChannelDeletionTargetState {
        val expectedGateway = requireNotNull(operation.expectedGatewayUrl)
        val targetState = store.pendingDeletionTargetState(
            gatewayUrl = expectedGateway,
            channelId = operation.targetIds.single(),
            expectedUpdatedAt = requireNotNull(operation.expectedUpdatedAt),
        )
        if (targetState == PendingChannelDeletionTargetState.ALREADY_DELETED) {
            return targetState
        }
        val currentGateway = channelRepository.loadGatewayConfig().first
        if (currentGateway.normalizedGateway() != expectedGateway.normalizedGateway()) {
            return PendingChannelDeletionTargetState.CONFLICTING_VERSION
        }
        return targetState
    }

    override suspend fun cleanupCredential(operation: PendingLocalDeletionOperation) {
        store.removePasswordIfDeletedVersionMatches(
            gatewayUrl = requireNotNull(operation.expectedGatewayUrl),
            channelId = operation.targetIds.single(),
            expectedUpdatedAt = requireNotNull(operation.expectedUpdatedAt),
        )
    }

    override suspend fun reconcileDeletedChannelNotifications(operation: PendingLocalDeletionOperation) {
        NotificationHelper.cancelChannelNotifications(
            context = appContext,
            channelId = operation.targetIds.single(),
        )
    }

    override fun currentlyUsesProvider(): Boolean =
        settingsRepository.getCachedUseFcmChannel() && FcmSupport.isAvailable(appContext)

    override suspend fun existingProviderToken(): String? = settingsRepository.getFcmToken()

    override suspend fun syncProviderToken(token: String, operation: PendingLocalDeletionOperation) {
        channelRepository.syncProviderDeviceToken(token, operation.expectedGatewayUrl)
    }

    override suspend fun unsubscribeProvider(token: String, operation: PendingLocalDeletionOperation) {
        try {
            channelRepository.unsubscribeProviderRemote(
                operation.targetIds.single(),
                token,
                operation.expectedGatewayUrl,
            )
        } catch (error: ChannelSubscriptionException) {
            if (!error.isAlreadyUnsubscribedForPendingDeletion()) throw error
        }
    }

    override suspend fun unsubscribePrivate(operation: PendingLocalDeletionOperation) {
        try {
            check(
                privateChannelClient.privateUnsubscribeChannel(
                    operation.targetIds.single(),
                    operation.expectedGatewayUrl,
                )
            )
        } catch (error: ChannelSubscriptionException) {
            if (!error.isAlreadyUnsubscribedForPendingDeletion()) throw error
        }
    }

    override suspend fun deleteLocal(operation: PendingLocalDeletionOperation) {
        channelRepository.deleteLocalHistoryAndSubscription(
            operation.targetIds.single(),
            requireNotNull(operation.expectedGatewayUrl),
            requireNotNull(operation.expectedUpdatedAt),
        )
    }

    override suspend fun currentCredential(operation: PendingLocalDeletionOperation): String? =
        channelRepository.channelPassword(
            requireNotNull(operation.expectedGatewayUrl),
            operation.targetIds.single(),
        )

    override suspend fun restoreProvider(
        token: String,
        credential: String,
        operation: PendingLocalDeletionOperation,
    ) {
        channelRepository.restoreProviderSubscriptionRemote(
            operation.targetIds.single(),
            credential,
            token,
            operation.expectedGatewayUrl,
        )
    }

    override suspend fun restorePrivate(
        credential: String,
        operation: PendingLocalDeletionOperation,
    ) {
        check(
            privateChannelClient.privateSubscribeChannel(
                operation.targetIds.single(),
                credential,
                operation.expectedGatewayUrl,
            )
        )
    }

    private fun String.normalizedGateway(): String = trim().removeSuffix("/")

}

internal fun ChannelSubscriptionException.isAlreadyUnsubscribedForPendingDeletion(): Boolean {
    val codes = setOf(
        "channel_not_found",
        "subscription_not_found",
        "subscriber_not_found",
        "not_subscribed",
    )
    return codes.any(::matchesCode) || codes.any(::containsLegacyText)
}
