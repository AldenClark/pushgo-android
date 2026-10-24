package io.ethan.pushgo.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only OS/default-channel diagnosis. Does not post a notification, create/change a
 * channel, request/grant permissions or change sound/settings. Only app-cache PCM is written. */
@RunWith(AndroidJUnit4::class)
class AlarmSourceBoundaryDeviceTest {
    @Test
    fun compareOriginalDefaultAliasActualUriAndKnownGoodPcmThroughTheSamePlayerSteps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = manager.notificationChannels.firstOrNull { it.id.endsWith("_critical") }
        val original = if (channel == null) Settings.System.DEFAULT_NOTIFICATION_URI else channel.sound
        val resolved = resolveAlertPlaybackSoundUri(context, original)
        val sources = mutableListOf<Pair<String, android.net.Uri>>()
        if (original != null) sources += "channel-or-default" to original
        if (original != null && RingtoneManager.isDefault(original)) {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.getDefaultType(original))?.let {
                sources += "actual-default" to it
            }
        }
        val root = File(context.cacheDir, "alarm-source-${UUID.randomUUID()}").apply { mkdirs() }
        val good = File(root, "known-good.wav").apply { writeBytes(wav()) }
        sources += "known-good-pcm" to android.net.Uri.fromFile(good)
        if (resolved != null) sources += "product-resolved" to resolved
        val results = JSONArray()
        try {
            for ((label, uri) in sources) {
                val item = JSONObject().put("source", label).put("scheme", uri.scheme)
                    .put("authority", uri.authority).put("uri_sha256", sha(uri.toString().toByteArray()))
                val allowed = uri.scheme == "file" || uri.authority in setOf("settings", "media")
                if (!allowed) { item.put("status", "NOT RUN custom source outside the synthetic/default scope"); results.put(item); continue }
                var bytes: ByteArray? = null
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        item.put("start_offset", afd.startOffset).put("declared_length", afd.declaredLength)
                            .put("stat_size", afd.parcelFileDescriptor.statSize)
                            .put("fstat_size", android.system.Os.fstat(afd.fileDescriptor).st_size)
                            .put("fstat_mode", android.system.Os.fstat(afd.fileDescriptor).st_mode)
                            .put("seek_position", runCatching { android.system.Os.lseek(afd.fileDescriptor, 0, android.system.OsConstants.SEEK_CUR) }.getOrNull())
                        bytes = afd.createInputStream().use { input ->
                            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                check(output.size()+count <= 2*1024*1024) { "bounded default-sound read exceeded2MiB" }
                                output.write(buffer, 0, count)
                            }; output.toByteArray()
                        }
                        item.put("read_bytes", bytes!!.size).put("sha256", sha(bytes!!))
                    } ?: item.put("descriptor", "null")
                } catch (error: Exception) { item.put("read_error", error.javaClass.simpleName+":"+error.message.orEmpty().take(100)) }
                item.put("context_uri_player", play(context, uri))
                if (bytes != null && bytes!!.isNotEmpty()) {
                    val copy = File(root, "copy-$label").apply { writeBytes(bytes!!) }
                    item.put("same_bytes_file_player", play(context, android.net.Uri.fromFile(copy)))
                }
                results.put(item)
            }
            val output = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: File(context.externalMediaDirs.first(), "additional_test_output").absolutePath).apply { mkdirs() }
            File(output, "alarm-source-${UUID.randomUUID()}.json").writeText(JSONObject()
                .put("sdk", android.os.Build.VERSION.SDK_INT).put("channel_existed", channel != null)
                .put("channel_sound_null", channel != null && channel.sound == null).put("results", results)
                .put("limits", "Direct same-API MediaPlayer playback, not physical audibility or notification-service delivery; permissions/channel/settings untouched").toString(2))
            val known = (0 until results.length()).map { results.getJSONObject(it) }.single { it.getString("source") == "known-good-pcm" }
            assertTrue("Known-good PCM must prepare/start for this diagnostic to be meaningful", known.getJSONObject("context_uri_player").getBoolean("playing"))
            org.junit.Assume.assumeTrue("Existing channel is silent; its sound is not replaced", original != null)
            // The original alias remains an explicit negative control if it is broken.
            // Success is required from the exact resolver now used by the production Service.
            val product = (0 until results.length()).map { results.getJSONObject(it) }.single { it.getString("source") == "product-resolved" }
            assertTrue("Production-resolved source must prepare/start", product.getJSONObject("context_uri_player").getBoolean("playing"))
            assertTrue(product.getJSONObject("context_uri_player").getBoolean("uid_alarm_playback_started"))
            org.junit.Assert.assertFalse(product.getJSONObject("context_uri_player").getBoolean("uid_alarm_after_release"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun resolverPreservesSilentCustomOlderAndInaccessibleChoices() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assert.assertNull(resolveAlertPlaybackSoundUri(context,null,37))
        val custom=android.net.Uri.parse("content://synthetic.invalid/private-choice")
        org.junit.Assert.assertEquals(custom,resolveAlertPlaybackSoundUri(context,custom,37))
        val alias=Settings.System.DEFAULT_NOTIFICATION_URI
        org.junit.Assert.assertEquals(alias,resolveAlertPlaybackSoundUri(context,alias,28))
        val denied=object:android.content.ContextWrapper(context) {
            override fun getContentResolver():android.content.ContentResolver = throw SecurityException("synthetic unavailable source")
        }
        org.junit.Assert.assertEquals(alias,resolveAlertPlaybackSoundUri(denied,alias,37))
    }

    private suspend fun play(context: Context, uri: android.net.Uri): JSONObject {
        val observation = JSONObject(); val player = MediaPlayer()
        val started = SystemClock.elapsedRealtime()
        try {
            // Exact production sequence; the distinct source is the only variable.
            player.reset(); player.setDataSource(context, uri)
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            player.isLooping=true; player.prepare(); player.start()
            val deadline = SystemClock.elapsedRealtime()+1200
            while (player.isPlaying && player.currentPosition<50 && SystemClock.elapsedRealtime()<deadline) delay(20)
            observation.put("playing", player.isPlaying).put("position_ms", player.currentPosition).put("duration_ms", player.duration)
                .put("uid_alarm_playback_started", ownAlarmStarted())
        } catch (error: Exception) { observation.put("playing", false).put("error", error.javaClass.simpleName+":"+error.message.orEmpty().take(180)) }
        finally { runCatching { player.stop() }; player.reset(); player.release(); observation.put("elapsed_ms", SystemClock.elapsedRealtime()-started).put("uid_alarm_after_release", ownAlarmStarted()) }
        return observation
    }
    private fun ownAlarmStarted(): Boolean {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys audio")
        val uid = android.os.Process.myUid()
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().useLines { lines ->
            lines.any { it.contains("AudioPlaybackConfiguration") && it.contains("u/pid:$uid/") &&
                it.contains("state:started") && it.contains("usage=USAGE_ALARM") }
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun wav(): ByteArray {
        val rate=16000; val frames=rate; val bytes=ByteBuffer.allocate(44+frames*2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36+frames*2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate*2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames*2)
        repeat(frames) { bytes.putShort((sin(it*2*Math.PI*440/rate)*6000).toInt().toShort()) }
        return bytes.array()
    }
}
