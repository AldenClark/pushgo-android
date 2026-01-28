package io.ethan.pushgo.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import android.os.SystemClock
import io.ethan.pushgo.data.db.MessageDao
import io.ethan.pushgo.data.db.MessageEntity
import io.ethan.pushgo.data.model.MessageChannelCount
import io.ethan.pushgo.data.model.MessageFilter
import io.ethan.pushgo.data.model.ReadFilter
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MessageRepository(
    private val dao: MessageDao,
    private val performanceCoordinator: PerformanceDegradationCoordinator? = null,
) {
    private companion object {
        const val MAX_QUERY_TOKENS = 6
        const val MAX_TOKEN_LENGTH = 32
    }

    fun observeMessages(filter: MessageFilter): Flow<PagingData<PushMessage>> {
        val readState = when (filter.readFilter) {
            ReadFilter.ALL -> null
            ReadFilter.UNREAD -> false
            ReadFilter.READ -> true
        }
        val operation = if (!filter.channel.isNullOrBlank()) {
            PerformanceOperation.CHANNEL_FILTER
        } else {
            PerformanceOperation.LIST_PAGE_LOAD
        }
        return Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 50
            ),
            pagingSourceFactory = {
                val source = dao.observeMessages(
                    readState = readState,
                    withUrl = if (filter.withUrlOnly) 1 else 0,
                    channel = filter.channel,
                    serverId = filter.serverId,
                )
                val coordinator = performanceCoordinator
                if (coordinator == null) {
                    source
                } else {
                    MeasuredPagingSource(source) { elapsed ->
                        coordinator.record(operation, elapsed)
                    }
                }
            }
        ).flow.map { pagingData ->
            pagingData.map(MessageEntity::asModel)
        }
    }

    fun searchMessages(rawQuery: String, limit: Int = 200): Flow<List<PushMessage>> {
        val query = buildFtsQuery(rawQuery)
        if (query.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        return dao.searchMessages(query = query, limit = limit)
            .map { list -> list.map(MessageEntity::asModel) }
    }

    fun observeChannelCounts(): Flow<List<MessageChannelCount>> = dao.observeChannelCounts()

    fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount().distinctUntilChanged()

    suspend fun getById(id: String): PushMessage? = dao.getById(id)?.asModel()

    suspend fun getByMessageId(messageId: String): PushMessage? = dao.getByMessageId(messageId)?.asModel()

    suspend fun getAll(): List<PushMessage> = dao.getAll().map(MessageEntity::asModel)

    suspend fun getIdsBefore(readState: Boolean?, cutoff: Long): List<String> {
        return dao.getIdsBefore(readState, cutoff)
    }

    suspend fun getIdsByChannelRead(channel: String?, readState: Boolean?): List<String> {
        return dao.getIdsByChannelRead(channel, readState)
    }

    suspend fun insert(message: PushMessage) {
        measure(PerformanceOperation.WRITE_BATCH) {
            dao.insert(MessageEntity.fromModel(message))
        }
    }

    suspend fun insertAll(messages: List<PushMessage>) {
        measure(PerformanceOperation.WRITE_BATCH) {
            dao.insertAll(messages.map(MessageEntity::fromModel))
        }
    }

    suspend fun markRead(id: String) {
        dao.markRead(id)
    }

    suspend fun markAllRead() {
        measure(PerformanceOperation.BULK_READ) {
            dao.markAllRead()
        }
    }

    suspend fun updateRawPayload(id: String, rawPayloadJson: String) {
        dao.updateRawPayload(id, rawPayloadJson)
    }

    suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    suspend fun deleteByChannel(channel: String): Int {
        return measure(PerformanceOperation.BULK_DELETE) {
            dao.deleteByChannel(channel)
        }
    }

    suspend fun deleteByChannelRead(channel: String?, readState: Boolean?): Int {
        return measure(PerformanceOperation.BULK_DELETE) {
            dao.deleteByChannelRead(channel, readState)
        }
    }

    suspend fun deleteOldestReadMessages(limit: Int): Int {
        return measure(PerformanceOperation.BULK_DELETE) {
            dao.deleteOldestRead(limit)
        }
    }

    suspend fun deleteOldestReadMessages(limit: Int, excludedChannels: List<String>): Int {
        if (excludedChannels.isEmpty()) {
            return deleteOldestReadMessages(limit)
        }
        return measure(PerformanceOperation.BULK_DELETE) {
            dao.deleteOldestReadExcludingChannels(
                limit = limit,
                excludedChannels = excludedChannels,
                excludedSize = excludedChannels.size,
            )
        }
    }

    suspend fun getOldestReadIds(limit: Int, excludedChannels: List<String>): List<String> {
        if (excludedChannels.isEmpty()) {
            return dao.getOldestReadIds(limit)
        }
        return dao.getOldestReadIdsExcludingChannels(
            limit = limit,
            excludedChannels = excludedChannels,
            excludedSize = excludedChannels.size,
        )
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    suspend fun deleteAllRead() {
        measure(PerformanceOperation.BULK_DELETE) {
            dao.deleteAllRead()
        }
    }

    suspend fun deleteBefore(readState: Boolean?, cutoff: Long) {
        measure(PerformanceOperation.BULK_DELETE) {
            dao.deleteBefore(readState, cutoff)
        }
    }

    suspend fun totalCount(): Int = dao.totalCount()

    suspend fun unreadCount(): Int = dao.unreadCount()

    suspend fun countMessages(readState: Boolean?, cutoff: Long?): Int {
        return dao.countMessages(readState, cutoff)
    }

    private fun buildFtsQuery(raw: String): String {
        // Keep tokens simple to avoid invalid FTS syntax and runaway queries.
        val tokens = raw.trim()
            .split("\\s+".toRegex())
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::sanitizeToken)
            .filter { it.isNotEmpty() }
            .take(MAX_QUERY_TOKENS)
            .toList()
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" AND ") { "${it}*" }
    }

    private fun sanitizeToken(raw: String): String {
        val cleaned = raw.replace(Regex("[^\\p{L}\\p{Nd}_]"), "")
        return if (cleaned.length > MAX_TOKEN_LENGTH) cleaned.substring(0, MAX_TOKEN_LENGTH) else cleaned
    }

    private suspend fun <T> measure(
        operation: PerformanceOperation,
        block: suspend () -> T,
    ): T {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val elapsed = SystemClock.elapsedRealtime() - start
        performanceCoordinator?.record(operation, elapsed)
        return result
    }
}
