package io.ethan.pushgo.ui.markdown

import android.text.Selection
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil.imageLoader
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin

@Composable
fun FullMarkdownRenderer(
    text: String,
    modifier: Modifier = Modifier,
    onOpenLink: ((String) -> Unit)? = null,
    onOpenImage: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.toArgb()
    val textSizeSp = androidx.compose.material3.MaterialTheme.typography.bodyMedium.fontSize.value

    val interactionMovementMethod = remember(onOpenLink, onOpenImage) {
        MarkdownInteractionMovementMethod(onOpenLink = onOpenLink, onOpenImage = onOpenImage)
    }
    val markwon = remember(context, onOpenLink) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(CoilImagesPlugin.create(context, context.imageLoader))
            .usePlugin(object : AbstractMarkwonPlugin() {
                private val fallbackResolver = LinkResolverDef()

                override fun configureConfiguration(builder: io.noties.markwon.MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        if (onOpenLink != null) {
                            onOpenLink.invoke(link)
                        } else {
                            fallbackResolver.resolve(view, link)
                        }
                    }
                }
            })
            .build()
    }

    AndroidView(
        modifier = modifier,
        factory = {
            AppCompatTextView(it).apply {
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setLineSpacing(0f, 1f)
                movementMethod = interactionMovementMethod
                isClickable = true
                isLongClickable = true
                setTextIsSelectable(false)
                includeFontPadding = false
            }
        },
        update = { textView ->
            textView.setTextColor(color)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            textView.setLineSpacing(0f, 1f)
            textView.movementMethod = interactionMovementMethod
            val previousText = textView.tag as? String
            if (previousText != text) {
                markwon.setMarkdown(textView, text)
                textView.tag = text
            }
        },
    )
}

private class MarkdownInteractionMovementMethod(
    private val onOpenLink: ((String) -> Unit)?,
    private val onOpenImage: ((String) -> Unit)?,
) : LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val pressedSpan = findPressedSpan(widget, buffer, event)
        if (pressedSpan == null) {
            Selection.removeSelection(buffer)
            return super.onTouchEvent(widget, buffer, event)
        }

        val pressedStart = buffer.getSpanStart(pressedSpan)
        val pressedEnd = buffer.getSpanEnd(pressedSpan)
        if (event.action == MotionEvent.ACTION_DOWN) {
            Selection.setSelection(buffer, pressedStart, pressedEnd)
            return true
        }
        if (event.action == MotionEvent.ACTION_UP) {
            when (pressedSpan) {
                is URLSpan -> {
                    if (onOpenLink != null) {
                        onOpenLink.invoke(pressedSpan.url)
                    } else {
                        pressedSpan.onClick(widget)
                    }
                }
                is AsyncDrawableSpan -> {
                    onOpenImage?.invoke(pressedSpan.drawable.destination)
                }
            }
            Selection.removeSelection(buffer)
            return true
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    private fun findPressedSpan(widget: TextView, buffer: Spannable, event: MotionEvent): Any? {
        val layout = widget.layout ?: return null
        val x = (event.x - widget.totalPaddingLeft + widget.scrollX).toInt()
        val y = (event.y - widget.totalPaddingTop + widget.scrollY).toInt()
        if (x < 0 || y < 0 || x > layout.width || y > layout.height) return null

        val line = layout.getLineForVertical(y)
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())

        val linkSpans = buffer.getSpans(offset, offset, URLSpan::class.java)
        if (linkSpans.isNotEmpty()) return linkSpans.first()

        val imageSpans = buffer.getSpans(offset, offset, AsyncDrawableSpan::class.java)
        if (imageSpans.isNotEmpty()) return imageSpans.first()

        return null
    }
}
