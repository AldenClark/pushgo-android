package io.ethan.pushgo.test

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import io.ethan.pushgo.testing.InstrumentationRuntime

class PushGoAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application {
        InstrumentationRuntime.markUnderInstrumentationTest()
        return super.newApplication(cl, className, context)
    }
}
