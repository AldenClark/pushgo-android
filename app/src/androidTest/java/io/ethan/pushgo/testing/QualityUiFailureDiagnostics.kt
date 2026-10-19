package io.ethan.pushgo.testing

import android.util.Log
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream

/** Bounded UI evidence for a failed quality assertion, emitted into that test's logcat artifact. */
internal object QualityUiFailureDiagnostics {
    private const val TAG = "QualityUiFailure"
    private const val MAX_CHARS = 24_000
    private const val LOG_CHUNK_CHARS = 2_500

    fun logWindowRoots(checkpoint: String) {
        logText(checkpoint, "elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
        val roots = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Best-effort passive snapshot: do not drain the main queue or
                // wait for focus/idle while observing the Cancel transition.
                val views = WindowInspector.getGlobalWindowViews()
                "rootCount=${views.size}\n" + views.mapIndexed { index, view ->
                    val parameters = view.layoutParams as? WindowManager.LayoutParams
                    "root[$index] identity=${System.identityHashCode(view)} " +
                        "class=${view.javaClass.name} type=${parameters?.type} " +
                        "title=${parameters?.title} attached=${view.isAttachedToWindow} " +
                        "windowFocus=${view.hasWindowFocus()} viewFocus=${view.hasFocus()} " +
                        "visibility=${view.visibility} shown=${view.isShown} " +
                        "token=${view.windowToken}"
                }.joinToString("\n")
            } else {
                "Global window inspection requires API29; actual=${Build.VERSION.SDK_INT}"
            }
        }.getOrElse { "Window roots unavailable: ${it.javaClass.simpleName}: ${it.message}" }
        logText("$checkpoint in-process roots", roots)
    }

    fun logWindowState(device: UiDevice, checkpoint: String) {
        logWindowRoots(checkpoint)
        val focusedWindow = runCatching {
            device.executeShellCommand("dumpsys window windows").lineSequence()
                .filter { line ->
                    line.contains("mCurrentFocus") || line.contains("mFocusedApp") ||
                        line.contains("mFocusedWindow") || line.contains("mTopFocusedDisplayId") ||
                        line.contains("mInputMethodTarget")
                }.joinToString("\n").ifEmpty { "No focused-window fields in dumpsys output" }
        }.getOrElse { "OS focused window unavailable: ${it.javaClass.simpleName}: ${it.message}" }
        logText("$checkpoint OS focused window", focusedWindow)
        logWindowHierarchy(device, checkpoint)
    }

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
