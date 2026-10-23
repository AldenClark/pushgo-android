package io.ethan.pushgo.data

/** A local operation crossed a confirmed Thing deletion before its next write. */
class ThingWriteInvalidatedException : IllegalStateException("Thing lifecycle changed during inbound persistence")

/**
 * Only live inbound operations are retained, never a permanent deleted-Thing set. Registration,
 * invalidation and checks are ordered by the owning Room database's transactions. A later inbound
 * invocation can register a new ticket; an unsaved cancelled invocation is not successfully ACKed.
 */
class ThingWriteTicket internal constructor(
    internal val thingId: String,
    internal val channel: String?,
    private val release: (ThingWriteTicket) -> Unit,
) : AutoCloseable {
    @Volatile private var current = true

    internal fun invalidate() { current = false }

    fun requireCurrent() {
        if (!current) throw ThingWriteInvalidatedException()
    }

    override fun close() {
        current = false
        release(this)
    }
}

internal class ThingWriteTickets {
    private val active = mutableSetOf<ThingWriteTicket>()

    @Synchronized
    fun register(thingId: String, channel: String?): ThingWriteTicket? {
        val normalized = thingId.trim().takeIf { it.isNotEmpty() } ?: return null
        return ThingWriteTicket(normalized, channel?.trim()?.takeIf { it.isNotEmpty() }) {
            release(it)
        }.also(active::add)
    }

    @Synchronized
    private fun release(ticket: ThingWriteTicket) { active.remove(ticket) }

    @Synchronized
    fun invalidateThings(ids: Collection<String>) {
        active.filter { it.thingId in ids }.forEach(ThingWriteTicket::invalidate)
    }

    @Synchronized
    fun invalidateChannel(channel: String?) {
        active.filter { channel == null || it.channel == channel }.forEach(ThingWriteTicket::invalidate)
    }
}
