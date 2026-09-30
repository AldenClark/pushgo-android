package io.ethan.pushgo.testing

import android.util.Log
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream

/** Bounded UI evidence for a failed quality assertion, emitted into that test's logcat artifact. */
internal object QualityUiFailureDiagnostics {
    private const val TAG = "QualityUiFailure"
    private const val MAX_CHARS = 24_000
    private const val LOG_CHUNK_CHARS = 2_500

    fun logWindowHierarchy(device: UiDevice, checkpoint: String) {
        val hierarchy = runCatching {
            ByteArrayOutputStream().use { output ->
                device.dumpWindowHierarchy(output)
                output.toString(Charsets.UTF_8.name())
            }
        }.getOrElse { error ->
            "Window hierarchy unavailable: ${error.javaClass.simpleName}: ${error.message}"
        }
        logText("$checkpoint window hierarchy", hierarchy)
    }

    fun logText(checkpoint: String, value: String) {
        val bounded = value.take(MAX_CHARS)
        Log.e(TAG, "$checkpoint (${value.length} chars, showing ${bounded.length})")
        bounded.chunked(LOG_CHUNK_CHARS).forEachIndexed { index, chunk ->
            Log.e(TAG, "$checkpoint chunk ${index + 1}: $chunk")
        }
    }
}
