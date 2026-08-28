package io.ethan.pushgo.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.PushGoApp
import kotlinx.coroutines.runBlocking

class BenchmarkFixtureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(BuildConfig.QUALITY_SESSION_CONTROL_ENABLED) {
            "benchmark fixture control is disabled"
        }
        return when (method) {
            METHOD_PREPARE -> prepare(checkNotNull(arg) { "encoded session is required" })
            METHOD_CLEAR -> clear()
            else -> error("unsupported benchmark fixture method: $method")
        }
    }

    private fun prepare(encodedSession: String): Bundle {
        val appContext = checkNotNull(context).applicationContext
        val app = appContext as PushGoApp
        val session = QualityRuntime.decode(encodedSession)
        check(session.fixture == QualityFixture.MESSAGES_LARGE) {
            "benchmark startup currently requires messages.large"
        }

        app.releaseStorageForQualityControl()
        check(app.deleteDatabase(session.databaseName) || !app.getDatabasePath(session.databaseName).exists()) {
            "benchmark database could not be reset"
        }
        app.deleteSharedPreferences(session.securePreferencesName)
        app.deleteSharedPreferences(session.settingsCachePreferencesName)
        val sessionRoot = QualityRuntime.sessionRoot(app, session)
        check(sessionRoot.deleteRecursively() || !sessionRoot.exists()) {
            "benchmark session directory could not be reset"
        }
        // Persist before updating the in-process profile. A fresh app process can
        // still be completing Application.onCreate while the provider receives
        // its first shell call; either ordering must resolve to this same session.
        QualityRuntime.persistAppOwnedSession(app, encodedSession)
        QualityRuntime.configure(encodedSession)

        val container = checkNotNull(app.containerOrNull()) {
            "benchmark AppContainer could not be created"
        }
        val verification = runBlocking {
            val actualSessionId = QualityRuntime.currentSession()?.sessionId
            check(actualSessionId == session.sessionId) {
                "benchmark AppContainer session mismatch: expected=${session.sessionId}, " +
                    "actual=${actualSessionId ?: "production"}"
            }
            container.initializeQualityFixtureIfNeeded()
            val count = container.messageRepository.totalCount()
            val sentinel = container.messageRepository.getByMessageId("quality-large-999")
            Triple(count, sentinel?.title, sentinel?.body)
        }
        check(verification.first == 1_000) { "benchmark fixture count was ${verification.first}" }
        check(verification.second == EXPECTED_TITLE) { "benchmark fixture title was not exact" }
        check(verification.third == EXPECTED_BODY) { "benchmark fixture body was not exact" }

        return Bundle().apply {
            putString("status", "ready")
            putInt("count", verification.first)
            putString("title", verification.second)
        }
    }

    private fun clear(): Bundle {
        val app = checkNotNull(context).applicationContext as PushGoApp
        val session = QualityRuntime.currentSession()
        app.releaseStorageForQualityControl()
        session?.let {
            app.deleteDatabase(it.databaseName)
            app.deleteSharedPreferences(it.securePreferencesName)
            app.deleteSharedPreferences(it.settingsCachePreferencesName)
            QualityRuntime.sessionRoot(app)?.let { root ->
                check(root.deleteRecursively() || !root.exists()) {
                    "benchmark session directory could not be cleared"
                }
            }
        }
        QualityRuntime.persistAppOwnedSession(app, null)
        QualityRuntime.configure(null)
        return Bundle().apply { putString("status", "cleared") }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val METHOD_PREPARE = "prepare"
        const val METHOD_CLEAR = "clear"
        const val EXPECTED_TITLE = "Quality message 999"
        const val EXPECTED_BODY = "Deterministic app-owned performance fixture row 999."
    }
}
