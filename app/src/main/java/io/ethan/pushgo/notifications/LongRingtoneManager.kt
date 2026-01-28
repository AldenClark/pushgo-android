package io.ethan.pushgo.notifications

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import io.ethan.pushgo.data.AppConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

object LongRingtoneManager {
    private const val TARGET_SECONDS = 30
    private const val TIMEOUT_US = 10_000L

    fun getExistingLongSoundUri(context: Context, ringtoneId: String?): Uri? {
        val file = getLongSoundFile(context, ringtoneId) ?: return null
        return if (file.exists()) Uri.fromFile(file) else null
    }

    fun ensureLongRingtone(context: Context, ringtoneId: String?): Uri? {
        val file = getLongSoundFile(context, ringtoneId) ?: return null
        if (file.exists()) return Uri.fromFile(file)
        val ringtone = RingtoneCatalog.findById(ringtoneId)
        val rawResId = ringtone.rawResId ?: return null
        val decoded = decodeResourceToPcm(context, rawResId) ?: return null
        return if (writeLoopedWav(file, decoded)) Uri.fromFile(file) else null
    }

    private fun getLongSoundFile(context: Context, ringtoneId: String?): File? {
        val ringtone = RingtoneCatalog.findById(ringtoneId)
        val baseName = ringtone.filename.substringBeforeLast('.').ifEmpty { ringtone.id }
        val outputDir = context.getExternalFilesDir(AppConstants.longRingtoneDirectory) ?: context.filesDir
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            return null
        }
        return File(outputDir, "${AppConstants.longRingtonePrefix}$baseName.wav")
    }

    private data class DecodedPcm(
        val sampleRate: Int,
        val channelCount: Int,
        val pcmData: ByteArray,
    )

    private fun decodeResourceToPcm(context: Context, rawResId: Int): DecodedPcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            val afd = context.resources.openRawResourceFd(rawResId) ?: return null
            afd.use {
                extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            val trackIndex = selectAudioTrack(extractor) ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            val activeCodec = codec
            activeCodec.configure(format, null, null, 0)
            activeCodec.start()
            val pcmData = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputFormat = format

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = activeCodec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = activeCodec.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            activeCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            activeCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            outputBuffer.clear()
                            pcmData.write(chunk)
                        }
                        activeCodec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = activeCodec.outputFormat
                    }
                }
            }

            val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
                return null
            }
            val data = pcmData.toByteArray()
            if (data.isEmpty()) return null
            DecodedPcm(sampleRate, channelCount, data)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return index
        }
        return null
    }

    private fun writeLoopedWav(outputFile: File, decoded: DecodedPcm): Boolean {
        val bytesPerSample = 2
        val bytesPerFrame = decoded.channelCount * bytesPerSample
        val targetFrames = decoded.sampleRate.toLong() * TARGET_SECONDS
        val totalAudioBytes = targetFrames * bytesPerFrame

        return try {
            FileOutputStream(outputFile).use { output ->
                writeWavHeader(
                    output,
                    totalAudioBytes,
                    decoded.sampleRate,
                    decoded.channelCount,
                    bytesPerSample * 8
                )
                var written = 0L
                val source = decoded.pcmData
                while (written < totalAudioBytes) {
                    val remaining = totalAudioBytes - written
                    val toWrite = min(remaining.toInt(), source.size)
                    output.write(source, 0, toWrite)
                    written += toWrite
                }
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun writeWavHeader(
        output: FileOutputStream,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeInt(header, 4, totalDataLen.toInt())
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)
        writeShort(header, 20, 1)
        writeShort(header, 22, channels.toShort())
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, (channels * bitsPerSample / 8).toShort())
        writeShort(header, 34, bitsPerSample.toShort())
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeInt(header, 40, totalAudioLen.toInt())
        output.write(header, 0, 44)
    }

    private fun writeInt(header: ByteArray, offset: Int, value: Int) {
        header[offset] = (value and 0xff).toByte()
        header[offset + 1] = (value shr 8 and 0xff).toByte()
        header[offset + 2] = (value shr 16 and 0xff).toByte()
        header[offset + 3] = (value shr 24 and 0xff).toByte()
    }

    private fun writeShort(header: ByteArray, offset: Int, value: Short) {
        header[offset] = (value.toInt() and 0xff).toByte()
        header[offset + 1] = (value.toInt() shr 8 and 0xff).toByte()
    }
}
