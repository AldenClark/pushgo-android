package io.ethan.pushgo.ui.export

import android.content.Context
import android.net.Uri
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.time.format.DateTimeFormatter

object ExportUtils {
    fun writeMessagesJson(context: Context, uri: Uri, messages: List<PushMessage>) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                BufferedWriter(writer).use { buffered ->
                    val arrayWriter = MessageJsonArrayWriter(buffered)
                    messages.forEach(arrayWriter::append)
                    arrayWriter.finish()
                }
            }
        }
    }

    suspend fun writeMessagesJsonStream(
        context: Context,
        uri: Uri,
        producer: suspend (MessageJsonArrayWriter) -> Unit,
    ): Int {
        return withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    BufferedWriter(writer).use { buffered ->
                        val arrayWriter = MessageJsonArrayWriter(buffered)
                        producer(arrayWriter)
                        arrayWriter.finish()
                        arrayWriter.count
                    }
                }
            } ?: 0
        }
    }

    class MessageJsonArrayWriter(
        private val writer: BufferedWriter,
        private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT,
    ) {
        var count: Int = 0
            private set

        init {
            writer.write("[")
        }

        fun append(message: PushMessage) {
            if (count > 0) {
                writer.write(",")
            }
            writer.write(MessageExportJsonSemantics.buildMessageMap(message, formatter).let(io.ethan.pushgo.util.JsonCompat::stringify))
            count += 1
        }

        fun finish() {
            writer.write("]")
            writer.flush()
        }
    }

    fun buildMessagesJson(messages: List<PushMessage>): String {
        return MessageExportJsonSemantics.buildMessagesJson(
            messages = messages,
            formatter = DateTimeFormatter.ISO_INSTANT,
        )
    }
}
