package io.ethan.pushgo.test

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Base64
import androidx.test.runner.AndroidJUnitRunner
import io.ethan.pushgo.PushGoApp
import io.ethan.pushgo.testing.InstrumentationRuntime
import io.ethan.pushgo.testing.QualityRuntime

class PushGoAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        restoreEncodedClassSelection(arguments)
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
        val session = QualityRuntime.currentSession()
        val databaseName = session?.databaseName
        val sessionRoot = QualityRuntime.sessionRoot(targetContext)
        runCatching {
            (targetContext.applicationContext as? PushGoApp)
                ?.releaseStorageForInstrumentationTest()
        }
        if (databaseName != null) {
            targetContext.deleteDatabase(databaseName)
        }
        session?.let {
            targetContext.deleteSharedPreferences(it.securePreferencesName)
            targetContext.deleteSharedPreferences(it.settingsCachePreferencesName)
        }
        runCatching { sessionRoot?.deleteRecursively() }
        QualityRuntime.resetForTesting()
        super.finish(resultCode, results)
    }
}

internal const val ENCODED_CLASS_SELECTION = "pushgo.test.classSelectorsBase64"

/** Test-only transport adaptation for AGP 9.4's comma-separated argument map. */
internal fun restoreEncodedClassSelection(arguments: Bundle?) {
    if (arguments == null || !arguments.containsKey(ENCODED_CLASS_SELECTION)) return
    require(arguments.getString("class").isNullOrBlank()) {
        "Encoded and standard class selectors cannot both be supplied"
    }
    val encoded = requireNotNull(arguments.getString(ENCODED_CLASS_SELECTION))
    require(encoded.isNotEmpty() && encoded.length <= 65_536 &&
        encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) {
        "Invalid encoded class selector"
    }
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    require(Base64.encodeToString(bytes, Base64.NO_WRAP) == encoded) {
        "Class selector encoding must be canonical Base64"
    }
    val selection = bytes.toString(Charsets.UTF_8)
    require(selection.toByteArray(Charsets.UTF_8).contentEquals(bytes) &&
        selection.split(',').all { it.isNotBlank() } &&
        '\n' !in selection && '\r' !in selection) {
        "Class selectors must be a nonempty UTF-8 scope list"
    }
    arguments.putString("class", selection)
    arguments.remove(ENCODED_CLASS_SELECTION)
}
