package io.ethan.pushgo.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.ethan.pushgo.markdown.MarkdownBlock
import io.ethan.pushgo.markdown.MarkdownCalloutType
import io.ethan.pushgo.markdown.MarkdownInline
import io.ethan.pushgo.markdown.MarkdownRenderPayload
import io.ethan.pushgo.markdown.MarkdownRenderRun
import io.ethan.pushgo.markdown.MarkdownTable
import io.ethan.pushgo.markdown.PushGoMarkdownParser

@Composable
fun MarkdownRenderer(
    text: String,
    modifier: Modifier = Modifier,
    maxNewlines: Int? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val displayText = remember(text, maxNewlines) { limitText(text, maxNewlines) }
    val document = remember(displayText) { PushGoMarkdownParser().parse(displayText) }
    PushGoMarkdownView(
        document = document,
        modifier = modifier,
        textStyle = textStyle,
        onLinkClick = onLinkClick,
    )
}

private fun limitText(text: String, maxNewlines: Int?): String {
    val limit = maxNewlines ?: return text
    if (limit <= 0) return text
    var newlineCount = 0
    for ((idx, char) in text.withIndex()) {
        if (char == '\n') {
            newlineCount += 1
            if (newlineCount >= limit) {
                return text.substring(0, idx)
            }
        }
    }
    return text
}

@Composable
fun PushGoMarkdownView(
    document: io.ethan.pushgo.markdown.PushGoMarkdownDocument,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val palette = rememberMarkdownPalette()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        document.blocks.forEach { block ->
            BlockView(
                block = block,
                textStyle = textStyle,
                palette = palette,
                onLinkClick = onLinkClick,
            )
        }
    }
}

@Composable
private fun BlockView(
    block: MarkdownBlock,
    textStyle: TextStyle,
    palette: MarkdownPalette,
    onLinkClick: ((String) -> Unit)?,
) {
    when (block) {
        is MarkdownBlock.Heading -> InlineText(
            inlines = block.content,
            textStyle = headingStyle(block.level),
            palette = palette,
            onLinkClick = onLinkClick,
        )
        is MarkdownBlock.Paragraph -> InlineText(
            inlines = block.content,
            textStyle = textStyle,
            palette = palette,
            onLinkClick = onLinkClick,
        )
        is MarkdownBlock.BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.items.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("•", style = textStyle, color = palette.muted)
                        Spacer(modifier = Modifier.width(8.dp))
                        InlineText(
                            inlines = item.content,
                            textStyle = textStyle,
                            palette = palette,
                            onLinkClick = onLinkClick,
                        )
                    }
                }
            }
        }
        is MarkdownBlock.OrderedList -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.items.forEachIndexed { idx, item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("${idx + 1}.", style = textStyle, color = palette.muted)
                        Spacer(modifier = Modifier.width(8.dp))
                        InlineText(
                            inlines = item.content,
                            textStyle = textStyle,
                            palette = palette,
                            onLinkClick = onLinkClick,
                        )
                    }
                }
            }
        }
        is MarkdownBlock.Blockquote -> BlockquoteView(block.content, textStyle, palette, onLinkClick)
        is MarkdownBlock.HorizontalRule -> HorizontalDivider(color = palette.divider)
        is MarkdownBlock.Table -> MarkdownTableView(block.table, textStyle, palette, onLinkClick)
        is MarkdownBlock.Callout -> CalloutView(block.type, block.content, textStyle, palette, onLinkClick)
    }
}

@Composable
private fun BlockquoteView(
    inlines: List<MarkdownInline>,
    textStyle: TextStyle,
    palette: MarkdownPalette,
    onLinkClick: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.blockquoteBackground, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(palette.blockquoteBar, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(10.dp))
        InlineText(
            inlines = inlines,
            textStyle = textStyle,
            palette = palette,
            onLinkClick = onLinkClick,
        )
    }
}

