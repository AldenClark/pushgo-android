package io.ethan.pushgo.notifications

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

data class CustomRingtone(
    val id: String,
    val name: String,
    val filename: String
)

object CustomRingtoneManager {
    private const val DIR_NAME = "custom_ringtones"
    private const val MAX_DURATION_MS = 30_000L
    private const val MAX_FILE_BYTES = 10 * 1024 * 1024L
    
    fun getCustomRingtones(context: Context): List<CustomRingtone> {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.mapNotNull { file ->
            val nameWithExt = file.name
            val underscoreIndex = nameWithExt.indexOf('_')
            if (underscoreIndex != -1) {
                val id = nameWithExt.substring(0, underscoreIndex)
                val name = nameWithExt.substring(underscoreIndex + 1).substringBeforeLast('.')
                CustomRingtone(id, name, file.name)
            } else {
                null
            }
        } ?: emptyList()
    }
    
    fun addRingtone(context: Context, sourceUri: Uri): AddResult {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, sourceUri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            if (durationMs > MAX_DURATION_MS) {
                return AddResult.Error("Audio too long (max 30s)")
            }
        } catch (e: Exception) {
            return AddResult.Error("Failed to read audio file")
        } finally {
            retriever.release()
        }
        val name = getFileName(context, sourceUri) ?: "Custom Ringtone"
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()

        val id = UUID.randomUUID().toString()
        val safeName = name.replace("[^a-zA-Z0-9.-]".toRegex(), "_").ifBlank { "custom_ringtone" }
        val fileName = "${id}_$safeName"
        val destFile = File(dir, fileName)

        try {
            val statSize = context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { it.statSize }
            if (statSize != null && statSize > MAX_FILE_BYTES) {
                return AddResult.Error("Audio file too large")
            }
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: return AddResult.Error("Failed to read audio file")
            input.use { stream ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_FILE_BYTES) {
                            destFile.delete()
                            return AddResult.Error("Audio file too large")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            destFile.delete()
            return AddResult.Error("Failed to copy file")
        }

        val displayName = name.substringBeforeLast('.').ifBlank { name }
        return AddResult.Success(CustomRingtone(id, displayName, fileName))
    }
    
    fun getRingtoneUri(context: Context, id: String): Uri? {
        val dir = File(context.filesDir, DIR_NAME)
        val file = dir.listFiles()?.find { it.name.startsWith("${id}_") } ?: return null
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            null
        }
    }
    
    fun deleteRingtone(context: Context, id: String) {
        val dir = File(context.filesDir, DIR_NAME)
        dir.listFiles()?.find { it.name.startsWith("${id}_") }?.delete()
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result
    }
    
    sealed class AddResult {
        data class Success(val ringtone: CustomRingtone) : AddResult()
        data class Error(val message: String) : AddResult()
    }
}
