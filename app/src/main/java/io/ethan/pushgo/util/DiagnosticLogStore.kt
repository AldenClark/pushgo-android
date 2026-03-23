package io.ethan.pushgo.util

import java.util.concurrent.atomic.AtomicLong

enum class DiagnosticLogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class DiagnosticLogEntry(
    val timestampMs: Long,
    val level: DiagnosticLogLevel,
    val tag: String,
    val message: String,
)

object DiagnosticLogStore {
    private const val MAX_ENTRIES = 400

    private val nextListenerId = AtomicLong(1L)
    private val listeners = LinkedHashMap<Long, (DiagnosticLogEntry) -> Unit>()
    private val entries = ArrayDeque<DiagnosticLogEntry>(MAX_ENTRIES)
    private val lock = Any()

    fun record(
        level: DiagnosticLogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val entry = DiagnosticLogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = buildString {
                append(message.trim())
                val detail = throwable?.let(::throwableSummary)
                if (!detail.isNullOrBlank()) {
                    if (isNotEmpty()) append(" | ")
                    append(detail)
                }
            }.ifBlank { "<empty>" },
        )
        val currentListeners = synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(entry)
            listeners.values.toList()
        }
        currentListeners.forEach { listener -> runCatching { listener(entry) } }
    }

    fun snapshot(): List<DiagnosticLogEntry> {
        return synchronized(lock) { entries.toList() }
    }

    fun registerListener(listener: (DiagnosticLogEntry) -> Unit): Long {
        val id = nextListenerId.getAndIncrement()
        synchronized(lock) {
            listeners[id] = listener
        }
        return id
    }

    fun unregisterListener(id: Long) {
        synchronized(lock) {
            listeners.remove(id)
        }
    }

    private fun throwableSummary(throwable: Throwable): String {
        val primary = throwable.message?.trim().takeUnless { it.isNullOrEmpty() }
        val cause = throwable.cause?.message?.trim().takeUnless { it.isNullOrEmpty() }
        return buildString {
            append(throwable::class.java.simpleName)
            if (primary != null) {
                append(": ")
                append(primary)
            }
            if (cause != null && cause != primary) {
                append(" | cause=")
                append(cause)
            }
        }
    }
}
