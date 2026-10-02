package io.ethan.pushgo.testing

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.ethan.pushgo.BuildConfig

/** Shell-only quality control without entering product UI or starting delivery work. */
class BenchmarkUnstopActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.QUALITY_SESSION_CONTROL_ENABLED) {
            "benchmark unstop control is disabled"
        }
        val clear = intent.getBooleanExtra(EXTRA_CLEAR_SESSION, false)
        val encodedSession = intent.getStringExtra(EXTRA_SESSION_BASE64)
        if (clear || encodedSession != null) {
            runCatching {
                if (clear) {
                    contentResolver.call(qualityFixtureUri(), METHOD_CLEAR, null, null)
                } else {
                    val result = contentResolver.call(
                        qualityFixtureUri(),
                        METHOD_PREPARE,
                        checkNotNull(encodedSession),
                        null,
                    )
                    check(result?.getString("status") == "ready") {
                        "quality fixture did not report readiness"
                    }
                }
            }.onFailure { error ->
                Log.e(LOG_TAG, "quality control failed", error)
            }.onSuccess {
                Log.i(LOG_TAG, "quality control completed: ${if (clear) "clear" else "prepare"}")
            }
            // Theme.NoDisplay requires finish() before onResume completes. Keep
            // this shell-only control synchronous so completion is observable.
            finish()
        } else {
            finish()
        }
    }

    private fun qualityFixtureUri(): Uri = Uri.parse(
        "content://${applicationContext.packageName}.quality-fixture",
    )

    companion object {
        const val EXTRA_SESSION_BASE64 = "io.ethan.pushgo.testing.SESSION_BASE64"
        const val EXTRA_CLEAR_SESSION = "io.ethan.pushgo.testing.CLEAR_SESSION"
        private const val METHOD_PREPARE = "prepare"
        private const val METHOD_CLEAR = "clear"
        private const val LOG_TAG = "PushGoQualityControl"
    }
}
