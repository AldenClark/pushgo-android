package io.ethan.pushgo.testing

import android.content.Context
import android.os.Build
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.EntityRepository
import io.ethan.pushgo.data.IncomingEntityRecord
import io.ethan.pushgo.data.MessageRepository
import io.ethan.pushgo.data.db.MessageEntity
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageFilter
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import java.time.Instant
import kotlin.math.min
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeDataLayerInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var database: PushGoDatabase? = null

    @Before
    fun setUp() {
        cleanupDatabase()
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        cleanupDatabase()
    }

    @Test
    fun realRoomDaoFtsAndPaging_defaultScaleMatrixThrough10000() = runBlocking {
        printEnvironment()
        val sizes = listOf(0, 1, 10, 100, 1_000, 10_000)
        sizes.forEach { size ->
            val opened = openFreshDatabase()
            val db = opened.database
            val messages = messageRepository(db)
            val entities = entityRepository(db)
            val metrics = RuntimeMetrics(scale = size)
            metrics.databaseOpenMs = opened.elapsedMs
            val memoryBefore = usedMemoryBytes()

            metrics.bulkWriteMs = elapsedMs {
                insertGeneratedMessages(messages, size)
                insertProjectionBaselineRecords(entities, size)
            }

            assertEquals("total message count for scale=$size", size, messages.totalCount())
            assertEquals("unread message count for scale=$size", unreadCountFor(size), messages.unreadCount())

            metrics.firstPageMs = elapsedMs {
                val firstPage = loadMessagePage(db, MessageFilter(), pageSize = PAGE_SIZE).data
                assertEquals(min(PAGE_SIZE, size), firstPage.size)
                assertNewestFirst(firstPage)
            }

            metrics.fivePagesMs = elapsedMs {
                val firstFivePages = loadPages(db, MessageFilter(), PAGE_SIZE, 5)
                assertEquals(min(PAGE_SIZE * 5, size), firstFivePages.size)
                assertNewestFirst(firstFivePages)
            }

            metrics.ftsCountMs = elapsedMs {
                assertEquals(size, ftsCount(db, "runtime*"))
            }
            metrics.ftsPageMs = elapsedMs {
                val result = messages.searchMessages("runtime", unreadOnly = false, limit = PAGE_SIZE).first()
                assertEquals(min(PAGE_SIZE, size), result.size)
            }
            metrics.channelFilterMs = elapsedMs {
                val result = loadMessagePage(db, MessageFilter(channels = setOf("runtime-channel-1")), PAGE_SIZE).data
                assertTrue(result.all { it.channel == "runtime-channel-1" })
            }
            metrics.tagFilterMs = elapsedMs {
                val result = loadMessagePage(db, MessageFilter(tags = setOf("task")), PAGE_SIZE).data
                assertTrue(result.all { payloadTags(it.rawPayloadJson).contains("task") })
            }
            metrics.unreadFilterMs = elapsedMs {
                val result = loadMessagePage(db, MessageFilter(unreadOnly = true), PAGE_SIZE).data
                assertTrue(result.all { !it.isRead })
            }
            metrics.eventProjectionMs = elapsedMs {
                assertTrue(entities.getEventProjectionMessages().any { it.title == "Event projection $size" })
            }
            metrics.thingProjectionMs = elapsedMs {
                assertTrue(entities.getThingProjectionMessages().any { it.title == "Thing projection $size" })
            }

            metrics.memoryDeltaBytes = usedMemoryBytes() - memoryBefore
            printMetrics(metrics)

            if (size == 10_000) {
                db.close()
                database = null
                val reopened = openExistingDatabase()
                val reopenedMessages = messageRepository(reopened.database)
                assertEquals(size, reopenedMessages.totalCount())
                val reopenMetric = elapsedMs {
                    assertEquals(PAGE_SIZE, loadMessagePage(reopened.database, MessageFilter(), PAGE_SIZE).data.size)
                }
                println("RUNTIME_DATA_LAYER databaseReopenFirstPageMs=$reopenMetric scale=$size")
            }
        }
    }

    @Test
    fun realRepositories_coverInboundDedupOrderingInvalidEntityAndTaskQueries() = runBlocking {
        printEnvironment()
        val opened = openFreshDatabase()
        val db = opened.database
        val messages = messageRepository(db)
        val entities = entityRepository(db)

        val fcm = generatedMessage(
            index = 1,
            messageId = "dual-delivery-1",
            channel = "fcm",
            receivedAtMs = BASE_TIME_MS + 2_000,
            deliveryId = "delivery-fcm-dual",
            opId = "op-fcm-dual",
            provider = "fcm",
        )
        val private = generatedMessage(
            index = 2,
            messageId = "dual-delivery-1",
            channel = "private",
            receivedAtMs = BASE_TIME_MS + 3_000,
            deliveryId = "delivery-private-dual",
            opId = "op-private-dual",
            provider = "private",
        )
        assertTrue(messages.insertIncoming(fcm))
        assertFalse(messages.insertIncoming(private))
        assertEquals(1, messages.totalCount())
        assertEquals("fcm", messages.getByMessageId("dual-delivery-1")?.channel)

        val newer = generatedMessage(
            index = 3,
            messageId = "late-old-channel",
            channel = "private",
            receivedAtMs = BASE_TIME_MS + 8_000,
            deliveryId = "delivery-newer-channel",
            opId = "op-newer-channel",
            title = "newer channel value",
        )
        val olderLate = generatedMessage(
            index = 4,
            messageId = "late-old-channel",
            channel = "fcm",
            receivedAtMs = BASE_TIME_MS + 1_000,
            deliveryId = "delivery-older-channel",
            opId = "op-older-channel",
            title = "older channel value",
        )
        assertTrue(messages.insertIncoming(newer))
        assertFalse(messages.insertIncoming(olderLate))
        assertEquals("private", messages.getByMessageId("late-old-channel")?.channel)
        assertEquals("newer channel value", messages.getByMessageId("late-old-channel")?.title)

        val sameTimestampA = generatedMessage(5, "same-ts-a", "runtime-channel-0", BASE_TIME_MS + 9_000)
        val sameTimestampB = generatedMessage(6, "same-ts-b", "runtime-channel-0", BASE_TIME_MS + 9_000)
        val old = generatedMessage(7, "old-out-of-order", "runtime-channel-0", BASE_TIME_MS + 10)
        assertTrue(messages.insertIncoming(old))
        assertTrue(messages.insertIncoming(sameTimestampA))
        assertTrue(messages.insertIncoming(sameTimestampB))
        val ordered = loadMessagePage(db, MessageFilter(), pageSize = 10).data
        assertTrue(ordered.indexOfFirst { it.messageId == "same-ts-b" } < ordered.indexOfFirst { it.messageId == "same-ts-a" })
        assertTrue(ordered.indexOfFirst { it.messageId == "same-ts-a" } < ordered.indexOfFirst { it.messageId == "old-out-of-order" })

        val beforeInvalid = messages.totalCount()
        assertFalse(messages.insertIncoming(invalidMessage("invalid-unknown-entity", """{"entity_type":"unknown"}""")))
        assertFalse(messages.insertIncoming(invalidMessage("invalid-malformed-json", "{")))
        assertEquals(beforeInvalid, messages.totalCount())

        val task = generatedMessage(
            index = 8,
            messageId = "task-message-1",
            channel = "runtime-channel-1",
            receivedAtMs = BASE_TIME_MS + 10_000,
            tags = listOf("task", "ops"),
            metadata = mapOf("state" to "triage-open"),
            title = "TaskUnique message",
        )
        assertTrue(messages.insertIncoming(task))
        assertTrue(messages.searchMessages("tag:task", unreadOnly = false).first().any { it.messageId == "task-message-1" })
        assertTrue(messages.searchMessages("tag:ops", unreadOnly = false).first().any { it.messageId == "task-message-1" })
        assertEquals(1, metadataCount(db, keyName = "state", value = "triage-open"))

        val eventRecord = incomingEntity(
            entityType = "event",
            entityId = "event-1",
            eventId = "event-1",
            thingId = null,
            title = "Event projection",
            deliveryId = "delivery-event-1",
            receivedAtMs = BASE_TIME_MS + 11_000,
        )
        val thingRecord = incomingEntity(
            entityType = "thing",
            entityId = "thing-1",
            eventId = null,
            thingId = "thing-1",
            title = "Thing projection",
            deliveryId = "delivery-thing-1",
            receivedAtMs = BASE_TIME_MS + 12_000,
        )
        assertTrue(entities.insertIncoming(eventRecord))
        assertTrue(entities.insertIncoming(thingRecord))

        val eventProjectionMs = elapsedMs {
            val projection = entities.getEventProjectionMessages()
            assertTrue(projection.any { it.title == "Event projection" })
        }
        val thingProjectionMs = elapsedMs {
            val projection = entities.getThingProjectionMessages()
            assertTrue(projection.any { it.title == "Thing projection" })
        }
        println("RUNTIME_DATA_LAYER eventProjectionQueryMs=$eventProjectionMs thingProjectionQueryMs=$thingProjectionMs")

        val ftsMatches = messages.searchMessages("runtime", unreadOnly = false, limit = 20).first()
        assertTrue(ftsMatches.isNotEmpty())
        assertEquals(1, ftsCount(db, "TaskUnique*"))
    }

    @Test
    fun entityProjectionPagesReadFinalHeadsOnlyWhileKeepingChangeLogs() = runBlocking {
        val opened = openFreshDatabase()
        val db = opened.database
        val entities = entityRepository(db)

        assertTrue(
            entities.insertIncoming(
                incomingEntity(
                    entityType = "event",
                    entityId = "event-head-only",
                    eventId = "event-head-only",
                    thingId = null,
                    title = "Event create",
                    deliveryId = "delivery-event-head-create",
                    receivedAtMs = BASE_TIME_MS + 1_000,
                )
            )
        )
        assertTrue(
            entities.insertIncoming(
                incomingEntity(
                    entityType = "event",
                    entityId = "event-head-only",
                    eventId = "event-head-only",
                    thingId = null,
                    title = "Event patched",
                    deliveryId = "delivery-event-head-patch",
                    receivedAtMs = BASE_TIME_MS + 2_000,
                )
            )
        )
        assertTrue(
            entities.insertIncoming(
                incomingEntity(
                    entityType = "thing",
                    entityId = "thing-head-only",
                    eventId = null,
                    thingId = "thing-head-only",
                    title = "Thing create",
                    deliveryId = "delivery-thing-head-create",
                    receivedAtMs = BASE_TIME_MS + 3_000,
                )
            )
        )
        assertTrue(
            entities.insertIncoming(
                incomingEntity(
                    entityType = "thing",
                    entityId = "thing-head-only",
                    eventId = null,
                    thingId = "thing-head-only",
                    title = "Thing patched",
                    deliveryId = "delivery-thing-head-patch",
                    receivedAtMs = BASE_TIME_MS + 4_000,
                )
            )
        )

        assertEquals(1, entities.getEventProjectionMessages().size)
        assertEquals(1, entities.getEventProjectionMessagesPage(before = null, limit = 50).size)
        assertEquals(1, entities.getThingProjectionMessages().size)
        assertEquals(1, entities.getThingProjectionMessagesPage(before = null, limit = 50).size)
        assertEquals(2, db.eventChangeLogDao().countAll())
        assertEquals(2, db.thingChangeLogDao().countAll())
        assertEquals(2, entities.getEventProjectionDetail("event-head-only")?.asMessages()?.size)
        assertEquals(2, entities.getThingProjectionDetail("thing-head-only")?.asMessages()?.size)
    }

    @Test
    fun eventRefreshAndChannelCleanupIncludeThingSubEvents() = runBlocking {
        val opened = openFreshDatabase()
        val db = opened.database
        val entities = entityRepository(db)
        val initialToken = entities.observeEventRefreshToken().first()

        assertTrue(
            entities.insertIncoming(
                incomingEntity(
                    entityType = "thing",
                    entityId = "cleanup-thing",
                    eventId = null,
                    thingId = "cleanup-thing",
                    title = "Cleanup thing",
                    deliveryId = "delivery-cleanup-thing",
                    receivedAtMs = BASE_TIME_MS + 1_000,
                    channel = "cleanup-channel",
                )
            )
        )
        assertTrue(
            entities.insertIncoming(
                incomingEntity(
                    entityType = "event",
                    entityId = "cleanup-top-event",
                    eventId = "cleanup-top-event",
                    thingId = null,
                    title = "Cleanup top event",
                    deliveryId = "delivery-cleanup-top-event",
                    receivedAtMs = BASE_TIME_MS + 2_000,
                    channel = "cleanup-channel",
                )
            )
        )
        assertTrue(
            entities.insertIncoming(
                incomingEntity(
                    entityType = "event",
                    entityId = "cleanup-sub-event",
                    eventId = "cleanup-sub-event",
                    thingId = "cleanup-thing",
                    title = "Cleanup sub event",
                    deliveryId = "delivery-cleanup-sub-event",
                    receivedAtMs = BASE_TIME_MS + 3_000,
                    channel = "cleanup-channel",
                )
            )
        )

        assertNotEquals(initialToken, entities.observeEventRefreshToken().first())
        assertEquals(1, db.eventChangeLogDao().countAll())
        assertEquals(1, db.thingSubEventDao().countAll())
        assertEquals(1, db.thingHeadDao().countAll())

        assertEquals(3, entities.deleteEvents("cleanup-channel"))
        assertEquals(0, db.eventChangeLogDao().countAll())
        assertEquals(0, db.thingSubEventDao().countAll())
        assertEquals(1, db.thingHeadDao().countAll())
    }

    @Test
    fun realRoomDaoFtsAndPaging_optIn100000() = runBlocking {
        assumeTrue(include100k())
        printEnvironment()
        val opened = openFreshDatabase()
        val db = opened.database
        val messages = messageRepository(db)
        val entities = entityRepository(db)
        val metrics = RuntimeMetrics(scale = 100_000)
        metrics.databaseOpenMs = opened.elapsedMs
        val memoryBefore = usedMemoryBytes()

        metrics.bulkWriteMs = elapsedMs {
            insertGeneratedMessages(messages, 100_000)
            insertProjectionBaselineRecords(entities, 100_000)
        }
        assertEquals(100_000, messages.totalCount())

        metrics.firstPageMs = elapsedMs {
            assertEquals(PAGE_SIZE, loadMessagePage(db, MessageFilter(), PAGE_SIZE).data.size)
        }
        metrics.fivePagesMs = elapsedMs {
            assertEquals(PAGE_SIZE * 5, loadPages(db, MessageFilter(), PAGE_SIZE, 5).size)
        }
        metrics.ftsCountMs = elapsedMs {
            assertEquals(100_000, ftsCount(db, "runtime*"))
        }
        metrics.ftsPageMs = elapsedMs {
            assertEquals(PAGE_SIZE, messages.searchMessages("runtime", unreadOnly = false, limit = PAGE_SIZE).first().size)
        }
        metrics.channelFilterMs = elapsedMs {
            assertTrue(loadMessagePage(db, MessageFilter(channels = setOf("runtime-channel-1")), PAGE_SIZE).data.isNotEmpty())
        }
        metrics.tagFilterMs = elapsedMs {
            assertTrue(loadMessagePage(db, MessageFilter(tags = setOf("task")), PAGE_SIZE).data.isNotEmpty())
        }
        metrics.unreadFilterMs = elapsedMs {
            assertTrue(loadMessagePage(db, MessageFilter(unreadOnly = true), PAGE_SIZE).data.all { !it.isRead })
        }
        metrics.eventProjectionMs = elapsedMs {
            assertTrue(entities.getEventProjectionMessages().any { it.title == "Event projection 100000" })
        }
        metrics.thingProjectionMs = elapsedMs {
            assertTrue(entities.getThingProjectionMessages().any { it.title == "Thing projection 100000" })
        }
        metrics.memoryDeltaBytes = usedMemoryBytes() - memoryBefore
        printMetrics(metrics)

        db.close()
        database = null
        val reopened = openExistingDatabase()
        assertEquals(100_000, messageRepository(reopened.database).totalCount())
        val reopenFirstPageMs = elapsedMs {
            assertEquals(PAGE_SIZE, loadMessagePage(reopened.database, MessageFilter(), PAGE_SIZE).data.size)
        }
        println("RUNTIME_DATA_LAYER databaseReopenFirstPageMs=$reopenFirstPageMs scale=100000")
    }

    private fun openFreshDatabase(): OpenedDatabase {
        database?.close()
        database = null
        cleanupDatabase()
        return openExistingDatabase()
    }

    private fun openExistingDatabase(): OpenedDatabase {
        lateinit var opened: PushGoDatabase
        val ms = elapsedBlockingMs {
            opened = Room.databaseBuilder(context, PushGoDatabase::class.java, DATABASE_NAME)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
            opened.openHelper.writableDatabase.query("PRAGMA journal_mode=WAL").close()
            opened.openHelper.writableDatabase.query("PRAGMA synchronous=NORMAL").close()
        }
        database = opened
        return OpenedDatabase(opened, ms)
    }

    private suspend fun insertGeneratedMessages(repository: MessageRepository, size: Int) {
        var start = 0
        while (start < size) {
            val end = min(start + INSERT_BATCH_SIZE, size)
            val batch = (start until end).map { index -> generatedMessage(index) }
            repository.insertAll(batch)
            start = end
        }
    }

    private suspend fun insertProjectionBaselineRecords(repository: EntityRepository, scale: Int) {
        repository.insertIncoming(
            incomingEntity(
                entityType = "event",
                entityId = "event-scale-$scale",
                eventId = "event-scale-$scale",
                thingId = null,
                title = "Event projection $scale",
                deliveryId = "delivery-event-scale-$scale",
                receivedAtMs = BASE_TIME_MS + scale + 100_000,
            )
        )
        repository.insertIncoming(
            incomingEntity(
                entityType = "thing",
                entityId = "thing-scale-$scale",
                eventId = null,
                thingId = "thing-scale-$scale",
                title = "Thing projection $scale",
                deliveryId = "delivery-thing-scale-$scale",
                receivedAtMs = BASE_TIME_MS + scale + 200_000,
            )
        )
    }

    private fun messageRepository(db: PushGoDatabase): MessageRepository {
        return MessageRepository(
            database = db,
            dao = db.messageDao(),
            channelStatsDao = db.messageChannelStatsDao(),
            metadataIndexDao = db.messageMetadataIndexDao(),
            inboundDeliveryLedgerDao = db.inboundDeliveryLedgerDao(),
            operationLedgerDao = db.operationLedgerDao(),
            thingHeadDao = db.thingHeadDao(),
            thingSubMessageDao = db.thingSubMessageDao(),
            pendingThingMessageDao = db.pendingThingMessageDao(),
        )
    }

    private fun entityRepository(db: PushGoDatabase): EntityRepository {
        return EntityRepository(
            database = db,
            inboundDeliveryLedgerDao = db.inboundDeliveryLedgerDao(),
            operationLedgerDao = db.operationLedgerDao(),
            eventChangeLogDao = db.eventChangeLogDao(),
            thingChangeLogDao = db.thingChangeLogDao(),
            thingSubEventDao = db.thingSubEventDao(),
            topLevelEventHeadDao = db.topLevelEventHeadDao(),
            thingHeadDao = db.thingHeadDao(),
            thingSubMessageDao = db.thingSubMessageDao(),
            pendingThingEventDao = db.pendingThingEventDao(),
        )
    }

    private suspend fun loadMessagePage(
        db: PushGoDatabase,
        filter: MessageFilter,
        pageSize: Int,
    ): PagingSource.LoadResult.Page<Int, MessageEntity> {
        val source = db.messageDao().observeMessages(
            readState = if (filter.unreadOnly) false else null,
            withUrl = if (filter.withUrlOnly) 1 else 0,
            channels = filter.channels.toList(),
            channelCount = filter.channels.size,
            tags = filter.tags.toList(),
            tagCount = filter.tags.size,
            serverId = filter.serverId,
            prioritizeUnread = 0,
        )
        val result = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = pageSize,
                placeholdersEnabled = false,
            )
        )
        assertTrue(result is PagingSource.LoadResult.Page)
        @Suppress("UNCHECKED_CAST")
        return result as PagingSource.LoadResult.Page<Int, MessageEntity>
    }

    private suspend fun loadPages(
        db: PushGoDatabase,
        filter: MessageFilter,
        pageSize: Int,
        pageCount: Int,
    ): List<MessageEntity> {
        val source = db.messageDao().observeMessages(
            readState = if (filter.unreadOnly) false else null,
            withUrl = if (filter.withUrlOnly) 1 else 0,
            channels = filter.channels.toList(),
            channelCount = filter.channels.size,
            tags = filter.tags.toList(),
            tagCount = filter.tags.size,
            serverId = filter.serverId,
            prioritizeUnread = 0,
        )
        val loaded = mutableListOf<MessageEntity>()
        var result = source.load(PagingSource.LoadParams.Refresh(null, pageSize, false))
        repeat(pageCount) {
            assertTrue(result is PagingSource.LoadResult.Page)
            val page = result as PagingSource.LoadResult.Page<Int, MessageEntity>
            loaded += page.data
            val next = page.nextKey ?: return loaded
            result = source.load(PagingSource.LoadParams.Append(next, pageSize, false))
        }
        return loaded
    }

    private fun ftsCount(db: PushGoDatabase, query: String): Int {
        val escapedQuery = query.replace("'", "''")
        val sql = """
            SELECT COUNT(*)
            FROM messages m
            JOIN message_fts f ON m.rowid = f.rowid
            WHERE message_fts MATCH '$escapedQuery'
        """.trimIndent()
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    private fun metadataCount(db: PushGoDatabase, keyName: String, value: String): Int {
        val safeKey = keyName.replace("'", "''")
        val safeValue = value.replace("'", "''")
        val sql = """
            SELECT COUNT(*)
            FROM message_metadata_index
            WHERE key_name = '$safeKey'
              AND value_norm = '$safeValue'
        """.trimIndent()
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    private fun generatedMessage(
        index: Int,
        messageId: String = "runtime-message-$index",
        channel: String = "runtime-channel-${index % 4}",
        receivedAtMs: Long = BASE_TIME_MS + index,
        deliveryId: String = "delivery-$index",
        opId: String = "op-$index",
        tags: List<String> = if (index % 5 == 0) listOf("task", "ops") else listOf("ops"),
        metadata: Map<String, String> = if (index % 5 == 0) mapOf("state" to "open") else emptyMap(),
        title: String = "Runtime title $index",
        provider: String? = null,
    ): PushMessage {
        val body = "runtime body $index channel $channel ${if (tags.contains("task")) "task" else "message"}"
        val rawPayload = JSONObject()
            .put("entity_type", "message")
            .put("entity_id", messageId)
            .put("message_id", messageId)
            .put("delivery_id", deliveryId)
            .put("op_id", opId)
            .put("channel", channel)
            .put("tags", JSONArray(tags).toString())
            .put("metadata", JSONObject(metadata).toString())
        provider?.let { rawPayload.put("provider", it) }
        return PushMessage(
            id = "local-$messageId-$index",
            messageId = messageId,
            title = title,
            body = body,
            channel = channel,
            url = if (index % 7 == 0) "https://pushgo.example/messages/$index" else null,
            isRead = index % 3 == 0,
            receivedAt = Instant.ofEpochMilli(receivedAtMs),
            rawPayloadJson = rawPayload.toString(),
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = "notification-$index",
            serverId = "runtime-server",
            bodyPreview = null,
        )
    }

    private fun invalidMessage(id: String, rawPayload: String): PushMessage {
        return PushMessage(
            id = id,
            messageId = id,
            title = "Invalid",
            body = "Invalid",
            channel = "invalid",
            url = null,
            isRead = false,
            receivedAt = Instant.ofEpochMilli(BASE_TIME_MS),
            rawPayloadJson = rawPayload,
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = "runtime-server",
            bodyPreview = null,
        )
    }

    private fun incomingEntity(
        entityType: String,
        entityId: String,
        eventId: String?,
        thingId: String?,
        title: String,
        deliveryId: String,
        receivedAtMs: Long,
        channel: String = "entity-channel",
    ): IncomingEntityRecord {
        val rawPayload = JSONObject()
            .put("entity_type", entityType)
            .put("entity_id", entityId)
            .put("delivery_id", deliveryId)
            .put("op_id", "op-$deliveryId")
            .put("event_time", Instant.ofEpochMilli(receivedAtMs).toString())
        eventId?.let { rawPayload.put("event_id", it) }
        thingId?.let { rawPayload.put("thing_id", it) }
        return IncomingEntityRecord(
            entityType = entityType,
            entityId = entityId,
            channel = channel,
            title = title,
            body = "$title body",
            rawPayloadJson = rawPayload.toString(),
            receivedAt = Instant.ofEpochMilli(receivedAtMs),
            opId = "op-$deliveryId",
            deliveryId = deliveryId,
            serverId = "runtime-server",
            eventId = eventId,
            thingId = thingId,
            eventState = if (entityType == "event") "open" else null,
            eventTimeEpoch = receivedAtMs,
            observedTimeEpoch = if (entityType == "thing") receivedAtMs else null,
        )
    }

    private fun payloadTags(rawPayloadJson: String): List<String> {
        val raw = runCatching { JSONObject(rawPayloadJson).optString("tags") }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) }
        }.getOrDefault(emptyList())
    }

    private fun assertNewestFirst(messages: List<MessageEntity>) {
        messages.zipWithNext().forEach { (left, right) ->
            val ordered = left.receivedAt > right.receivedAt ||
                (left.receivedAt == right.receivedAt && left.id >= right.id)
            assertTrue("messages are not newest-first: ${left.id} before ${right.id}", ordered)
        }
    }

    private fun unreadCountFor(size: Int): Int = size - ((size + 2) / 3)

    private fun include100k(): Boolean {
        val args = InstrumentationRegistry.getArguments()
        return args.getString("pushgo.runtime.include100k")?.toBooleanStrictOrNull() == true
    }

    private fun cleanupDatabase() {
        context.deleteDatabase(DATABASE_NAME)
        context.getDatabasePath(DATABASE_NAME).delete()
        context.getDatabasePath("$DATABASE_NAME-wal").delete()
        context.getDatabasePath("$DATABASE_NAME-shm").delete()
    }

    private fun printEnvironment() {
        val device = Build.MODEL
        val version = Build.VERSION.RELEASE
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        println("RUNTIME_DATA_LAYER emulator=$device android=$version abi=$abi variant=debug")
    }

    private fun printMetrics(metrics: RuntimeMetrics) {
        println(
            "RUNTIME_DATA_LAYER scale=${metrics.scale} " +
                "databaseOpenMs=${metrics.databaseOpenMs} " +
                "bulkWriteMs=${metrics.bulkWriteMs} " +
                "firstPageMs=${metrics.firstPageMs} " +
                "fivePagesMs=${metrics.fivePagesMs} " +
                "ftsCountMs=${metrics.ftsCountMs} " +
                "ftsPageMs=${metrics.ftsPageMs} " +
                "channelFilterMs=${metrics.channelFilterMs} " +
                "tagFilterMs=${metrics.tagFilterMs} " +
                "unreadFilterMs=${metrics.unreadFilterMs} " +
                "eventProjectionMs=${metrics.eventProjectionMs} " +
                "thingProjectionMs=${metrics.thingProjectionMs} " +
                "memoryDeltaBytes=${metrics.memoryDeltaBytes}"
        )
    }

    private fun usedMemoryBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private suspend fun elapsedMs(block: suspend () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun elapsedBlockingMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private data class OpenedDatabase(
        val database: PushGoDatabase,
        val elapsedMs: Long,
    )

    private data class RuntimeMetrics(
        val scale: Int,
        var databaseOpenMs: Long = 0,
        var bulkWriteMs: Long = 0,
        var firstPageMs: Long = 0,
        var fivePagesMs: Long = 0,
        var ftsCountMs: Long = 0,
        var ftsPageMs: Long = 0,
        var channelFilterMs: Long = 0,
        var tagFilterMs: Long = 0,
        var unreadFilterMs: Long = 0,
        var eventProjectionMs: Long = 0,
        var thingProjectionMs: Long = 0,
        var memoryDeltaBytes: Long = 0,
    )

    private companion object {
        private const val DATABASE_NAME = "pushgo-runtime-quality.db"
        private const val BASE_TIME_MS = 1_710_000_000_000L
        private const val PAGE_SIZE = 50
        private const val INSERT_BATCH_SIZE = 1_000
    }
}
