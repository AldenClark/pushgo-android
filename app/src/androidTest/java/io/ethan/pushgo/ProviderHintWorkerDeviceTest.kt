package io.ethan.pushgo

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.ethan.pushgo.data.POST_PROCESS_TABLES
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.loadPendingPostProcess
import io.ethan.pushgo.notifications.InboundMessagePayloadCodec
import io.ethan.pushgo.notifications.InboundMessageWorker
import io.ethan.pushgo.testing.QualityRuntime
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual normal Worker and durable retry; container recreation is not process death or FCM delivery. */
@RunWith(AndroidJUnit4::class)
class ProviderHintWorkerDeviceTest {
    @Test
    fun fullInlineHintRetriesTargetedPullAfterContainerReopenWithoutPersistingItsUntrustedBody() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<PushGoApp>()
        assertNull(QualityRuntime.currentSession())
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("reuse only the already installed synthetic test key",
            keys.getKey("pushgo.secure.secrets.v1", null) is javax.crypto.SecretKey)
        app.releaseStorageForInstrumentationTest()
        var container = app.container
        val initial = container
        val database = container.database
        val existingIds = messageIds(database)
        assertTrue("only the exact named earlier synthetic default-database batches are permitted",
            existingIds.size in setOf(71, 141) && "worker-canonical-synthetic" in existingIds && existingIds.all {
                it == "worker-canonical-synthetic" ||
                    Regex("^bulk-worker-([0-9a-f-]{36})-[0-9]{3}$").matches(it) ||
                    Regex("^bulk-worker-current-([0-9a-f-]{36})-[0-9]{3}$").matches(it)
            })
        for (table in POST_PROCESS_TABLES.drop(1)) assertEquals(0, count(database, "SELECT COUNT(*) FROM $table"))
        assertTrue(POST_PROCESS_TABLES.indices.all { database.loadPendingPostProcess(it).isEmpty() })
        for (table in listOf("pending_thing_messages", "legacy_provider_ingress", "inbound_delivery_ack_outbox", "provider_pull_frontier")) {
            assertEquals("do not adopt unfinished responsibility in $table", 0, count(database, "SELECT COUNT(*) FROM $table"))
        }
        val manager = WorkManager.getInstance(app)
        assertTrue("do not adopt another unfinished inbound Worker",
            manager.getWorkInfosByTag(InboundMessageWorker::class.java.name).get(5, TimeUnit.SECONDS).all { it.state.isFinished })
        val settingsBefore = database.appSettingsDao().get()
        val cache = app.getSharedPreferences("pushgo_settings_cache", Context.MODE_PRIVATE)
        val cacheBefore = snapshot(cache)
        val oldGatewayCredential = container.secureSecretStore.gatewayToken()
        val oldDevice = container.secureSecretStore.deviceKey()
        assertTrue("no configured external Gateway is allowed", settingsBefore?.serverAddress.isNullOrBlank())
        assertTrue("no existing Gateway credential is adopted", oldGatewayCredential.isNullOrBlank() && settingsBefore?.token.isNullOrBlank())
        assertNull(container.settingsRepository.getGatewayTransitionJournal())
        val suffix = UUID.randomUUID().toString()
        val delivery = "hint-worker-delivery-$suffix"
        val canonicalId = "hint-worker-canonical-$suffix"
        val untrustedId = "hint-worker-inline-$suffix"
        val device = "hint-worker-device-$suffix"
        val pullCount = AtomicInteger()
        val ackCount = AtomicInteger()
        val authoritative = JSONObject().put("entity_type", "message").put("entity_id", canonicalId)
            .put("message_id", canonicalId).put("title", "Authoritative targeted Pull")
            .put("body", "Only authenticated Pull content may be retained")
            .put("op_id", "hint-worker-operation-$suffix").put("ttl", "1")
        val gateway = ProviderV2PullOnlyDeviceTest.Gateway { path, request ->
            when (path) {
                "/v2/messages/pull" -> {
                    assertEquals(device, request.getString("device_key"))
                    assertEquals(delivery, request.getString("delivery_id"))
                    if (pullCount.incrementAndGet() == 1) {
                        503 to JSONObject().put("success", false).put("error", "synthetic temporary unavailability")
                    } else {
                        200 to JSONObject().put("success", true).put("data", JSONObject()
                            .put("items", JSONArray().put(JSONObject().put("delivery_id", delivery).put("payload", authoritative)))
                            .put("has_more", false).put("scan_complete", true).put("next_page_token", JSONObject.NULL))
                    }
                }
                "/v2/messages/ack" -> {
                    val accepted = runBlocking { app.container.messageRepository.getByMessageId(canonicalId) }
                    assertEquals(authoritative.getString("body"), accepted?.body)
                    assertNull(runBlocking { app.container.messageRepository.getByMessageId(untrustedId) })
                    assertEquals(1, count(app.container.database,
                        "SELECT COUNT(*) FROM inbound_delivery_ledger WHERE delivery_id=? AND acceptance_proven=1", delivery))
                    ackCount.incrementAndGet()
                    assertEquals(device, request.getString("device_key"))
                    assertEquals(listOf(delivery), request.getJSONArray("delivery_ids").let { values -> (0 until values.length()).map(values::getString) })
                    200 to JSONObject().put("success", true).put("data", JSONObject().put("requested_count", 1).put("removed_count", 1))
                }
                else -> throw AssertionError("Unexpected local fixture path")
            }
        }
        val oldAckCredential = container.secureSecretStore.gatewayAckToken(gateway.baseUrl)
        val input = mapOf("entity_type" to "message", "entity_id" to untrustedId, "message_id" to untrustedId,
            "title" to "Untrusted inline title", "body" to "Untrusted inline content must never persist",
            "delivery_id" to delivery, "base_url" to gateway.baseUrl, "provider_device_key" to device,
            "ack_contract" to "v2_batch", "ttl" to "1",
            "images" to "http://127.0.0.1:1/unverified-inline-media.png",
            "token" to "synthetic-inline-token-must-not-persist", "password" to "synthetic-inline-password-must-not-persist")
        val transport = "hint-worker-transport-$suffix"
        val workName = InboundMessageWorker.buildUniqueWorkName(transport, InboundMessagePayloadCodec.encode(input))
        val states = JSONArray()
        var lastObserved = ""
        fun work(): WorkInfo? = manager.getWorkInfosForUniqueWork(workName).get(5, TimeUnit.SECONDS).singleOrNull()?.also {
            val key = "${it.state}:${it.runAttemptCount}"
            if (key != lastObserved) {
                states.put(JSONObject().put("state", it.state.name).put("run_attempt_count", it.runAttemptCount)
                    .put("elapsed_realtime_ms", SystemClock.elapsedRealtime()))
                lastObserved = key
            }
        }
        var complete = false
        var durableInputBytes: Int? = null
        var restored = false
        var failure: Throwable? = null
        try {
            container.settingsRepository.setServerAddress(gateway.baseUrl)
            container.settingsRepository.setGatewayToken("synthetic-local-credential-$suffix")
            container.settingsRepository.setDeviceKey(device)
            container.settingsRepository.setUseFcmChannel(false)
            assertTrue(manager.getWorkInfosForUniqueWork(workName).get(5, TimeUnit.SECONDS).isEmpty())
            InboundMessageWorker.enqueue(app, input, transport)
            withTimeout(15_000) {
                while (true) {
                    val info = work()
                    assertTrue("Worker cannot silently fail", info?.state != WorkInfo.State.FAILED)
                    if (info?.state == WorkInfo.State.ENQUEUED && info.runAttemptCount == 1 && pullCount.get() == 1) break
                    delay(50)
                }
            }
            // WorkInfo intentionally exposes no input. Read only this exact owned
            // WorkSpec's committed input BLOB; do not use a restricted library API.
            val workDatabase = File(app.noBackupFilesDir, "androidx.work.workdb")
            assertTrue("current WorkManager database must exist", workDatabase.isFile)
            val inputBlob = android.database.sqlite.SQLiteDatabase.openDatabase(workDatabase.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                sqlite.rawQuery("SELECT input FROM WorkSpec WHERE id=?", arrayOf(requireNotNull(work()).id.toString())).use { cursor ->
                    assertTrue(cursor.moveToFirst()); val value = cursor.getBlob(0); assertFalse(cursor.moveToNext()); value
                }
            }
            durableInputBytes = inputBlob.size
            assertTrue(inputBlob.size <= 10_240)
            val persistedInputMarkers = inputBlob.toString(Charsets.ISO_8859_1)
            // WorkSpec contains the JSON-encoded hint string, not independent raw
            // map values: JSONObject escapes characters such as URL slashes.
            val encodedHint = InboundMessagePayloadCodec.encode(InboundMessagePayloadCodec.providerHint(input))
            assertTrue("the complete encoded hint must be the committed WorkSpec input", persistedInputMarkers.contains(encodedHint))
            for (name in listOf("body", "title", "images", "token", "password", "message_id", "entity_id")) {
                input[name]?.let { assertFalse("inline $name must not be duplicated in durable input", persistedInputMarkers.contains(it)) }
            }
            assertEquals(existingIds, messageIds(container.database))
            assertEquals(0, ackCount.get())
            assertEquals(0, count(container.database, "SELECT COUNT(*) FROM inbound_delivery_ledger WHERE delivery_id=?", delivery))
            assertEquals(0, count(container.database, "SELECT COUNT(*) FROM inbound_delivery_ack_outbox"))
            app.releaseStorageForInstrumentationTest()
            container = app.container
            assertNotSame(initial, container)
            withTimeout(25_000) {
                while (true) {
                    val info = work()
                    assertTrue("retry must not become a permanent failure", info?.state != WorkInfo.State.FAILED)
                    if (info?.state == WorkInfo.State.SUCCEEDED) break
                    delay(50)
                }
            }
            gateway.assertHealthy()
            assertEquals(2, pullCount.get())
            assertEquals(1, ackCount.get())
            assertEquals(listOf("/v2/messages/pull", "/v2/messages/pull", "/v2/messages/ack"), gateway.paths())
            val saved = container.messageRepository.getByMessageId(canonicalId)
            assertEquals(existingIds + requireNotNull(saved).id, messageIds(container.database))
            assertEquals(authoritative.getString("title"), saved?.title)
            assertEquals(authoritative.getString("body"), saved?.body)
            assertNull(container.messageRepository.getByMessageId(untrustedId))
            assertEquals(1, count(container.database,
                "SELECT COUNT(*) FROM inbound_delivery_ledger WHERE gateway_url=? AND device_key=? AND delivery_id=? AND acceptance_proven=1 AND ack_state='acked'",
                gateway.baseUrl, device, delivery))
            assertEquals(1, count(container.database,
                "SELECT COUNT(*) FROM message_resource_owners WHERE source_table='messages' AND owner_id=? AND authenticated_gateway_url=? AND authenticated_device_key=?",
                requireNotNull(saved).id, gateway.baseUrl, device))
            assertEquals(0, count(container.database, "SELECT COUNT(*) FROM inbound_delivery_ack_outbox"))
            complete = true
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                var cleanupError: Throwable? = null
                suspend fun cleanup(action: suspend () -> Unit) {
                    try { action() } catch (error: Throwable) {
                        if (cleanupError == null) cleanupError = error else cleanupError?.addSuppressed(error)
                    }
                }
                cleanup {
                    val info = work()
                    if (info != null && !info.state.isFinished) {
                        manager.cancelUniqueWork(workName).result.get(5, TimeUnit.SECONDS)
                        withTimeout(5_000) { while (work()?.state?.isFinished == false) delay(50) }
                    }
                }
                cleanup {
                    container = app.container
                    container.secureSecretStore.setGatewayToken(oldGatewayCredential)
                    container.secureSecretStore.setDeviceKey(oldDevice)
                    container.secureSecretStore.setGatewayAckToken(gateway.baseUrl, oldAckCredential)
                    val dao = container.database.appSettingsDao()
                    if (settingsBefore == null) dao.deleteAll() else dao.upsert(settingsBefore)
                    restore(cache, cacheBefore)
                    restored = dao.get() == settingsBefore && snapshot(cache) == cacheBefore &&
                        container.secureSecretStore.gatewayToken() == oldGatewayCredential &&
                        container.secureSecretStore.deviceKey() == oldDevice &&
                        container.secureSecretStore.gatewayAckToken(gateway.baseUrl) == oldAckCredential
                    assertTrue("all temporary settings must be restored", restored)
                }
                cleanup { gateway.close() }
                cleanup {
                    val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                        ?: File(app.cacheDir, "provider-hint-worker-output").absolutePath).apply { mkdirs() }
                    File(output, "actual-provider-hint-worker.json").writeText(JSONObject()
                        .put("completed", complete).put("settings_restored", restored).put("work_states", states)
                        .put("pull_requests", pullCount.get()).put("ack_requests", ackCount.get())
                        .put("durable_input_blob_bytes", durableInputBytes)
                        .put("original_inline_json_bytes", InboundMessagePayloadCodec.encode(input).toByteArray(Charsets.UTF_8).size)
                        .put("input_observation", "Exact owned WorkSpec read-only BLOB: required hints present and synthetic inline/media/credential sentinels absent; codec exact-map oracle is separate")
                        .put("container_reopened", container !== initial).put("new_synthetic_message_id", canonicalId)
                        .put("limits", "API37 Debug normal PushGoApp/real WorkManager/local HTTP fixture; no QA session, native runtime, real FCM, process death, TLS, performance or OS notification claim. Only this unique Worker is canceled; all synthetic business bodies and ledger evidence are retained.")
                        .toString(2))
                }
                cleanup { app.releaseStorageForInstrumentationTest() }
                cleanupError?.let { error -> if (failure != null) failure?.addSuppressed(error) else throw error }
            }
        }
    }

    private fun messageIds(database: PushGoDatabase): Set<String> = database.openHelper.readableDatabase.query("SELECT id FROM messages").use {
        buildSet { while (it.moveToNext()) add(it.getString(0)) }
    }

    private fun count(database: PushGoDatabase, sql: String, vararg arguments: Any): Int =
        database.openHelper.readableDatabase.query(sql, arguments).use { check(it.moveToFirst()); it.getInt(0) }

    private fun snapshot(preferences: SharedPreferences): Map<String, Any?> = preferences.all.mapValues { (_, value) ->
        if (value is Set<*>) value.toSet() else value
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, Any?>) {
        val editor = preferences.edit().clear()
        for ((key, value) in values) when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.map { requireNotNull(it as? String) }.toSet())
            else -> error("Unsupported existing preferences type")
        }
        check(editor.commit()) { "Settings projection restore failed" }
    }
}
