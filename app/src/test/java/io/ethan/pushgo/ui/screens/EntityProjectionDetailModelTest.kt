package io.ethan.pushgo.ui.screens

import io.ethan.pushgo.data.EntityProjectionDetail
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityProjectionDetailModelTest {
    @Test
    fun eventDetailKeepsFinalHeadFieldsWhenHistoryHasPatchFallbackText() {
        val head = pushMessage(
            id = "evt-update",
            title = "Original event",
            body = "Original body",
            receivedAt = Instant.ofEpochSecond(1_800_000_010),
            rawPayloadJson = """
                {
                  "entity_type":"event",
                  "entity_id":"evt-detail-1",
                  "event_id":"evt-detail-1",
                  "title":"Original event",
                  "description":"Original body",
                  "metadata":{"source":"create","stage":"update"},
                  "attrs":{"temperature":"21"},
                  "event_time":"1800000000000"
                }
            """.trimIndent(),
        )
        val create = pushMessage(
            id = "evt-create",
            title = "Original event",
            body = "Original body",
            receivedAt = Instant.ofEpochSecond(1_800_000_000),
            rawPayloadJson = """
                {
                  "entity_type":"event",
                  "entity_id":"evt-detail-1",
                  "event_id":"evt-detail-1",
                  "title":"Original event",
                  "description":"Original body",
                  "event_time":"1800000000000"
                }
            """.trimIndent(),
        )
        val patch = pushMessage(
            id = "evt-update",
            title = "Patch fallback must not win",
            body = "",
            receivedAt = Instant.ofEpochSecond(1_800_000_010),
            rawPayloadJson = """
                {
                  "entity_type":"event",
                  "entity_id":"evt-detail-1",
                  "event_id":"evt-detail-1",
                  "metadata":{"stage":"update"},
                  "attrs":{"temperature":"21"}
                }
            """.trimIndent(),
        )

        val model = buildEventCardFromProjectionDetailInternal(
            EntityProjectionDetail(head = head, history = listOf(create, patch)),
            "evt-detail-1",
        )

        assertEquals("Original event", model?.title)
        assertEquals("Original body", model?.summary)
        assertEquals("""{"temperature":"21"}""", normalizedJson(model?.attrsJson))
        assertEquals(2, model?.timeline?.size)
        assertTrue(model?.timeline?.none { it.title == "Patch fallback must not win" } == true)
    }

    @Test
    fun thingDetailKeepsFinalHeadFieldsAndUsesHistoryForRelatedUpdates() {
        val head = pushMessage(
            id = "thing-update",
            title = "Original thing",
            body = "Original description",
            receivedAt = Instant.ofEpochSecond(1_800_000_020),
            rawPayloadJson = """
                {
                  "entity_type":"thing",
                  "entity_id":"thing-detail-1",
                  "thing_id":"thing-detail-1",
                  "title":"Original thing",
                  "description":"Original description",
                  "metadata":{"owner":"noc"},
                  "attrs":{"rpm":"50"},
                  "observed_at":"1800000000000"
                }
            """.trimIndent(),
        )
        val create = pushMessage(
            id = "thing-create",
            title = "Original thing",
            body = "Original description",
            receivedAt = Instant.ofEpochSecond(1_800_000_000),
            rawPayloadJson = """
                {
                  "entity_type":"thing",
                  "entity_id":"thing-detail-1",
                  "thing_id":"thing-detail-1",
                  "title":"Original thing",
                  "description":"Original description",
                  "observed_at":"1800000000000"
                }
            """.trimIndent(),
        )
        val patch = pushMessage(
            id = "thing-update",
            title = "Patch fallback must not win",
            body = "",
            receivedAt = Instant.ofEpochSecond(1_800_000_020),
            rawPayloadJson = """
                {
                  "entity_type":"thing",
                  "entity_id":"thing-detail-1",
                  "thing_id":"thing-detail-1",
                  "metadata":{"owner":"noc"},
                  "attrs":{"rpm":"50"}
                }
            """.trimIndent(),
        )

        val model = buildThingCardFromProjectionDetailInternal(
            EntityProjectionDetail(head = head, history = listOf(create, patch)),
            "thing-detail-1",
        )

        assertEquals("Original thing", model?.title)
        assertEquals("Original description", model?.summary)
        assertEquals("""{"rpm":"50"}""", normalizedJson(model?.attrsJson))
        assertEquals("""{"owner":"noc"}""", normalizedJson(model?.metadataJson))
        assertTrue(model?.relatedUpdates?.none { it.title == "Patch fallback must not win" } == true)
    }

    private fun pushMessage(
        id: String,
        title: String,
        body: String,
        receivedAt: Instant,
        rawPayloadJson: String,
    ): PushMessage {
        return PushMessage(
            id = id,
            messageId = id,
            title = title,
            body = body,
            channel = "ch-1",
            url = null,
            isRead = false,
            receivedAt = receivedAt,
            rawPayloadJson = rawPayloadJson,
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = null,
        )
    }

    private fun normalizedJson(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return JSONObject(text).toString()
    }
}
