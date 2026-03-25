package io.ethan.pushgo.markdown

import org.json.JSONObject
import java.util.Locale

data class PushGoMarkdownDocument(val blocks: List<MarkdownBlock>)

sealed class MarkdownBlock {
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock()
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock()
    data class BulletList(val items: List<MarkdownListItem>) : MarkdownBlock()
    data class OrderedList(val items: List<MarkdownListItem>) : MarkdownBlock()
    data class Blockquote(val content: List<MarkdownInline>) : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
    data class Table(val table: MarkdownTable) : MarkdownBlock()
    data class Callout(val type: MarkdownCalloutType, val content: List<MarkdownInline>) : MarkdownBlock()
}

data class MarkdownListItem(
    val content: List<MarkdownInline>,
    val ordinal: Int? = null,
)

data class MarkdownTable(
    val headers: List<List<MarkdownInline>>,
    val rows: List<List<List<MarkdownInline>>>,
)

enum class MarkdownCalloutType {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

sealed class MarkdownInline {
    data class Text(val value: String) : MarkdownInline()
    data class Bold(val content: List<MarkdownInline>) : MarkdownInline()
    data class Italic(val content: List<MarkdownInline>) : MarkdownInline()
    data class Strikethrough(val content: List<MarkdownInline>) : MarkdownInline()
    data class Highlight(val content: List<MarkdownInline>) : MarkdownInline()
    data class Code(val value: String) : MarkdownInline()
    data class Link(val text: List<MarkdownInline>, val url: String) : MarkdownInline()
    data class Mention(val value: String) : MarkdownInline()
    data class Tag(val value: String) : MarkdownInline()
    data class Autolink(val link: AutolinkValue) : MarkdownInline()
}

data class AutolinkValue(
    val kind: Kind,
    val value: String,
) {
    enum class Kind {
        URL,
        EMAIL,
        PHONE,
    }

    fun urlValue(): String? {
        return when (kind) {
            Kind.URL -> value
            Kind.EMAIL -> "mailto:$value"
            Kind.PHONE -> {
                val compact = value.replace(" ", "").replace("-", "")
                "tel:$compact"
            }
        }
    }
}

data class MarkdownRenderPayload(
    val version: Int,
    val runs: List<MarkdownRenderRun>,
) {
    companion object {
        const val CURRENT_VERSION = 2

        fun buildIfMarkdown(
            text: String,
            isMarkdown: Boolean,
            maxCharacters: Int?,
        ): MarkdownRenderPayload? {
            val budget = MarkdownRenderBudget(
                maxCharacters = maxCharacters,
                maxListItems = null,
                maxTableRows = null,
            )
            return buildIfMarkdown(text, isMarkdown, budget)
        }

        fun buildIfMarkdown(
            text: String,
            isMarkdown: Boolean,
            budget: MarkdownRenderBudget,
        ): MarkdownRenderPayload? {
            val trimmed = text.trim()
            if (!isMarkdown || trimmed.isEmpty()) return null
            return MarkdownRenderBuilder(budget).build(trimmed)
        }

        fun decode(jsonString: String): MarkdownRenderPayload? {
            val obj = runCatching { JSONObject(jsonString) }.getOrNull() ?: return null
            val version = obj.optInt("v", 0)
            if (version != CURRENT_VERSION) return null
            val runsArray = obj.optJSONArray("r") ?: return null
            val runs = mutableListOf<MarkdownRenderRun>()
            for (index in 0 until runsArray.length()) {
                val runObj = runsArray.optJSONObject(index) ?: continue
                val text = runObj.optString("t").takeIf { it.isNotEmpty() } ?: continue
                val flags = runObj.optInt("f", 0)
                val link = runObj.optString("l").takeIf { it.isNotEmpty() }
                val role = runObj.optString("r").takeIf { it.isNotEmpty() }?.let { MarkdownInlineRole.fromValue(it) }
                runs.add(
                    MarkdownRenderRun(
                        text = text,
                        isBold = (flags and 1) != 0,
                        isItalic = (flags and 2) != 0,
                        isStrikethrough = (flags and 4) != 0,
                        isHighlight = (flags and 8) != 0,
                        isCode = (flags and 16) != 0,
                        link = link,
                        role = role,
                    )
                )
            }
            return MarkdownRenderPayload(version = version, runs = runs)
        }
    }
}

data class MarkdownRenderRun(
    val text: String,
    val isBold: Boolean,
    val isItalic: Boolean,
    val isStrikethrough: Boolean,
    val isHighlight: Boolean,
    val isCode: Boolean,
    val link: String?,
    val role: MarkdownInlineRole?,
)

private fun MarkdownRenderRun.canMerge(other: MarkdownRenderRun): Boolean {
    return isBold == other.isBold &&
        isItalic == other.isItalic &&
        isStrikethrough == other.isStrikethrough &&
        isHighlight == other.isHighlight &&
        isCode == other.isCode &&
        link == other.link &&
        role == other.role
}

private fun MarkdownRenderRun.merged(other: MarkdownRenderRun): MarkdownRenderRun {
    return MarkdownRenderRun(
        text = text + other.text,
        isBold = isBold,
        isItalic = isItalic,
        isStrikethrough = isStrikethrough,
        isHighlight = isHighlight,
        isCode = isCode,
        link = link,
        role = role,
    )
}

enum class MarkdownInlineRole(val raw: String) {
    MENTION("mention"),
    TAG("tag"),
    ;

