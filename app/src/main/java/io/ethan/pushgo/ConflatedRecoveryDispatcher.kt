package io.ethan.pushgo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

/** Keeps one recovery worker alive and retains a request made while it is finishing. */
internal class ConflatedRecoveryDispatcher<T : Any>(
    scope: CoroutineScope,
    private val recover: suspend (T) -> Unit,
    private val onRecovered: (T) -> Unit,
) {
    private val requests = Channel<T>(Channel.CONFLATED)
    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        for (request in requests) {
            recover(request)
            onRecovered(request)
        }
    }

    fun request(value: T) {
        check(requests.trySend(value).isSuccess)
        worker.start()
    }
}
