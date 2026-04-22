package io.ethan.pushgo.ui.markdown

import android.graphics.Typeface
import android.os.Build
import android.os.LocaleList
import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.graphics.text.LineBreaker
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
import coil3.imageLoader
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.linkify.LinkifyPlugin
import io.ethan.pushgo.ui.theme.PushGoThemeExtras

@Composable
fun SelectablePlainTextRenderer(
    text: String,
    modifier: Modifier = Modifier,
    typeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL),
    textSizeSp: Float = androidx.compose.material3.MaterialTheme.typography.bodyMedium.fontSize.value,
    textColorArgb: Int = PushGoThemeExtras.colors.textPrimary.toArgb(),
    lineSpacingExtraPx: Float = 0f,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            AppCompatTextView(context).apply {
                this.typeface = typeface
                setTextColor(textColorArgb)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setLineSpacing(lineSpacingExtraPx, 1f)
                isClickable = true
                isLongClickable = true
                linksClickable = false
                setTextIsSelectable(true)
                includeFontPadding = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    textLocales = LocaleList.forLanguageTags("zh-CN,en-US")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isFallbackLineSpacing = true
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    justificationMode = LineBreaker.JUSTIFICATION_MODE_NONE
                }
            }
        },
        update = { textView ->
            textView.typeface = typeface
            textView.setTextColor(textColorArgb)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            textView.setLineSpacing(lineSpacingExtraPx, 1f)
            if (textView.text.toString() != text) {
                textView.text = text
            }
        },
    )
}