    companion object {
        fun fromValue(value: String): MarkdownInlineRole? {
            return entries.firstOrNull { it.raw == value }
        }
    }
}

data class MarkdownRenderBudget(
    val maxCharacters: Int?,
    val maxListItems: Int?,
    val maxTableRows: Int?,
)

private data class MarkdownRenderStyle(
    var isBold: Boolean = false,
    var isItalic: Boolean = false,
    var isStrikethrough: Boolean = false,
    var isHighlight: Boolean = false,
    var isCode: Boolean = false,
    var link: String? = null,
    var role: MarkdownInlineRole? = null,
)

private class MarkdownRenderBuilder(budget: MarkdownRenderBudget) {
    private val maxCharacters: Int? = budget.maxCharacters
    private val maxListItems: Int? = budget.maxListItems
    private val maxTableRows: Int? = budget.maxTableRows
    private val runs = mutableListOf<MarkdownRenderRun>()
    private var characterCount = 0
    private var isTruncated = false

    fun build(text: String): MarkdownRenderPayload {
        val document = PushGoMarkdownParser().parse(text)
        if (document.blocks.isEmpty()) {
            appendText(text, MarkdownRenderStyle())
            return MarkdownRenderPayload(version = MarkdownRenderPayload.CURRENT_VERSION, runs = runs)
        }

        document.blocks.forEachIndexed { index, block ->
            if (isTruncated) return@forEachIndexed
            appendBlock(block)
            if (index < document.blocks.size - 1) {
                appendLineBreakIfNeeded()
            }
        }

        return MarkdownRenderPayload(version = MarkdownRenderPayload.CURRENT_VERSION, runs = runs)
    }

    private fun appendBlock(block: MarkdownBlock) {
        when (block) {
            is MarkdownBlock.Heading -> {
                val headerStyle = MarkdownRenderStyle(isBold = true)
                appendInlines(block.content, headerStyle)
                appendLineBreakIfNeeded()
            }
            is MarkdownBlock.Paragraph -> {
                appendInlines(block.content, MarkdownRenderStyle())
                appendLineBreakIfNeeded()
            }
            is MarkdownBlock.BulletList -> {
                val limit = maxListItems?.let { minOf(it, block.items.size) } ?: block.items.size
                block.items.take(limit).forEach { item ->
                    if (isTruncated) return@forEach
                    appendText("- ", MarkdownRenderStyle())
                    appendInlines(item.content, MarkdownRenderStyle())
                    appendText("\n", MarkdownRenderStyle())
                }
                if (block.items.size > limit) appendEllipsisLine()
            }
            is MarkdownBlock.OrderedList -> {
                val limit = maxListItems?.let { minOf(it, block.items.size) } ?: block.items.size
                block.items.take(limit).forEachIndexed { index, item ->
                    if (isTruncated) return@forEachIndexed
                    appendText("${item.ordinal ?: (index + 1)}. ", MarkdownRenderStyle())
                    appendInlines(item.content, MarkdownRenderStyle())
                    appendText("\n", MarkdownRenderStyle())
                }
                if (block.items.size > limit) appendEllipsisLine()
            }
            is MarkdownBlock.Blockquote -> {
                appendText("> ", MarkdownRenderStyle())
                val quoteStyle = MarkdownRenderStyle(isItalic = true)
                appendInlines(block.content, quoteStyle)
                appendLineBreakIfNeeded()
            }
            is MarkdownBlock.HorizontalRule -> {
                appendText("----", MarkdownRenderStyle())
                appendLineBreakIfNeeded()
            }
            is MarkdownBlock.Table -> appendTable(block.table)
            is MarkdownBlock.Callout -> {
                val labelStyle = MarkdownRenderStyle(isBold = true)
                appendText("[${block.type.name}] ", labelStyle)
                val calloutStyle = MarkdownRenderStyle(isHighlight = true)
                appendInlines(block.content, calloutStyle)
                appendLineBreakIfNeeded()
            }
        }
    }

    private fun appendTable(table: MarkdownTable) {
        val rowLimit = maxTableRows?.let { minOf(it, table.rows.size) } ?: table.rows.size
        val columnCount = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)
        appendTableRow(table.headers, isHeader = true)
        appendTableSeparator(maxOf(columnCount, 1))
        table.rows.take(rowLimit).forEach { row ->
            if (isTruncated) return@forEach
            appendTableRow(row, isHeader = false)
        }
        if (table.rows.size > rowLimit) appendEllipsisLine()
    }

    private fun appendTableRow(row: List<List<MarkdownInline>>, isHeader: Boolean) {
        val style = MarkdownRenderStyle(isBold = isHeader)
        appendText("| ", MarkdownRenderStyle())
        row.forEachIndexed { index, cell ->
            if (isTruncated) return@forEachIndexed
            appendInlines(cell, style)
            if (index < row.size - 1) {
                appendText(" | ", MarkdownRenderStyle())
            }
        }
        appendText(" |", MarkdownRenderStyle())
        appendText("\n", MarkdownRenderStyle())
    }

    private fun appendTableSeparator(columnCount: Int) {
        if (columnCount <= 0) return
        appendText("|", MarkdownRenderStyle())
        for (index in 0 until columnCount) {
            appendText("---", MarkdownRenderStyle())
            appendText(if (index == columnCount - 1) "|" else "|", MarkdownRenderStyle())
        }
        appendText("\n", MarkdownRenderStyle())
    }

    private fun appendInlines(inlines: List<MarkdownInline>, style: MarkdownRenderStyle) {
        inlines.forEach { inline ->
            if (isTruncated) return@forEach
            appendInline(inline, style)
        }
    }

