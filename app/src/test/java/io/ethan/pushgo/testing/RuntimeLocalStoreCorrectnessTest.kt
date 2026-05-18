package io.ethan.pushgo.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

class RuntimeLocalStoreCorrectnessTest {
    @Test
    fun localStore_handlesSupportedFixtureSizesThroughTenThousand() {
        val generator = RuntimeFixtureGenerator(seed = 30L)

        listOf(0, 1, 10, 100, 1_000, 10_000).forEach { size ->
            val store = RuntimeLocalStore()
            val results = store.ingestAll(generator.generateRecords(size).map { it.fcmPayload() })

            assertEquals("size=$size ingested attempts", size, results.size)
            assertEquals(
                "size=$size canonical message count",
                results.count { it.accepted && (it.kind == RuntimeFixtureKind.MESSAGE || it.kind == RuntimeFixtureKind.TASK_MESSAGE) },
                store.messageCount(),
            )
            if (size >= 10) {
                assertTrue("size=$size has messages", store.messageCount() > 0)
                assertTrue("size=$size has event projections", store.eventProjectionsNewestFirst().isNotEmpty())
                assertTrue("size=$size has thing projections", store.thingProjectionsNewestFirst().isNotEmpty())
            }
        }
    }

    @Test
    fun localStore_writesAndReadsFcmAndPrivatePayloads() {
        val records = RuntimeFixtureGenerator(seed = 31L).generateRecords(10).toList()
        val fcmMessage = records.first { it.kind == RuntimeFixtureKind.MESSAGE }
        val privateMessage = records.first { it.kind == RuntimeFixtureKind.TASK_MESSAGE }
        val store = RuntimeLocalStore()

        val fcmResult = store.ingest(fcmMessage.fcmPayload())
        val privateResult = store.ingest(privateMessage.privatePayload())

        assertTrue(fcmResult.accepted)
        assertTrue(privateResult.accepted)
        val messages = store.allMessagesNewestFirst()
        assertTrue(messages.any { it.messageId == fcmMessage.canonicalId && it.channel == fcmMessage.channelId })
        assertTrue(messages.any { it.messageId == privateMessage.canonicalId && it.channel == privateMessage.channelId })
    }

    @Test
    fun localStore_dedupesFcmAndPrivateSameMessageId() {
        val record = RuntimeFixtureGenerator(seed = 32L)
            .generateRecords(10)
            .first { it.kind == RuntimeFixtureKind.MESSAGE }
        val store = RuntimeLocalStore()

        val first = store.ingest(record.fcmPayload())
        val duplicate = store.ingest(record.privatePayload())

        assertTrue(first.accepted)
        assertFalse(duplicate.accepted)
        assertEquals("duplicate_operation", duplicate.reason)
        assertEquals(1, store.messageCount())
        assertEquals(record.canonicalId, store.allMessagesNewestFirst().single().messageId)
    }

    @Test
    fun localStore_oldChannelLateDoesNotOverwriteNewerMessage() {
        val duplicates = RuntimeFixtureGenerator(seed = 33L)
            .duplicateAndOutOfOrderPayloads()
            .filter { it.canonicalId == "duplicate-message-1" }
            .sortedByDescending { it.sentAtEpochMillis }
        val store = RuntimeLocalStore()

        val newer = store.ingest(duplicates[0].privatePayload())
        val olderLate = store.ingest(duplicates[1].fcmPayload())

        assertTrue(newer.accepted)
        assertFalse(olderLate.accepted)
        assertEquals(1, store.messageCount())
        assertEquals("newer duplicate", store.allMessagesNewestFirst().single().title)
    }

    @Test
    fun localStore_ordersOutOfOrderMessagesNewestFirstWithStableTieBreak() {
        val generator = RuntimeFixtureGenerator(seed = 34L)
        val store = RuntimeLocalStore()
        generator.duplicateAndOutOfOrderPayloads().forEach { store.ingest(it.fcmPayload()) }

        val sorted = store.allMessagesNewestFirst()

        assertEquals("future-message", sorted.first().messageId)
        assertEquals("ancient-message", sorted.last().messageId)
        assertTrue(sorted.zipWithNext().all { (left, right) ->
            val leftTime = left.receivedAt.toEpochMilli()
            val rightTime = right.receivedAt.toEpochMilli()
            leftTime > rightTime ||
                (leftTime == rightTime && (left.messageId ?: left.id) >= (right.messageId ?: right.id))
        })
    }

