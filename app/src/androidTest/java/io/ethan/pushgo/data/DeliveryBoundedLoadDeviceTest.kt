package io.ethan.pushgo.data

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.data.model.PushMessage
import io.ethan.pushgo.notifications.InboundPersistenceCoordinator
import io.ethan.pushgo.notifications.InboundPersistenceRequest
import java.time.Instant
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Predeclared paired-module plan: two same-process load/falloff cycles per arm.
 * No notification/network; ACK disposition is not wire ACK and canonical body is not an HTTP boundary. */
@RunWith(AndroidJUnit4::class)
class DeliveryBoundedLoadDeviceTest {
    @Test
    fun fixedSameProcessLoadAndFalloff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "bounded-delivery-load-synthetic.db"
        context.deleteDatabase(name)
        var db = PushGoDatabase.buildForTest(context, name)
        fun repositories(database: PushGoDatabase) = Components(context, database)
        var components = repositories(db)
        val identity = requireNotNull(ProviderAckIdentity.create(ProviderAckDestination("https://bounded-load.invalid", "synthetic-device"), ProviderAckContract.V2_BATCH, "provider_pull"))
        // Current authenticated Room39 input for both compared candidates. Warmup has its own DB;
        // it cannot contribute canonical rows, ACK markers or latency samples.
        repeat(64) { i ->
            val raw=JSONObject().put("entity_type","message").put("entity_id","warm-$i")
                .put("message_id","warm-$i").put("delivery_id","warm-$i").put("op_id","warm-op-$i").toString()
            val m=PushMessage("warm-$i","warm-$i","Warm", "x".repeat(1024),"synthetic",null,false,
                Instant.ofEpochMilli(1_800_000_000_000L+i),raw,MessageStatus.NORMAL,null,null,"synthetic")
            assertTrue(InboundPersistenceCoordinator.persistAndNotify(context,components.messages,components.entities,
                components.ledger,components.settings,InboundPersistenceRequest.Message(m,null,null,false,identity),
                deliveryScope=identity.inboundDeliveryScope()).shouldAck)
        }
        db.close(); context.deleteDatabase(name); db=PushGoDatabase.buildForTest(context,name); components=repositories(db)
        val samples = JSONArray(); val observations = JSONArray()
        val beginning = SystemClock.elapsedRealtimeNanos()
        var offered=0;var accepted=0;var safe=0;var replayed=0;var completed=0;var failed=0;var failure:Throwable?=null
        fun observation(stage:String) {
            val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
            val page=android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            val rssPages=File("/proc/self/statm").readText().trim().split(Regex("\\s+"))[1].toLong()
            observations.put(JSONObject().put("stage",stage).put("elapsed_ms",(SystemClock.elapsedRealtimeNanos()-beginning)/1_000_000.0)
                .put("cpu_ms",android.os.Process.getElapsedCpuTime()).put("pss_kib",info.totalPss).put("rss_bytes",rssPages*page)
                .put("private_dirty_kib",info.totalPrivateDirty).put("java_heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                .put("jvm_threads_not_async_tasks",Thread.getAllStackTraces().size))
            check(info.totalPss<512*1024){"fixed safety ceiling512MiB sampledPSS; not a product SLA"}
        }
        suspend fun offer(id:Int,stage:String,scheduled:Long,replay:Boolean=false,pendingAck:Boolean=false) {
            val operation="load-$id"
            val text=if(id%600 in 100..103) "x".repeat(40*1024) else ("body-$id-".repeat(200)).take(1024)
            val raw=JSONObject().put("entity_type","message").put("entity_id",operation).put("message_id",operation)
                .put("delivery_id",operation).put("op_id","op-$operation").put("body",text).toString()
            val message=PushMessage(operation,operation,"Title $id",text,"synthetic",null,false,
                Instant.ofEpochMilli(1_800_000_000_000L+id),raw,MessageStatus.NORMAL,null,null,"synthetic-server")
            val started=SystemClock.elapsedRealtimeNanos();var committed:Long?=null;var durableEligible=false;offered++
            try {
            val outcome=InboundPersistenceCoordinator.persistAndNotify(context,components.messages,components.entities,
                components.ledger,components.settings,InboundPersistenceRequest.Message(message,null,null,false,identity),
                deliveryScope=identity.inboundDeliveryScope(),
                beforeMessageNotify={_,_->committed=SystemClock.elapsedRealtimeNanos()})
            val ended=SystemClock.elapsedRealtimeNanos()
            assertTrue("durable acceptance must produce safe ACK eligibility",outcome.shouldAck)
            assertEquals(text,components.messages.getById(operation)?.body)
            durableEligible=true;safe++;if(replay)replayed++ else accepted++
            // The ACK-disposition endpoint remains 'ended'. The optional outbox stage
            // is separate, but one invocation gets exactly one terminal sample record.
            if(pendingAck)components.ledger.enqueueAcks(listOf(operation),identity)
            val outboxEnded=SystemClock.elapsedRealtimeNanos()
            completed++
            samples.put(JSONObject().put("id",operation).put("stage",stage).put("replay",replay)
                .put("status","PASS").put("durable_ack_eligibility_observed",true)
                .put("outbox_stage_ms",if(pendingAck)(outboxEnded-ended)/1_000_000.0 else 0.0)
                .put("producer_queue_ms",(started-scheduled)/1_000_000.0)
                .put("ingress_to_ack_disposition_ms",(ended-started)/1_000_000.0)
                .put("scheduled_offer_to_ack_disposition_ms",(ended-scheduled)/1_000_000.0)
                .put("ingress_to_postcommit_upper_bound_ms",committed?.let{(it-started)/1_000_000.0}?:JSONObject.NULL))
            } catch(error:Throwable) {
                failed++
                samples.put(JSONObject().put("id",operation).put("stage",stage).put("status","FAIL")
                    .put("error_type",error.javaClass.simpleName).put("replay",replay).put("durable_ack_eligibility_observed",durableEligible)
                    .put("ingress_to_failure_ms",(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0))
                throw error
            }
        }
        suspend fun phase(base:Int,count:Int,stage:String,intervalMs:Long,pendingAck:Boolean=false) {
            val start=SystemClock.elapsedRealtimeNanos()
            repeat(count) { i ->
                val scheduled=start+i*intervalMs*1_000_000
                val left=scheduled-SystemClock.elapsedRealtimeNanos();if(left>0)delay((left+999_999)/1_000_000)
                offer(base+i,stage,scheduled,pendingAck=pendingAck)
            }
            observation("after-$stage")
        }
        try {
            observation("warmed-empty")
            repeat(2) { cycle ->
                val base=cycle*600
                delay(15_000);observation("cycle$cycle-idle15s")
                phase(base,50,"cycle$cycle-low10-per-s",100)
                phase(base+50,200,"cycle$cycle-burst",0)
                phase(base+250,250,"cycle$cycle-sustain50-per-s",20)
                phase(base+500,100,"cycle$cycle-unavailable-ack",0,pendingAck=true)
                assertEquals(100,components.ledger.loadPendingAcks().size)
                db.close();db=PushGoDatabase.buildForTest(context,name);components=repositories(db)
                assertEquals(100,components.ledger.loadPendingAcks().size)
                val replayStart=SystemClock.elapsedRealtimeNanos()
                repeat(100){offer(base+500+it,"cycle$cycle-reopen-replay",replayStart,replay=true)}
                components.ledger.markAckRecordsAcked(components.ledger.loadPendingAcks())
                assertTrue(components.ledger.loadPendingAcks().isEmpty())
                assertEquals(((cycle+1)*600).toLong(),components.messages.totalCount().toLong())
                observation("cycle$cycle-after-recovery")
                delay(30_000);observation("cycle$cycle-falloff30s")
            }
            assertEquals(1400,offered);assertEquals(1200,accepted);assertEquals(200,replayed);assertEquals(1400,safe);assertEquals(1400,completed);assertEquals(offered,samples.length())
        } catch(error:Throwable) { failure=error;throw error }
        finally {
            val record=JSONObject().put("test","paired-module-plan-v3-authenticated").put("label",InstrumentationRegistry.getArguments().getString("deliveryPairLabel"))
                .put("warmup_excluded",64).put("offered",offered).put("new_canonical",accepted).put("replayed",replayed)
                .put("ack_safe_dispositions_not_wire_ack",safe).put("completed_samples",completed).put("failed",failed).put("unfinished",1400-completed).put("not_started",1400-offered).put("error_type",failure?.javaClass?.simpleName)
                .put("canonical_rows",runCatching{components.messages.totalCount().toLong()}.getOrNull()).put("pending_ack_after",runCatching{components.ledger.loadPendingAcks().size}.getOrNull()).put("samples",samples).put("observations",observations)
                .put("limits","Debug API37 ARM64 emulator production modules; no network or wire ACK; two cycles/30s falloff not6h maintenance; phasePSS not exact allocation peak; no physical energy/release throughput claim")
            val output=File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?:File(context.externalMediaDirs.first(),"additional_test_output").absolutePath).apply{mkdirs()}
            File(output,"delivery-bounded-load-${java.util.UUID.randomUUID()}.json").writeText(record.toString(2))
            db.close()
            if(failure==null)context.deleteDatabase(name) // failed synthetic source remains available for reconciliation
        }
    }

    private class Components(context: Context, db: PushGoDatabase) {
        val messages = MessageRepository(db, db.messageDao(), db.messageChannelStatsDao(), db.messageMetadataIndexDao(),
            db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.thingHeadDao(), db.thingSubMessageDao(), db.pendingThingMessageDao())
        val entities = EntityRepository(db, db.inboundDeliveryLedgerDao(), db.operationLedgerDao(), db.eventChangeLogDao(),
            db.thingChangeLogDao(), db.thingSubEventDao(), db.topLevelEventHeadDao(), db.thingHeadDao(), db.thingSubMessageDao())
        val ledger = InboundDeliveryLedgerRepository(db, db.inboundDeliveryLedgerDao(), db.inboundDeliveryAckOutboxDao(), db.legacyProviderIngressDao())
        val settings = SettingsRepository(db.appSettingsDao(), AndroidKeystoreSecretStore(context, "bounded-load-synthetic-secrets"),
            context.getSharedPreferences("bounded-load-synthetic-settings", Context.MODE_PRIVATE))
    }
}
