package io.ethan.pushgo.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import io.ethan.pushgo.data.db.PendingLocalDeletionDao
import io.ethan.pushgo.data.db.PendingLocalDeletionEntity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface PendingLocalDeletionRepository {
    fun observeFailures(): Flow<List<PendingLocalDeletionRecord>>
    suspend fun loadFailures(): List<PendingLocalDeletionRecord>
    suspend fun loadUnconfirmed(): List<PendingLocalDeletionRecord>
    suspend fun findUnconfirmed(id: Long): PendingLocalDeletionRecord?
    suspend fun loadCommittedChannelCleanup(afterId: Long, limit: Int): List<PendingLocalDeletionRecord>
    suspend fun cancelUnconfirmed(id: Long): Boolean

    fun observeActive(): Flow<List<PendingLocalDeletionRecord>>

    suspend fun loadActive(): List<PendingLocalDeletionRecord>

    suspend fun enqueue(
        summary: String,
        operation: PendingLocalDeletionOperation,
        requestedAtEpochMillis: Long,
        undoWindowMillis: Long,
        confirmationProven: Boolean = false,
    ): PendingLocalDeletionRecord

    suspend fun claim(
        id: Long,
        nowEpochMillis: Long,
        force: Boolean,
    ): PendingLocalDeletionRecord?

    suspend fun completeClaimed(id: Long): Boolean

    suspend fun retryClaimed(
        id: Long,
        nowEpochMillis: Long,
        nextAttemptAtEpochMillis: Long,
        lastError: String,
    ): Boolean

    suspend fun failClaimed(
        id: Long,
        nowEpochMillis: Long,
        lastError: String,
    ): Boolean

    suspend fun retryFailed(id: Long, nowEpochMillis: Long): Boolean

    suspend fun recoverInterruptedClaims(nowEpochMillis: Long): Int
}

class RoomPendingLocalDeletionRepository(
    private val database: RoomDatabase,
    private val dao: PendingLocalDeletionDao,
) : PendingLocalDeletionRepository {
    override fun observeFailures(): Flow<List<PendingLocalDeletionRecord>> =
        dao.observeFailures().map { rows -> rows.map(PendingLocalDeletionEntity::toRecord).filter { it.state in REVIEW_STATES } }

    override suspend fun loadFailures(): List<PendingLocalDeletionRecord> =
        dao.loadFailures().map(PendingLocalDeletionEntity::toRecord).filter { it.state in REVIEW_STATES }

    override fun observeActive(): Flow<List<PendingLocalDeletionRecord>> =
        dao.observeActive().map { rows -> rows.map(PendingLocalDeletionEntity::toRecord).filter { it.state in EXECUTABLE_STATES } }

    override suspend fun loadActive(): List<PendingLocalDeletionRecord> =
        dao.loadActive().map(PendingLocalDeletionEntity::toRecord).filter { it.state in EXECUTABLE_STATES }

    override suspend fun loadUnconfirmed(): List<PendingLocalDeletionRecord> =
        dao.loadUnconfirmed().map(PendingLocalDeletionEntity::toRecord)

    override suspend fun findUnconfirmed(id: Long): PendingLocalDeletionRecord? =
        dao.findById(id)?.takeUnless { it.confirmationProven }?.toRecord()
    override suspend fun loadCommittedChannelCleanup(afterId: Long, limit: Int): List<PendingLocalDeletionRecord> =
        dao.loadCommittedChannelCleanup(afterId, limit.coerceIn(1, 32)).map(PendingLocalDeletionEntity::toRecord)
    override suspend fun cancelUnconfirmed(id: Long): Boolean = database.withTransaction {
        val row = dao.findById(id) ?: return@withTransaction false
        if (!row.isCancelableLegacy()) return@withTransaction false
        dao.cancelUnconfirmed(id) == 1
    }

    override suspend fun enqueue(
        summary: String,
        operation: PendingLocalDeletionOperation,
        requestedAtEpochMillis: Long,
        undoWindowMillis: Long,
        confirmationProven: Boolean,
    ): PendingLocalDeletionRecord = database.withTransaction {
        val deadline = requestedAtEpochMillis + undoWindowMillis.coerceAtLeast(0L)
        val entity = pendingLocalDeletionEntity(
            summary = summary,
            operation = operation,
            requestedAtEpochMillis = requestedAtEpochMillis,
            undoDeadlineEpochMillis = deadline,
            confirmationProven = confirmationProven,
        )
        entity.copy(id = dao.insert(entity)).toRecord()
    }

    override suspend fun claim(
        id: Long,
        nowEpochMillis: Long,
        force: Boolean,
    ): PendingLocalDeletionRecord? = database.withTransaction {
        val before = dao.findById(id)?.toRecord() ?: return@withTransaction null
        if (!before.confirmationProven || before.state != PendingLocalDeletionState.PENDING) return@withTransaction null
        if (dao.claim(id, nowEpochMillis, force) != 1) return@withTransaction null
        dao.findById(id)?.toRecord()
    }

    override suspend fun completeClaimed(id: Long): Boolean = dao.completeClaimed(id) == 1

    override suspend fun retryClaimed(
        id: Long,
        nowEpochMillis: Long,
        nextAttemptAtEpochMillis: Long,
        lastError: String,
    ): Boolean = dao.retryClaimed(
        id = id,
        nowEpochMillis = nowEpochMillis,
        nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
        lastError = lastError.take(MAX_ERROR_LENGTH),
    ) == 1

    override suspend fun failClaimed(
        id: Long,
        nowEpochMillis: Long,
        lastError: String,
    ): Boolean = database.withTransaction {
        val failed = dao.failClaimed(
            id = id,
            nowEpochMillis = nowEpochMillis,
            lastError = lastError.take(MAX_ERROR_LENGTH),
        ) == 1
        failed
    }

    override suspend fun retryFailed(id: Long, nowEpochMillis: Long): Boolean = database.withTransaction {
        val row = dao.findById(id)?.toRecord() ?: return@withTransaction false
        if (!row.confirmationProven || row.state != PendingLocalDeletionState.FAILED) return@withTransaction false
        dao.retryFailed(id, nowEpochMillis) == 1
    }

    override suspend fun recoverInterruptedClaims(nowEpochMillis: Long): Int = database.withTransaction {
        val ids = dao.loadActive().map(PendingLocalDeletionEntity::toRecord)
            .filter { it.confirmationProven && it.state == PendingLocalDeletionState.COMMITTING }.map { it.id }
        if (ids.isEmpty()) 0 else dao.recoverInterruptedClaims(ids, nowEpochMillis)
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 2_000
    }
}