    private fun appendInline(inline: MarkdownInline, style: MarkdownRenderStyle) {
        when (inline) {
            is MarkdownInline.Text -> appendText(inline.value, style)
            is MarkdownInline.Bold -> appendInlines(inline.content, style.copy(isBold = true))
            is MarkdownInline.Italic -> appendInlines(inline.content, style.copy(isItalic = true))
            is MarkdownInline.Strikethrough -> appendInlines(inline.content, style.copy(isStrikethrough = true))
            is MarkdownInline.Highlight -> appendInlines(inline.content, style.copy(isHighlight = true))
            is MarkdownInline.Code -> appendText(inline.value, style.copy(isCode = true))
            is MarkdownInline.Link -> appendInlines(inline.text, style.copy(link = inline.url))
            is MarkdownInline.Mention -> appendText("@${inline.value}", style.copy(role = MarkdownInlineRole.MENTION))
            is MarkdownInline.Tag -> appendText("#${inline.value}", style.copy(role = MarkdownInlineRole.TAG))
            is MarkdownInline.Autolink -> appendText(
                inline.link.value,
                style.copy(link = inline.link.urlValue()),
            )
        }
    }

    private fun appendLineBreakIfNeeded() {
        val last = runs.lastOrNull() ?: return
        if (!last.text.endsWith("\n")) {
            appendText("\n", MarkdownRenderStyle())
        }
    }

    private fun appendEllipsisLine() {
        if (isTruncated) return
        appendText("...", MarkdownRenderStyle())
        appendText("\n", MarkdownRenderStyle())
    }

    private fun appendText(text: String, style: MarkdownRenderStyle): Boolean {
        if (text.isEmpty() || isTruncated) return false

        val max = maxCharacters
        if (max != null) {
            val remaining = max - characterCount
            if (remaining <= 0) {
                isTruncated = true
                return false
            }
            if (text.length > remaining) {
                val truncatedText = truncate(text, remaining)
                appendRun(truncatedText, style)
                characterCount += truncatedText.length
                isTruncated = true
                return false
            }
        }

        appendRun(text, style)
        characterCount += text.length
        return true
    }

    private fun appendRun(text: String, style: MarkdownRenderStyle) {
        val run = MarkdownRenderRun(
            text = text,
            isBold = style.isBold,
            isItalic = style.isItalic,
            isStrikethrough = style.isStrikethrough,
            isHighlight = style.isHighlight,
            isCode = style.isCode,
            link = style.link,
            role = style.role,
        )
        val last = runs.lastOrNull()
        if (last != null && last.canMerge(run)) {
            runs[runs.lastIndex] = last.merged(run)
        } else {
            runs.add(run)
        }
    }

    private fun truncate(text: String, remaining: Int): String {
        if (remaining <= 0) return ""
        if (remaining <= 3) return text.take(remaining)
        return text.take(remaining - 3) + "..."
    }
}

object PushGoMarkdownDetector {
    fun containsMarkdownSyntax(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false

        if (raw.contains("```")) return true

        if (InlineRegex.BOLD.containsMatchIn(raw)) return true
        if (InlineRegex.ITALIC_ASTERISK.containsMatchIn(raw)) return true
        if (InlineRegex.ITALIC_UNDERSCORE.containsMatchIn(raw)) return true
        if (InlineRegex.STRIKETHROUGH.containsMatchIn(raw)) return true
        if (InlineRegex.HIGHLIGHT.containsMatchIn(raw)) return true
        if (InlineRegex.INLINE_CODE.containsMatchIn(raw)) return true
        if (InlineRegex.LINK.containsMatchIn(raw)) return true

        val lines = raw
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n", ignoreCase = false, limit = 0)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (BlockRegex.HEADING.containsMatchIn(line)) return true
            if (BlockRegex.CALLOUT.containsMatchIn(line)) return true
            if (BlockRegex.BLOCKQUOTE.containsMatchIn(line)) return true
            if (BlockRegex.UNORDERED_LIST.containsMatchIn(line)) return true
            if (BlockRegex.ORDERED_LIST.containsMatchIn(line)) return true
            if (BlockRegex.HORIZONTAL_RULE.containsMatchIn(line)) return true
        }

        if (lines.size >= 2) {
            for (index in 0 until lines.size - 1) {
                val headerLine = lines[index]
                val separatorLine = lines[index + 1]
                if (headerLine.contains("|") && BlockRegex.TABLE_SEPARATOR.containsMatchIn(separatorLine)) {
                    return true
                }
            }
        }

        return false
    }
}

class PushGoMarkdownParser {
    fun parse(text: String): PushGoMarkdownDocument {
        val blocks = mutableListOf<MarkdownBlock>()
        val normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        val lines = normalized.split("\n", ignoreCase = false, limit = 0)
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                index += 1
                continue
            }

            if (isHorizontalRule(trimmed)) {
                blocks.add(MarkdownBlock.HorizontalRule)
                index += 1
                continue
            }

            parseHeading(line)?.let {
                blocks.add(it)
                index += 1
                continue
            }

            parseCallout(lines, index)?.let { result ->
                blocks.add(result.block)
                index = result.nextIndex
                continue
            }

            parseTable(lines, index)?.let { result ->
                blocks.add(MarkdownBlock.Table(result.table))
                index = result.nextIndex
                continue
            }

            parseList(lines, index)?.let { result ->
                blocks.add(result.block)
                index = result.nextIndex
                continue
            }

            parseBlockquote(lines, index)?.let { result ->
                blocks.add(result.block)
                index = result.nextIndex
                continue
            }

            parseParagraph(lines, index)?.let { result ->
                blocks.add(result.block)
                index = result.nextIndex
                continue
            }

