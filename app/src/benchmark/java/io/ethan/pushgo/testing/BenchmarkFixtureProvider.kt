package io.ethan.pushgo.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.ethan.pushgo.BuildConfig
import io.ethan.pushgo.PushGoApp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

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
        val session = preparationPhase("session.decode") {
            QualityRuntime.decode(encodedSession)
        }
        return try {
            preparationPhase("fixture.allowlist") {
                check(
                    session.fixture == QualityFixture.MESSAGES_LARGE
                        || session.fixture == QualityFixture.MESSAGES_STANDARD
                        || session.fixture == QualityFixture.EMPTY_CLEAN
                ) {
                    "external quality control supports messages.large, messages.standard, or empty.clean"
                }
            }
            val sessionRoot = QualityRuntime.sessionRoot(app, session)

            preparationPhase("storage.reset") {
                app.releaseStorageForQualityControl()
                check(app.deleteDatabase(session.databaseName) || !app.getDatabasePath(session.databaseName).exists()) {
                    "quality database could not be reset"
                }
                app.deleteSharedPreferences(session.securePreferencesName)
                app.deleteSharedPreferences(session.settingsCachePreferencesName)
                app.deleteSharedPreferences(session.reminderSnoozePreferencesName)
                check(sessionRoot.deleteRecursively() || !sessionRoot.exists()) {
                    "quality session directory could not be reset"
                }
            }
            // Persist before updating the in-process profile. A fresh app process can
            // still be completing Application.onCreate while the provider receives
            // its first shell call; either ordering must resolve to this same session.
            preparationPhase("session.persist") {
                QualityRuntime.persistAppOwnedSession(app, encodedSession)
                QualityRuntime.configure(encodedSession)
            }

            val container = preparationPhase("storage.open") {
                checkNotNull(app.containerOrNull()) {
                    "quality AppContainer could not be created"
                }
            }
            val verification = preparationPhase("fixture.seed") {
                runBlocking {
                    val actualSessionId = QualityRuntime.currentSession()?.sessionId
                    check(actualSessionId == session.sessionId) {
                        "quality AppContainer session mismatch: expected=${session.sessionId}, " +
                            "actual=${actualSessionId ?: "production"}"
                    }
                    container.initializeQualityFixtureIfNeeded()
                    val count = container.messageRepository.totalCount()
                    val sentinelId = when (session.fixture) {
                        QualityFixture.MESSAGES_LARGE -> "quality-large-999"
                        QualityFixture.MESSAGES_STANDARD -> "quality-standard-message"
                        else -> null
                    }
                    val sentinel = sentinelId?.let { container.messageRepository.getByMessageId(it) }
                    Triple(count, sentinel?.title, sentinel?.body)
                }
            }
            preparationPhase("fixture.verify") {
                when (session.fixture) {
                    QualityFixture.MESSAGES_LARGE -> {
                        check(verification.first == 1_000) { "benchmark fixture count was ${verification.first}" }
                        check(verification.second == EXPECTED_TITLE) { "benchmark fixture title was not exact" }
                        check(verification.third == EXPECTED_BODY) { "benchmark fixture body was not exact" }
                    }
                    QualityFixture.EMPTY_CLEAN -> {
                        check(verification.first == 0) { "empty update fixture count was ${verification.first}" }
                    }
                    QualityFixture.MESSAGES_STANDARD -> {
                        check(verification.first == 1) { "standard update fixture count was ${verification.first}" }
                        check(verification.second == EXPECTED_STANDARD_TITLE) {
                            "standard update fixture title was not exact"
                        }
                        check(verification.third == EXPECTED_STANDARD_BODY) {
                            "standard update fixture body was not exact"
                        }
                    }
                    else -> error("unsupported fixture escaped the external quality-control allowlist")
                }
            }
            if (session.systemCapabilities.contains(QualitySystemCapability.NOTIFICATION_PERMISSION_JOURNEY)) {
                snapshotAndResetNotificationPermissionDecision(app, sessionRoot)
            }

            Bundle().apply {
                putString("status", "ready")
                putInt("count", verification.first)
                putString("title", verification.second)
            }
        } catch (error: Throwable) {
            rollbackFailedPreparation(app, session, error)
            throw error
        }
    }

    private fun rollbackFailedPreparation(
        app: PushGoApp,
        session: QualitySessionDescriptor,
        originalError: Throwable,
    ) {
        val cleanupErrors = buildList {
            runCatching { app.releaseStorageForQualityControl() }.exceptionOrNull()?.let(::add)
            runCatching { app.deleteDatabase(session.databaseName) }.exceptionOrNull()?.let(::add)
            runCatching { app.deleteSharedPreferences(session.securePreferencesName) }
                .exceptionOrNull()?.let(::add)
            runCatching { app.deleteSharedPreferences(session.settingsCachePreferencesName) }
                .exceptionOrNull()?.let(::add)
            runCatching { app.deleteSharedPreferences(session.reminderSnoozePreferencesName) }
                .exceptionOrNull()?.let(::add)
            runCatching { QualityRuntime.sessionRoot(app, session).deleteRecursively() }
                .exceptionOrNull()?.let(::add)
            runCatching { QualityRuntime.persistAppOwnedSession(app, null) }
                .exceptionOrNull()?.let(::add)
            runCatching { QualityRuntime.configure(null) }.exceptionOrNull()?.let(::add)
        }
        cleanupErrors.forEach { cleanupError ->
            originalError.addSuppressed(
                IllegalStateException(
                    "QUALITY_PRECONDITION phase=cleanup reason=${cleanupError.message.orEmpty()}",
                    cleanupError,
                )
            )
        }
    }

    private inline fun <T> preparationPhase(phase: String, block: () -> T): T {
        return try {
            block()
        } catch (error: Throwable) {
            val reason = error.message
                ?.replace(Regex("[\\r\\n]+"), " ")
                ?.take(200)
                .orEmpty()
            throw IllegalStateException(
                "QUALITY_PRECONDITION phase=$phase reason=$reason",
                error,
            )
        }
    }

    private fun clear(): Bundle {
        val app = checkNotNull(context).applicationContext as PushGoApp
        val session = QualityRuntime.currentSession()
        app.releaseStorageForQualityControl()
        session?.let {
            restoreNotificationPermissionDecision(app, QualityRuntime.sessionRoot(app, it))
            app.deleteDatabase(it.databaseName)
            app.deleteSharedPreferences(it.securePreferencesName)
            app.deleteSharedPreferences(it.settingsCachePreferencesName)
            app.deleteSharedPreferences(it.reminderSnoozePreferencesName)
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

    private fun snapshotAndResetNotificationPermissionDecision(app: PushGoApp, sessionRoot: File) {
        check(sessionRoot.mkdirs() || sessionRoot.isDirectory) {
            "notification permission preference snapshot directory could not be created"
        }
        val preferences = app.getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, 0)
        val snapshot = JSONObject()
            .put("existed", preferences.contains(POST_NOTIFICATIONS_REQUESTED_KEY))
            .put("value", preferences.getBoolean(POST_NOTIFICATIONS_REQUESTED_KEY, false))
        File(sessionRoot, NOTIFICATION_PERMISSION_SNAPSHOT_FILE).writeText(snapshot.toString())
        check(preferences.edit().remove(POST_NOTIFICATIONS_REQUESTED_KEY).commit()) {
            "notification permission decision could not be reset"
        }
    }

    private fun restoreNotificationPermissionDecision(app: PushGoApp, sessionRoot: File) {
        val snapshotFile = File(sessionRoot, NOTIFICATION_PERMISSION_SNAPSHOT_FILE)
        if (!snapshotFile.isFile) return
        val snapshot = JSONObject(snapshotFile.readText())
        val editor = app.getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, 0).edit()
        if (snapshot.getBoolean("existed")) {
            editor.putBoolean(POST_NOTIFICATIONS_REQUESTED_KEY, snapshot.getBoolean("value"))
        } else {
            editor.remove(POST_NOTIFICATIONS_REQUESTED_KEY)
        }
        check(editor.commit()) { "notification permission decision could not be restored" }
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
        const val EXPECTED_STANDARD_TITLE = "P2 Split Seed Message"
        const val EXPECTED_STANDARD_BODY = "Seeded from fixture.seed_messages for UI validation."
        const val NOTIFICATION_PERMISSION_PREFERENCES = "pushgo_notification_permission"
        const val POST_NOTIFICATIONS_REQUESTED_KEY = "post_notifications_requested"
        const val NOTIFICATION_PERMISSION_SNAPSHOT_FILE = "notification-permission-preference.json"
    }
}
