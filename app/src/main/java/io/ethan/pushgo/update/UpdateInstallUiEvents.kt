package io.ethan.pushgo.update

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class UpdateInstallBlockedEvent(
    val detail: String,
    val apkPath: String?,
)

object UpdateInstallUiEvents {
    private val blockedInstallEventsInternal = MutableSharedFlow<UpdateInstallBlockedEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val blockedInstallEvents: SharedFlow<UpdateInstallBlockedEvent> =
        blockedInstallEventsInternal.asSharedFlow()

    fun emitInstallBlocked(detail: String, apkPath: String?) {
        blockedInstallEventsInternal.tryEmit(
            UpdateInstallBlockedEvent(
                detail = detail,
                apkPath = apkPath,
            )
        )
    }
}