class InMemoryPendingLocalDeletionRepository : PendingLocalDeletionRepository {
    private val mutex = Mutex()
    private val nextId = AtomicLong(1L)
    private val rows = MutableStateFlow<List<PendingLocalDeletionRecord>>(emptyList())

    override fun observeFailures(): Flow<List<PendingLocalDeletionRecord>> = rows.map { all -> all.filter { (it.state in REVIEW_STATES) } }
    override suspend fun loadFailures(): List<PendingLocalDeletionRecord> = rows.value.filter { (it.state in REVIEW_STATES) }

    override fun observeActive(): Flow<List<PendingLocalDeletionRecord>> = rows.map { all -> all.filter { (it.confirmationProven && it.state in setOf(PendingLocalDeletionState.PENDING, PendingLocalDeletionState.COMMITTING)) } }

    override suspend fun loadActive(): List<PendingLocalDeletionRecord> = rows.value.filter { (it.confirmationProven && it.state in setOf(PendingLocalDeletionState.PENDING, PendingLocalDeletionState.COMMITTING)) }

    override suspend fun loadUnconfirmed(): List<PendingLocalDeletionRecord> = rows.value.filter { !it.confirmationProven }
    override suspend fun findUnconfirmed(id: Long): PendingLocalDeletionRecord? = rows.value.firstOrNull { it.id == id && !it.confirmationProven }
    override suspend fun loadCommittedChannelCleanup(afterId: Long, limit: Int): List<PendingLocalDeletionRecord> = emptyList()
    override suspend fun cancelUnconfirmed(id: Long): Boolean = mutex.withLock {
        if (rows.value.none { it.id == id && !it.confirmationProven && it.canCancelUnconfirmed }) return@withLock false
        rows.value = rows.value.filterNot { it.id == id }
        true
    }