            index += 1
        }

        return PushGoMarkdownDocument(blocks = blocks)
    }

    private fun parseHeading(line: String): MarkdownBlock? {
        val match = BlockRegex.HEADING.find(line) ?: return null
        val levelRange = match.groups[1]?.range ?: return null
        val textRange = match.groups[2]?.range ?: return null
        val level = line.substring(levelRange).length.coerceIn(1, 6)
        val content = line.substring(textRange).trim()
        return MarkdownBlock.Heading(level = level, content = parseInlines(content))
    }

    private fun parseCallout(lines: List<String>, startIndex: Int): ParseResult? {
        val line = lines.getOrNull(startIndex) ?: return null
        val match = BlockRegex.CALLOUT.find(line) ?: return null
        val typeRange = match.groups[1]?.range ?: return null
        val typeText = line.substring(typeRange).lowercase(Locale.US)
        val type = when (typeText) {
            "info" -> MarkdownCalloutType.INFO
            "success" -> MarkdownCalloutType.SUCCESS
            "warning" -> MarkdownCalloutType.WARNING
            "error" -> MarkdownCalloutType.ERROR
            else -> null
        } ?: return null

        val contentLines = mutableListOf<String>()
        match.groups[2]?.range?.let { range ->
            contentLines.add(line.substring(range).trim())
        }

        var index = startIndex + 1
        while (index < lines.size) {
            val peek = lines[index]
            if (BlockRegex.CALLOUT.containsMatchIn(peek)) break
            if (!BlockRegex.CALLOUT_CONTINUATION.containsMatchIn(peek)) break
            val cleaned = BlockRegex.LEADING_QUOTE.replace(peek, "")
            contentLines.add(cleaned.trim())
            index += 1
        }

        val joined = contentLines.joinToString("\n")
        return ParseResult(
            block = MarkdownBlock.Callout(type = type, content = parseInlines(joined)),
            nextIndex = index,
        )
    }

    private fun parseTable(lines: List<String>, startIndex: Int): TableParseResult? {
        if (startIndex + 1 >= lines.size) return null
        val headerLine = lines[startIndex]
        val separatorLine = lines[startIndex + 1]
        if (!BlockRegex.TABLE_SEPARATOR.containsMatchIn(separatorLine)) return null
        if (!headerLine.contains("|")) return null

        val headers = parseTableRow(headerLine)
        if (headers.isEmpty()) return null
        val columnLimit = headers.size.coerceAtLeast(1).coerceAtMost(6)
        val parsedHeaders = headers.take(columnLimit).map { parseInlines(it) }

        val rows = mutableListOf<List<List<MarkdownInline>>>()
        var current = startIndex + 2
        while (current < lines.size) {
            val rowLine = lines[current]
            val trimmed = rowLine.trim()
            if (trimmed.isEmpty()) break
            if (!rowLine.contains("|")) break
            if (BlockRegex.HEADING.containsMatchIn(rowLine)) break
            val cells = parseTableRow(rowLine)
            if (cells.isEmpty()) break
            rows.add(cells.take(columnLimit).map { parseInlines(it) })
            current += 1
            if (rows.size >= 30) break
        }

        return TableParseResult(
            table = MarkdownTable(headers = parsedHeaders, rows = rows),
            nextIndex = current,
        )
    }

    private fun parseTableRow(line: String): List<String> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()

        val content = trimmed
            .removePrefix("|")
            .removeSuffix("|")

        val cells = mutableListOf<String>()
        val buffer = StringBuilder()
        var bracketDepth = 0
        var parenDepth = 0
        var inCode = false
        var isEscaped = false

        for (char in content) {
            if (isEscaped) {
                buffer.append(char)
                isEscaped = false
                continue
            }

            if (char == '\\') {
                isEscaped = true
                buffer.append(char)
                continue
            }

            if (char == '`') {
                inCode = !inCode
                buffer.append(char)
                continue
            }

            if (!inCode) {
                when (char) {
                    '[' -> bracketDepth += 1
                    ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    '(' -> parenDepth += 1
                    ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    '|' -> if (bracketDepth == 0 && parenDepth == 0) {
                        cells.add(buffer.toString().trim())
                        buffer.setLength(0)
                        continue
                    }
                }
            }

            buffer.append(char)
        }

        cells.add(buffer.toString().trim())
        return cells
    }

    private fun parseList(lines: List<String>, startIndex: Int): ParseResult? {
        val line = lines.getOrNull(startIndex) ?: return null

        if (BlockRegex.UNORDERED_LIST.containsMatchIn(line)) {
            val items = mutableListOf<MarkdownListItem>()
            var current = startIndex
            while (current < lines.size) {
                val candidate = lines[current]
                val match = BlockRegex.UNORDERED_LIST.find(candidate) ?: break
                val range = match.groups[2]?.range ?: break
                val text = candidate.substring(range).trim()
                items.add(MarkdownListItem(content = parseInlines(text)))
                current += 1
                if (current < lines.size && lines[current].trim().isEmpty()) break
            }
            return ParseResult(MarkdownBlock.BulletList(items), current)
        }

        if (BlockRegex.ORDERED_LIST.containsMatchIn(line)) {
            val items = mutableListOf<MarkdownListItem>()
            var current = startIndex
            while (current < lines.size) {
                val candidate = lines[current]
                val match = BlockRegex.ORDERED_LIST.find(candidate) ?: break
                val ordinal = match.groups[1]?.value?.toIntOrNull()
                val range = match.groups[2]?.range ?: break
                val text = candidate.substring(range).trim()
                items.add(MarkdownListItem(content = parseInlines(text), ordinal = ordinal))
                current += 1
                if (current < lines.size && lines[current].trim().isEmpty()) break
            }
            return ParseResult(MarkdownBlock.OrderedList(items), current)
        }

        return null
    }

    private fun parseBlockquote(lines: List<String>, startIndex: Int): ParseResult? {
        val line = lines.getOrNull(startIndex) ?: return null
        if (!BlockRegex.BLOCKQUOTE.containsMatchIn(line)) return null
        val contentLines = mutableListOf<String>()
        var current = startIndex
        while (current < lines.size) {
            val candidate = lines[current]
            if (!BlockRegex.BLOCKQUOTE.containsMatchIn(candidate)) break
            val stripped = BlockRegex.LEADING_QUOTE.replace(candidate, "")
            contentLines.add(stripped.trim())
            current += 1
        }
        val joined = contentLines.joinToString("\n")
        return ParseResult(MarkdownBlock.Blockquote(parseInlines(joined)), current)
    }

    private fun parseParagraph(lines: List<String>, startIndex: Int): ParseResult? {
        val contentLines = mutableListOf<String>()
        var current = startIndex
        while (current < lines.size) {
            val candidate = lines[current]
            val trimmed = candidate.trim()
            if (trimmed.isEmpty() || isBlockBoundary(candidate)) break
            contentLines.add(candidate)
            current += 1
        }
        if (contentLines.isEmpty()) return null
        val joined = contentLines.joinToString("\n")
        return ParseResult(MarkdownBlock.Paragraph(parseInlines(joined)), current)
    }

    private fun isBlockBoundary(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        if (isHorizontalRule(trimmed)) return true
        if (BlockRegex.HEADING.containsMatchIn(line)) return true
        if (BlockRegex.CALLOUT.containsMatchIn(line)) return true
        if (BlockRegex.UNORDERED_LIST.containsMatchIn(line)) return true
        if (BlockRegex.ORDERED_LIST.containsMatchIn(line)) return true
        if (BlockRegex.BLOCKQUOTE.containsMatchIn(line)) return true
        if (line.contains("|") && BlockRegex.TABLE_SEPARATOR.containsMatchIn(line)) return true
        return false
    }

    private fun isHorizontalRule(line: String): Boolean {
        return BlockRegex.HORIZONTAL_RULE.containsMatchIn(line)
    }

    private fun parseInlines(text: String): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        val buffer = StringBuilder()
        var index = 0

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                result.add(MarkdownInline.Text(buffer.toString()))
                buffer.setLength(0)
            }
        }

        while (index < text.length) {
            val char = text[index]
            if (char == '\\') {
                val nextIndex = index + 1
                if (nextIndex < text.length) {
                    buffer.append(text[nextIndex])
                    index = nextIndex + 1
                    continue
                }
                buffer.append(char)
                index += 1
                continue
            }

            if (text.startsWith("**", index)) {
                val closing = text.indexOf("**", index + 2)
                if (closing != -1) {
                    flushBuffer()
                    val inner = text.substring(index + 2, closing)
                    result.add(MarkdownInline.Bold(parseInlines(inner)))
                    index = closing + 2
                    continue
                }
            }

            if (text.startsWith("~~", index)) {
                val closing = text.indexOf("~~", index + 2)
                if (closing != -1) {
                    flushBuffer()
                    val inner = text.substring(index + 2, closing)
                    result.add(MarkdownInline.Strikethrough(parseInlines(inner)))
                    index = closing + 2
                    continue
                }
            }

            if (text.startsWith("==", index)) {
                val closing = text.indexOf("==", index + 2)
                if (closing != -1) {
                    flushBuffer()
                    val inner = text.substring(index + 2, closing)
                    result.add(MarkdownInline.Highlight(parseInlines(inner)))
                    index = closing + 2
                    continue
                }
            }

            if (char == '*') {
                val nextIndex = index + 1
                if (nextIndex < text.length) {
                    val closing = text.indexOf("*", nextIndex)
                    if (closing != -1) {
                        flushBuffer()
                        val inner = text.substring(nextIndex, closing)
                        result.add(MarkdownInline.Italic(parseInlines(inner)))
                        index = closing + 1
                        continue
                    }
                }
            }

            if (char == '`') {
                val nextIndex = index + 1
                val closing = text.indexOf("`", nextIndex)
                if (closing != -1) {
                    flushBuffer()
                    val inner = text.substring(nextIndex, closing)
                    result.add(MarkdownInline.Code(inner))
                    index = closing + 1
                    continue
                }
            }

            if (char == '[') {
                val linkMatch = parseLink(text, index)
                if (linkMatch != null) {
                    val lowerUrl = linkMatch.destination.lowercase(Locale.US)
                    if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) {
                        flushBuffer()
                        result.add(MarkdownInline.Link(parseInlines(linkMatch.label), linkMatch.destination))
                        index = linkMatch.nextIndex
                        continue
                    }
                }
            }

            buffer.append(char)
            index += 1
        }

        flushBuffer()
        return tokenizeSpecials(result)
    }

    private fun parseLink(text: String, startIndex: Int): LinkMatch? {
        if (startIndex >= text.length || text[startIndex] != '[') return null

        var index = startIndex + 1
        var bracketDepth = 1
        var isEscaped = false
        while (index < text.length) {
            val char = text[index]
            if (isEscaped) {
                isEscaped = false
            } else {
                when (char) {
                    '\\' -> isEscaped = true
                    '[' -> bracketDepth += 1
                    ']' -> {
                        bracketDepth -= 1
                        if (bracketDepth == 0) break
                    }
                }
            }
            index += 1
        }

        if (index >= text.length || text[index] != ']') return null
        val closingBracket = index
        val openParen = closingBracket + 1
        if (openParen >= text.length || text[openParen] != '(') return null

        index = openParen + 1
        val destinationStart = index
        var parenDepth = 1
        isEscaped = false
        while (index < text.length) {
            val char = text[index]
            if (isEscaped) {
                isEscaped = false
            } else {
                when (char) {
                    '\\' -> isEscaped = true
                    '(' -> parenDepth += 1
                    ')' -> {
                        parenDepth -= 1
                        if (parenDepth == 0) {
                            return LinkMatch(
                                label = text.substring(startIndex + 1, closingBracket),
                                destination = text.substring(destinationStart, index).trim(),
                                nextIndex = index + 1,
                            )
                        }
                    }
                }
            }
            index += 1
        }

        return null
    }

    private fun tokenizeSpecials(inlines: List<MarkdownInline>): List<MarkdownInline> {
        val flattened = mutableListOf<MarkdownInline>()

        for (inline in inlines) {
            if (inline !is MarkdownInline.Text) {
                flattened.add(inline)
                continue
            }

            val text = inline.value
            val candidates = mutableListOf<MatchCandidate>()

            InlineRegex.URL.findAll(text).forEach { match ->
                val range = match.groups[1]?.range ?: return@forEach
                val value = text.substring(range)
                candidates.add(MatchCandidate(range, MarkdownInline.Autolink(AutolinkValue(AutolinkValue.Kind.URL, value))))
            }
            InlineRegex.EMAIL.findAll(text).forEach { match ->
                val range = match.groups[1]?.range ?: return@forEach
                val value = text.substring(range)
                candidates.add(MatchCandidate(range, MarkdownInline.Autolink(AutolinkValue(AutolinkValue.Kind.EMAIL, value))))
            }
            InlineRegex.PHONE.findAll(text).forEach { match ->
                val range = match.groups[1]?.range ?: return@forEach
                val value = text.substring(range)
                candidates.add(MatchCandidate(range, MarkdownInline.Autolink(AutolinkValue(AutolinkValue.Kind.PHONE, value))))
            }
            InlineRegex.MENTION.findAll(text).forEach { match ->
                val range = match.range
                val value = match.groups[1]?.value ?: return@forEach
                candidates.add(MatchCandidate(range, MarkdownInline.Mention(value)))
            }
            InlineRegex.TAG.findAll(text).forEach { match ->
                val range = match.range
                val value = match.groups[1]?.value ?: return@forEach
                candidates.add(MatchCandidate(range, MarkdownInline.Tag(value)))
            }

            if (candidates.isEmpty()) {
                flattened.add(inline)
                continue
            }

            candidates.sortWith { a, b ->
                if (a.range.first == b.range.first) {
                    b.range.last.compareTo(a.range.last)
                } else {
                    a.range.first.compareTo(b.range.first)
                }
            }

            val filtered = mutableListOf<MatchCandidate>()
            val occupied = mutableListOf<IntRange>()
            for (candidate in candidates) {
                if (occupied.any { rangesOverlap(it, candidate.range) }) continue
                occupied.add(candidate.range)
                filtered.add(candidate)
            }

            filtered.sortBy { it.range.first }
            var cursor = 0
            for (candidate in filtered) {
                if (candidate.range.first > cursor) {
                    flattened.add(MarkdownInline.Text(text.substring(cursor, candidate.range.first)))
                }
                flattened.add(candidate.inline)
                cursor = candidate.range.last + 1
            }
            if (cursor < text.length) {
                flattened.add(MarkdownInline.Text(text.substring(cursor, text.length)))
            }
        }

        return flattened
    }

    private data class ParseResult(val block: MarkdownBlock, val nextIndex: Int)

    private data class TableParseResult(val table: MarkdownTable, val nextIndex: Int)

    private data class MatchCandidate(val range: IntRange, val inline: MarkdownInline)
    private data class LinkMatch(val label: String, val destination: String, val nextIndex: Int)

    private fun rangesOverlap(a: IntRange, b: IntRange): Boolean {
        return a.first <= b.last && b.first <= a.last
    }
}

