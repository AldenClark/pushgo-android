package io.ethan.pushgo

import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.*
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.notifications.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual PrivateChannelClient/JNI/TLS/Room/AndroidKeystore. Host coordination
 * submits to the real candidate, owns fresh-DB ACK-failure triggers and restarts,
 * and cuts an opaque TLS relay. It never manufactures Gateway protocol success.
 * Host force-stops the isolated package before coherent main+WAL exports.
 */
@RunWith(AndroidJUnit4::class)
class CandidatePrivateC7DeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val base get() = requireNotNull(args.getString("c7GatewayUrl")).trimEnd('/')
    private val controlUrl get() = requireNotNull(args.getString("c7ControlUrl")).trimEnd('/')
    private val auth get() = args.getString("c7GatewayToken")?.takeIf { it.isNotBlank() }
    private val session get() = File(context.filesDir,"c7-private-session.json")
    private lateinit var db: PushGoDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var store: ChannelSubscriptionStore
    private lateinit var messages: MessageRepository
    private lateinit var entities: EntityRepository
    private lateinit var ledger: InboundDeliveryLedgerRepository
    private lateinit var channels: ChannelSubscriptionRepository
    private lateinit var client: PrivateChannelClient
    private lateinit var coordinator: TransportSwitchCoordinator
    private var phase="open"

    @Before fun open() {
        WarpLinkNativeBridge.installTestRuntime(null)
        assertTrue("source-bound real JNI must load",WarpLinkNativeBridge.isAvailable())
        assertEquals(2,WarpLinkNativeBridge.abiVersion())
        db=PushGoDatabase.buildForTest(context,"c7_private.db")
        val protectedStore=AndroidKeystoreSecretStore(context,"c7_private_keystore")
        settings=SettingsRepository(db.appSettingsDao(),protectedStore,
            context.getSharedPreferences("c7_private_settings",Context.MODE_PRIVATE))
        store=ChannelSubscriptionStore(db.channelSubscriptionDao(),protectedStore)
        messages=MessageRepository(db,db.messageDao(),db.messageChannelStatsDao(),db.messageMetadataIndexDao(),
            db.inboundDeliveryLedgerDao(),db.operationLedgerDao(),db.thingHeadDao(),db.thingSubMessageDao(),db.pendingThingMessageDao())
        entities=EntityRepository(db,db.inboundDeliveryLedgerDao(),db.operationLedgerDao(),db.eventChangeLogDao(),
            db.thingChangeLogDao(),db.thingSubEventDao(),db.topLevelEventHeadDao(),db.thingHeadDao(),db.thingSubMessageDao())
        ledger=InboundDeliveryLedgerRepository(db,db.inboundDeliveryLedgerDao(),db.inboundDeliveryAckOutboxDao(),db.legacyProviderIngressDao())
        val gate=CoordinatorTransportRouteWriterGate({coordinator}) { if(settings.getUseFcmChannel()) "fcm" else "private" }
        channels=ChannelSubscriptionRepository(store,settings,MessageStateCoordinator(context,messages),messages,entities,db,
            object:PushTokenProvider { override suspend fun fetchToken(timeoutMs:Long)=args.getString("c7MergeProviderToken") },
            awaitRouteSynchronization={coordinator.synchronize()},transportRouteWriterGate=gate,onRemoteRoute={coordinator.observeRoute(it)})
        client=PrivateChannelClient(context,channels,ledger,messages,entities,settings,
            awaitRouteSynchronization={coordinator.synchronize()},transportRouteWriterGate=gate,onRemoteRoute={coordinator.observeRoute(it)})
        coordinator=TransportSwitchCoordinator(client,object:TransportSelectionApplier {
            override suspend fun apply(useFcm:Boolean,providerToken:String?) {
                settings.setFcmToken(providerToken.takeIf { useFcm });settings.setUseFcmChannel(useFcm)
                client.setRuntime(useFcm,providerToken.takeIf { useFcm })
            }
            override suspend fun currentUseFcm()=settings.getUseFcmChannel()
            override suspend fun providerToken()=settings.getFcmToken()
        },currentGatewayUrl={channels.loadGatewayConfig().first},currentDeviceKey={settings.getDeviceKey()})
    }
    @After fun close() = runBlocking { if(::client.isInitialized) stopLoop();if(::db.isInitialized) db.close() }

    @Test fun phase1RealPrivatePersistsBeforeRejectedServerAck() = runBlocking {
        phase="phase1";check(!session.exists());assertEquals(0,count("messages"))
        settings.setServerAddress(base);settings.setGatewayToken(auth);settings.setUseFcmChannel(false)
        coordinator.synchronize();val key=requireNotNull(settings.getDeviceKey())
        val credential=UUID.randomUUID().toString()
        val channel=client.privateCreateChannel("C7 native Private ordinary",credential)
        assertTrue(channel.subscribed)
        store.upsertSubscription(base,channel.channelId,"C7 native Private ordinary",credential,System.currentTimeMillis())
        assertEquals(credential,ChannelSubscriptionStore(db.channelSubscriptionDao(),
            AndroidKeystoreSecretStore(context,"c7_private_keystore")).passwordFor(base,channel.channelId))
        startLoop();checkpoint("connected",JSONObject().put("device_key",key))
        await("real native TCP transport selected") { client.readTransportStatus().transport=="tcp" }
        val positive=submit("positive",channel.channelId,credential,"C7 actual native Private","c7-positive-exact-body")
        awaitCanonical(positive,key);checkpoint("ack-committed",positive)
        await("local ACKED after actual server commit") { ackState(positive.getString("delivery_id"),key)=="acked" }
        // Host installs a native-engine fresh-DB trigger rejecting the actual targets
        // ACK update. Expected Gateway exit70 is an independent real process receipt.
        checkpoint("arm-server-ack-storage-failure",JSONObject().put("device_key",key))
        val uncertain=submit("ack-storage-failure",channel.channelId,credential,
            "C7 Room survives server ACK commit failure","c7-server-ack-storage-failure-exact-body")
        awaitCanonical(uncertain,key);checkpoint("server-ack-storage-failed",uncertain)
        assertEquals(2,count("messages"))
        val state=ackState(uncertain.getString("delivery_id"),key);assertTrue(state in setOf("pending","acked"))
        write(session,JSONObject().put("phase1_pid",Process.myPid()).put("gateway",base).put("device_key",key)
            .put("channel_id",channel.channelId).put("password",credential).put("positive",positive).put("uncertain",uncertain)
            .put("uncertain_local_ack_state",state).put("canonical_count",2).put("phase1_complete",true))
        receipt("phase1",JSONObject().put("device_key",key).put("canonical_count",2).put("ledger_state",state)
            .put("scope","actual Room durability and real server DB ACK failure; pure wire-lost ACK NOT RUN"))
    }

    @Test fun phase2NewProcessReplaysReconnectsUnsubscribesAndPreparesTransfer() = runBlocking {
        phase="phase2";val prior=JSONObject(session.readText());assertTrue(prior.getBoolean("phase1_complete"))
        assertNotEquals(prior.getInt("phase1_pid"),Process.myPid())
        val key=prior.getString("device_key");val uncertain=prior.getJSONObject("uncertain")
        // Storage assertions precede every network call/session start in this PID.
        assertEquals(base,settings.getServerAddress());assertEquals(key,settings.getDeviceKey())
        assertEquals(key,SettingsRepository(db.appSettingsDao(),AndroidKeystoreSecretStore(context,"c7_private_keystore"),
            context.getSharedPreferences("c7_private_settings",Context.MODE_PRIVATE)).getDeviceKey())
        assertCanonical(uncertain,key)
        assertEquals(prior.getString("uncertain_local_ack_state"),ackState(uncertain.getString("delivery_id"),key))
        assertEquals(prior.getInt("canonical_count"),count("messages"))
        startLoop();checkpoint("replay-ack-committed",uncertain)
        await("replayed local ledger ACKED") { ackState(uncertain.getString("delivery_id"),key)=="acked" }
        assertEquals(prior.getInt("canonical_count"),count("messages"))
        checkpoint("cut-owned-tcp-relay",JSONObject().put("device_key",key))
        checkpoint("reconnected",JSONObject().put("device_key",key))
        val reconnected=submit("after-reconnect",prior.getString("channel_id"),prior.getString("password"),
            "C7 actual reconnect","c7-reconnect-exact-body")
        awaitCanonical(reconnected,key);checkpoint("ack-committed",reconnected);assertEquals(3,count("messages"))
        client.privateUnsubscribeChannel(prior.getString("channel_id"));store.softDeleteSubscription(base,prior.getString("channel_id"))
        assertTrue(channels.loadSubscriptions().isEmpty())
        val excluded=submit("after-unsubscribe",prior.getString("channel_id"),prior.getString("password"),
            "C7 future fanout excluded","c7-no-private-target-after-unsubscribe")
        checkpoint("no-private-target",excluded);assertEquals(3,count("messages"))
        val transferCredential=UUID.randomUUID().toString()
        val transferChannel=client.privateCreateChannel("C7 retained Private transfer",transferCredential)
        store.upsertSubscription(base,transferChannel.channelId,"C7 retained Private transfer",transferCredential,System.currentTimeMillis())
        // Stop the real old session before acceptance; no DELIVER-hold API needed.
        stopLoop();checkpoint("old-private-offline",JSONObject().put("device_key",key))
        val transferred=submit("owner-transfer",transferChannel.channelId,transferCredential,
            "C7 retained old Private owner","c7-owner-transfer-exact-body")
        assertEquals(0,canonicalCount(transferred.getString("message_id")))
        val mergeCapability=requireNotNull(args.getString("c7MergeProviderToken"))
        coordinator.switchToFcm(mergeCapability);assertTrue(settings.getUseFcmChannel())
        checkpoint("old-route-exited-to-fcm",JSONObject().put("device_key",key).put("delivery",transferred))
        val transfer=JSONObject().put("old_owner",key).put("channel_id",transferChannel.channelId)
            .put("password",transferCredential).put("delivery",transferred).put("merge_provider_token",mergeCapability)
        write(File(context.filesDir,"c7-owner-transfer.json"),transfer)
        prior.put("phase2_pid",Process.myPid()).put("phase2_complete",true).put("canonical_count",3)
        write(session,prior);receipt("phase2",JSONObject().put("device_key",key).put("canonical_count",3)
            .put("replay_ack_state",ackState(uncertain.getString("delivery_id"),key)).put("transfer",transfer))
    }

    @Test fun phase3FreshRealOwnerCoalescesAndConsumesRetainedPrivateDelivery() = runBlocking {
        phase="phase3";check(!session.exists());assertEquals(0,count("messages"))
        val transferFile=File(context.filesDir,"c8-owner-transfer-input.json")
        val expectedSize=requireNotNull(args.getString("c8OwnerTransferBytes")).toLong()
        val expectedHash=requireNotNull(args.getString("c8OwnerTransferSha256"))
        require(expectedSize in 1L..131072L && expectedHash.matches(Regex("[0-9a-f]{64}")))
        assertEquals("exact host transfer byte count",expectedSize,transferFile.length())
        val transferRaw=transferFile.readText(Charsets.UTF_8)
        assertEquals("exact host transfer SHA-256",expectedHash,sha256(transferRaw))
        val transfer=JSONObject(transferRaw)
        val oldOwner=transfer.getString("old_owner");val expected=transfer.getJSONObject("delivery")
        val mergeCapability=transfer.getString("merge_provider_token")
        settings.setServerAddress(base);settings.setGatewayToken(auth);settings.setUseFcmChannel(true);settings.setFcmToken(mergeCapability)
        val owner=channels.syncProviderDeviceToken(mergeCapability)
        assertNotEquals(oldOwner,owner);assertEquals(owner,settings.getDeviceKey())
        val reopened=SettingsRepository(db.appSettingsDao(),AndroidKeystoreSecretStore(context,"c7_private_keystore"),
            context.getSharedPreferences("c7_private_settings",Context.MODE_PRIVATE))
        assertEquals(owner,reopened.getDeviceKey())
        checkpoint("owner-coalesced",JSONObject().put("old_owner",oldOwner).put("current_owner",owner).put("delivery",expected))
        store.upsertSubscription(base,transfer.getString("channel_id"),"C7 retained Private transfer",transfer.getString("password"),System.currentTimeMillis())
        coordinator.switchToPrivate();assertFalse(settings.getUseFcmChannel())
        startLoop();awaitCanonical(expected,owner);checkpoint("transferred-private-ack-committed",expected)
        await("current owner ledger ACKED") { ackState(expected.getString("delivery_id"),owner)=="acked" };assertEquals(1,count("messages"))
        coordinator.switchToFcm(mergeCapability);assertTrue(settings.getUseFcmChannel())
        checkpoint("winner-private-route-exited",JSONObject().put("device_key",owner).put("delivery",expected));assertEquals(1,count("messages"))
        receipt("phase3",JSONObject().put("old_owner",oldOwner).put("current_owner",owner).put("persisted_owner",reopened.getDeviceKey())
            .put("delivery",expected).put("canonical_count",1).put("ledger_state",ackState(expected.getString("delivery_id"),owner)))
    }

    @Test fun c18SmallAndLargePersistBeforeWireAckLoss() = runBlocking {
        phase="c18-phase1";check(!session.exists());assertEquals(0,count("messages"))
        settings.setServerAddress(base);settings.setGatewayToken(auth);settings.setUseFcmChannel(false)
        coordinator.synchronize();val key=requireNotNull(settings.getDeviceKey())
        val credential=UUID.randomUUID().toString()
        val channel=client.privateCreateChannel("C18 Private wire ACK loss",credential)
        assertTrue(channel.subscribed)
        store.upsertSubscription(base,channel.channelId,"C18 Private wire ACK loss",credential,System.currentTimeMillis())
        assertEquals(credential,ChannelSubscriptionStore(db.channelSubscriptionDao(),
            AndroidKeystoreSecretStore(context,"c7_private_keystore")).passwordFor(base,channel.channelId))
        startLoop();checkpoint("connected",JSONObject().put("device_key",key))
        await("real native TCP transport selected") { client.readTransportStatus().transport=="tcp" }

        val smallTitle="C18 小消息 ‘quoted’"
        val smallBody="中文 emoji 🐈，引号 \"双\" 和 \\ 反斜杠"
        val small=submitC18("c18-small",channel.channelId,credential,smallTitle,smallBody)
        awaitCanonical(small,key);checkpoint("ack-committed",small)
        await("small delivery locally ACKED after server commit") {
            ackState(small.getString("delivery_id"),key)=="acked"
        }
        assertEquals(1,count("messages"))

        val largeTitle="C18 numeric metadata 48KiB"
        val baseRequest=JSONObject().put("channel_id",channel.channelId).put("password",credential)
            .put("title",largeTitle).put("body","").put("op_id",UUID.randomUUID().toString())
            .put("metadata",JSONObject.NULL)
        val baseRaw=baseRequest.toString()
        val entries=mutableListOf<String>()
        fun rawWithMetadata()=baseRaw.replace("\"metadata\":null","\"metadata\":{${entries.joinToString(",")}}")
        while(true) {
            entries.add("\"${entries.size.toString(16)}\":1e15")
            if(rawWithMetadata().toByteArray(Charsets.UTF_8).size>49152) { entries.removeAt(entries.lastIndex);break }
        }
        val unpadded=rawWithMetadata()
        val largeBody="x".repeat(49152-unpadded.toByteArray(Charsets.UTF_8).size)
        val largeRaw=unpadded.replace("\"body\":\"\"","\"body\":\"$largeBody\"")
        assertEquals("complete HTTP JSON is exactly the accepted UTF-8 ceiling",49152,
            largeRaw.toByteArray(Charsets.UTF_8).size)
        assertTrue("metadata has thousands of independent values",entries.size>3500)
        checkpoint("arm-wire-ack-loss",JSONObject().put("device_key",key)
            .put("channel_id",channel.channelId).put("password",credential))
        val large=submitC18("c18-large-numeric-metadata",channel.channelId,credential,largeTitle,largeBody,largeRaw)
        assertEquals("host preserved the complete raw request hash",sha256(largeRaw),large.getString("request_sha256"))
        assertEquals(49152,large.getInt("request_utf8_bytes"))
        awaitCanonical(large,key)
        val metadataDigest=assertC18StoredMetadata(large,entries.size)
        assertEquals(2,count("messages"))
        // This checkpoint is host evidence that the actual client ACK frame was dropped.
        // The server target remains unacknowledged; local ledger state is not server commit proof.
        checkpoint("wire-ack-dropped",JSONObject().put("device_key",key).put("delivery",large)
            .put("metadata_keys",entries.size).put("metadata_semantic_sha256",metadataDigest))
        assertTrue(ackState(large.getString("delivery_id"),key) in setOf("pending","acked"))
        stopLoop();checkpoint("old-private-offline",JSONObject().put("device_key",key))
        assertEquals(2,count("messages"))
        write(session,JSONObject().put("phase1_pid",Process.myPid()).put("gateway",base).put("device_key",key)
            .put("channel_id",channel.channelId).put("password",credential).put("small",small).put("large",large)
            .put("metadata_keys",entries.size).put("metadata_semantic_sha256",metadataDigest)
            .put("canonical_count",2).put("phase1_complete",true))
        receipt("c18-phase1",JSONObject().put("device_key",key).put("canonical_count",2)
            .put("small",small).put("large",large).put("metadata_keys",entries.size)
            .put("metadata_semantic_sha256",metadataDigest)
            .put("scope","Room persistence before real client ACK-frame loss; server target remains unacknowledged"))
    }

    @Test fun c18NewProcessDeduplicatesRedeliveryAndReconnects() = runBlocking {
        phase="c18-phase2";val prior=JSONObject(session.readText());assertTrue(prior.getBoolean("phase1_complete"))
        assertNotEquals("phase two must be another Android process",prior.getInt("phase1_pid"),Process.myPid())
        val key=prior.getString("device_key");val small=prior.getJSONObject("small");val large=prior.getJSONObject("large")
        assertEquals(base,settings.getServerAddress());assertEquals(key,settings.getDeviceKey())
        assertEquals(key,SettingsRepository(db.appSettingsDao(),AndroidKeystoreSecretStore(context,"c7_private_keystore"),
            context.getSharedPreferences("c7_private_settings",Context.MODE_PRIVATE)).getDeviceKey())
        assertEquals(prior.getString("password"),ChannelSubscriptionStore(db.channelSubscriptionDao(),
            AndroidKeystoreSecretStore(context,"c7_private_keystore")).passwordFor(base,prior.getString("channel_id")))
        assertCanonical(small,key);assertCanonical(large,key)
        assertEquals(prior.getInt("canonical_count"),count("messages"))
        val entries=prior.getInt("metadata_keys")
        assertEquals(prior.getString("metadata_semantic_sha256"),assertC18StoredMetadata(large,entries))

        startLoop();checkpoint("replay-ack-committed",JSONObject().put("device_key",key).put("delivery",large))
        await("replayed large delivery locally ACKED after server commit") {
            ackState(large.getString("delivery_id"),key)=="acked"
        }
        assertEquals(prior.getInt("canonical_count"),count("messages"))
        assertCanonical(small,key);assertCanonical(large,key)
        checkpoint("cut-owned-tcp-relay",JSONObject().put("device_key",key))
        checkpoint("reconnected",JSONObject().put("device_key",key))

        val third=submitC18("c18-after-reconnect",prior.getString("channel_id"),prior.getString("password"),
            "C18 after reconnect","第三条小消息 🐈")
        awaitCanonical(third,key);checkpoint("ack-committed",third)
        await("reconnected delivery locally ACKED after server commit") {
            ackState(third.getString("delivery_id"),key)=="acked"
        }
        assertEquals(3,count("messages"))
        assertEquals("acked",ackState(small.getString("delivery_id"),key))
        assertEquals("acked",ackState(large.getString("delivery_id"),key))

        val rejected=checkpoint("reject-overlimit",JSONObject().put("channel_id",prior.getString("channel_id"))
            .put("password",prior.getString("password")))
        assertEquals(400,rejected.getInt("status"))
        assertEquals("invalid_request_body",rejected.getString("error_code"))
        assertFalse("oversized request is rejected before identity",rejected.getBoolean("identity_established"))
        assertEquals(0,rejected.getInt("target_count"))
        assertEquals(3,count("messages"))
        receipt("c18-phase2",JSONObject().put("device_key",key).put("canonical_count",3)
            .put("small",small).put("large",large).put("third",third).put("metadata_keys",entries)
            .put("metadata_semantic_sha256",prior.getString("metadata_semantic_sha256"))
            .put("ledger_small",ackState(small.getString("delivery_id"),key))
            .put("ledger_large",ackState(large.getString("delivery_id"),key))
            .put("ledger_third",ackState(third.getString("delivery_id"),key))
            .put("overlimit_status",rejected.getInt("status")).put("overlimit_identity_established",false)
            .put("overlimit_target_count",0))
    }

    @Test fun numericMetadataLegalHttp48kPersistsEverySemanticValueBeforeAck() = runBlocking {
        phase="numeric-metadata";check(!session.exists());assertEquals(0,count("messages"))
        settings.setServerAddress(base);settings.setGatewayToken(auth);settings.setUseFcmChannel(false)
        coordinator.synchronize();val owner=requireNotNull(settings.getDeviceKey())
        val credential=UUID.randomUUID().toString()
        val channel=client.privateCreateChannel("C7 numeric metadata",credential)
        store.upsertSubscription(base,channel.channelId,"C7 numeric metadata",credential,System.currentTimeMillis())
        startLoop();checkpoint("connected",JSONObject().put("device_key",owner))
        await("real upgraded native TCP") { client.readTransportStatus().transport=="tcp" }
        val request=JSONObject().put("channel_id",channel.channelId).put("password",credential)
            .put("title","x").put("body","").put("op_id",UUID.randomUUID().toString()).put("metadata",JSONObject.NULL)
        val rawBase=request.toString();val entries=mutableListOf<String>()
        fun rawWithMetadata()=rawBase.replace("\"metadata\":null","\"metadata\":{${entries.joinToString(",")}}")
        while(true) {
            entries.add("\"${entries.size.toString(16)}\":1e15")
            if(rawWithMetadata().toByteArray(Charsets.UTF_8).size>49152) { entries.removeAt(entries.lastIndex);break }
        }
        val unpadded=rawWithMetadata()
        val raw=unpadded.replace("\"body\":\"\"","\"body\":\"${"x".repeat(49152-unpadded.toByteArray(Charsets.UTF_8).size)}\"")
        assertEquals(49152,raw.toByteArray(Charsets.UTF_8).size);assertTrue(entries.size>3500)
        val source=JSONObject(raw)
        val expected=checkpoint("submit",JSONObject().put("label","numeric-metadata").put("channel_id",channel.channelId)
            .put("password",credential).put("title","x").put("body",source.getString("body"))
            .put("raw_request",raw).put("request_sha256",sha256(raw)).put("metadata_keys",entries.size))
        assertEquals(sha256(raw),expected.getString("request_sha256"))
        awaitCanonical(expected,owner)
        fun assertStoredMetadata():String {
            val payload=db.openHelper.readableDatabase.query("SELECT raw_payload_json FROM messages WHERE message_id=?",
                arrayOf(expected.getString("message_id"))).use { assertTrue(it.moveToFirst());it.getString(0) }
            val value=JSONObject(payload).get("metadata")
            val actual=if(value is JSONObject)value else JSONObject(value.toString())
            assertEquals(entries.size,actual.length())
            for(index in entries.indices) {
                val key=index.toString(16);assertTrue("every metadata key survives",actual.has(key))
                assertEquals("semantic numeric value for $key",0,java.math.BigDecimal(actual.get(key).toString()).compareTo(java.math.BigDecimal("1e15")))
            }
            return sha256((0 until entries.size).joinToString("\n") { "${it.toString(16)}=1000000000000000" })
        }
        val metadataDigest=assertStoredMetadata()
        checkpoint("ack-committed",expected)
        await("numeric metadata durable ACKED") { ackState(expected.getString("delivery_id"),owner)=="acked" }
        assertEquals(metadataDigest,assertStoredMetadata());assertEquals(1,count("messages"))
        receipt("numeric-metadata",JSONObject().put("metadata_keys",entries.size).put("semantic_sha256",metadataDigest)
            .put("request_sha256",sha256(raw)).put("request_utf8_bytes",49152).put("delivery",expected)
            .put("ledger_state",ackState(expected.getString("delivery_id"),owner)))
    }

    @Test fun tlsRejectsWrongCaHostOrLeafPinBeforePrivateDelivery() = runBlocking {
        phase="tls-negative";assertEquals(0,count("messages"))
        val kind=requireNotNull(args.getString("c7TlsFailureKind"));assertTrue(kind in setOf("wrong_ca","wrong_host","wrong_pin"))
        settings.setServerAddress(base);settings.setGatewayToken(auth);settings.setUseFcmChannel(false)
        val transitions=java.util.Collections.synchronizedList(mutableListOf<PrivateChannelClient.TransportStatus>())
        val observed=java.util.concurrent.atomic.AtomicReference<String?>(null)
        // Observe the existing production flow synchronously before real session start.
        // A Fatal TLS detail is immediately followed by Reconnecting/backoff; polling
        // only the latest state can miss it. No fake runtime or native event injection.
        val watcher=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {
            client.transportStatusFlow.collect { status ->
                if(transitions.size<128) transitions.add(status)
                val detail=status.detail.orEmpty();val value=detail.lowercase()
                val matches=when(kind) {
                    "wrong_ca" -> value.contains("unknownissuer") || value.contains("unknown issuer")
                    "wrong_host" -> value.contains("notvalidforname") || value.contains("not valid for name") || value.contains("name mismatch")
                    else -> value.contains("certificate pin mismatch")
                }
                if(matches) observed.compareAndSet(null,detail)
            }
        }
        try {
            startLoop()
            await("actual native TLS rejection") { observed.get()!=null }
            val detail=requireNotNull(observed.get())
            checkpoint("tls-rejected",JSONObject().put("kind",kind).put("native_detail",detail))
            assertEquals(0,count("messages"));assertEquals(0,count("inbound_delivery_ledger"))
            receipt("tls-$kind",JSONObject().put("kind",kind).put("native_detail",detail).put("canonical_count",0))
        } finally {
            watcher.cancelAndJoin()
            val snapshot=synchronized(transitions) { transitions.toList() }
            val sequence=org.json.JSONArray()
            snapshot.forEach { status -> sequence.put(JSONObject().put("stage",status.stage).put("transport",status.transport)
                .put("detail",status.detail).put("updated_at_ms",status.updatedAtMs)) }
            receipt("tls-observer-$kind",JSONObject().put("kind",kind).put("transitions",sequence)
                .put("specific_native_detail_observed",observed.get()!=null).put("deadline_ms",20_000))
        }
    }
    private fun startLoop() { client.setRuntime(false,null);client.setForeground(true) }
    private suspend fun stopLoop() {
        client.setForeground(false);client.setRuntime(true,null)
        // Teardown bookkeeping only, never a product outcome or injected event.
        val field=PrivateChannelClient::class.java.getDeclaredField("scope");field.isAccessible=true
        val ownedScope=field.get(client) as CoroutineScope
        await("owned client cleanup jobs finish") { ownedScope.coroutineContext[Job]?.children?.none { it.isActive } != false }
    }
    private suspend fun await(label:String,check:suspend ()->Boolean) {
        withTimeout(20_000) { while(!check()) delay(25) }
    }
    private suspend fun awaitCanonical(expected:JSONObject,owner:String) {
        await("exact canonical ${expected.getString("delivery_id")}") { canonicalCount(expected.getString("message_id"))==1 };assertCanonical(expected,owner)
    }
    private fun assertCanonical(expected:JSONObject,owner:String) {
        db.openHelper.readableDatabase.query("SELECT title,body FROM messages WHERE message_id=?",arrayOf(expected.getString("message_id"))).use {
            assertTrue(it.moveToFirst());assertEquals(expected.getString("title"),it.getString(0))
            val actual=it.getString(1);val wanted=expected.getString("body")
            assertEquals("complete UTF-8 byte count in Room",wanted.toByteArray(Charsets.UTF_8).size,actual.toByteArray(Charsets.UTF_8).size)
            assertEquals("full body digest including the tail",sha256(wanted),sha256(actual))
            assertEquals(wanted,actual);assertFalse(it.moveToNext())
        }
        assertTrue(ackState(expected.getString("delivery_id"),owner) in setOf("pending","acked"))
    }
    private fun canonicalCount(id:String)=db.openHelper.readableDatabase.query("SELECT count(*) FROM messages WHERE message_id=?",arrayOf(id)).use { it.moveToFirst();it.getInt(0) }
    private fun count(table:String)=db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst();it.getInt(0) }
    private fun ackState(id:String,owner:String):String?=db.openHelper.readableDatabase.query(
        "SELECT ack_state FROM inbound_delivery_ledger WHERE gateway_url=? AND device_key=? AND delivery_id=?",arrayOf(base,owner,id)
    ).use { if(it.moveToFirst())it.getString(0) else null }
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun submitC18(label:String,channel:String,credential:String,title:String,body:String,raw:String?=null):JSONObject {
        val entity=raw ?: JSONObject().put("channel_id",channel).put("password",credential)
            .put("title",title).put("body",body).put("op_id",UUID.randomUUID().toString()).toString()
        val bytes=entity.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..49152)
        val parsed=JSONObject(entity)
        assertEquals(channel,parsed.getString("channel_id"));assertEquals(credential,parsed.getString("password"))
        assertEquals(title,parsed.getString("title"));assertEquals(body,parsed.getString("body"))
        val expected=checkpoint("submit",JSONObject().put("label",label).put("channel_id",channel)
            .put("password",credential).put("title",title).put("body",body)
            .put("raw_request",entity).put("request_sha256",sha256(entity)))
        assertEquals("host preserved the submitted title",title,expected.getString("title"))
        assertEquals("host preserved the submitted body",body,expected.getString("body"))
        assertEquals("host preserved the complete request byte count",bytes.size,expected.getInt("request_utf8_bytes"))
        assertEquals("host preserved the complete request hash",sha256(entity),expected.getString("request_sha256"))
        assertTrue("host returned a canonical message identity",expected.getString("message_id").isNotBlank())
        assertTrue("host returned a delivery identity",expected.getString("delivery_id").isNotBlank())
        return expected
    }
    private fun assertC18StoredMetadata(expected:JSONObject,keyCount:Int):String {
        val payload=db.openHelper.readableDatabase.query("SELECT raw_payload_json FROM messages WHERE message_id=?",
            arrayOf(expected.getString("message_id"))).use { assertTrue(it.moveToFirst());it.getString(0) }
        val encoded=JSONObject(payload)
        assertEquals(expected.getString("title"),encoded.getString("title"))
        assertEquals(expected.getString("body"),encoded.getString("body"))
        val value=encoded.get("metadata")
        val actual=if(value is JSONObject)value else JSONObject(value.toString())
        assertEquals("all Room metadata entries persisted",keyCount,actual.length())
        for(index in 0 until keyCount) {
            val key=index.toString(16);assertTrue("Room metadata contains $key",actual.has(key))
            assertEquals("semantic metadata value for $key",0,
                java.math.BigDecimal(actual.get(key).toString()).compareTo(java.math.BigDecimal("1e15")))
        }
        return sha256((0 until keyCount).joinToString("\n") { "${it.toString(16)}=1000000000000000" })
    }
    private fun submit(label:String,channel:String,credential:String,title:String,body:String):JSONObject {
        val head="C7 complete HTTP $label $body\n";val tail="\nC7-END-$label-完整正文"
        val request=JSONObject().put("channel_id",channel).put("password",credential)
            .put("title",title).put("body",head+tail).put("op_id",UUID.randomUUID().toString())
        // Measure the ENTIRE HTTP JSON entity, including JSON escaping, fields,
        // generated op_id and multibyte tail. ASCII padding cannot add escapes.
        val padding=49152-request.toString().toByteArray(Charsets.UTF_8).size
        assertTrue("request metadata leaves room for a real body",padding>0)
        val complete=head+"0123456789abcdef".repeat((padding+15)/16).take(padding)+tail
        request.put("body",complete)
        val raw=request.toString()
        assertEquals("legal HTTP request cap, measured as UTF-8",49152,raw.toByteArray(Charsets.UTF_8).size)
        val expected=checkpoint("submit",JSONObject().put("label",label).put("channel_id",channel)
            .put("password",credential).put("title",title).put("body",complete)
            .put("raw_request",raw).put("request_sha256",sha256(raw)))
        assertEquals("independent submitted-body oracle",sha256(complete),sha256(expected.getString("body")))
        assertEquals("raw request preserved by host",sha256(raw),expected.getString("request_sha256"))
        assertEquals(49152,expected.getInt("request_utf8_bytes"))
        return expected
    }
    private fun checkpoint(action:String,data:JSONObject):JSONObject {
        val request=JSONObject().put("action",action).put("phase",phase).put("pid",Process.myPid()).put("data",data)
        val connection=(URL("$controlUrl/checkpoint").openConnection() as HttpURLConnection).apply {
            requestMethod="POST";connectTimeout=5_000;readTimeout=20_000;doOutput=true;setRequestProperty("Content-Type","application/json")
        }
        return try {
            connection.outputStream.use { it.write(request.toString().toByteArray()) }
            val status=connection.responseCode;val raw=(if(status>=400)connection.errorStream else connection.inputStream).bufferedReader().use { it.readText() }
            assertEquals("host coordination failed: $raw",200,status)
            JSONObject(raw).also { assertTrue("facts were not verified",it.getBoolean("verified")) }.getJSONObject("data")
        } finally { connection.disconnect() }
    }
    private fun receipt(name:String,value:JSONObject) { write(File(context.filesDir,"c7-$name.json"),value.put("pid",Process.myPid())) }
    private fun write(file:File,value:JSONObject) { file.outputStream().use { it.write(value.toString().toByteArray());it.fd.sync() } }
}
