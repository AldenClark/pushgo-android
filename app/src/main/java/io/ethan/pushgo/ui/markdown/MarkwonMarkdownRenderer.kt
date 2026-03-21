package io.ethan.pushgo.ui.markdown

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil.imageLoader
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin

@Composable
fun FullMarkdownRenderer(
    text: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.toArgb()
    val textSizeSp = androidx.compose.material3.MaterialTheme.typography.bodyMedium.fontSize.value

    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(CoilImagesPlugin.create(context, context.imageLoader))
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = {
            AppCompatTextView(it).apply {
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setLineSpacing(0f, 1f)
                movementMethod = LinkMovementMethod.getInstance()
                isClickable = true
                isLongClickable = true
                setTextIsSelectable(true)
                includeFontPadding = false
            }
        },
        update = { textView ->
            textView.setTextColor(color)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            textView.setLineSpacing(0f, 1f)
            val previousText = textView.tag as? String
            if (previousText != text) {
                markwon.setMarkdown(textView, text)
                textView.tag = text
            }
        },
    )
}