@Composable
private fun CalloutView(
    type: MarkdownCalloutType,
    inlines: List<MarkdownInline>,
    textStyle: TextStyle,
    palette: MarkdownPalette,
    onLinkClick: ((String) -> Unit)?,
) {
    val (accent, background, icon) = when (type) {
        MarkdownCalloutType.INFO -> Triple(palette.accent, palette.accent.copy(alpha = 0.12f), Icons.Outlined.Info)
        MarkdownCalloutType.SUCCESS -> Triple(palette.success, palette.success.copy(alpha = 0.12f), Icons.Outlined.CheckCircle)
        MarkdownCalloutType.WARNING -> Triple(palette.warning, palette.warning.copy(alpha = 0.12f), Icons.Outlined.Warning)
        MarkdownCalloutType.ERROR -> Triple(palette.error, palette.error.copy(alpha = 0.12f), Icons.Outlined.ErrorOutline)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(0.6.dp, palette.divider),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
            )
            Spacer(modifier = Modifier.width(10.dp))
            InlineText(
                inlines = inlines,
                textStyle = textStyle,
                palette = palette,
                onLinkClick = onLinkClick,
            )
        }
    }
}

@Composable
private fun MarkdownTableView(
    table: MarkdownTable,
    textStyle: TextStyle,
    palette: MarkdownPalette,
    onLinkClick: ((String) -> Unit)?,
) {
    val columnCount = remember(table) {
        val longestRow = table.rows.maxOfOrNull { it.size } ?: 0
        maxOf(table.headers.size, longestRow, 1)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.tableBackground, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TableRow(
            row = table.headers,
            columnCount = columnCount,
            textStyle = textStyle.copy(fontWeight = FontWeight.SemiBold),
            palette = palette,
            onLinkClick = onLinkClick,
        )

        HorizontalDivider(color = palette.divider)

        table.rows.forEach { row ->
            TableRow(
                row = row,
                columnCount = columnCount,
                textStyle = textStyle,
                palette = palette,
                onLinkClick = onLinkClick,
            )
        }
    }
}

