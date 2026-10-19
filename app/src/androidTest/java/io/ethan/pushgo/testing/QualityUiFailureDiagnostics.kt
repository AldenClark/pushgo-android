package io.ethan.pushgo.testing

import android.util.Log
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Bounded UI evidence for a failed quality assertion, emitted into that test's logcat artifact. */
internal object QualityUiFailureDiagnostics {
    private const val TAG = "QualityUiFailure"
    private const val MAX_CHARS = 24_000
    private const val LOG_CHUNK_CHARS = 2_500
    private const val MAX_OBSERVED_ROOTS = 8
    private const val MAX_HIERARCHY_BYTES = 64 * 1024

    /** A single-test observer, not an idling resource or a focus repair. */
    fun observeWindowLifecycle(selector: String): WindowLifecycleObservation =
        WindowLifecycleObservation(selector)

    internal class WindowLifecycleObservation(private val selector: String) : AutoCloseable {
        private data class ObservedRoot(
            val view: View,
            val treeObserver: ViewTreeObserver,
            val attachListener: View.OnAttachStateChangeListener,
            val focusListener: ViewTreeObserver.OnWindowFocusChangeListener,
        )

        private val roots = mutableListOf<ObservedRoot>()
        private var closed = false
        private val hierarchyDirectory by lazy {
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "quality-back-window-${SystemClock.elapsedRealtime()}",
            )
        }
        private val exportedCheckpoints = mutableSetOf<String>()

        fun logState(device: UiDevice, stage: String) {
            val slot = when (stage) {
                "before Back" -> "before-back"
                "after Back" -> "after-back"
                "original Back failure" -> "back-failure"
                else -> error("Unsupported diagnostic checkpoint: $stage")
            }
            // Preserve the first snapshot in each of three bounded slots.
            val file = if (exportedCheckpoints.add(slot)) File(hierarchyDirectory, "$slot.xml") else null
            logWindowState(device, "$selector $stage", file)
        }

