package io.ethan.pushgo.testing

import android.app.Activity
import android.os.Bundle
import io.ethan.pushgo.BuildConfig

/** Clears Android's stopped-state without entering product UI or starting delivery work. */
class BenchmarkUnstopActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.QUALITY_SESSION_CONTROL_ENABLED) {
            "benchmark unstop control is disabled"
        }
        finish()
    }
}
