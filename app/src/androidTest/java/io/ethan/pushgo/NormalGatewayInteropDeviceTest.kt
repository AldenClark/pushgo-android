package io.ethan.pushgo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the explicitly provisioned, task-owned normal local Gateway context. */
@RunWith(AndroidJUnit4::class)
class NormalGatewayInteropDeviceTest {
    @Test
    fun actualNormalGatewayDeepScanSurvivesContainerReopenAndAutomaticContinuation() = kotlinx.coroutines.runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val contextPath = InstrumentationRegistry.getArguments().getString("pushgo.normalGatewayContextPath")
        assumeTrue("No task-owned normal Gateway supplied; this is NOT RUN", contextPath != null)
        require(requireNotNull(contextPath).matches(Regex("/data/local/tmp/pushgo-delivery-[A-Za-z0-9_-]+\\.json")))
        val supplied = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cat $contextPath"))
            .bufferedReader().use { JSONObject(it.readText()) }
        check(supplied.getBoolean("synthetic_only") && supplied.getBoolean("normal_product"))
        val origin = supplied.getString("base_url")
        check(origin.startsWith("http://127.0.0.1:"))
        val device = supplied.getString("device_key")
        val conflicts = supplied.getJSONArray("rejected")
        val healthy = supplied.getJSONArray("healthy")
        assertEquals(1_800, conflicts.length()); assertEquals(5, healthy.length())
        val boundary = supplied.getJSONObject("boundary")
        assertEquals(49_152, boundary.getInt("accepted_request_bytes"))
        assertEquals(49_153, boundary.getInt("rejected_request_bytes"))
        assertEquals(400, boundary.getInt("status"))
        assertEquals("invalid_request_body", boundary.getString("error_code"))
        assertEquals(0, boundary.getInt("identity_rows")); assertEquals(0, boundary.getInt("target_rows"))
        val base = instrument.targetContext
        val ownerId = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "normal-gateway-deep-owner-$ownerId").apply { mkdirs() }
        val databaseRoot = File(root, "databases").apply { mkdirs() }
        val preferencePrefix = "normal_gateway_deep_${ownerId}_"
        val preferenceNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val owned = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File {
                val suppliedFile = File(name)
                val file = if (suppliedFile.isAbsolute) suppliedFile else File(databaseRoot, name)
                check(file.canonicalPath.startsWith(databaseRoot.canonicalPath + File.separator))
                file.parentFile?.mkdirs()
                return file
            }
            override fun databaseList(): Array<String> = databaseRoot.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".db") }.map { it.name }.toTypedArray()
            override fun deleteDatabase(name: String) = android.database.sqlite.SQLiteDatabase.deleteDatabase(getDatabasePath(name))
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolatedName = preferencePrefix + name
                preferenceNames += isolatedName
                return base.getSharedPreferences(isolatedName, mode)
            }
        }
        assertNull(io.ethan.pushgo.testing.QualityRuntime.currentSession())
        assertTrue("Existing LAN permission is required and never changed here", io.ethan.pushgo.util.LocalNetworkPermission.isGranted(owned))
        val notificationPermissionBefore = androidx.core.app.NotificationManagerCompat.from(owned).areNotificationsEnabled()
        val notificationManager = owned.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val initialNotifications = notificationManager.activeNotifications.map { Triple(it.tag, it.id, it.postTime) }.toSet()
        // A granted baseline is not revoked for a test premise. Real low-severity
        // product notifications may publish or be rate-limited independently of ACK.
        val notificationCleanup = JSONObject()
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("Reuse the existing app key; never create a new alias for this test",
            keyStore.getKey("pushgo.secure.secrets.v1", null) is javax.crypto.SecretKey)
        var actual: io.ethan.pushgo.data.AppContainer? = null
        var ownerJob: kotlinx.coroutines.CompletableJob? = null
        val observations = org.json.JSONArray()
        val retained = linkedMapOf<String, String>()
        val badDeliveries = org.json.JSONArray()
        val settled = org.json.JSONArray()
        var firstHealthy = 0
        var secondForeground = 0
        var pollCount = 0
        var passed = false
        var originalFailure: Throwable? = null
        fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun scalar(container: io.ethan.pushgo.data.AppContainer, sql: String, args: Array<Any> = emptyArray()): Int =
            container.database.openHelper.readableDatabase.query(sql, args).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
        fun currentAcked(container: io.ethan.pushgo.data.AppContainer) = scalar(container,
            "SELECT COUNT(*) FROM inbound_delivery_ledger WHERE gateway_url=? AND device_key=? AND ack_state='acked'", arrayOf(origin, device))
        fun currentLedger(container: io.ethan.pushgo.data.AppContainer) = scalar(container,
            "SELECT COUNT(*) FROM inbound_delivery_ledger WHERE gateway_url=? AND device_key=?", arrayOf(origin, device))
        fun rowHash(container: io.ethan.pushgo.data.AppContainer, messageId: String): String =
            container.database.openHelper.readableDatabase.query(
                "SELECT m.*,o.* FROM messages m JOIN message_resource_owners o ON o.source_table='messages' AND o.owner_id=m.id AND o.owner_slot=0 WHERE m.message_id=?",
                arrayOf(messageId),
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Expected retained synthetic canonical owner is missing" }
                val values = org.json.JSONArray()
                for (column in 0 until cursor.columnCount) values.put(if (cursor.isNull(column)) JSONObject.NULL else cursor.getString(column))
                check(!cursor.moveToNext()) { "Synthetic canonical identity was duplicated" }
                hash(values.toString().toByteArray(Charsets.UTF_8))
            }
        fun observe(stage: String, container: io.ethan.pushgo.data.AppContainer) {
            observations.put(JSONObject().put("stage", stage).put("elapsed_realtime_ms", android.os.SystemClock.elapsedRealtime())
                .put("canonical_rows", scalar(container, "SELECT COUNT(*) FROM messages"))
                .put("current_scope_acked", currentAcked(container))
                .put("ack_outbox_rows", scalar(container, "SELECT COUNT(*) FROM inbound_delivery_ack_outbox"))
                .put("process_cpu_ms", android.os.Process.getElapsedCpuTime())
                .put("pss_kib", android.os.Debug.getPss())
                .put("native_heap_bytes", android.os.Debug.getNativeHeapAllocatedSize())
                .put("java_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()))
        }
        suspend fun closeOwner() {
            val closing = actual
            val closingJob = ownerJob
            actual = null; ownerJob = null
            val failures = mutableListOf<Throwable>()
            runCatching { closing?.close() }.exceptionOrNull()?.let(failures::add)
            runCatching { closingJob?.cancel(); closingJob?.join() }.exceptionOrNull()?.let(failures::add)
            runCatching { closing?.messageImageStore?.close() }.exceptionOrNull()?.let(failures::add)
            runCatching { io.ethan.pushgo.data.ImageAssetMetadataStore.closeForTest(owned) }.exceptionOrNull()?.let(failures::add)
            runCatching { closing?.database?.close() }.exceptionOrNull()?.let(failures::add)
            failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
        }
        try {
            val firstGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            ownerJob = kotlinx.coroutines.SupervisorJob()
            val first = io.ethan.pushgo.data.AppContainer(owned,
                kotlinx.coroutines.CoroutineScope(requireNotNull(ownerJob) + kotlinx.coroutines.Dispatchers.IO),
                awaitRouteSynchronization = { firstGate.await() }).also { actual = it }
            assertEquals(0, scalar(first, "SELECT COUNT(*) FROM messages"))
            val foreign = requireNotNull(io.ethan.pushgo.data.InboundDeliveryScope.create("https://foreign-normal-gateway.invalid", "foreign-device"))
            for (index in 0 until conflicts.length()) {
                val item = conflicts.getJSONObject(index)
                val wire = item.getJSONObject("payload")
                val deliveryId = item.getString("delivery_id")
                val values = wire.keys().asSequence().associateWith { key ->
                    check(wire.get(key) is String) { "Head conflict context must contain actual string-valued wire payloads" }
                    wire.getString(key)
                }.toMutableMap().apply { put("delivery_id", deliveryId) }
                val parsed = (io.ethan.pushgo.notifications.NotificationIngressParser.parse(values, deliveryId, null)
                    as io.ethan.pushgo.notifications.InboundPersistenceRequest.Message).copy(shouldNotify = false)
                val message = parsed.message
                val oldBody = if (index % 2 == 0) message.body else "foreign preserved $index: ${message.body}"
                val preserved = message.copy(body = oldBody,
                    rawPayloadJson = JSONObject(message.rawPayloadJson).put("body", oldBody).toString())
                assertTrue(first.messageRepository.insertIncoming(preserved, deliveryScope = foreign, postProcessFlags = 0))
                val messageId = requireNotNull(message.messageId)
                check(retained.put(messageId, rowHash(first, messageId)) == null)
                badDeliveries.put(JSONObject().put("delivery_id", deliveryId).put("message_id", messageId))
            }
            first.settingsRepository.setServerAddress(origin)
            first.settingsRepository.setDeviceKey(device)
            first.settingsRepository.setGatewayToken(supplied.getString("token"))
            first.settingsRepository.setGatewayAckToken(origin, supplied.getString("token"))
            first.settingsRepository.setUseFcmChannel(false)
            observe("foreign_head_seeded", first)
            // Only automatic continuation awaits the existing route-readiness gate.
            // Foreground production Pull still owns its normal bounded four-page turn.
            val initialFailure = runCatching {
                io.ethan.pushgo.notifications.ProviderIngressCoordinator.pullPersistAndDrainAcks(owned,
                    first.channelRepository, first.messageRepository, first.entityRepository,
                    first.inboundDeliveryLedgerRepository, first.settingsRepository, reason = "normal_deep_scan_first_turn")
            }.exceptionOrNull()
            assertTrue("Independent conflicts retain their original delivery responsibility",
                initialFailure is io.ethan.pushgo.notifications.InboundMessageProcessor.InboundRetryableException)
            firstHealthy = scalar(first, "SELECT COUNT(*) FROM messages") - conflicts.length()
            assertEquals("The first bounded turn has only retained conflicts, without a saved healthy item", 0, firstHealthy)
            assertEquals(firstHealthy, currentAcked(first))
            assertEquals(firstHealthy, currentLedger(first))
            assertEquals(0, scalar(first, "SELECT COUNT(*) FROM inbound_delivery_ack_outbox"))
            val firstToken = requireNotNull(first.database.providerPullFrontierDao().get()?.pageToken)
            assertFalse(firstGate.isCompleted)
            for ((id, expected) in retained) assertEquals(expected, rowHash(first, id))
            observe("bounded_first_turn_frontier_retained", first)
            closeOwner() // Same UUID DB/prefs remain; this is container recreation, not process death.
            ownerJob = kotlinx.coroutines.SupervisorJob()
            val secondGate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val reopened = io.ethan.pushgo.data.AppContainer(owned,
                kotlinx.coroutines.CoroutineScope(requireNotNull(ownerJob) + kotlinx.coroutines.Dispatchers.IO),
                awaitRouteSynchronization = { secondGate.await() }).also { actual = it }
            check(reopened.settingsRepository.getServerAddress() == origin && reopened.settingsRepository.getDeviceKey() == device &&
                reopened.settingsRepository.getGatewayToken() == supplied.getString("token")) { "Owned configuration did not survive container recreation" }
            assertNotNull(reopened.database.providerPullFrontierDao().get()?.pageToken)
            observe("same_owned_database_reopened", reopened)
            val secondFailure = runCatching {
                secondForeground = io.ethan.pushgo.notifications.ProviderIngressCoordinator.pullPersistAndDrainAcks(owned,
                    reopened.channelRepository, reopened.messageRepository, reopened.entityRepository,
                    reopened.inboundDeliveryLedgerRepository, reopened.settingsRepository, reason = "normal_deep_scan_reopened_turn")
            }.exceptionOrNull()
            assertTrue("A second all-conflict turn still retains its original failure", secondFailure is io.ethan.pushgo.notifications.InboundMessageProcessor.InboundRetryableException)
            assertEquals(0, secondForeground)
            assertEquals(0, currentLedger(reopened)); assertEquals(0, currentAcked(reopened))
            assertEquals(conflicts.length(), scalar(reopened, "SELECT COUNT(*) FROM messages"))
            assertNotEquals("A failed-but-durable scan advances instead of rereading its first frontier", firstToken,
                requireNotNull(reopened.database.providerPullFrontierDao().get()?.pageToken))
            assertTrue("The two foreground turns cannot account for all healthy items; the real owner must continue",
                firstHealthy + secondForeground < healthy.length())
            observe("second_zero_accepted_turn_frontier_advanced", reopened)
            secondGate.complete(Unit) // Only the existing automatic continuation owner may now reach the healthy tail.
            kotlinx.coroutines.withTimeout(60_000L) {
                while (scalar(reopened, "SELECT COUNT(*) FROM messages") != 1_805 || currentAcked(reopened) != healthy.length() ||
                    scalar(reopened, "SELECT COUNT(*) FROM inbound_delivery_ack_outbox") != 0 || reopened.database.providerPullFrontierDao().get() != null) {
                    if (pollCount++ % 20 == 0) observe("automatic_continuation_observed", reopened)
                    kotlinx.coroutines.delay(50L)
                }
            }
            observe("all_wire_ACKs_observed_before_content_checks", reopened)
            for ((id, expected) in retained) assertEquals(expected, rowHash(reopened, id))
            var largeSeen = 0
            for (index in 0 until healthy.length()) {
                val expected = healthy.getJSONObject(index)
                val id = expected.getString("message_id")
                check(id !in retained)
                val stored = requireNotNull(reopened.messageRepository.getByMessageId(id))
                assertEquals(expected.getString("title"), stored.title)
                assertEquals(expected.getString("body_sha256"), hash(stored.body.toByteArray(Charsets.UTF_8)))
                assertEquals(expected.getString("op_id"), JSONObject(stored.rawPayloadJson).getString("op_id"))
                if (id == boundary.getString("large_message_id")) {
                    largeSeen++
                    assertEquals(49_152, expected.getInt("request_bytes"))
                    assertEquals(boundary.getString("large_body_sha256"), hash(stored.body.toByteArray(Charsets.UTF_8)))
                }
                settled.put(JSONObject().put("message_id", id).put("op_id", expected.getString("op_id")).apply {
                    if (expected.has("request_bytes")) put("request_bytes", expected.getInt("request_bytes"))
                })
            }
            assertEquals(1, largeSeen)
            assertEquals(1_805, scalar(reopened, "SELECT COUNT(*) FROM messages"))
            assertEquals(healthy.length(), currentAcked(reopened))
            assertEquals(healthy.length(), currentLedger(reopened))
            assertEquals(0, scalar(reopened, "SELECT COUNT(*) FROM inbound_delivery_ack_outbox"))
            assertNull(reopened.database.providerPullFrontierDao().get())
            observe("all_healthy_settled_foreign_content_unchanged", reopened)
            kotlinx.coroutines.delay(10_000L) // Fixed post-load observation, never a business retry/wait extension.
            assertEquals(1_805, scalar(reopened, "SELECT COUNT(*) FROM messages"))
            assertEquals(healthy.length(), currentAcked(reopened)); assertNull(reopened.database.providerPullFrontierDao().get())
            assertEquals(0, scalar(reopened, "SELECT COUNT(*) FROM inbound_delivery_ack_outbox"))
            observe("idle_after_completion_10seconds", reopened)
            passed = true
        } catch (failure: Throwable) { originalFailure = failure; throw failure }
        finally {
            val cleanup = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val notificationFailure = runCatching {
                        actual?.let { container ->
                            val ids = container.database.openHelper.readableDatabase.query(
                                "SELECT m.id FROM messages m JOIN message_resource_owners o ON o.source_table='messages' AND o.owner_id=m.id AND o.owner_slot=0 WHERE o.authenticated_gateway_url=? AND o.authenticated_device_key=?",
                                arrayOf(origin, device),
                            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                            val untaggedLegacyCollision = notificationManager.activeNotifications.any {
                                Triple(it.tag, it.id, it.postTime) in initialNotifications && it.tag == null && it.id in ids.map(String::hashCode)
                            }
                            if (!untaggedLegacyCollision) {
                                io.ethan.pushgo.notifications.NotificationHelper.cancelMessageNotifications(owned, ids)
                                notificationCleanup.put("normal_exact_owner_cancel_count", ids.size)
                            } else {
                                // Do not use the legacy hash fallback if it could name
                                // a pre-existing untagged notification. Only exact new
                                // owner+generation instances can be removed here.
                                val targets = notificationManager.activeNotifications.filter { status ->
                                    val extras = status.notification.extras
                                    extras.getString("io.ethan.pushgo.notification.own_kind") == "message" &&
                                        extras.getString("io.ethan.pushgo.notification.own_id") in ids &&
                                        !extras.getString("io.ethan.pushgo.notification.generation").isNullOrBlank() &&
                                        Triple(status.tag, status.id, status.postTime) !in initialNotifications
                                }
                                targets.forEach { notificationManager.cancel(it.tag, it.id) }
                                notificationCleanup.put("exact_new_nonce_cancel_count", targets.size).put("legacy_fallback_avoided", true)
                            }
                            notificationCleanup.put("pending_notification_rows", scalar(container, "SELECT COUNT(*) FROM messages WHERE (post_process_pending & 2)!=0"))
                            notificationCleanup.put("granted_baseline_was_not_changed", notificationPermissionBefore == androidx.core.app.NotificationManagerCompat.from(owned).areNotificationsEnabled())
                        }
                    }.exceptionOrNull()
                    val closeFailure = runCatching { closeOwner() }.exceptionOrNull()
                    if (closeFailure != null) notificationFailure?.let(closeFailure::addSuppressed)
                    val secretFailure = runCatching {
                        val secretsName = preferencePrefix + "pushgo_secure_secrets_v1"
                        check(base.getSharedPreferences(secretsName, android.content.Context.MODE_PRIVATE).edit().clear().commit()) {
                            "Owned synthetic secret namespace cleanup failed"
                        }
                    }.exceptionOrNull()
                    if (closeFailure != null) { secretFailure?.let(closeFailure::addSuppressed); throw closeFailure }
                    notificationFailure?.let { failure -> secretFailure?.let(failure::addSuppressed); throw failure }
                    secretFailure?.let { throw it }
                }
            }
            val receipt = JSONObject().put("kind", "actual-normal-Gateway-deep-scan-container-reopen-automatic-continuation")
                .put("assertions_status", if (passed && cleanup.isSuccess) "PASSED" else "FAILED")
                .put("native_execution_status", "Use fresh native status; receipt alone is not a test pass")
                .put("isolated_root", root.absolutePath).put("isolated_database", owned.getDatabasePath("pushgo.db").absolutePath)
                .put("first_foreground_healthy", firstHealthy).put("second_foreground_healthy", secondForeground)
                .put("manual_foreground_turns", 2)
                .put("minimum_healthy_requiring_automatic_owner", (healthy.length() - firstHealthy - secondForeground).coerceAtLeast(0))
                .put("expected_canonical", 1_805).put("expected_current_scope_acked", healthy.length())
                .put("poll_observations", pollCount).put("observations", observations)
                .put("boundary", boundary).put("foreign_head", badDeliveries).put("foreign_row_hashes", JSONObject(retained))
                .put("healthy_settled_for_Gateway_readonly_reconciliation", settled)
                .put("notification_permission_before", notificationPermissionBefore).put("notification_cleanup", notificationCleanup)
                .put("notification_permission_read_only", androidx.core.app.NotificationManagerCompat.from(owned).areNotificationsEnabled())
                .put("local_network_permission_read_only", io.ethan.pushgo.util.LocalNetworkPermission.isGranted(owned))
                .put("owned_preference_names_count", preferenceNames.size)
                .put("limits", "API37 Debug production AppContainer/Room and real normal Gateway over HTTP adb-reverse loopback; no QA session/transport, TLS/native Private/FCM/OS notification/process-death/comparative performance/energy/peak claim; phase CPU/PSS are discrete observations with10sfalloff;60s/50ms polling includes observer cost; 49152 request bytes are not body or wire-frame bytes")
            cleanup.exceptionOrNull()?.let { receipt.put("cleanup_failure_type", it.javaClass.name) }
            originalFailure?.let { receipt.put("original_failure_type", it.javaClass.name) }
            val outputResult = runCatching {
                val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: File(root, "additional_test_output").absolutePath).apply { mkdirs() }
                val text = receipt.toString(2)
                File(root, "normal-gateway-deep-result.json").writeText(text)
                File(output, "normal-gateway-deep-$ownerId.json").writeText(text)
            }
            val trailing = cleanup.exceptionOrNull() ?: outputResult.exceptionOrNull()
            val primary = originalFailure
            if (primary != null) trailing?.let { primary.addSuppressed(it) } else trailing?.let { throw it }
        }
    }

    @Test
    fun actualNormalGatewaySettlesOnlyDurablySavedAndroidContent() = kotlinx.coroutines.runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("pushgo.normalGatewayContextPath")
        assumeTrue("No task-owned normal Gateway supplied; this is NOT RUN", path != null)
        require(requireNotNull(path).matches(Regex("/data/local/tmp/pushgo-delivery-[A-Za-z0-9_-]+\\.json")))
        val context = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("cat $path"))
            .bufferedReader().use { JSONObject(it.readText()) }
        val scenario = ProviderV2PullOnlyDeviceTest()
        scenario.setUp()
        try {
            val receipt = scenario.verifyNormalGateway(context)
            val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: File(instrument.targetContext.externalMediaDirs.first(), "additional_test_output").absolutePath).apply { mkdirs() }
            File(output, "normal-gateway-android-${UUID.randomUUID()}.json").writeText(receipt.toString(2))
        } finally { scenario.tearDown() }
    }
}
