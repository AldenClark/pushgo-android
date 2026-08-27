package io.ethan.pushgo.test

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.testing.InstrumentationRuntime
import io.ethan.pushgo.testing.QualityRuntime

class PushGoAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        // Existing migration and storage tests must retain their production DB
        // contract. Quality tests either pass an explicit runner argument or own
        // their session before launching the activity.
        QualityRuntime.configure(arguments?.getString(QualityRuntime.ARG_SESSION_BASE64))
        super.onCreate(arguments)
    }

    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application {
        InstrumentationRuntime.markUnderInstrumentationTest()
        return super.newApplication(cl, className, context)
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        val databaseName = QualityRuntime.currentSession()?.databaseName
        val sessionRoot = QualityRuntime.sessionRoot(targetContext)
        runCatching {
            (targetContext.applicationContext as? PushGoApp)
                ?.releaseStorageForInstrumentationTest()
        }
        if (databaseName != null) {
            targetContext.deleteDatabase(databaseName)
        }
        runCatching { sessionRoot?.deleteRecursively() }
        QualityRuntime.resetForTesting()
        super.finish(resultCode, results)
    }
}
