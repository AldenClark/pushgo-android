package io.ethan.pushgo.testing

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FcmTokenDiagnosticsInstrumentedTest {
    @Test
    fun fcmTokenDiagnostics_dumpRuntimeConfigAndFetchResult() {
        val args = InstrumentationRegistry.getArguments()
        val enabled = args.getString("pushgo.runtime.fcmDiag")?.toBooleanStrictOrNull() == true
        assumeTrue("fcm diagnostics disabled; pass -e pushgo.runtime.fcmDiag true", enabled)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageName = context.packageName
        val buildAppId = io.ethan.pushgo.BuildConfig.APPLICATION_ID

        val apps = FirebaseApp.getApps(context)
        val app = apps.firstOrNull() ?: FirebaseApp.initializeApp(context)
        assertNotNull("FirebaseApp not initialized", app)
        val options = requireNotNull(app).options

        val googleAppIdRes = readResString(context, "google_app_id")
        val senderIdRes = readResString(context, "gcm_defaultSenderId")
        val apiKeyRes = readResString(context, "google_api_key")
        val projectIdRes = readResString(context, "project_id")

        assertFalse("google_app_id resource missing", googleAppIdRes.value.isNullOrBlank())
        assertFalse("gcm_defaultSenderId resource missing", senderIdRes.value.isNullOrBlank())
        assertFalse("google_api_key resource missing", apiKeyRes.value.isNullOrBlank())
        assertFalse("project_id resource missing", projectIdRes.value.isNullOrBlank())
        assertFalse("Firebase options applicationId missing", options.applicationId.isBlank())
        assertFalse("Firebase options gcmSenderId missing", options.gcmSenderId.isNullOrBlank())
        assertFalse("Firebase options apiKey missing", options.apiKey.isBlank())
        assertFalse("Firebase options projectId missing", options.projectId.isNullOrBlank())

        val playServicesCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        val playServicesLabel = when (playServicesCode) {
            ConnectionResult.SUCCESS -> "success"
            ConnectionResult.SERVICE_MISSING -> "service_missing"
            ConnectionResult.SERVICE_UPDATING -> "service_updating"
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "update_required"
            ConnectionResult.SERVICE_DISABLED -> "service_disabled"
            ConnectionResult.SERVICE_INVALID -> "service_invalid"
            ConnectionResult.NETWORK_ERROR -> "network_error"
            ConnectionResult.DEVELOPER_ERROR -> "developer_error"
            ConnectionResult.SIGN_IN_REQUIRED -> "sign_in_required"
            ConnectionResult.INTERNAL_ERROR -> "internal_error"
            ConnectionResult.TIMEOUT -> "timeout"
            ConnectionResult.API_UNAVAILABLE -> "api_unavailable"
            else -> "other_$playServicesCode"
        }
        val playServicesVersionCode = runCatching {
            val pkg = context.packageManager.getPackageInfo("com.google.android.gms", 0)
            pkg.longVersionCode.toString()
        }.getOrElse { "unavailable:${it.javaClass.simpleName}" }

        val latch = CountDownLatch(1)
        var token: String? = null
        var tokenFailure: Throwable? = null
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener {
                token = it?.trim()?.ifEmpty { null }
                latch.countDown()
            }
            .addOnFailureListener {
                tokenFailure = it
                latch.countDown()
            }
        val finished = latch.await(45, TimeUnit.SECONDS)
        assertTrue("FCM token task timeout", finished)

        val failureChain = throwableChain(tokenFailure)

        println(
            "RUNTIME_FCM_DIAG " +
                "package_name=$packageName " +
                "build_config_application_id=$buildAppId " +
                "firebase_apps_count=${apps.size} " +
                "firebase_application_id=${mask(options.applicationId)} " +
                "firebase_sender_id=${mask(options.gcmSenderId)} " +
                "firebase_project_id=${mask(options.projectId)} " +
                "firebase_api_key_hash=${sha256(options.apiKey)} " +
                "res_google_app_id_present=${googleAppIdRes.present} " +
                "res_google_app_id=${mask(googleAppIdRes.value)} " +
                "res_gcm_sender_id_present=${senderIdRes.present} " +
                "res_gcm_sender_id=${mask(senderIdRes.value)} " +
                "res_project_id_present=${projectIdRes.present} " +
                "res_project_id=${mask(projectIdRes.value)} " +
                "res_google_api_key_present=${apiKeyRes.present} " +
                "res_google_api_key_len=${apiKeyRes.value?.length ?: 0} " +
                "res_google_api_key_hash=${sha256(apiKeyRes.value)} " +
                "play_services_code=$playServicesCode " +
                "play_services_label=$playServicesLabel " +
                "play_services_version_code=$playServicesVersionCode " +
                "device_model=${Build.MODEL} " +
                "device_fingerprint=${Build.FINGERPRINT} " +
                "sdk_int=${Build.VERSION.SDK_INT} " +
                "token_present=${!token.isNullOrBlank()} " +
                "token_hash=${sha256(token)} " +
                "token_failure_chain=${sanitize(failureChain)}",
        )
    }

    private fun readResString(context: Context, name: String): ResValue {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        if (id == 0) return ResValue(false, null)
        return runCatching { ResValue(true, context.getString(id)) }.getOrElse { ResValue(true, null) }
    }

    private fun throwableChain(error: Throwable?): String {
        if (error == null) return "none"
        val parts = mutableListOf<String>()
        var cursor: Throwable? = error
        var depth = 0
        while (cursor != null && depth < 8) {
            parts += "${cursor.javaClass.simpleName}:${cursor.message.orEmpty()}"
            cursor = cursor.cause
            depth += 1
        }
        return parts.joinToString(" -> ")
    }

    private fun sanitize(value: String): String {
        return value.replace(Regex("\\s+"), "_").take(420)
    }

    private fun mask(value: String?): String {
        if (value.isNullOrBlank()) return "missing"
        if (value.length <= 8) return value
        return "${value.take(4)}...${value.takeLast(4)}"
    }

    private fun sha256(value: String?): String {
        if (value.isNullOrBlank()) return "missing"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class ResValue(
        val present: Boolean,
        val value: String?,
    )
}