private object InlineRegex {
    val BOLD = Regex("(?:\\*\\*|__)(?=\\S).+?(?<=\\S)(?:\\*\\*|__)")
    val ITALIC_ASTERISK = Regex("(?<!\\*)\\*(?=\\S)[^*\\n]+?(?<=\\S)\\*(?!\\*)")
    val ITALIC_UNDERSCORE = Regex("(?<!_)_(?=\\S)[^_\\n]+?(?<=\\S)_(?!_)")
    val STRIKETHROUGH = Regex("~~(?=\\S).+?(?<=\\S)~~")
    val HIGHLIGHT = Regex("==(?=\\S).+?(?<=\\S)==")
    val INLINE_CODE = Regex("`[^`\\n]+`")
    val LINK = Regex("\\[[^\\]\\n]+\\]\\(([^)\\s]+)\\)")

    val URL = Regex("(?i)\\b(https?://[^\\s<>()]+)")
    val EMAIL = Regex("(?i)\\b([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,})")
    val PHONE = Regex("(?i)(?<!\\w)(\\+?[0-9][0-9\\-\\s]{6,}[0-9])(?!\\w)")
    val MENTION = Regex("(?<!\\w)@([A-Za-z0-9_]{1,30})")
    val TAG = Regex("(?<!\\w)#([A-Za-z0-9_\\p{IsHan}]{1,30})")
}

