package io.ethan.pushgo.data

import io.ethan.pushgo.data.db.PushGoDatabase

/** Called inside the existing canonical acceptance transaction with its verified ingress scope. */
internal suspend fun PushGoDatabase.recordResourceOrigin(id: String, receivedAt: Long, scope: InboundDeliveryScope?) {
    if (scope == null) return
    val origin = MessageImageStore.originScope(scope)
    messageResourceDao().owners(id).filter { it.receivedAt == receivedAt }.forEach {
        messageResourceDao().setOrigin(id, it.generation, origin)
    }
}
