package io.ethan.pushgo.ui.ringtone

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.core.net.toUri
import io.ethan.pushgo.notifications.BuiltInRingtone

class RingtonePreviewPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentId: String? = null

    fun toggle(ringtone: BuiltInRingtone) {
        if (currentId == ringtone.id) {
            stop()
        } else {
            play(ringtone)
        }
    }

    fun stop() {
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
        currentId = null
    }

    fun play(ringtone: BuiltInRingtone) {
        stop()
        val resId = ringtone.rawResId ?: return
        val uri = "android.resource://${context.packageName}/$resId".toUri()
        startPlayback(ringtone.id, uri)
    }

    fun play(id: String, uri: Uri) {
        stop()
        startPlayback(id, uri)
    }

    private fun startPlayback(id: String, uri: Uri) {
        val player = MediaPlayer.create(context, uri) ?: return
        mediaPlayer = player
        currentId = id
        player.setOnCompletionListener {
            stop()
        }
        player.start()
    }
}