private object BlockRegex {
    val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
    val CALLOUT = Regex("^\\s{0,3}>\\s*\\[!(info|success|warning|error)\\]\\s*(.*)$", RegexOption.IGNORE_CASE)
    val CALLOUT_CONTINUATION = Regex("^\\s{0,3}>\\s+(.+)$")
    val BLOCKQUOTE = Regex("^\\s{0,3}>\\s?.+$")
    val UNORDERED_LIST = Regex("^\\s{0,3}([-*])\\s+(.+)$")
    val ORDERED_LIST = Regex("^\\s{0,3}(\\d+)\\.\\s+(.+)$")
    val HORIZONTAL_RULE = Regex("^\\s{0,3}(([-*_])\\s*){3,}$")
    val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*-+\\s*(\\|\\s*-+\\s*)+\\|?\\s*$")
    val LEADING_QUOTE = Regex("^\\s{0,3}>\\s?")
}

data class ResolvedBody(
    val rawText: String,
    val isMarkdown: Boolean,
    val source: BodySource,
)

enum class BodySource {
    BODY,
}

object MessageBodyResolver {
    fun resolve(rawPayloadJson: String, envelopeBody: String): ResolvedBody {
        return resolve(envelopeBody)
    }

    fun resolve(envelopeBody: String): ResolvedBody {
        val rawText = envelopeBody.trim()
        return ResolvedBody(rawText = rawText, isMarkdown = true, source = BodySource.BODY)
    }
}