@Composable
private fun TableRow(
    row: List<List<MarkdownInline>>,
    columnCount: Int,
    textStyle: TextStyle,
    palette: MarkdownPalette,
    onLinkClick: ((String) -> Unit)?,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        for (index in 0 until columnCount) {
            Box(modifier = Modifier.weight(1f)) {
                if (index < row.size) {
                    InlineText(
                        inlines = row[index],
                        textStyle = textStyle,
                        palette = palette,
                        onLinkClick = onLinkClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineText(
    inlines: List<MarkdownInline>,
    textStyle: TextStyle,
    palette: MarkdownPalette,
    onLinkClick: ((String) -> Unit)?,
) {
    val linkListener = remember(onLinkClick) {
        onLinkClick?.let { handler ->
            LinkInteractionListener { link ->
                if (link is LinkAnnotation.Url) {
                    handler(link.url)
                }
            }
        }
    }
    val annotated = remember(inlines, palette, linkListener) {
        annotatedStringFor(inlines, palette, linkListener)
    }
    Text(text = annotated, style = textStyle)
}

data class MarkdownPalette(
    val accent: Color,
    val muted: Color,
    val divider: Color,
    val highlight: Color,
    val codeBackground: Color,
    val tagBackground: Color,
    val blockquoteBar: Color,
    val blockquoteBackground: Color,
    val tableBackground: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

@Composable
fun rememberMarkdownPalette(): MarkdownPalette {
    val scheme = MaterialTheme.colorScheme
    return MarkdownPalette(
        accent = scheme.primary,
        muted = scheme.onSurfaceVariant,
        divider = scheme.outlineVariant.copy(alpha = 0.6f),
        highlight = scheme.tertiaryContainer.copy(alpha = 0.5f),
        codeBackground = scheme.surfaceVariant.copy(alpha = 0.7f),
        tagBackground = scheme.secondaryContainer.copy(alpha = 0.5f),
        blockquoteBar = scheme.onSurface.copy(alpha = 0.18f),
        blockquoteBackground = scheme.surfaceVariant.copy(alpha = 0.35f),
        tableBackground = scheme.surfaceVariant.copy(alpha = 0.4f),
        success = Color(0xFF2E7D32),
        warning = Color(0xFFED6C02),
        error = scheme.error,
    )
}

@Composable
fun MarkdownRenderPayloadText(
    payload: MarkdownRenderPayload,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    enableLinks: Boolean = false,
) {
    val palette = rememberMarkdownPalette()
    val annotated = remember(payload, palette, enableLinks) { payload.toAnnotatedString(palette, enableLinks) }
    Text(
        text = annotated,
        style = textStyle,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

@Composable
private fun headingStyle(level: Int): TextStyle {
    return when (level) {
        1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
        2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    }
}

private fun annotatedStringFor(
    inlines: List<MarkdownInline>,
    palette: MarkdownPalette,
    linkListener: LinkInteractionListener?,
): AnnotatedString {
    return buildAnnotatedString {
        inlines.forEach { inline ->
            appendInline(inline, palette, linkListener)
        }
    }
}

private fun MarkdownRenderPayload.toAnnotatedString(
    palette: MarkdownPalette,
    enableLinks: Boolean,
): AnnotatedString {
    return buildAnnotatedString {
        runs.forEach { run ->
            appendRun(run, palette, enableLinks)
        }
    }
}

private fun AnnotatedString.Builder.appendRun(
    run: MarkdownRenderRun,
    palette: MarkdownPalette,
    enableLinks: Boolean,
) {
    var decoration: TextDecoration? = null
    if (run.isStrikethrough) {
        decoration = TextDecoration.LineThrough
    }
    if (run.link != null) {
        decoration = if (decoration == null) {
            TextDecoration.Underline
        } else {
            TextDecoration.combine(listOf(decoration, TextDecoration.Underline))
        }
    }

    var color: Color? = null
    var background: Color? = null
    if (run.isHighlight) {
        background = palette.highlight
    }
    if (run.isCode) {
        background = palette.codeBackground
    }
    if (run.role == io.ethan.pushgo.markdown.MarkdownInlineRole.TAG) {
        background = palette.tagBackground
    }

    if (run.link != null) {
        color = palette.accent
    } else if (run.role == io.ethan.pushgo.markdown.MarkdownInlineRole.MENTION) {
        color = palette.accent
    } else if (run.role == io.ethan.pushgo.markdown.MarkdownInlineRole.TAG) {
        color = palette.muted
    } else if (run.isStrikethrough) {
        color = palette.muted
    }

    val style = SpanStyle(
        fontWeight = if (run.isBold) FontWeight.SemiBold else null,
        fontStyle = if (run.isItalic) FontStyle.Italic else null,
        textDecoration = decoration,
        background = background ?: Color.Unspecified,
        color = color ?: Color.Unspecified,
        fontFamily = if (run.isCode) FontFamily.Monospace else null,
    )

    val start = length
    withStyle(style) { append(run.text) }
    val end = length
    if (enableLinks && run.link != null && start != end) {
        addLink(
            LinkAnnotation.Url(
                url = run.link,
                styles = linkStyles(palette),
                linkInteractionListener = null,
            ),
            start,
            end,
        )
    }
}

private fun AnnotatedString.Builder.appendInline(
    inline: MarkdownInline,
    palette: MarkdownPalette,
    linkListener: LinkInteractionListener?,
) {
    when (inline) {
        is MarkdownInline.Text -> append(inline.value)
        is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
            inline.content.forEach { appendInline(it, palette, linkListener) }
        }
        is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            inline.content.forEach { appendInline(it, palette, linkListener) }
        }
        is MarkdownInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            inline.content.forEach { appendInline(it, palette, linkListener) }
        }
        is MarkdownInline.Highlight -> withStyle(SpanStyle(background = palette.highlight)) {
            inline.content.forEach { appendInline(it, palette, linkListener) }
        }
        is MarkdownInline.Code -> withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = palette.codeBackground,
            )
        ) {
            append(inline.value)
        }
        is MarkdownInline.Link -> {
            val start = length
            inline.text.forEach { appendInline(it, palette, linkListener) }
            val end = length
            if (start != end) {
                addLink(
                    LinkAnnotation.Url(
                        url = inline.url,
                        styles = linkStyles(palette),
                        linkInteractionListener = linkListener,
                    ),
                    start,
                    end,
                )
            }
        }
        is MarkdownInline.Mention -> {
            val start = length
            append("@${inline.value}")
            val end = length
            addStyle(SpanStyle(color = palette.accent), start, end)
        }
        is MarkdownInline.Tag -> {
            val start = length
            append("#${inline.value}")
            val end = length
            addStyle(SpanStyle(color = palette.muted, background = palette.tagBackground), start, end)
        }
        is MarkdownInline.Autolink -> {
            val start = length
            append(inline.link.value)
            val end = length
            inline.link.urlValue()?.let { url ->
                if (start != end) {
                    addLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = linkStyles(palette),
                            linkInteractionListener = linkListener,
                        ),
                        start,
                        end,
                    )
                }
            }
        }
    }
}

private fun linkStyles(palette: MarkdownPalette): TextLinkStyles {
    return TextLinkStyles(
        style = SpanStyle(
            color = palette.accent,
            textDecoration = TextDecoration.Underline,
        )
    )
}