    override suspend fun enqueue(
        summary: String,
        operation: PendingLocalDeletionOperation,
        requestedAtEpochMillis: Long,
        undoWindowMillis: Long,
        confirmationProven: Boolean,
    ): PendingLocalDeletionRecord = mutex.withLock {
        val deadline = maxOf(
            requestedAtEpochMillis,
            rows.value.maxOfOrNull { it.undoDeadlineEpochMillis } ?: requestedAtEpochMillis,
        ) + undoWindowMillis.coerceAtLeast(0L)
        val record = PendingLocalDeletionRecord(
            id = nextId.getAndIncrement(),
            summary = summary,
            operation = operation,
            requestedAtEpochMillis = requestedAtEpochMillis,
            undoDeadlineEpochMillis = deadline,
            confirmationProven = confirmationProven,
            state = if (confirmationProven && operation.kind == PendingLocalDeletionKind.CHANNEL) PendingLocalDeletionState.PENDING
                else if (confirmationProven) PendingLocalDeletionState.QUARANTINED
                else legacyDeletionState(operation, "PENDING", 0),
            legacyState = if (confirmationProven) null else "PENDING",
            canCancelUnconfirmed = !confirmationProven && operation.kind != PendingLocalDeletionKind.RUNTIME_COMPATIBILITY,
            attemptCount = 0,
            nextAttemptAtEpochMillis = requestedAtEpochMillis,
            updatedAtEpochMillis = requestedAtEpochMillis,
            lastError = null,
        )
        rows.value = (rows.value + record).sortedWith(RECORD_ORDER)
        record
    }

    override suspend fun claim(
        id: Long,
        nowEpochMillis: Long,
        force: Boolean,
    ): PendingLocalDeletionRecord? = mutex.withLock {
        val current = rows.value.firstOrNull { it.id == id } ?: return@withLock null
        if (!current.confirmationProven || current.state != PendingLocalDeletionState.PENDING) return@withLock null
        if (current.nextAttemptAtEpochMillis > nowEpochMillis) return@withLock null
        if (!force && current.undoDeadlineEpochMillis > nowEpochMillis) return@withLock null
        val claimed = current.copy(
            state = PendingLocalDeletionState.COMMITTING,
            attemptCount = current.attemptCount + 1,
            updatedAtEpochMillis = nowEpochMillis,
            lastError = null,
        )
        rows.value = rows.value.map { if (it.id == id) claimed else it }
        claimed
    }

    override suspend fun completeClaimed(id: Long): Boolean = mutex.withLock {
        val current = rows.value.firstOrNull { it.id == id } ?: return@withLock false
        if (current.state != PendingLocalDeletionState.COMMITTING) return@withLock false
        rows.value = rows.value.filterNot { it.id == id }
        true
    }

    override suspend fun retryClaimed(
        id: Long,
        nowEpochMillis: Long,
        nextAttemptAtEpochMillis: Long,
        lastError: String,
    ): Boolean = mutex.withLock {
        val current = rows.value.firstOrNull { it.id == id } ?: return@withLock false
        if (current.state != PendingLocalDeletionState.COMMITTING) return@withLock false
        val retry = current.copy(
            state = PendingLocalDeletionState.PENDING,
            nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            lastError = lastError,
        )
        rows.value = rows.value.map { if (it.id == id) retry else it }.sortedWith(RECORD_ORDER)
        true
    }

    override suspend fun failClaimed(
        id: Long,
        nowEpochMillis: Long,
        lastError: String,
    ): Boolean = mutex.withLock {
        val current = rows.value.firstOrNull { it.id == id } ?: return@withLock false
        if (current.state != PendingLocalDeletionState.COMMITTING) return@withLock false
        rows.value = rows.value.map { if (it.id == id) it.copy(state = PendingLocalDeletionState.FAILED, updatedAtEpochMillis = nowEpochMillis, lastError = lastError) else it }
        true
    }

    override suspend fun retryFailed(id: Long, nowEpochMillis: Long): Boolean = mutex.withLock {
        val current = rows.value.firstOrNull { it.id == id && it.confirmationProven && it.state == PendingLocalDeletionState.FAILED } ?: return@withLock false
        rows.value = rows.value.map { if (it.id == id) current.copy(state = PendingLocalDeletionState.PENDING, nextAttemptAtEpochMillis = nowEpochMillis, updatedAtEpochMillis = nowEpochMillis) else it }
        true
    }

    override suspend fun recoverInterruptedClaims(nowEpochMillis: Long): Int = mutex.withLock {
        var recovered = 0
        rows.value = rows.value.map { record ->
            if (record.confirmationProven && record.state == PendingLocalDeletionState.COMMITTING) {
                recovered += 1
                record.copy(
                    state = PendingLocalDeletionState.PENDING,
                    nextAttemptAtEpochMillis = nowEpochMillis,
                    updatedAtEpochMillis = nowEpochMillis,
                    lastError = record.lastError
                        ?: "Interrupted while committing; execution will be retried",
                )
            } else {
                record
            }
        }
        recovered
    }

