package io.ethan.pushgo.ui.screens

import androidx.paging.LoadState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRefreshPresentationTest {
    @Test
    fun terminalWithoutTargetLoadingDoesNotCompleteButNoOpGenerationDoes() = runBlocking {
        val signals = MutableStateFlow(
            MessagePagingPresentationSignal(LoadState.NotLoading(false)),
        )
        val refreshRequested = CompletableDeferred<Unit>()
        val completed = AtomicBoolean(false)

        val job = async {
            awaitPresentedMessageRefresh(
                presentationSignals = signals,
                requestRefresh = { refreshRequested.complete(Unit) },
            )
            completed.set(true)
        }

        refreshRequested.await()
        signals.value = MessagePagingPresentationSignal(LoadState.NotLoading(true))
        yield()
        assertFalse("terminal state without target Loading must not finish refresh", completed.get())

        signals.value = MessagePagingPresentationSignal(LoadState.Loading)
        yield()
        signals.value = MessagePagingPresentationSignal(LoadState.NotLoading(false))
        withTimeout(1_000) { job.await() }
        assertTrue(completed.get())
    }

    @Test
    fun missingTerminalStateBecomesAnExplicitTimeoutFailure() = runBlocking {
        val signals = MutableStateFlow(
            MessagePagingPresentationSignal(LoadState.NotLoading(false)),
        )

        val failure = runCatching {
            awaitPresentedMessageRefresh(
                presentationSignals = signals,
                requestRefresh = {
                    signals.value = MessagePagingPresentationSignal(LoadState.Loading)
                },
                timeoutMillis = 25L,
            )
        }.exceptionOrNull()

        assertTrue(failure is MessageRefreshPresentationTimeoutException)
        assertEquals(
            "message refresh presentation timed out after 25ms",
            failure?.message,
        )
    }

    @Test
    fun replacementPresentationIsObservedEvenWhenItsContentsAreStructurallyEqual() = runBlocking {
        val baseline = listOf("same-row")
        val replacement = listOf("same-row")
        val signals = MutableStateFlow(MessagePagingSnapshotSignal(baseline))

        val observed = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            signals.drop(1).first()
        }
        signals.value = MessagePagingSnapshotSignal(replacement)

        assertTrue(observed.await().snapshot === replacement)
    }

    @Test
    fun unchangedMessageStoreDoesNotRequireAPresentationAdvance() = runBlocking {
        val baseline = Any()
        val signals = MutableStateFlow(MessagePagingSnapshotSignal(baseline))
        val advance = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            signals.drop(1).first()
        }

        awaitRequiredMessagePagingSnapshotAdvance(
            messageStoreChanged = false,
            presentationAdvance = advance,
            timeoutMillis = 25L,
        )
        assertTrue(advance.isCancelled)
    }

    @Test
    fun changedMessageStoreCannotSucceedWithoutAPresentationAdvance() = runBlocking {
        val baseline = Any()
        val signals = MutableStateFlow(MessagePagingSnapshotSignal(baseline))
        val advance = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            signals.drop(1).first()
        }

        val failure = runCatching {
            awaitRequiredMessagePagingSnapshotAdvance(
                messageStoreChanged = true,
                presentationAdvance = advance,
                timeoutMillis = 25L,
            )
        }.exceptionOrNull()

        assertTrue(failure is MessageRefreshSnapshotTimeoutException)
    }
}
