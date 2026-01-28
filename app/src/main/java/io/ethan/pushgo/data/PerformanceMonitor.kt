package io.ethan.pushgo.data

import android.os.SystemClock

enum class PerformanceOperation {
    LIST_PAGE_LOAD,
    CHANNEL_FILTER,
    BULK_READ,
    BULK_DELETE,
    WRITE_BATCH,
}

class PerformanceMonitor(
    private val sampleWindow: Int = AppConstants.performanceSampleWindow,
    private val cooldownMs: Long = AppConstants.performanceDegradationCooldownMs,
) {
    private data class Bucket(
        val samples: MutableList<Long> = mutableListOf(),
        var lastTriggeredAt: Long? = null,
    )

    private val buckets = mutableMapOf<PerformanceOperation, Bucket>()

    fun record(operation: PerformanceOperation, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val bucket = buckets.getOrPut(operation) { Bucket() }
        bucket.samples.add(durationMs)
        if (bucket.samples.size > sampleWindow) {
            bucket.samples.subList(0, bucket.samples.size - sampleWindow).clear()
        }

        if (bucket.samples.size == sampleWindow && shouldTrigger(bucket, operation)) {
            bucket.lastTriggeredAt = SystemClock.elapsedRealtime()
            bucket.samples.clear()
            return true
        }
        return false
    }

    private fun shouldTrigger(bucket: Bucket, operation: PerformanceOperation): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = bucket.lastTriggeredAt
        if (last != null && now - last < cooldownMs) return false
        val avg = bucket.samples.average()
        return avg >= thresholdMs(operation)
    }

    private fun thresholdMs(operation: PerformanceOperation): Double {
        return when (operation) {
            PerformanceOperation.LIST_PAGE_LOAD -> 180.0
            PerformanceOperation.CHANNEL_FILTER -> 240.0
            PerformanceOperation.BULK_READ -> 260.0
            PerformanceOperation.BULK_DELETE -> 320.0
            PerformanceOperation.WRITE_BATCH -> 200.0
        }
    }
}
