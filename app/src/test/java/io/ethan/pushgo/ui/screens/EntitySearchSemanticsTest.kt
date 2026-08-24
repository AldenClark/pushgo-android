package io.ethan.pushgo.ui.screens

import io.ethan.pushgo.data.EntityProjectionDetail
import io.ethan.pushgo.data.IncomingEntityRecord
import io.ethan.pushgo.data.db.ThingHeadEntity
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitySearchSemanticsTest {
    @Test
    fun eventSearchCoversAppleIdentityMetadataAndBodyFields() {
        val event = event(
            title = "Crème Brûlée alert",
            summary = "Database pressure",
            status = "acknowledged",
            message = "Failover requested",
            severity = EventSeverity.Critical,
            tags = listOf("On-Call", "数据库"),
            state = EventLifecycleState.Closed,
            thingId = "thing-db-01",
            channelId = "ops-上海",
        )

        listOf(
            "CREME BRULEE",
            "pressure",
            "failover",
            "acknowledged",
            "critical",
            "on-call",
            "数据库",
            "vent-42",
            "closed",
            "db-01",
            "上海",
        ).forEach { query ->
            assertTrue("expected event query to match: $query", eventMatchesSearch(event, query))
        }
        assertFalse(eventMatchesSearch(event, "unrelated needle"))
    }

    @Test
    fun thingSearchCoversLocationExternalAttributesIdsAndAssociatedMessageText() {
        val relatedMessage = PushMessage(
            id = "local-message-17",
            messageId = "server-message-99",
            title = "Pump vibration",
            body = "Bearing temperature elevated",
            channel = "maintenance",
            url = null,
            isRead = false,
            receivedAt = NOW,
            rawPayloadJson = "{}",
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = null,
            bodyPreview = "Temperature preview",
        )
        val thing = ThingCardModel(
            thingId = "thing-pump-42",
            title = "Ｐｕｍｐ Café",
            summary = "Primary coolant pump",
            state = "archived",
            channelId = "factory-west",
            decryptionState = null,
            imageUrl = null,
            tags = listOf("Rotating", "Critical-Asset"),
            createdAt = NOW,
            updatedAt = NOW,
            imageUrls = emptyList(),
            attrsJson = """{"bearing":"SKF-6205","rpm":1450}""",
            metadataJson = """{"owner":"Equipo Málaga"}""",
            relatedEvents = emptyList(),
            relatedMessages = listOf(ThingRelatedMessage(relatedMessage, NOW)),
            relatedUpdates = emptyList(),
            locationType = "plant-zone",
            locationValue = "Bâtiment Ａ / 03",
            externalIds = mapOf("serial" to "SN-8899", "asset" to "EXT-P-42"),
        )

        listOf(
            "pump cafe",
            "coolant",
            "critical-asset",
            "pump-42",
            "archived",
            "factory-west",
            "plant-zone",
            "batiment a",
            "serial",
            "8899",
            "skf-6205",
            "malaga",
            "vibration",
            "temperature elevated",
            "message-99",
        ).forEach { query ->
            assertTrue("expected thing query to match: $query", thingMatchesSearch(thing, query))
        }
        assertFalse(thingMatchesSearch(thing, "missing turbine"))
    }

    @Test
    fun blankQueriesKeepEntityFilterCompositionNeutral() {
        assertTrue(eventMatchesSearch(event(), "  \n"))
        assertTrue(
            thingMatchesSearch(
                ThingCardModel(
                    thingId = "thing-1",
                    title = "Thing",
                    summary = null,
                    state = null,
                    channelId = null,
                    decryptionState = null,
                    imageUrl = null,
                    tags = emptyList(),
                    createdAt = null,
                    updatedAt = NOW,
                    imageUrls = emptyList(),
                    attrsJson = null,
                    metadataJson = null,
                    relatedEvents = emptyList(),
                    relatedMessages = emptyList(),
                    relatedUpdates = emptyList(),
                ),
                "",
            )
        )
    }

    @Test
    fun entitySearchAutoloadContinuesThroughTheBoundedScanEvenAfterAnEarlyMatch() {
        assertTrue(shouldAutoloadEntitySearch(true, hasMore = true, isLoading = false))
        assertFalse(shouldAutoloadEntitySearch(false, hasMore = true, isLoading = false))
        assertFalse(shouldAutoloadEntitySearch(true, hasMore = false, isLoading = false))
        assertFalse(shouldAutoloadEntitySearch(true, hasMore = true, isLoading = true))
        assertFalse(
            shouldAutoloadEntitySearch(
                true,
                hasMore = true,
                isLoading = false,
                scannedPages = 8,
                maxScanPages = 8,
            )
        )
        assertTrue(
            shouldAutoloadEntitySearch(
                true,
                hasMore = true,
                isLoading = false,
                scannedPages = 7,
                maxScanPages = 8,
            )
        )
        assertFalse(
            shouldLoadMoreEntityPage(
                hasMore = true,
                isLoading = false,
                hasActiveSearch = true,
                automaticSearchLoad = true,
                scannedPages = 8,
                maxScanPages = 8,
            )
        )
        assertTrue(
            shouldLoadMoreEntityPage(
                hasMore = true,
                isLoading = false,
                hasActiveSearch = true,
                automaticSearchLoad = false,
                scannedPages = 8,
                maxScanPages = 8,
            )
        )
    }

    @Test
    fun thingProjectionBuildRetainsSearchableLocationAndExternalIdsFromPayload() {
        val payload = JSONObject()
            .put("entity_type", "thing")
            .put("thing_id", "thing-payload-1")
            .put("location", JSONObject().put("type", "warehouse").put("value", "Aisle C-7"))
            .put("external_ids", JSONObject().put("serial", "SER-700"))
            .put("attrs", JSONObject().put("firmware", "v9.4").toString())
        val message = PushMessage(
            id = "thing-payload-local",
            messageId = "thing-payload-server",
            title = "Payload thing",
            body = "Payload body",
            channel = "inventory",
            url = null,
            isRead = false,
            receivedAt = NOW,
            rawPayloadJson = payload.toString(),
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = null,
        )

        val thing = buildThingCardsInternal(listOf(message)).single()

        assertTrue(thingMatchesSearch(thing, "warehouse"))
        assertTrue(thingMatchesSearch(thing, "aisle c-7"))
        assertTrue(thingMatchesSearch(thing, "ser-700"))
        assertTrue(thingMatchesSearch(thing, "firmware"))
    }

    @Test
    fun thingPatchTombstonesRemoveMetadataAndLocationFromSearch() {
        val createPayload = JSONObject()
            .put("entity_type", "thing")
            .put("thing_id", "thing-tombstone-1")
            .put("metadata", JSONObject().put("owner", "Legacy Search Owner"))
            .put("location_type", "legacy-zone")
            .put("location_value", "legacy-rack-9")
        val clearPayload = JSONObject()
            .put("entity_type", "thing")
            .put("thing_id", "thing-tombstone-1")
            .put(
                "metadata",
                JSONObject()
                    .put("owner", JSONObject.NULL)
                    .put("current", "Current Search Owner"),
            )
            .put("location_type", "")
            .put("location_value", JSONObject.NULL)

        val thing = buildThingCardsInternal(
            listOf(
                thingMessage("thing-create", createPayload, NOW.minusSeconds(10)),
                thingMessage("thing-clear", clearPayload, NOW),
            )
        ).single()

        assertFalse(thingMatchesSearch(thing, "legacy search owner"))
        assertTrue(thingMatchesSearch(thing, "current search owner"))
        assertFalse(thingMatchesSearch(thing, "legacy-zone"))
        assertFalse(thingMatchesSearch(thing, "legacy-rack-9"))
    }

    @Test
    fun canonicalThingHeadPreventsClearedHistoryFromReenteringSearch() {
        val historyPayload = JSONObject()
            .put("entity_type", "thing")
            .put("thing_id", "thing-canonical-1")
            .put("metadata", JSONObject().put("owner", "Removed Metadata Needle"))
            .put("location", JSONObject().put("type", "removed-zone").put("value", "removed-rack"))
        val canonicalHeadPayload = JSONObject()
            .put("entity_type", "thing")
            .put("thing_id", "thing-canonical-1")
            .put("location", JSONObject.NULL)
        val history = thingMessage("thing-history", historyPayload, NOW.minusSeconds(10))
        val head = thingMessage("thing-head", canonicalHeadPayload, NOW)

        val thing = buildThingCardFromProjectionDetailInternal(
            detail = EntityProjectionDetail(head = head, history = listOf(history)),
            thingId = "thing-canonical-1",
        )!!

        assertFalse(thingMatchesSearch(thing, "removed metadata needle"))
        assertFalse(thingMatchesSearch(thing, "removed-zone"))
        assertFalse(thingMatchesSearch(thing, "removed-rack"))
    }

    @Test
    fun repositoryMergedNestedLocationTombstoneClearsFlatAliasesFromUiAndSearch() {
        val createPayload = JSONObject()
            .put("entity_type", "thing")
            .put("entity_id", "thing-repository-tombstone")
            .put("thing_id", "thing-repository-tombstone")
            .put("location_type", "legacy-zone")
            .put("location_value", "legacy-rack-9")
        val clearPayload = JSONObject()
            .put("entity_type", "thing")
            .put("entity_id", "thing-repository-tombstone")
            .put("thing_id", "thing-repository-tombstone")
            .put("location", JSONObject.NULL)
        val created = ThingHeadEntity.fromMerged(
            existing = null,
            entity = incomingThingRecord("thing-create", createPayload, NOW.minusSeconds(10)),
        )
        val cleared = ThingHeadEntity.fromMerged(
            existing = created,
            entity = incomingThingRecord("thing-clear", clearPayload, NOW),
        )

        val mergedPayload = JSONObject(cleared.rawPayloadJson)
        assertFalse(mergedPayload.has("location_type"))
        assertFalse(mergedPayload.has("location_value"))
        assertTrue(mergedPayload.has("location"))
        assertTrue(mergedPayload.isNull("location"))

        val thing = buildThingCardsInternal(listOf(cleared.asModel())).single()
        assertNull(thing.locationType)
        assertNull(thing.locationValue)
        assertFalse(thingMatchesSearch(thing, "legacy-zone"))
        assertFalse(thingMatchesSearch(thing, "legacy-rack-9"))
    }

    private fun thingMessage(id: String, payload: JSONObject, receivedAt: Instant): PushMessage {
        return PushMessage(
            id = id,
            messageId = id,
            title = "Thing update",
            body = "Update body",
            channel = "things",
            url = null,
            isRead = false,
            receivedAt = receivedAt,
            rawPayloadJson = payload.toString(),
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = null,
        )
    }

    private fun incomingThingRecord(
        deliveryId: String,
        payload: JSONObject,
        receivedAt: Instant,
    ): IncomingEntityRecord = IncomingEntityRecord(
        entityType = "thing",
        entityId = "thing-repository-tombstone",
        channel = "things",
        title = "Thing update",
        body = "Update body",
        rawPayloadJson = payload.toString(),
        receivedAt = receivedAt,
        opId = null,
        deliveryId = deliveryId,
        serverId = null,
        eventId = null,
        thingId = "thing-repository-tombstone",
        eventState = null,
        eventTimeEpoch = null,
        observedTimeEpoch = null,
    )

    private fun event(
        title: String = "Event",
        summary: String? = null,
        status: String? = null,
        message: String? = null,
        severity: EventSeverity? = null,
        tags: List<String> = emptyList(),
        state: EventLifecycleState = EventLifecycleState.Ongoing,
        thingId: String? = null,
        channelId: String? = null,
    ) = EventCardModel(
        eventId = "event-42",
        title = title,
        summary = summary,
        status = status,
        message = message,
        imageUrl = null,
        severity = severity,
        tags = tags,
        state = state,
        thingId = thingId,
        channelId = channelId,
        decryptionState = null,
        attachmentUrls = emptyList(),
        attrsJson = null,
        updatedAt = NOW,
        timeline = emptyList(),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-22T00:00:00Z")
    }
}