        fun checkpoint(stage: String) {
            // Listener registration must use the UI thread. This and the OS snapshots
            // change scheduling; a diagnostic PASS is not evidence of a causal repair.
            runCatching {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    if (closed || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runOnMainSync
                    WindowInspector.getGlobalWindowViews().forEach { view ->
                        if (roots.size >= MAX_OBSERVED_ROOTS || roots.any { it.view === view }) return@forEach
                        val attachListener = object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(view: View) = record(view, "attached")
                            override fun onViewDetachedFromWindow(view: View) = record(view, "detached")
                        }
                        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                            record(view, "windowFocus=$focused")
                        }
                        val treeObserver = view.viewTreeObserver
                        roots += ObservedRoot(view, treeObserver, attachListener, focusListener)
                        view.addOnAttachStateChangeListener(attachListener)
                        treeObserver.addOnWindowFocusChangeListener(focusListener)
                        record(view, "firstObserved stage=$stage")
                    }
                }
            }.onFailure { logText("$selector lifecycle unavailable", "${it.javaClass.simpleName}: ${it.message}") }
            logWindowRoots("$selector $stage")
        }

        private fun record(view: View, event: String) {
            val parameters = view.layoutParams as? WindowManager.LayoutParams
            logText(
                "$selector lifecycle",
                "elapsedRealtimeMs=${SystemClock.elapsedRealtime()} event=$event " +
                    "identity=${System.identityHashCode(view)} type=${parameters?.type} " +
                    "title=${parameters?.title} attached=${view.isAttachedToWindow} " +
                    "windowFocus=${view.hasWindowFocus()} visibility=${view.visibility}",
            )
        }

        override fun close() {
            runCatching {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    closed = true
                    var cleanupFailures = 0
                    roots.forEach { root ->
                        runCatching {
                            root.view.removeOnAttachStateChangeListener(root.attachListener)
                            if (root.treeObserver.isAlive) {
                                root.treeObserver.removeOnWindowFocusChangeListener(root.focusListener)
                            }
                            val currentObserver = root.view.viewTreeObserver
                            if (currentObserver !== root.treeObserver && currentObserver.isAlive) {
                                currentObserver.removeOnWindowFocusChangeListener(root.focusListener)
                            }
                        }.onFailure {
                            cleanupFailures++
                            logText("$selector lifecycle cleanup unavailable", "${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                    logText("$selector lifecycle cleanup", "observedRoots=${roots.size} cleanupFailures=$cleanupFailures")
                    roots.clear()
                }
            }.onFailure { logText("$selector lifecycle cleanup unavailable", "${it.javaClass.simpleName}: ${it.message}") }
        }
    }

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

    fun logWindowState(device: UiDevice, checkpoint: String, hierarchyFile: File? = null) {
        logWindowRoots(checkpoint)
        val focusedWindow = runCatching {
            device.executeShellCommand("dumpsys window").lineSequence()
                .filter { line ->
                    line.contains("mCurrentFocus") || line.contains("mFocusedApp") ||
                        line.contains("mFocusedWindow") || line.contains("mTopFocusedDisplayId") ||
                        line.contains("mInputMethodTarget")
                }.joinToString("\n").ifEmpty { "No focused-window fields in dumpsys output" }
        }.getOrElse { "OS focused window unavailable: ${it.javaClass.simpleName}: ${it.message}" }
        logText("$checkpoint OS focused window", focusedWindow)
        logWindowHierarchy(device, checkpoint, hierarchyFile)
    }

    fun logWindowHierarchy(device: UiDevice, checkpoint: String, hierarchyFile: File? = null) {
        val hierarchy = runCatching {
            val output = object : ByteArrayOutputStream() {
                var sourceBytes = 0L
                override fun write(value: Int) {
                    sourceBytes++
                    if (size() < MAX_HIERARCHY_BYTES) super.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    sourceBytes += length.toLong()
                    super.write(bytes, offset, minOf(length, MAX_HIERARCHY_BYTES - size()))
                }
            }
            output.use {
                device.dumpWindowHierarchy(output)
                val captured = output.toByteArray()
                // A byte cap may split one UTF-8 code point. Retain a valid prefix.
                var retainedEnd = captured.size
                if (output.sourceBytes > captured.size && captured.isNotEmpty()) {
                    var lead = captured.lastIndex
                    while (lead >= 0 && (captured[lead].toInt() and 0xc0) == 0x80) lead--
                    if (lead >= 0) {
                        val first = captured[lead].toInt() and 0xff
                        val codePointBytes = when {
                            first < 0x80 -> 1
                            first < 0xe0 -> 2
                            first < 0xf0 -> 3
                            else -> 4
                        }
                        if (lead + codePointBytes > retainedEnd) retainedEnd = lead
                    }
                }
                val retained = captured.copyOf(retainedEnd)
                if (hierarchyFile != null) {
                    check(hierarchyFile.parentFile?.mkdirs() == true || hierarchyFile.parentFile?.isDirectory == true)
                    hierarchyFile.writeBytes(retained)
                    val metadata = JSONObject()
                        .put("checkpoint", checkpoint)
                        .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
                        .put("source_bytes", output.sourceBytes)
                        .put("retained_bytes", retained.size)
                        .put("truncated", output.sourceBytes > retained.size)
                        .put("utf8_boundary_bytes_dropped", captured.size - retained.size)
                        .put("log_max_chars", MAX_CHARS)
                        .put("sha256", MessageDigest.getInstance("SHA-256").digest(retained).joinToString("") { "%02x".format(it) })
                    File(hierarchyFile.parentFile, "${hierarchyFile.nameWithoutExtension}.json")
                        .writeText(metadata.toString(), Charsets.UTF_8)
                    logText("$checkpoint hierarchy export", "path=${hierarchyFile.absolutePath} metadata=$metadata")
                }
                retained.toString(Charsets.UTF_8)
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