    @Test
    fun localStore_rejectsInvalidPayloadsWithoutPollutingNormalData() {
        val generator = RuntimeFixtureGenerator(seed = 35L)
        val valid = generator.generateRecords(10).first { it.kind == RuntimeFixtureKind.MESSAGE }
        val invalids = generator.boundaryPayloads().filter { it.kind == RuntimeFixtureKind.INVALID }
        val store = RuntimeLocalStore()

        assertTrue(store.ingest(valid.fcmPayload()).accepted)
        invalids.forEach { invalid ->
            assertFalse("invalid=${invalid.canonicalId}", store.ingest(invalid.fcmPayload()).accepted)
        }

        assertEquals(1, store.messageCount())
        assertEquals(valid.canonicalId, store.allMessagesNewestFirst().single().messageId)
    }

    @Test
    fun localStore_projectsEventThingAndTaskLikeRecords() {
        val records = RuntimeFixtureGenerator(seed = 36L).generateRecords(100).toList()
        val store = RuntimeLocalStore()
        records.forEach { store.ingest(it.fcmPayload()) }

        assertTrue(store.eventProjectionsNewestFirst().isNotEmpty())
        assertTrue(store.thingProjectionsNewestFirst().isNotEmpty())
        assertEquals(records.count { it.kind == RuntimeFixtureKind.TASK_MESSAGE }, store.taskMessages().size)
        assertTrue(store.searchMessages("tag:task").all { it.tags.any { tag -> tag == "task" } })
        assertTrue(store.filterMessages(channelId = "channel-0").all { it.channel == "channel-0" })
        assertTrue(store.filterMessages(tag = "ops").all { message ->
            message.tags.any { it.equals("ops", ignoreCase = true) }
        })
        assertEquals(store.messageCount(), store.filterMessages(unreadOnly = true).size)
    }

    @Test
    fun localStore_rebuildSnapshotPreservesCanonicalState() {
        val store = RuntimeLocalStore()
        RuntimeFixtureGenerator(seed = 37L).generateRecords(100).forEach { store.ingest(it.privatePayload()) }
        val rebuilt = RuntimeLocalStore.fromSnapshot(store.snapshot())

        assertEquals(store.messageCount(), rebuilt.messageCount())
        assertEquals(store.eventHeadCount(), rebuilt.eventHeadCount())
        assertEquals(store.thingHeadCount(), rebuilt.thingHeadCount())
        assertEquals(
            store.allMessagesNewestFirst().map { it.messageId to it.title },
            rebuilt.allMessagesNewestFirst().map { it.messageId to it.title },
        )
    }

    @Test
    fun localStore_recordsTenThousandScaleQueryPerformance() {
        val sample = capturePerformance(size = 10_000, seed = 38L)

        assertEquals(10_000, sample.attemptedPayloads)
        assertTrue(sample.acceptedPayloads > 0)
        assertTrue(sample.messageCount > 0)
        assertEquals(50, sample.firstPageCount)
        assertTrue(sample.searchCount > 0)
        assertTrue(sample.channelFilterCount > 0)
        assertTrue(sample.tagFilterCount > 0)

        println(sample.asLogLine())
    }

    @Test
    fun localStore_recordsHundredThousandScaleQueryPerformanceWhenEnabled() {
        val enabled = System.getenv("PUSHGO_ANDROID_RUNTIME_100K") == "true" ||
            System.getProperty("pushgo.android.runtime.100k") == "true"
        if (!enabled) {
            println("runtime-local-performance size=100000 skipped=true reason=set_PUSHGO_ANDROID_RUNTIME_100K_true")
            return
        }

        val sample = capturePerformance(size = 100_000, seed = 39L)

        assertEquals(100_000, sample.attemptedPayloads)
        assertTrue(sample.acceptedPayloads > 0)
        assertEquals(50, sample.firstPageCount)
        println(sample.asLogLine())
    }