    private companion object {
        val RECORD_ORDER = compareBy<PendingLocalDeletionRecord>(
            PendingLocalDeletionRecord::undoDeadlineEpochMillis,
            PendingLocalDeletionRecord::id,
        )
    }
}

internal fun PendingLocalDeletionEntity.decodedOperation(): PendingLocalDeletionOperation =
    PendingLocalDeletionOperation(
        kind = PendingLocalDeletionKind.valueOf(operationKind),
        targetIds = Json.decodeFromString<List<String>>(targetIdsJson).toSet(),
        expectedGatewayUrl = expectedGatewayUrl,
        expectedUpdatedAt = expectedUpdatedAt,
        expectedUseProvider = expectedUseProvider,
    )

internal fun legacyDeletionState(operation: PendingLocalDeletionOperation, state: String, attempts: Int): PendingLocalDeletionState {
    if (state != "PENDING" || attempts != 0) return PendingLocalDeletionState.QUARANTINED
    return when (operation.kind) {
        PendingLocalDeletionKind.CHANNEL -> PendingLocalDeletionState.NEEDS_CONFIRMATION
        // Message IDs are app-issued canonical local UUIDs. Entity business IDs do not
        // carry an incarnation, so a recreated Event/Thing must not inherit an old request.
        PendingLocalDeletionKind.MESSAGES -> if (operation.targetIds.all { id ->
            runCatching { java.util.UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)
        }) PendingLocalDeletionState.NEEDS_CONFIRMATION else PendingLocalDeletionState.QUARANTINED
        else -> PendingLocalDeletionState.QUARANTINED
    }
}

internal fun PendingLocalDeletionEntity.isCancelableLegacy(): Boolean =
    !confirmationProven && state == "PENDING" && attemptCount == 0 &&
        runCatching { decodedOperation().kind != PendingLocalDeletionKind.RUNTIME_COMPATIBILITY }.getOrDefault(false)

internal fun PendingLocalDeletionEntity.toRecord(): PendingLocalDeletionRecord {
    val decoded = runCatching { decodedOperation() }.getOrNull()
    val knownState = runCatching { PendingLocalDeletionState.valueOf(state) }.getOrNull()
    val safeState = when {
        decoded == null || knownState == null || knownState !in (EXECUTABLE_STATES + PendingLocalDeletionState.FAILED) || attemptCount < 0 -> PendingLocalDeletionState.QUARANTINED
        confirmationProven && decoded.kind != PendingLocalDeletionKind.CHANNEL -> PendingLocalDeletionState.QUARANTINED
        !confirmationProven -> legacyDeletionState(decoded, state, attemptCount)
        else -> requireNotNull(knownState)
    }
    return PendingLocalDeletionRecord(
        id, summary, decoded ?: PendingLocalDeletionOperation.runtimeCompatibility(),
        requestedAtEpochMillis, undoDeadlineEpochMillis, safeState, attemptCount,
        nextAttemptAtEpochMillis, updatedAtEpochMillis, lastError, confirmationProven,
        legacyState = if (confirmationProven) null else state,
        canCancelUnconfirmed = isCancelableLegacy(),
    )
}

private fun pendingLocalDeletionEntity(
    summary: String,
    operation: PendingLocalDeletionOperation,
    requestedAtEpochMillis: Long,
    undoDeadlineEpochMillis: Long,
    confirmationProven: Boolean,
): PendingLocalDeletionEntity = PendingLocalDeletionEntity(
    summary = summary.trim(),
    operationKind = operation.kind.name,
    targetIdsJson = Json.encodeToString(operation.targetIds.sorted()),
    expectedGatewayUrl = operation.expectedGatewayUrl,
    expectedUpdatedAt = operation.expectedUpdatedAt,
    expectedUseProvider = operation.expectedUseProvider,
    requestedAtEpochMillis = requestedAtEpochMillis,
    undoDeadlineEpochMillis = undoDeadlineEpochMillis,
    state = PendingLocalDeletionState.PENDING.name,
    attemptCount = 0,
    nextAttemptAtEpochMillis = requestedAtEpochMillis,
    updatedAtEpochMillis = requestedAtEpochMillis,
    lastError = null,
    confirmationProven = confirmationProven,
)

private val EXECUTABLE_STATES = setOf(PendingLocalDeletionState.PENDING, PendingLocalDeletionState.COMMITTING)
private val REVIEW_STATES = setOf(PendingLocalDeletionState.FAILED, PendingLocalDeletionState.NEEDS_CONFIRMATION, PendingLocalDeletionState.QUARANTINED)
