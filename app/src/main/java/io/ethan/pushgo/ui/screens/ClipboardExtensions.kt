package io.ethan.pushgo.ui.screens

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString

suspend fun Clipboard.setText(text: AnnotatedString) {
    val clipData = ClipData.newPlainText("pushgo", text.text)
    setClipEntry(ClipEntry(clipData))
}