@Composable
fun FullMarkdownRenderer(
    text: String,
    modifier: Modifier = Modifier,
    onOpenLink: ((String) -> Unit)? = null,
    onOpenImage: ((String) -> Unit)? = null,
    onAnimatedImagePlay: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uiColors = PushGoThemeExtras.colors
    val color = uiColors.textPrimary.toArgb()
    val textSizeSp = androidx.compose.material3.MaterialTheme.typography.bodyMedium.fontSize.value
    val bodyTypeface = remember { Typeface.create("sans-serif", Typeface.NORMAL) }
    val linkColor = uiColors.accentPrimary.toArgb()
    val codeBackgroundColor = uiColors.codeBackground.toArgb()
    val quoteColor = uiColors.quoteStroke.toArgb()
    val tableBorderColor = uiColors.tableBorder.toArgb()
    val tableHeaderBackgroundColor = uiColors.tableHeaderBackground.toArgb()
    val tableOddRowBackgroundColor = uiColors.tableOddRowBackground.toArgb()
    val tableEvenRowBackgroundColor = uiColors.tableEvenRowBackground.toArgb()
    val dp = context.resources.displayMetrics.density
    val blockMarginPx = (20f * dp).toInt()
    val codeBlockMarginPx = (12f * dp).toInt()
    val tableCellPaddingPx = (10f * dp).toInt()
    val headingBreakHeightPx = (1f * dp).toInt().coerceAtLeast(1)
    val thematicBreakHeightPx = (3f * dp).toInt().coerceAtLeast(1)
    val blockQuoteWidthPx = (4f * dp).toInt().coerceAtLeast(1)
    val bulletWidthPx = (7f * dp).toInt().coerceAtLeast(1)
    val lineSpacingExtraPx = 7f * dp

    val interactionMovementMethod = remember(onOpenLink, onOpenImage, onAnimatedImagePlay) {
        TableAwareMovementMethod.wrap(
            MarkdownInteractionMovementMethod(
                onOpenLink = onOpenLink,
                onOpenImage = onOpenImage,
                onAnimatedImagePlay = onAnimatedImagePlay,
            ),
        )
    }
    val markwon = remember(
        context,
        onOpenLink,
        linkColor,
        codeBackgroundColor,
        quoteColor,
        tableBorderColor,
        tableHeaderBackgroundColor,
        tableOddRowBackgroundColor,
        tableEvenRowBackgroundColor,
        blockMarginPx,
        codeBlockMarginPx,
        tableCellPaddingPx,
        headingBreakHeightPx,
        thematicBreakHeightPx,
        blockQuoteWidthPx,
        bulletWidthPx,
        bodyTypeface,
    ) {
        Markwon.builder(context)
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create { builder: TableTheme.Builder ->
                builder
                    .tableCellPadding(tableCellPaddingPx)
                    .tableBorderWidth((1f * dp).toInt().coerceAtLeast(1))
                    .tableBorderColor(tableBorderColor)
                    .tableHeaderRowBackgroundColor(tableHeaderBackgroundColor)
                    .tableOddRowBackgroundColor(tableOddRowBackgroundColor)
                    .tableEvenRowBackgroundColor(tableEvenRowBackgroundColor)
            })
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(Coil3ImagesPlugin.create(context, context.imageLoader))
            .usePlugin(object : AbstractMarkwonPlugin() {
                private val fallbackResolver = LinkResolverDef()

                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(linkColor)
                        .isLinkUnderlined(false)
                        .blockMargin(blockMarginPx)
                        .blockQuoteColor(quoteColor)
                        .blockQuoteWidth(blockQuoteWidthPx)
                        .listItemColor(color)
                        .bulletWidth(bulletWidthPx)
                        .codeTextColor(color)
                        .codeBlockTextColor(color)
                        .codeBackgroundColor(codeBackgroundColor)
                        .codeBlockBackgroundColor(codeBackgroundColor)
                        .codeBlockMargin(codeBlockMarginPx)
                        .codeTextSize(
                            TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_SP,
                                textSizeSp * 0.96f,
                                context.resources.displayMetrics,
                            ).toInt()
                        )
                        .headingBreakColor(quoteColor)
                        .headingBreakHeight(headingBreakHeightPx)
                        .thematicBreakHeight(thematicBreakHeightPx)
                        .headingTextSizeMultipliers(floatArrayOf(1.56f, 1.36f, 1.2f, 1.08f, 1.0f, 0.96f))
                }

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
                typeface = bodyTypeface
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setLineSpacing(lineSpacingExtraPx, 1f)
                movementMethod = interactionMovementMethod
                isClickable = true
                isLongClickable = true
                linksClickable = true
                setTextIsSelectable(true)
                includeFontPadding = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    textLocales = LocaleList.forLanguageTags("zh-CN,en-US")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    isFallbackLineSpacing = true
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    justificationMode = LineBreaker.JUSTIFICATION_MODE_NONE
                }
            }
        },
        update = { textView ->
            textView.typeface = bodyTypeface
            textView.setTextColor(color)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            textView.setLineSpacing(lineSpacingExtraPx, 1f)
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
    private val onAnimatedImagePlay: (() -> Unit)?,
) : LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val pressedTarget = findPressedSpan(widget, buffer, event)
        val pressedSpan = pressedTarget?.span
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
                    val playable = pressedSpan.drawable.result as? MarkdownPlayableDrawable
                    val didStartPlayback = playable != null && isPlayButtonPressed(
                        widget = widget,
                        buffer = buffer,
                        span = pressedSpan,
                        touchX = pressedTarget.touchX,
                        touchY = pressedTarget.touchY,
                        drawable = playable,
                    ) && MarkdownAnimatedImagePlaybackRegistry.play(playable)
                    if (didStartPlayback) {
                        onAnimatedImagePlay?.invoke()
                        widget.postInvalidateOnAnimation()
                    } else {
                        onOpenImage?.invoke(pressedSpan.drawable.destination)
                    }
                }
            }
            Selection.removeSelection(buffer)
            return true
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    private fun findPressedSpan(widget: TextView, buffer: Spannable, event: MotionEvent): PressedSpanTarget? {
        val layout = widget.layout ?: return null
        val x = (event.x - widget.totalPaddingLeft + widget.scrollX).toInt()
        val y = (event.y - widget.totalPaddingTop + widget.scrollY).toInt()
        if (x < 0 || y < 0 || x > layout.width || y > layout.height) return null

        val line = layout.getLineForVertical(y)
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())

        val linkSpans = buffer.getSpans(offset, offset, URLSpan::class.java)
        if (linkSpans.isNotEmpty()) {
            return PressedSpanTarget(span = linkSpans.first(), touchX = x, touchY = y)
        }

        val imageSpans = buffer.getSpans(offset, offset, AsyncDrawableSpan::class.java)
        if (imageSpans.isNotEmpty()) {
            return PressedSpanTarget(span = imageSpans.first(), touchX = x, touchY = y)
        }

        return null
    }

    private fun isPlayButtonPressed(
        widget: TextView,
        buffer: Spannable,
        span: AsyncDrawableSpan,
        touchX: Int,
        touchY: Int,
        drawable: MarkdownPlayableDrawable,
    ): Boolean {
        if (!drawable.isOverlayVisible) return false
        val layout = widget.layout ?: return false
        val spanStart = buffer.getSpanStart(span)
        if (spanStart < 0) return false
        val line = layout.getLineForOffset(spanStart)
        val imageLeft = layout.getPrimaryHorizontal(spanStart)
        val imageTop = (layout.getLineBottom(line) - span.drawable.bounds.bottom).toFloat()
        val localX = touchX - imageLeft
        val localY = touchY - imageTop
        return drawable.isPlayButtonHit(localX = localX, localY = localY)
    }

    private data class PressedSpanTarget(
        val span: Any,
        val touchX: Int,
        val touchY: Int,
    )
}
