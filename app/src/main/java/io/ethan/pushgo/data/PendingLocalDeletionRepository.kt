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
    fun observeActive(): Flow<List<PendingLocalDeletionRecord>>

    suspend fun loadActive(): List<PendingLocalDeletionRecord>

    suspend fun enqueue(
        summary: String,
        operation: PendingLocalDeletionOperation,
        requestedAtEpochMillis: Long,
        undoWindowMillis: Long,
    ): PendingLocalDeletionRecord

    suspend fun cancelPending(id: Long): Boolean

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

    suspend fun recoverInterruptedClaims(nowEpochMillis: Long): Int
}

class RoomPendingLocalDeletionRepository(
    private val database: RoomDatabase,
    private val dao: PendingLocalDeletionDao,
) : PendingLocalDeletionRepository {
    override fun observeActive(): Flow<List<PendingLocalDeletionRecord>> =
        dao.observeActive().map { rows -> rows.map(PendingLocalDeletionEntity::toRecord) }

    override suspend fun loadActive(): List<PendingLocalDeletionRecord> =
        dao.loadActive().map(PendingLocalDeletionEntity::toRecord)

    override suspend fun enqueue(
        summary: String,
        operation: PendingLocalDeletionOperation,
        requestedAtEpochMillis: Long,
        undoWindowMillis: Long,
    ): PendingLocalDeletionRecord = database.withTransaction {
        val deadline = maxOf(
            requestedAtEpochMillis,
            dao.latestUndoDeadline() ?: requestedAtEpochMillis,
        ) + undoWindowMillis.coerceAtLeast(0L)
        val entity = pendingLocalDeletionEntity(
            summary = summary,
            operation = operation,
            requestedAtEpochMillis = requestedAtEpochMillis,
            undoDeadlineEpochMillis = deadline,
        )
        entity.copy(id = dao.insert(entity)).toRecord()
    }

    override suspend fun cancelPending(id: Long): Boolean = dao.cancelPending(id) == 1

    override suspend fun claim(
        id: Long,
        nowEpochMillis: Long,
        force: Boolean,
    ): PendingLocalDeletionRecord? = database.withTransaction {
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
        dao.pruneFailedHistory(MAX_FAILED_HISTORY)
        failed
    }

    override suspend fun recoverInterruptedClaims(nowEpochMillis: Long): Int =
        dao.recoverInterruptedClaims(nowEpochMillis)

    private companion object {
        const val MAX_ERROR_LENGTH = 2_000
        const val MAX_FAILED_HISTORY = 100
    }
}

class InMemoryPendingLocalDeletionRepository : PendingLocalDeletionRepository {
    private val mutex = Mutex()
    private val nextId = AtomicLong(1L)
    private val rows = MutableStateFlow<List<PendingLocalDeletionRecord>>(emptyList())

    override fun observeActive(): Flow<List<PendingLocalDeletionRecord>> = rows

    override suspend fun loadActive(): List<PendingLocalDeletionRecord> = rows.value

    override suspend fun enqueue(
        summary: String,
        operation: PendingLocalDeletionOperation,
        requestedAtEpochMillis: Long,
        undoWindowMillis: Long,
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
            state = PendingLocalDeletionState.PENDING,
            attemptCount = 0,
            nextAttemptAtEpochMillis = requestedAtEpochMillis,
            updatedAtEpochMillis = requestedAtEpochMillis,
            lastError = null,
        )
        rows.value = (rows.value + record).sortedWith(RECORD_ORDER)
        record
    }

    override suspend fun cancelPending(id: Long): Boolean = mutex.withLock {
        val current = rows.value.firstOrNull { it.id == id } ?: return@withLock false
        if (!current.isUndoable) return@withLock false
        rows.value = rows.value.filterNot { it.id == id }
        true
    }

    override suspend fun claim(
        id: Long,
        nowEpochMillis: Long,
        force: Boolean,
    ): PendingLocalDeletionRecord? = mutex.withLock {
        val current = rows.value.firstOrNull { it.id == id } ?: return@withLock null
        if (current.state != PendingLocalDeletionState.PENDING) return@withLock null
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
        rows.value = rows.value.filterNot { it.id == id }
        true
    }

    override suspend fun recoverInterruptedClaims(nowEpochMillis: Long): Int = mutex.withLock {
        var recovered = 0
        rows.value = rows.value.map { record ->
            if (record.state == PendingLocalDeletionState.COMMITTING) {
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

private fun PendingLocalDeletionEntity.toRecord(): PendingLocalDeletionRecord =
    PendingLocalDeletionRecord(
        id = id,
        summary = summary,
        operation = PendingLocalDeletionOperation(
            kind = PendingLocalDeletionKind.valueOf(operationKind),
            targetIds = Json.decodeFromString<List<String>>(targetIdsJson).toSet(),
            expectedGatewayUrl = expectedGatewayUrl,
            expectedUpdatedAt = expectedUpdatedAt,
            expectedUseProvider = expectedUseProvider,
        ),
        requestedAtEpochMillis = requestedAtEpochMillis,
        undoDeadlineEpochMillis = undoDeadlineEpochMillis,
        state = PendingLocalDeletionState.valueOf(state),
        attemptCount = attemptCount,
        nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        lastError = lastError,
    )

private fun pendingLocalDeletionEntity(
    summary: String,
    operation: PendingLocalDeletionOperation,
    requestedAtEpochMillis: Long,
    undoDeadlineEpochMillis: Long,
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
)
