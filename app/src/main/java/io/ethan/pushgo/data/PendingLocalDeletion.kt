package io.ethan.pushgo.data

enum class PendingLocalDeletionKind {
    MESSAGES,
    EVENTS,
    THINGS,
    CHANNEL,
    RUNTIME_COMPATIBILITY,
}

data class PendingLocalDeletionOperation(
    val kind: PendingLocalDeletionKind,
    val targetIds: Set<String>,
    val expectedGatewayUrl: String? = null,
    val expectedUpdatedAt: Long? = null,
    val expectedUseProvider: Boolean? = null,
) {
    init {
        require(targetIds.none { it.isBlank() }) { "Deletion target IDs must not be blank" }
        when (kind) {
            PendingLocalDeletionKind.MESSAGES,
            PendingLocalDeletionKind.EVENTS,
            PendingLocalDeletionKind.THINGS,
            -> require(targetIds.isNotEmpty()) { "$kind deletion requires at least one target" }

            PendingLocalDeletionKind.CHANNEL -> {
                require(targetIds.size == 1) { "Channel deletion requires exactly one channel" }
                require(!expectedGatewayUrl.isNullOrBlank()) {
                    "Channel deletion requires the expected gateway"
                }
                require(expectedUpdatedAt != null) {
                    "Channel deletion requires the expected subscription version"
                }
                require(expectedUseProvider != null) {
                    "Channel deletion requires the expected delivery mode"
                }
            }

            PendingLocalDeletionKind.RUNTIME_COMPATIBILITY -> Unit
        }
    }

    companion object {
        fun messages(ids: Collection<String>) = idsOperation(PendingLocalDeletionKind.MESSAGES, ids)

        fun events(ids: Collection<String>) = idsOperation(PendingLocalDeletionKind.EVENTS, ids)

        fun things(ids: Collection<String>) = idsOperation(PendingLocalDeletionKind.THINGS, ids)

        fun channel(
            id: String,
            expectedGatewayUrl: String,
            expectedUpdatedAt: Long,
            expectedUseProvider: Boolean,
        ) = PendingLocalDeletionOperation(
            kind = PendingLocalDeletionKind.CHANNEL,
            targetIds = setOf(id.trim()),
            expectedGatewayUrl = expectedGatewayUrl.trim(),
            expectedUpdatedAt = expectedUpdatedAt,
            expectedUseProvider = expectedUseProvider,
        )

        fun runtimeCompatibility(ids: Collection<String> = emptySet()) = PendingLocalDeletionOperation(
            kind = PendingLocalDeletionKind.RUNTIME_COMPATIBILITY,
            targetIds = ids.mapTo(linkedSetOf()) { it.trim() }.filterTo(linkedSetOf()) { it.isNotEmpty() },
        )

        private fun idsOperation(
            kind: PendingLocalDeletionKind,
            ids: Collection<String>,
        ) = PendingLocalDeletionOperation(
            kind = kind,
            targetIds = ids.mapTo(linkedSetOf()) { it.trim() }.filterTo(linkedSetOf()) { it.isNotEmpty() },
        )
    }
}

enum class PendingLocalDeletionState {
    PENDING,
    COMMITTING,
    FAILED,
}

class PermanentPendingLocalDeletionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class PendingLocalDeletionCredentialCleanupException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

class PendingLocalDeletionNotificationReconciliationException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

data class PendingLocalDeletionRecord(
    val id: Long,
    val summary: String,
    val operation: PendingLocalDeletionOperation,
    val requestedAtEpochMillis: Long,
    val undoDeadlineEpochMillis: Long,
    val state: PendingLocalDeletionState,
    val attemptCount: Int,
    val nextAttemptAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val lastError: String?,
) {
    /** Undo is a one-way capability: the first execution attempt permanently consumes it. */
    val isUndoable: Boolean
        get() = state == PendingLocalDeletionState.PENDING && attemptCount == 0

    fun eligibleAtEpochMillis(): Long = if (attemptCount == 0) {
        maxOf(undoDeadlineEpochMillis, nextAttemptAtEpochMillis)
    } else {
        nextAttemptAtEpochMillis
    }
}

/**
 * Executes only declarative operations. Implementations must treat an already-applied deletion as success;
 * the queue can deliberately execute an operation again after a crash between data commit and queue cleanup.
 */
fun interface PendingLocalDeletionExecutor {
    suspend fun execute(operation: PendingLocalDeletionOperation)
}

/** The channel implementation must reconcile remote/local state so repeated execution is idempotent. */
fun interface PendingChannelDeletionExecutor {
    suspend fun execute(operation: PendingLocalDeletionOperation)
}
