package io.ethan.pushgo

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class ConflatedRecoveryDispatcherTest {
    @Test
    fun repeatedRequestsCoalesceAndRequestAtCompletionIsNotLost() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstEntered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val secondFinished = CompletableDeferred<Unit>()
        val thirdFinished = CompletableDeferred<Unit>()
        val invocations = AtomicInteger()
        val activeWorkers = AtomicInteger()
        val peakWorkers = AtomicInteger()
        try {
            val dispatcher = ConflatedRecoveryDispatcher(
                scope = scope,
                recover = { _: Unit ->
                    val active = activeWorkers.incrementAndGet()
                    peakWorkers.accumulateAndGet(active, ::maxOf)
                    val invocation = invocations.incrementAndGet()
                    try {
                        if (invocation == 1) {
                            firstEntered.complete(Unit)
                            finishFirst.await()
                        }
                    } finally {
                        activeWorkers.decrementAndGet()
                    }
                },
                onRecovered = {
                    when (invocations.get()) {
                        2 -> secondFinished.complete(Unit)
                        3 -> thirdFinished.complete(Unit)
                    }
                },
            )
            dispatcher.request(Unit)
            withTimeout(5_000) { firstEntered.await() }
            repeat(100) { dispatcher.request(Unit) }
            assertEquals(1, invocations.get())

            // The first pass has accepted its work and is about to finish.
            finishFirst.complete(Unit)
            withTimeout(5_000) { secondFinished.await() }
            assertEquals(2, invocations.get())
            assertEquals(1, peakWorkers.get())

            // A later request wakes the same idle consumer rather than queuing a new worker.
            dispatcher.request(Unit)
            withTimeout(5_000) { thirdFinished.await() }
            assertEquals(3, invocations.get())
            assertEquals(1, peakWorkers.get())
        } finally {
            scope.cancel()
        }
    }
}
