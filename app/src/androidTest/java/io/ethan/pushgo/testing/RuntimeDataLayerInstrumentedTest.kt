package io.ethan.pushgo.testing

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import io.ethan.pushgo.data.db.MessageListRow
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageFilter
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.R
import io.ethan.pushgo.notifications.MessageStateCoordinator
import io.ethan.pushgo.ui.screens.buildThingCardsInternal
import io.ethan.pushgo.ui.screens.thingMatchesSearch
import java.time.Instant
import kotlin.math.min
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    fun realRoomDaoSearchAndPaging_defaultScaleMatrixThrough10000() = runBlocking {
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
            metrics.searchPageMs = elapsedMs {
                val result = messages.searchMessagesSnapshot("runtime", unreadOnly = false, limit = PAGE_SIZE)
                assertEquals(min(PAGE_SIZE, size), result.size)
            }
            metrics.channelFilterMs = elapsedMs {
                val result = loadMessagePage(db, MessageFilter(channels = setOf("runtime-channel-1")), PAGE_SIZE).data
                assertTrue(result.all { it.channel == "runtime-channel-1" })
            }
            metrics.tagFilterMs = elapsedMs {
                val result = loadMessagePage(db, MessageFilter(tags = setOf("task")), PAGE_SIZE).data
                assertTrue(result.all { payloadTags(it.listPayloadJson).contains("task") })
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
    fun durableMessageDeleteReplayCancelsNotificationWhenRoomRowIsAlreadyGone() = runBlocking {
        grantNotificationPermissionIfNeeded()
        val db = openFreshDatabase().database
        val messageId = "durable-delete-replay-message"
        val notificationId = messageId.hashCode()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                DELETE_REPLAY_NOTIFICATION_CHANNEL,
                "Delete replay",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        try {
            manager.notify(
                notificationId,
                Notification.Builder(context, DELETE_REPLAY_NOTIFICATION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_pushgo)
                    .setContentTitle("stale notification")
                    .build(),
            )
            assertTrue(awaitNotificationState(manager, notificationId, expectedActive = true))

            val deleted = MessageStateCoordinator(context, messageRepository(db))
                .deleteMessages(listOf(messageId))

            assertEquals(0, deleted)
            assertTrue(awaitNotificationState(manager, notificationId, expectedActive = false))
        } finally {
            manager.cancel(notificationId)
            manager.deleteNotificationChannel(DELETE_REPLAY_NOTIFICATION_CHANNEL)
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
        assertTrue(messages.searchMessagesSnapshot("tag:task", unreadOnly = false, limit = 20).any { it.messageId == "task-message-1" })
        assertTrue(messages.searchMessagesSnapshot("tag:ops", unreadOnly = false, limit = 20).any { it.messageId == "task-message-1" })
        assertEquals(1, metadataCount(db, keyName = "metadata_state", value = "triage-open"))

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

        val ftsMatches = messages.searchMessagesSnapshot("runtime", unreadOnly = false, limit = 20)
        assertTrue(ftsMatches.isNotEmpty())
        assertEquals(1, ftsCount(db, "TaskUnique*"))
    }

    @Test
    fun messageSearchMatchesNormalizedLiteralSubstringsAndComposesTagsUnreadAndExclusions() = runBlocking {
        val db = openFreshDatabase().database
        val messages = messageRepository(db)
        val read = generatedMessage(
            index = 300,
            messageId = "normalized-read",
            tags = listOf("ops", "parity"),
            title = "Préfixe ＡＬＰＨＡ suffix %_literal",
            body = "BodyNeedle 数据库",
            isRead = true,
        )
        val unread = generatedMessage(
            index = 301,
            messageId = "normalized-unread",
            tags = listOf("ops", "parity"),
            title = "Préfixe ＡＬＰＨＡ suffix %_literal",
            body = "BodyNeedle 数据库",
            isRead = false,
        )
        messages.insertAll(listOf(read, unread))

        suspend fun ids(query: String, unreadOnly: Boolean = false, excludedIds: Set<String> = emptySet()) =
            messages.searchMessagesSnapshot(query, unreadOnly, limit = 20, excludedIds = excludedIds)
                .mapNotNull { it.messageId }
                .toSet()

        assertEquals(setOf("normalized-read", "normalized-unread"), ids("FIXE"))
        assertEquals(setOf("normalized-read", "normalized-unread"), ids("alpha bodyneedle tag:ops"))
        assertEquals(setOf("normalized-read", "normalized-unread"), ids("%_lit"))
        assertEquals(setOf("normalized-read", "normalized-unread"), ids("据库"))
        assertEquals(setOf("normalized-unread"), ids("alpha tag:parity", unreadOnly = true))
        assertEquals(emptySet<String>(), ids("alpha tag:missing"))
        assertEquals(
            setOf("normalized-read"),
            ids("alpha", excludedIds = setOf(unread.id)),
        )
    }

    @Test
    fun messageSearchBoundsTokensTagsAndMixedExclusionsWithoutRoomBindOverflow() = runBlocking {
        val db = openFreshDatabase().database
        val messages = messageRepository(db)
        val target = generatedMessage(
            index = 350,
            messageId = "bounded-search-target",
            tags = listOf("task"),
            title = "one two three four five six needle ${"x".repeat(40)}",
        )
        messages.insert(target)

        suspend fun ids(query: String, excludedIds: Set<String> = emptySet()) =
            messages.searchMessagesSnapshot(query, unreadOnly = false, limit = 20, excludedIds = excludedIds)
                .mapNotNull { it.messageId }
                .toSet()

        assertEquals(setOf(target.messageId), ids("one"))
        assertEquals(setOf(target.messageId), ids("one two three four five six"))
        // The seventh token is outside the six-token contract and is ignored.
        assertEquals(setOf(target.messageId), ids("one two three four five six absent"))
        assertEquals(setOf(target.messageId), ids("one one one one one one one"))
        // Search tokens are bounded to 32 normalized characters.
        assertEquals(setOf(target.messageId), ids("${"x".repeat(40)}"))

        val mixedExclusions = linkedSetOf(target.id).apply {
            addAll((0 until 1_000).map { "not-present-$it" })
        }
        assertTrue(ids("tag:task", mixedExclusions).isEmpty())

        // Around one thousand tags must be bounded before Room expands IN arguments.
        assertTrue(ids((0 until 1_000).joinToString(" ") { "#tag-$it" }).isEmpty())
    }

    @Test
    fun textSearchWaitsForNormalizedLegacyBackfillAndBlankMessagesReachReadySentinel() = runBlocking {
        val db = openFreshDatabase().database
        val messages = messageRepository(db)
        val legacy = generatedMessage(
            index = 410,
            messageId = "legacy-search-shadow",
            title = "Historical Résumé ＦＵＬＬＷＩＤＴＨ suffix",
            body = "legacy body",
        )
        val blank = generatedMessage(
            index = 411,
            messageId = "blank-search-shadow",
            channel = "",
            title = "",
            body = "",
            tags = emptyList(),
            metadata = emptyMap(),
        )
        messages.insertAll(listOf(legacy, blank))
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM message_metadata_index WHERE key_name = 'search_text'"
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE message_derived_state SET status = 'ready' WHERE component = 'message_metadata_index'"
        )

        val matches = coroutineScope {
            val startupBackfill = async { messages.backfillTagMetadataIndexIfNeeded(context) }
            val search = async {
                messages.searchMessagesSnapshot("resume fullwidth suf", false, 20)
            }
            val result = search.await()
            startupBackfill.await()
            result
        }
        assertEquals(listOf("legacy-search-shadow"), matches.mapNotNull { it.messageId })
        db.openHelper.readableDatabase.query(
            """
            SELECT value_norm, label
            FROM message_metadata_index
            WHERE message_id = ? AND key_name = 'search_text'
            """.trimIndent(),
            arrayOf(legacy.id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("normalization_v1", cursor.getString(0))
            assertTrue(cursor.getString(1).contains("historical resume fullwidth suffix"))
        }
        db.openHelper.readableDatabase.query(
            """
            SELECT value_norm, label
            FROM message_metadata_index
            WHERE message_id = ? AND key_name = 'search_text'
            """.trimIndent(),
            arrayOf(blank.id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("normalization_v1", cursor.getString(0))
            assertEquals("", cursor.getString(1))
        }
        assertEquals(0, db.messageMetadataIndexDao().countMessagesMissingSearchText("normalization_v1"))

        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TEMP TRIGGER fail_search_metadata_second_pass
            BEFORE DELETE ON message_metadata_index
            BEGIN SELECT RAISE(ABORT, 'search metadata backfill was not a no-op'); END
            """.trimIndent()
        )
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TEMP TRIGGER fail_search_state_second_pass
            BEFORE UPDATE ON message_derived_state
            BEGIN SELECT RAISE(ABORT, 'search readiness update was not a no-op'); END
            """.trimIndent()
        )
        messageRepository(db).backfillTagMetadataIndexIfNeeded(context)
    }

    @Test
    fun completeSearchIndexRepairsStaleSentinelWithoutRewritingMessages() = runBlocking {
        val db = openFreshDatabase().database
        val messages = messageRepository(db)
        insertGeneratedMessages(messages, 1_000)
        assertEquals(0, db.messageMetadataIndexDao().countMessagesMissingSearchText("normalization_v1"))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE message_derived_state SET status = 'stale' WHERE component = 'message_metadata_index'"
        )
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TEMP TRIGGER fail_complete_search_index_rewrite
            BEFORE UPDATE OF list_payload_json ON messages
            BEGIN SELECT RAISE(ABORT, 'complete search index must not be rebuilt'); END
            """.trimIndent()
        )

        val result = messageRepository(db)
            .searchMessagesSnapshot("runtime", unreadOnly = false, limit = PAGE_SIZE)

        assertEquals(PAGE_SIZE, result.size)
        db.openHelper.readableDatabase.query(
            "SELECT status FROM message_derived_state WHERE component = 'message_metadata_index'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ready", cursor.getString(0))
        }
    }

    @Test
    fun oneMissingSearchRowRepairsOnlyThatMessage() = runBlocking {
        val db = openFreshDatabase().database
        val messages = messageRepository(db)
        insertGeneratedMessages(messages, 1_000)
        val target = requireNotNull(messages.getByMessageId("runtime-message-999"))
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM message_metadata_index WHERE message_id = ? AND key_name = 'search_text'",
            arrayOf(target.id),
        )
        assertEquals(1, db.messageMetadataIndexDao().countMessagesMissingSearchText("normalization_v1"))
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TEMP TRIGGER fail_unrelated_search_metadata_rewrite
            BEFORE DELETE ON message_metadata_index
            WHEN OLD.message_id != '${target.id}'
            BEGIN SELECT RAISE(ABORT, 'search repair touched an indexed message'); END
            """.trimIndent()
        )
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TEMP TRIGGER fail_search_list_payload_rewrite
            BEFORE UPDATE OF list_payload_json ON messages
            BEGIN SELECT RAISE(ABORT, 'search repair touched the summary projection'); END
            """.trimIndent()
        )

        val result = messages.searchMessagesSnapshot("runtime", unreadOnly = false, limit = PAGE_SIZE)

        assertEquals(PAGE_SIZE, result.size)
        assertEquals(0, db.messageMetadataIndexDao().countMessagesMissingSearchText("normalization_v1"))
        assertEquals(0, db.messageDao().countMessagesMissingSummaryProjection("projection_v1"))
    }

    @Test
    fun legitimatelyEmptySummaryProjectionUsesExplicitCompletionMarker() = runBlocking {
        val db = openFreshDatabase().database
        val messages = messageRepository(db)
        val rawPayload = """{"custom_payload":"preserved outside the list projection"}"""
        val entity = MessageEntity.fromModel(invalidMessage("empty-summary", rawPayload))
        db.messageDao().insert(entity)

        messages.backfillTagMetadataIndexIfNeeded(context)

        assertEquals(0, db.messageDao().countMessagesMissingSummaryProjection("projection_v1"))
        val row = loadMessagePage(db, MessageFilter(), pageSize = PAGE_SIZE).data.single()
        assertEquals("{}", row.listPayloadJson)
        db.openHelper.readableDatabase.query(
            """
            SELECT COUNT(*) FROM message_metadata_index
            WHERE message_id = ?
              AND key_name = 'summary_projection'
              AND value_norm = 'projection_v1'
            """.trimIndent(),
            arrayOf(entity.id),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun thingProjectionPageBatchesAssociatedMessagesIntoRealRepositorySearchModel() = runBlocking {
        val db = openFreshDatabase().database
        val entities = entityRepository(db)
        val messages = messageRepository(db)
        val thingRecord = incomingEntity(
            entityType = "thing",
            entityId = "thing-associated-search",
            eventId = null,
            thingId = "thing-associated-search",
            title = "Cooling pump",
            deliveryId = "delivery-thing-associated-search",
            receivedAtMs = BASE_TIME_MS + 500,
            channel = "thing-channel",
        ).let { record ->
            record.copy(
                rawPayloadJson = JSONObject(record.rawPayloadJson)
                    .put("attrs", JSONObject().put("owner", "canonical-thing"))
                    .put("metadata", JSONObject().put("source", "thing-head"))
                    .put("location", JSONObject().put("type", "plant").put("value", "zone-a"))
                    .put("external_ids", JSONObject().put("asset", "asset-42"))
                    .toString(),
            )
        }
        assertTrue(entities.insertIncoming(thingRecord))
        val relatedPayload = JSONObject()
            .put("entity_type", "message")
            .put("entity_id", "associated-message")
            .put("message_id", "associated-message")
            .put("delivery_id", "delivery-associated-message")
            .put("op_id", "op-associated-message")
            .put("thing_id", "thing-associated-search")
            .put("attrs", JSONObject().put("owner", "poison-message"))
            .put("metadata", JSONObject().put("source", "message-child"))
            .put("location", JSONObject().put("type", "poison").put("value", "zone-z"))
            .put("external_ids", JSONObject().put("asset", "poison-asset"))
        messages.insert(
            generatedMessage(
                index = 512,
                messageId = "associated-message",
                title = "Vibración ＦＵＬＬＷＩＤＴＨ",
                body = "Historical résumé needle",
                channel = "message-channel",
            ).copy(rawPayloadJson = relatedPayload.toString())
        )

        val page = entities.getThingProjectionPage(before = null, limit = 40)
        assertEquals(1, page.headMessages.size)
        assertEquals(1, page.relatedMessages.size)
        val card = buildThingCardsInternal(page.asMessages()).single()
        assertEquals(listOf("associated-message"), card.relatedMessages.mapNotNull { it.message.messageId })
        assertEquals("thing-channel", card.channelId)
        assertEquals("plant", card.locationType)
        assertEquals("zone-a", card.locationValue)
        assertEquals(mapOf("asset" to "asset-42"), card.externalIds)
        assertTrue(card.attrsJson.orEmpty().contains("canonical-thing"))
        assertFalse(card.attrsJson.orEmpty().contains("poison-message"))
        assertTrue(card.metadataJson.orEmpty().contains("thing-head"))
        assertFalse(card.metadataJson.orEmpty().contains("message-child"))
        assertTrue(thingMatchesSearch(card, "vibracion fullwidth"))
        assertTrue(thingMatchesSearch(card, "resume needle"))
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
    fun entityHeadsRejectLateOlderOperationsWhileRetainingChangeLogs() = runBlocking {
        val opened = openFreshDatabase()
        val db = opened.database
        val entities = entityRepository(db)

        assertTrue(entities.insertIncoming(incomingEntity(
            entityType = "event", entityId = "ordered-event", eventId = "ordered-event", thingId = null,
            title = "new event", deliveryId = "event-new", receivedAtMs = BASE_TIME_MS + 2_000,
        )))
        assertTrue(entities.insertIncoming(incomingEntity(
            entityType = "event", entityId = "ordered-event", eventId = "ordered-event", thingId = null,
            title = "old event", deliveryId = "event-old", receivedAtMs = BASE_TIME_MS + 1_000,
        )))
        val eventWithoutBusinessTime = incomingEntity(
            entityType = "event", entityId = "ordered-event", eventId = "ordered-event", thingId = null,
            title = "latest event", deliveryId = "event-latest", receivedAtMs = BASE_TIME_MS + 5_000,
        ).let { record ->
            record.copy(
                rawPayloadJson = JSONObject(record.rawPayloadJson).apply {
                    remove("event_time")
                    put("title", "latest event")
                }.toString(),
                eventTimeEpoch = null,
            )
        }
        assertTrue(entities.insertIncoming(eventWithoutBusinessTime))
        assertTrue(entities.insertIncoming(incomingEntity(
            entityType = "event", entityId = "ordered-event", eventId = "ordered-event", thingId = null,
            title = "stale explicit event", deliveryId = "event-stale-explicit", receivedAtMs = BASE_TIME_MS + 4_500,
        )))
        assertTrue(entities.insertIncoming(incomingEntity(
            entityType = "thing", entityId = "ordered-thing", eventId = null, thingId = "ordered-thing",
            title = "new thing", deliveryId = "thing-new", receivedAtMs = BASE_TIME_MS + 4_000,
        )))
        assertTrue(entities.insertIncoming(incomingEntity(
            entityType = "thing", entityId = "ordered-thing", eventId = null, thingId = "ordered-thing",
            title = "old thing", deliveryId = "thing-old", receivedAtMs = BASE_TIME_MS + 3_000,
        )))
        val thingWithoutBusinessTime = incomingEntity(
            entityType = "thing", entityId = "ordered-thing", eventId = null, thingId = "ordered-thing",
            title = "latest thing", deliveryId = "thing-latest", receivedAtMs = BASE_TIME_MS + 7_000,
        ).let { record ->
            record.copy(
                rawPayloadJson = JSONObject(record.rawPayloadJson).apply {
                    remove("event_time")
                    put("title", "latest thing")
                }.toString(),
                eventTimeEpoch = null,
                observedTimeEpoch = null,
            )
        }
        assertTrue(entities.insertIncoming(thingWithoutBusinessTime))
        assertTrue(entities.insertIncoming(incomingEntity(
            entityType = "thing", entityId = "ordered-thing", eventId = null, thingId = "ordered-thing",
            title = "stale explicit thing", deliveryId = "thing-stale-explicit", receivedAtMs = BASE_TIME_MS + 6_500,
        )))

        assertEquals("latest event", db.topLevelEventHeadDao().getByEventId("ordered-event")?.title)
        assertEquals("latest thing", db.thingHeadDao().getByThingId("ordered-thing")?.title)
        assertEquals(4, db.eventChangeLogDao().countAll())
        assertEquals(4, db.thingChangeLogDao().countAll())
    }

    @Test
    fun thingSubMessageCanBeLoadedAndDeletedByNotificationLocalId() = runBlocking {
        val opened = openFreshDatabase()
        val db = opened.database
        val entities = entityRepository(db)
        val messages = messageRepository(db)
        val thingId = "notification-thing"
        assertTrue(entities.insertIncoming(incomingEntity(
            entityType = "thing", entityId = thingId, eventId = null, thingId = thingId,
            title = "Thing", deliveryId = "thing-head", receivedAtMs = BASE_TIME_MS,
        )))
        val child = generatedMessage(index = 8_001, messageId = "thing-child-notification").copy(
            rawPayloadJson = JSONObject()
                .put("entity_type", "message")
                .put("entity_id", "thing-child-notification")
                .put("message_id", "thing-child-notification")
                .put("thing_id", thingId)
                .toString(),
        )

        messages.insertAll(listOf(child))

        assertEquals(child.body, messages.getById(child.id)?.body)
        assertEquals(1, messages.deleteByIds(listOf(child.id)))
        assertEquals(null, messages.getById(child.id))
    }

    @Test
    fun facetFilteringUsesCompleteIndexWhileListPayloadRemainsBounded() = runBlocking {
        val db = openFreshDatabase().database
        val messages = messageRepository(db)
        val longTag = "long-${"x".repeat(100)}"
        val tags = (0 until 20).map { "tag-$it" } + longTag
        val message = generatedMessage(index = 8_002, tags = tags)
        messages.insertAll(listOf(message))

        val seventeenthTagPage = loadMessagePage(db, MessageFilter(tags = setOf("tag-16")), PAGE_SIZE)
        assertEquals(listOf(message.id), seventeenthTagPage.data.map { it.id })
        assertTrue(
            seventeenthTagPage.data.single().listPayloadJson.toByteArray(Charsets.UTF_8).size <=
                MessageEntity.MAX_LIST_PAYLOAD_BYTES
        )

        val searchSource = db.messageDao().searchMessages(
            normalizedTokens = "runtime",
            searchTextVersion = "normalization_v1",
            readState = null,
            channels = listOf(message.channel.orEmpty()),
            channelCount = 1,
            facetTags = listOf(longTag),
            facetTagCount = 1,
            excludedIds = emptyList(),
            excludedCount = 0,
        )
        val searchResult = searchSource.load(PagingSource.LoadParams.Refresh(null, PAGE_SIZE, false))
        assertTrue(searchResult is PagingSource.LoadResult.Page)
        val searchPage = searchResult as PagingSource.LoadResult.Page<Int, MessageListRow>
        assertEquals(listOf(message.id), searchPage.data.map { it.id })
    }

    @Test
    fun listProjectionNeverFallsBackToUnboundedRawPayloadWithoutMarker() = runBlocking {
        val db = openFreshDatabase().database
        val messages = messageRepository(db)
        val message = generatedMessage(index = 8_003)
        messages.insertAll(listOf(message))
        db.messageMetadataIndexDao().deleteSummaryProjectionMarker(message.id)
        db.messageDao().updateListPayload(message.id, "{}")
        db.messageDao().updateRawPayload(
            message.id,
            JSONObject().put("metadata", "m".repeat(1_000_000)).toString(),
        )

        val page = loadMessagePage(db, MessageFilter(), PAGE_SIZE)

        assertEquals("{}", page.data.single { it.id == message.id }.listPayloadJson)
    }

    @Test
    fun thingProjectionPageHydratesChildHistoryWithPerThingAndTotalBounds() = runBlocking {
        val opened = openFreshDatabase()
        val db = opened.database
        val entities = entityRepository(db)
        val messages = messageRepository(db)
        val thingId = "child-heavy-thing"
        assertTrue(
            entities.insertIncoming(
                incomingEntity(
                    entityType = "thing",
                    entityId = thingId,
                    eventId = null,
                    thingId = thingId,
                    title = "Child-heavy thing",
                    deliveryId = "delivery-$thingId-head",
                    receivedAtMs = BASE_TIME_MS,
                )
            )
        )
        messages.insertAll(
            (0 until 25).map { index ->
                val message = generatedMessage(
                    index = 4_000 + index,
                    messageId = "child-heavy-message-$index",
                    receivedAtMs = BASE_TIME_MS + 1_000 + index,
                    title = if (index == 24) "Ｐｕｍｐ Café" else "Child history $index",
                    body = if (index == 24) "Résumé bearing needle" else "child body $index",
                )
                message.copy(
                    rawPayloadJson = JSONObject(message.rawPayloadJson)
                        .put("thing_id", thingId)
                        .toString(),
                )
            }
        )

        val page = entities.getThingProjectionPage(before = null, limit = 40)
        assertEquals(1, page.headMessages.size)
        assertEquals(8, page.relatedMessages.size)
        assertTrue(page.hasMoreRelatedMessages)
        val card = buildThingCardsInternal(
            messages = page.asMessages(),
            hasMoreRelatedMessages = page.hasMoreRelatedMessages,
        ).single()
        assertEquals(8, card.relatedMessages.size)
        assertTrue(card.hasMoreRelatedMessages)
        val lateChildSearch = entities.searchThingIdsByRelatedMessageText("child-heavy-message-0")
        assertEquals(listOf(thingId), lateChildSearch.thingIds)
        assertFalse(lateChildSearch.hasMore)
        assertEquals(
            listOf(thingId),
            entities.searchThingIdsByRelatedMessageText("pump cafe").thingIds,
        )
        assertEquals(26, entities.getThingProjectionDetail(thingId)?.asMessages()?.size)
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
    fun realRoomDaoSearchAndPaging_optIn100000() = runBlocking {
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
        metrics.searchPageMs = elapsedMs {
            assertEquals(PAGE_SIZE, messages.searchMessagesSnapshot("runtime", unreadOnly = false, limit = PAGE_SIZE).size)
        }
        assertTrue(
            "100k search page exceeded ${SEARCH_100K_MAX_MS}ms: ${metrics.searchPageMs}ms",
            metrics.searchPageMs <= SEARCH_100K_MAX_MS,
        )
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
            opened = PushGoDatabase.build(context)
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
    ): PagingSource.LoadResult.Page<Int, MessageListRow> {
        val source = db.messageDao().observeMessages(
            readState = if (filter.unreadOnly) false else null,
            withUrl = if (filter.withUrlOnly) 1 else 0,
            channels = filter.channels.toList(),
            channelCount = filter.channels.size,
            tags = filter.tags.toList(),
            tagCount = filter.tags.size,
            serverId = filter.serverId,
            excludedIds = emptyList(),
            excludedCount = 0,
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
        return result as PagingSource.LoadResult.Page<Int, MessageListRow>
    }

    private suspend fun loadPages(
        db: PushGoDatabase,
        filter: MessageFilter,
        pageSize: Int,
        pageCount: Int,
    ): List<MessageListRow> {
        val source = db.messageDao().observeMessages(
            readState = if (filter.unreadOnly) false else null,
            withUrl = if (filter.withUrlOnly) 1 else 0,
            channels = filter.channels.toList(),
            channelCount = filter.channels.size,
            tags = filter.tags.toList(),
            tagCount = filter.tags.size,
            serverId = filter.serverId,
            excludedIds = emptyList(),
            excludedCount = 0,
            prioritizeUnread = 0,
        )
        val loaded = mutableListOf<MessageListRow>()
        var result = source.load(PagingSource.LoadParams.Refresh(null, pageSize, false))
        repeat(pageCount) {
            assertTrue(result is PagingSource.LoadResult.Page)
            val page = result as PagingSource.LoadResult.Page<Int, MessageListRow>
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
        body: String = "runtime body $index channel $channel ${if (tags.contains("task")) "task" else "message"}",
        isRead: Boolean = index % 3 == 0,
        provider: String? = null,
    ): PushMessage {
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
            isRead = isRead,
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

    private fun assertNewestFirst(messages: List<MessageListRow>) {
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

    private fun grantNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
            .close()
    }

    private suspend fun awaitNotificationState(
        manager: NotificationManager,
        notificationId: Int,
        expectedActive: Boolean,
    ): Boolean {
        repeat(50) {
            val isActive = manager.activeNotifications.any { notification ->
                notification.id == notificationId
            }
            if (isActive == expectedActive) return true
            delay(20)
        }
        return false
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
                "searchPageMs=${metrics.searchPageMs} " +
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
        var searchPageMs: Long = 0,
        var channelFilterMs: Long = 0,
        var tagFilterMs: Long = 0,
        var unreadFilterMs: Long = 0,
        var eventProjectionMs: Long = 0,
        var thingProjectionMs: Long = 0,
        var memoryDeltaBytes: Long = 0,
    )

    private companion object {
        private const val DATABASE_NAME = "pushgo.db"
        private const val BASE_TIME_MS = 1_710_000_000_000L
        private const val PAGE_SIZE = 50
        private const val INSERT_BATCH_SIZE = 1_000
        private const val SEARCH_100K_MAX_MS = 2_000L
        private const val DELETE_REPLAY_NOTIFICATION_CHANNEL = "pushgo_test_delete_replay"
    }
}
