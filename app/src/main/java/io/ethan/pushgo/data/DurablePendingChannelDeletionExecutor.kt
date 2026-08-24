package io.ethan.pushgo.data

enum class PendingChannelDeletionTargetState {
    ALREADY_DELETED,
    ACTIVE_EXPECTED_VERSION,
    CONFLICTING_VERSION,
}

interface PendingChannelDeletionBackend {
    suspend fun <T> withChannelMutationLock(
        operation: PendingLocalDeletionOperation,
        block: suspend () -> T,
    ): T = block()

    suspend fun inspect(operation: PendingLocalDeletionOperation): PendingChannelDeletionTargetState
    suspend fun reconcileDeletedChannelNotifications(operation: PendingLocalDeletionOperation)
    suspend fun cleanupCredential(operation: PendingLocalDeletionOperation)
    fun currentlyUsesProvider(): Boolean
    suspend fun existingProviderToken(): String?
    suspend fun syncProviderToken(token: String, operation: PendingLocalDeletionOperation)
    suspend fun unsubscribeProvider(token: String, operation: PendingLocalDeletionOperation)
    suspend fun unsubscribePrivate(operation: PendingLocalDeletionOperation)
    suspend fun deleteLocal(operation: PendingLocalDeletionOperation)
    suspend fun currentCredential(operation: PendingLocalDeletionOperation): String?
    suspend fun restoreProvider(token: String, credential: String, operation: PendingLocalDeletionOperation)
    suspend fun restorePrivate(credential: String, operation: PendingLocalDeletionOperation)
}

/** Durable, replay-safe channel removal. It never requests a token or interacts with UI. */
class DurablePendingChannelDeletionExecutor(
    private val backend: PendingChannelDeletionBackend,
) : PendingChannelDeletionExecutor {
    override suspend fun execute(operation: PendingLocalDeletionOperation) {
        try {
            backend.withChannelMutationLock(operation) {
                executeOperation(operation)
            }
        } catch (error: ChannelSubscriptionException) {
            if (!error.retryable || error.category in PERMANENT_GATEWAY_CATEGORIES) {
                throw PermanentPendingLocalDeletionException(error.message.orEmpty(), error)
            }
            throw error
        }
    }

    private suspend fun executeOperation(operation: PendingLocalDeletionOperation) {
        require(operation.kind == PendingLocalDeletionKind.CHANNEL)
        when (backend.inspect(operation)) {
            PendingChannelDeletionTargetState.ALREADY_DELETED -> {
                backend.reconcileDeletedChannelNotifications(operation)
                backend.cleanupCredential(operation)
                return
            }
            PendingChannelDeletionTargetState.CONFLICTING_VERSION -> throw permanentConflict()
            PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION -> Unit
        }
        val expectedProvider = requireNotNull(operation.expectedUseProvider)
        if (backend.currentlyUsesProvider() != expectedProvider) {
            throw PermanentPendingLocalDeletionException(
                "Channel delivery mode changed while removal was pending"
            )
        }
        val providerToken = if (expectedProvider) {
            backend.existingProviderToken()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Existing provider token is unavailable")
        } else {
            null
        }

        if (providerToken != null) {
            backend.syncProviderToken(providerToken, operation)
            backend.unsubscribeProvider(providerToken, operation)
        } else {
            backend.unsubscribePrivate(operation)
        }

        try {
            backend.deleteLocal(operation)
        } catch (localError: Throwable) {
            if (
                localError is PendingLocalDeletionCredentialCleanupException ||
                localError is PendingLocalDeletionNotificationReconciliationException
            ) {
                throw localError
            }
            when (backend.inspect(operation)) {
                PendingChannelDeletionTargetState.ALREADY_DELETED -> {
                    backend.reconcileDeletedChannelNotifications(operation)
                    backend.cleanupCredential(operation)
                    return
                }
                PendingChannelDeletionTargetState.CONFLICTING_VERSION -> {
                    compensate(operation, providerToken, localError)
                    throw permanentConflict(localError)
                }
                PendingChannelDeletionTargetState.ACTIVE_EXPECTED_VERSION -> {
                    compensate(operation, providerToken, localError)
                    throw localError
                }
            }
        }
    }

    private suspend fun compensate(
        operation: PendingLocalDeletionOperation,
        providerToken: String?,
        localError: Throwable,
    ) {
        val credential = backend.currentCredential(operation)
            ?: throw ChannelSubscriptionException(
                message = "Channel removal compensation state could not be verified",
                code = "channel_removal_compensation_state_unavailable",
                category = GatewayErrorCategory.INTERNAL,
                detail = "local=${localError.message}",
                retryable = true,
            )
        try {
            if (providerToken != null) {
                backend.restoreProvider(providerToken, credential, operation)
            } else {
                backend.restorePrivate(credential, operation)
            }
        } catch (compensationError: Throwable) {
            throw ChannelSubscriptionException(
                message = "Channel removal local transaction and remote compensation both failed",
                code = "channel_removal_compensation_failed",
                category = GatewayErrorCategory.INTERNAL,
                detail = "local=${localError.message}; compensation=${compensationError.message}",
                retryable = true,
            )
        }
    }

    private fun permanentConflict(cause: Throwable? = null) = PermanentPendingLocalDeletionException(
        message = "Channel subscription changed while removal was pending",
        cause = cause,
    )

    private companion object {
        val PERMANENT_GATEWAY_CATEGORIES = setOf(
            GatewayErrorCategory.VALIDATION,
            GatewayErrorCategory.AUTH,
            GatewayErrorCategory.PERMISSION,
            GatewayErrorCategory.NOT_FOUND,
            GatewayErrorCategory.CONFLICT,
            GatewayErrorCategory.FEATURE_DISABLED,
        )
    }
}