object MessagePreviewExtractor {
    fun notificationPreview(markdown: String): String {
        return preview(markdown = markdown, maxLines = 1, maxChars = 180)
    }

    fun listPreview(markdown: String): String {
        return preview(markdown = markdown, maxLines = 6, maxChars = 1200)
    }

    private fun preview(markdown: String, maxLines: Int, maxChars: Int): String {
        val normalized = preprocessMarkdownForPreview(markdown)
        val lines = plainLines(normalized)
        val selected = lines.take(maxLines).mapNotNull { line ->
            val trimmed = line.trim()
            trimmed.takeIf { it.isNotEmpty() && !isPureLinkCollectionLine(it) }
        }
        val joined = selected.joinToString("\n")
        if (joined.length <= maxChars) return joined
        return joined.take(maxChars).trim()
    }

    private fun plainLines(markdown: String): List<String> {
        val document = PushGoMarkdownParser().parse(markdown)
        if (document.blocks.isEmpty()) {
            return markdown
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        return buildList {
            document.blocks.forEach { block ->
                plainLines(block).forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        add(trimmed)
                    }
                }
            }
        }
    }

    private fun plainLines(block: MarkdownBlock): List<String> {
        return when (block) {
            is MarkdownBlock.Heading -> plainPreviewLine(block.content)
            is MarkdownBlock.Paragraph -> plainPreviewLine(block.content)
            is MarkdownBlock.Blockquote -> plainPreviewLine(block.content, prefix = "> ")
            is MarkdownBlock.Callout -> plainPreviewLine(block.content, prefix = "[${block.type.name}] ")
            is MarkdownBlock.BulletList -> block.items.mapNotNull { plainPreviewLine(it.content, prefix = "- ").firstOrNull() }
            is MarkdownBlock.OrderedList -> block.items.mapIndexedNotNull { index, item ->
                plainPreviewLine(item.content, prefix = "${item.ordinal ?: (index + 1)}. ").firstOrNull()
            }
            is MarkdownBlock.Table -> emptyList()
            MarkdownBlock.HorizontalRule -> emptyList()
        }
    }

    private fun plainPreviewLine(
        inlines: List<MarkdownInline>,
        prefix: String = "",
    ): List<String> {
        if (isSkippableInlineSequence(inlines)) return emptyList()
        return listOf(prefix + plainText(inlines))
    }

    private fun plainText(inlines: List<MarkdownInline>): String {
        return inlines.joinToString(separator = "") { plainText(it) }
    }

    private fun plainText(inline: MarkdownInline): String {
        return when (inline) {
            is MarkdownInline.Text -> inline.value
            is MarkdownInline.Bold -> plainText(inline.content)
            is MarkdownInline.Italic -> plainText(inline.content)
            is MarkdownInline.Strikethrough -> plainText(inline.content)
            is MarkdownInline.Highlight -> plainText(inline.content)
            is MarkdownInline.Code -> inline.value
            is MarkdownInline.Link -> {
                val label = plainText(inline.text).trim()
                val destination = inline.url.trim()
                when {
                    label.isEmpty() -> ""
                    label == destination -> label
                    else -> "$label ($destination)"
                }
            }
            is MarkdownInline.Mention -> "@${inline.value}"
            is MarkdownInline.Tag -> "#${inline.value}"
            is MarkdownInline.Autolink -> inline.link.value
        }
    }

    private fun stripMarkdownImages(markdown: String): String {
        val output = StringBuilder(markdown.length)
        var index = 0
        while (index < markdown.length) {
            if (markdown[index] == '!' && index + 1 < markdown.length && markdown[index + 1] == '[') {
                val imageEnd = parseMarkdownImage(markdown, index)
                if (imageEnd != null) {
                    index = imageEnd
                    continue
                }
            }
            output.append(markdown[index])
            index += 1
        }
        return output.toString()
    }

    private fun preprocessMarkdownForPreview(markdown: String): String {
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val withoutImages = stripMarkdownImages(normalized)
        val withoutCodeBlocks = stripFencedCodeBlocks(withoutImages)
        val withoutTables = stripMarkdownTables(withoutCodeBlocks)
        return stripRawHtml(withoutTables)
    }

    private fun stripFencedCodeBlocks(markdown: String): String {
        val output = mutableListOf<String>()
        var activeFence: Char? = null
        markdown.split('\n').forEach { line ->
            val trimmed = line.trimStart()
            if (activeFence != null) {
                if (trimmed.startsWith(activeFence.toString().repeat(3))) {
                    activeFence = null
                }
                return@forEach
            }
            when {
                trimmed.startsWith("```") -> {
                    activeFence = '`'
                    return@forEach
                }
                trimmed.startsWith("~~~") -> {
                    activeFence = '~'
                    return@forEach
                }
                else -> output += line
            }
        }
        return output.joinToString("\n")
    }

    private fun stripMarkdownTables(markdown: String): String {
        val lines = markdown.split('\n')
        val filtered = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (index + 1 < lines.size && line.contains('|') && isMarkdownTableSeparator(lines[index + 1])) {
                index += 2
                while (index < lines.size) {
                    val row = lines[index]
                    val trimmed = row.trim()
                    if (trimmed.isEmpty() || !row.contains('|')) {
                        break
                    }
                    index += 1
                }
                continue
            }
            filtered += line
            index += 1
        }
        return filtered.joinToString("\n")
    }

    private fun isMarkdownTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.contains('-')) return false
        return trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' || it == '\t' }
    }

    private fun stripRawHtml(markdown: String): String {
        return markdown
            .split('\n')
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (isRawHtmlBlockLine(trimmed)) {
                    null
                } else {
                    removeInlineHtmlTags(line)
                }
            }
            .joinToString("\n")
    }

    private fun isRawHtmlBlockLine(line: String): Boolean {
        if (!line.startsWith('<') || !line.contains('>')) return false
        if (line.startsWith("<!--")) return true
        return line.contains("</") || line.endsWith("/>") || line.endsWith('>')
    }

    private fun removeInlineHtmlTags(line: String): String {
        val output = StringBuilder(line.length)
        var insideTag = false
        line.forEach { char ->
            when {
                char == '<' -> insideTag = true
                char == '>' -> insideTag = false
                !insideTag -> output.append(char)
            }
        }
        return output.toString()
    }

    private fun isSkippableInlineSequence(inlines: List<MarkdownInline>): Boolean {
        if (containsCodeInline(inlines)) return true
        return isPureLinkCollection(inlines)
    }

    private fun containsCodeInline(inlines: List<MarkdownInline>): Boolean {
        return inlines.any { inline ->
            when (inline) {
                is MarkdownInline.Code -> true
                is MarkdownInline.Bold -> containsCodeInline(inline.content)
                is MarkdownInline.Italic -> containsCodeInline(inline.content)
                is MarkdownInline.Strikethrough -> containsCodeInline(inline.content)
                is MarkdownInline.Highlight -> containsCodeInline(inline.content)
                is MarkdownInline.Link -> containsCodeInline(inline.text)
                else -> false
            }
        }
    }

    private fun isPureLinkCollection(inlines: List<MarkdownInline>): Boolean {
        var linkCount = 0

        fun walk(inline: MarkdownInline): Boolean {
            return when (inline) {
                is MarkdownInline.Link, is MarkdownInline.Autolink -> {
                    linkCount += 1
                    true
                }
                is MarkdownInline.Text -> inline.value.trim().trim(
                    *charArrayOf(
                        ' ', '\n', '\t', ',', '.', ';', ':', '!', '?', '|', '/', '-', '_',
                        '(', ')', '[', ']', '{', '}',
                    ),
                ).isEmpty()
                is MarkdownInline.Bold -> inline.content.all(::walk)
                is MarkdownInline.Italic -> inline.content.all(::walk)
                is MarkdownInline.Strikethrough -> inline.content.all(::walk)
                is MarkdownInline.Highlight -> inline.content.all(::walk)
                is MarkdownInline.Mention -> inline.value.trim().isEmpty()
                is MarkdownInline.Tag -> inline.value.trim().isEmpty()
                is MarkdownInline.Code -> false
            }
        }

        return inlines.all(::walk) && linkCount >= 2
    }

    private fun isPureLinkCollectionLine(line: String): Boolean {
        val trimmed = line.trim()
        if (
            trimmed.startsWith("- ") ||
            trimmed.startsWith("* ") ||
            trimmed.startsWith("+ ") ||
            hasOrderedListPrefix(trimmed)
        ) {
            return false
        }

        if (trimmed.contains(" | ") && trimmed.split(" | ").size >= 2 && trimmed.split(" (http").size > 2) {
            return true
        }

        if (isSingleLinkToken(trimmed)) {
            return true
        }

        for (separator in charArrayOf('|', ',', ';')) {
            val parts = trimmed.split(separator).map(String::trim).filter(String::isNotEmpty)
            if (parts.size >= 2) {
                return parts.all(::isSingleLinkToken)
            }
        }
        return false
    }

    private fun hasOrderedListPrefix(line: String): Boolean {
        val separatorIndex = line.indexOf(". ")
        if (separatorIndex <= 0) return false
        return line.substring(0, separatorIndex).all(Char::isDigit)
    }

    private fun isSingleLinkToken(token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed.none(Char::isWhitespace)
        }

        val open = trimmed.indexOf(" (")
        if (open <= 0) return false
        val label = trimmed.substring(0, open).trim()
        if (label.isEmpty()) return false
        val tail = trimmed.substring(open + 2)
        return (tail.startsWith("http://") || tail.startsWith("https://")) && trimmed.endsWith(')')
    }

    private fun parseMarkdownImage(markdown: String, bangIndex: Int): Int? {
        if (bangIndex + 1 >= markdown.length || markdown[bangIndex + 1] != '[') return null

        var index = bangIndex + 2
        var bracketDepth = 1
        var escaped = false
        while (index < markdown.length) {
            val char = markdown[index]
            if (escaped) {
                escaped = false
            } else {
                when (char) {
                    '\\' -> escaped = true
                    '[' -> bracketDepth += 1
                    ']' -> {
                        bracketDepth -= 1
                        if (bracketDepth == 0) break
                    }
                }
            }
            index += 1
        }

        if (index >= markdown.length || markdown[index] != ']') return null
        val openParen = index + 1
        if (openParen >= markdown.length || markdown[openParen] != '(') return null

        index = openParen + 1
        var parenDepth = 1
        escaped = false
        while (index < markdown.length) {
            val char = markdown[index]
            if (escaped) {
                escaped = false
            } else {
                when (char) {
                    '\\' -> escaped = true
                    '(' -> parenDepth += 1
                    ')' -> {
                        parenDepth -= 1
                        if (parenDepth == 0) return index + 1
                    }
                }
            }
            index += 1
        }
        return null
    }
}
