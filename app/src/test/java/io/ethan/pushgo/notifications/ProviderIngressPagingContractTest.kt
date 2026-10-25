package io.ethan.pushgo.notifications

import io.ethan.pushgo.data.ProviderPullPage
import io.ethan.pushgo.data.ProviderPullTurnEnded
import io.ethan.pushgo.data.PullItem
import io.ethan.pushgo.data.ProviderAckContract
import io.ethan.pushgo.data.ProviderAckDestination
import io.ethan.pushgo.data.ProviderAckIdentity
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProviderIngressPagingContractTest {
    @Test
    fun durableProviderHintPropagatesContextEndWhileBroadDueTurnMayYield() = runBlocking {
        val ended = ProviderPullTurnEnded()
        val failure = runCatching {
            consumeProviderPullPages("targeted-hint", { throw ended }, propagateContextEnd = true) {
                error("An invalidated response must not be processed")
            }
        }.exceptionOrNull()
        assertEquals(ended, failure)
        assertEquals(0, consumeProviderPullPages(null, { throw ProviderPullTurnEnded() }) {
            error("A broad invalidated response must not be processed")
        })
    }

    @Test
    fun consumeProviderPullPages_drainsAll207ItemsAcrossHasMoreBoundary() = runBlocking {
        val pages = ArrayDeque(
            listOf(
                page(ids = (1..200).map { "delivery-$it" }, hasMore = true),
                page(ids = (201..207).map { "delivery-$it" }, hasMore = false),
            )
        )
        val consumed = mutableListOf<String>()

        val count = consumeProviderPullPages(
            requestedDeliveryId = null,
            pullPage = { pages.removeFirst() },
            processPage = { page ->
                consumed += page.items.map { it.deliveryId }
                ProviderPullPageProcessResult(page.items.size, hadPersistenceFailure = false)
            },
        )

        assertEquals(207, count)
        assertEquals((1..207).map { "delivery-$it" }, consumed)
        assertEquals(0, pages.size)
    }

    @Test
    fun consumeProviderPullPages_continuesAfterEmptyCorruptPage() = runBlocking {
        val pages = ArrayDeque(
            listOf(
                page(ids = emptyList(), hasMore = true),
                page(ids = listOf("healthy-after-corrupt"), hasMore = false),
            )
        )
        var processedPages = 0

        val count = consumeProviderPullPages(
            requestedDeliveryId = null,
            pullPage = { pages.removeFirst() },
            processPage = { page ->
                processedPages += 1
                ProviderPullPageProcessResult(page.items.size, hadPersistenceFailure = false)
            },
        )

        assertEquals(1, count)
        assertEquals(2, processedPages)
    }

    @Test
    fun consumeProviderPullPages_targetedPullNeverFollowsHasMore() = runBlocking {
        var pullCount = 0
        val count = consumeProviderPullPages(
            requestedDeliveryId = "target",
            pullPage = {
                pullCount += 1
                page(ids = listOf("target"), hasMore = true)
            },
            processPage = {
                ProviderPullPageProcessResult(it.items.size, hadPersistenceFailure = false)
            },
        )

        assertEquals(1, count)
        assertEquals(1, pullCount)
    }

    @Test
    fun consumeProviderPullPages_stopsWhenAnyItemFailedPersistence() = runBlocking {
        var pullCount = 0
        consumeProviderPullPages(
            requestedDeliveryId = null,
            pullPage = {
                pullCount += 1
                page(ids = listOf("failed"), hasMore = true)
            },
            processPage = {
                ProviderPullPageProcessResult(0, hadPersistenceFailure = true)
            },
        )

        assertEquals(1, pullCount)
    }

    @Test
    fun newScanContinuesPastFailedItemsAndCheckpointsAfterEachHandledPage() = runBlocking {
        val pages = ArrayDeque(listOf(
            page(listOf("retained-failure"), false).copy(nextPageToken = "next-opaque", scanComplete = false),
            page(listOf("healthy-later"), true).copy(scanComplete = true),
        ))
        val order = mutableListOf<String>()
        val count = consumeProviderPullPages(null, { pages.removeFirst() }, checkpointPage = {
            order += "checkpoint:${it.items.single().deliveryId}"
        }) {
            order += "handled:${it.items.single().deliveryId}"
            ProviderPullPageProcessResult(if (it.scanComplete == true) 1 else 0, it.scanComplete == false)
        }
        assertEquals(1, count)
        assertEquals(listOf("handled:retained-failure", "checkpoint:retained-failure", "handled:healthy-later", "checkpoint:healthy-later"), order)
        assertTrue(pages.isEmpty())
    }

    @Test
    fun newScanAlsoContinuesAfterDurableRetryableAckFailure() = runBlocking {
        var pulled = 0
        val count = consumeProviderPullPages(null, {
            pulled++
            page(listOf("row-$pulled"), false).copy(nextPageToken = if (pulled == 1) "next" else null, scanComplete = pulled == 2)
        }) { ProviderPullPageProcessResult(1, false, hadAckFailure = true) }
        assertEquals(2, count)
        assertEquals(2, pulled)
    }

    @Test
    fun boundedTurnPersistsFourthFrontierAndDoesNotRollImmediatelyBackToHead() = runBlocking {
        var pulled = 0
        val checkpoints = mutableListOf<String?>()
        val count = consumeProviderPullPages(null, {
            pulled++
            page(listOf("row-$pulled"), true).copy(nextPageToken = "opaque-$pulled", scanComplete = false)
        }, checkpointPage = { checkpoints += it.nextPageToken }) { ProviderPullPageProcessResult(1, false) }
        assertEquals(4, count)
        assertEquals(4, pulled)
        assertEquals(listOf("opaque-1", "opaque-2", "opaque-3", "opaque-4"), checkpoints)
    }

    @Test
    fun checkpointFailureCannotFetchAnotherPageOrClaimAnAdoptedPosition() = runBlocking {
        var pulled = 0
        val failure = IllegalStateException("synthetic frontier write failure")
        val actual = runCatching {
            consumeProviderPullPages(null, {
                pulled++
                page(listOf("committed"), true).copy(nextPageToken = "next", scanComplete = false)
            }, checkpointPage = { throw failure }) { ProviderPullPageProcessResult(1, false) }
        }.exceptionOrNull()
        assertEquals(failure, actual)
        assertEquals(1, pulled)
    }

    @Test
    fun invalidatedScopeEndsTheTurnWithoutAnInTurnHeadReset() = runBlocking {
        var pulled = 0
        val count = consumeProviderPullPages(null, {
            pulled++
            throw ProviderPullTurnEnded()
        }) { error("an invalidated request has no owned page") }
        assertEquals(0, count)
        assertEquals(1, pulled)
    }

    @Test
    fun authoritativePayload_overwritesConflictingEmbeddedDeliveryId() {
        val item = PullItem(
            deliveryId = "outer-authoritative",
            payload = mapOf(
                "delivery_id" to "inner-conflict",
                "title" to "kept",
            ),
        )

        val payload = item.authoritativePayload()

        assertEquals("outer-authoritative", payload["delivery_id"])
        assertEquals("kept", payload["title"])
        assertFalse(payload === item.payload)
    }

    @Test
    fun providerAckIdentity_isNotAttachedToAuthenticationFailure() {
        val identity = checkNotNull(
            ProviderAckIdentity.create(
                destination = ProviderAckDestination(
                    baseUrl = "https://gateway.example",
                    deviceKey = "device-key",
                ),
                contract = ProviderAckContract.V2_BATCH,
                source = "test",
            )
        )
        val quarantined = messageRequest(InboundSecurityDisposition.AUTHENTICATION_FAILED)
            .withProviderAckIdentity(identity) as InboundPersistenceRequest.Message
        val accepted = messageRequest(InboundSecurityDisposition.ACCEPTED)
            .withProviderAckIdentity(identity) as InboundPersistenceRequest.Message

        assertNull(quarantined.providerAckIdentity)
        assertNotNull(accepted.providerAckIdentity)
    }

    @Test
    fun legacyPull_isRetainedUntilAuthenticatedContentCanBeAccepted() {
        val persisted = InboundPersistenceOutcome(
            status = InboundPersistenceStatus.PERSISTED_MAIN,
            notified = false,
            shouldAck = false,
        )

        assertFalse(
            shouldCompleteLegacyPull(
                messageRequest(InboundSecurityDisposition.AUTHENTICATION_FAILED),
                persisted,
            )
        )
        assertFalse(
            shouldCompleteLegacyPull(
                messageRequest(InboundSecurityDisposition.ENCRYPTED_UNAVAILABLE),
                persisted.copy(status = InboundPersistenceStatus.REJECTED),
            )
        )
        assertEquals(
            true,
            shouldCompleteLegacyPull(
                messageRequest(InboundSecurityDisposition.ACCEPTED),
                persisted.copy(shouldAck = true),
            ),
        )
    }

    @Test
    fun failedThingLifecycleIsNotCompletedOrConsumedByProviderRecovery() {
        val failed = InboundPersistenceOutcome(InboundPersistenceStatus.FAILED, notified = false, shouldAck = false)
        assertFalse(failed.isCanonicalPersistenceComplete())
        assertFalse(failed.shouldAck)
        assertFalse(shouldCompleteLegacyPull(messageRequest(InboundSecurityDisposition.ACCEPTED), failed))
    }

    @Test
    fun rejectedEncryptedEntity_isNotCountedAsCanonicalProviderPersistence() {
        assertFalse(
            InboundPersistenceOutcome(
                status = InboundPersistenceStatus.REJECTED,
                notified = false,
                shouldAck = false,
            ).isCanonicalPersistenceComplete()
        )
        assertEquals(
            true,
            InboundPersistenceOutcome(
                status = InboundPersistenceStatus.PERSISTED_MAIN,
                notified = false,
                shouldAck = false,
            ).isCanonicalPersistenceComplete(),
        )
    }

    private fun messageRequest(
        securityDisposition: InboundSecurityDisposition,
    ): InboundPersistenceRequest.Message = InboundPersistenceRequest.Message(
        message = PushMessage(
            id = "local-id",
            messageId = "stable-id",
            title = "title",
            body = "body",
            channel = null,
            url = null,
            isRead = false,
            receivedAt = Instant.EPOCH,
            rawPayloadJson = "{}",
            status = MessageStatus.NORMAL,
            decryptionState = null,
            notificationId = null,
            serverId = null,
        ),
        level = null,
        imageUrl = null,
        shouldNotify = securityDisposition.allowsNotification,
        securityDisposition = securityDisposition,
    )

    private fun page(ids: List<String>, hasMore: Boolean) = ProviderPullPage(
        items = ids.map { PullItem(deliveryId = it, payload = emptyMap()) },
        hasMore = hasMore,
    )
}