    private fun capturePerformance(size: Int, seed: Long): PerformanceSample {
        val generator = RuntimeFixtureGenerator(seed = seed)
        val store = RuntimeLocalStore()
        var acceptedPayloads = 0
        val writeNs = measureNanoTime {
            generator.generateRecords(size).forEach { record ->
                if (store.ingest(record.fcmPayload()).accepted) {
                    acceptedPayloads += 1
                }
            }
        }
        var firstPageCount = 0
        val firstPageNs = measureNanoTime {
            firstPageCount = store.firstMessagePage(limit = 50).size
        }
        var pagedCount = 0
        val pagingNs = measureNanoTime {
            pagedCount = store.continuousMessagePages(pageSize = 50, pageCount = 5).sumOf { it.size }
        }
        var searchCount = 0
        val searchNs = measureNanoTime {
            searchCount = store.searchMessages("Runtime", limit = 50).size
        }
        var channelFilterCount = 0
        val channelFilterNs = measureNanoTime {
            channelFilterCount = store.filterMessages(channelId = "channel-0").size
        }
        var tagFilterCount = 0
        val tagFilterNs = measureNanoTime {
            tagFilterCount = store.filterMessages(tag = "ops").size
        }
        var unreadFilterCount = 0
        val unreadFilterNs = measureNanoTime {
            unreadFilterCount = store.filterMessages(unreadOnly = true).size
        }
        var eventProjectionCount = 0
        val eventProjectionNs = measureNanoTime {
            eventProjectionCount = store.eventProjectionsNewestFirst().size
        }
        var thingProjectionCount = 0
        val thingProjectionNs = measureNanoTime {
            thingProjectionCount = store.thingProjectionsNewestFirst().size
        }

        return PerformanceSample(
            size = size,
            attemptedPayloads = size,
            acceptedPayloads = acceptedPayloads,
            messageCount = store.messageCount(),
            firstPageCount = firstPageCount,
            pagedCount = pagedCount,
            searchCount = searchCount,
            channelFilterCount = channelFilterCount,
            tagFilterCount = tagFilterCount,
            unreadFilterCount = unreadFilterCount,
            eventProjectionCount = eventProjectionCount,
            thingProjectionCount = thingProjectionCount,
            writeMs = writeNs.toMillis(),
            firstPageMs = firstPageNs.toMillis(),
            pagingMs = pagingNs.toMillis(),
            searchMs = searchNs.toMillis(),
            channelFilterMs = channelFilterNs.toMillis(),
            tagFilterMs = tagFilterNs.toMillis(),
            unreadFilterMs = unreadFilterNs.toMillis(),
            eventProjectionMs = eventProjectionNs.toMillis(),
            thingProjectionMs = thingProjectionNs.toMillis(),
        )
    }

    private data class PerformanceSample(
        val size: Int,
        val attemptedPayloads: Int,
        val acceptedPayloads: Int,
        val messageCount: Int,
        val firstPageCount: Int,
        val pagedCount: Int,
        val searchCount: Int,
        val channelFilterCount: Int,
        val tagFilterCount: Int,
        val unreadFilterCount: Int,
        val eventProjectionCount: Int,
        val thingProjectionCount: Int,
        val writeMs: Long,
        val firstPageMs: Long,
        val pagingMs: Long,
        val searchMs: Long,
        val channelFilterMs: Long,
        val tagFilterMs: Long,
        val unreadFilterMs: Long,
        val eventProjectionMs: Long,
        val thingProjectionMs: Long,
    ) {
        fun asLogLine(): String {
            return "runtime-local-performance " +
                "size=$size attempted=$attemptedPayloads accepted=$acceptedPayloads messages=$messageCount " +
                "first_page_count=$firstPageCount paged_count=$pagedCount search_count=$searchCount " +
                "channel_filter_count=$channelFilterCount tag_filter_count=$tagFilterCount unread_filter_count=$unreadFilterCount " +
                "event_projection_count=$eventProjectionCount thing_projection_count=$thingProjectionCount " +
                "write_ms=$writeMs first_page_ms=$firstPageMs paging_ms=$pagingMs search_ms=$searchMs " +
                "channel_filter_ms=$channelFilterMs tag_filter_ms=$tagFilterMs unread_filter_ms=$unreadFilterMs " +
                "event_projection_ms=$eventProjectionMs thing_projection_ms=$thingProjectionMs"
        }
    }

    private fun Long.toMillis(): Long = this / 1_000_000L
}
