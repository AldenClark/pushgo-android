package io.ethan.pushgo.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushGoMarkdownSemanticsTest {

    @Test
    fun parser_keepsLinkInsideTableCell() {
        val markdown = """
            | Name | Link |
            | --- | --- |
            | Docs | [Guide](https://example.com/path_(v2)) |
        """.trimIndent()

        val document = PushGoMarkdownParser().parse(markdown)
        val table = document.blocks.single() as MarkdownBlock.Table
        assertEquals(1, table.table.rows.size)
        assertEquals(listOf(MarkdownInline.Text("Docs")), table.table.rows[0][0])
        assertEquals(
            listOf(MarkdownInline.Link(listOf(MarkdownInline.Text("Guide")), "https://example.com/path_(v2)")),
            table.table.rows[0][1],
        )
    }

    @Test
    fun listPreview_preservesOrderedListNumbersAndLinkSemantics() {
        val markdown = """
            3. [Read guide](https://example.com/guide)
            4. Next step
        """.trimIndent()

        assertEquals(
            """
            3. Read guide (https://example.com/guide)
            4. Next step
            """.trimIndent(),
            MessagePreviewExtractor.listPreview(markdown),
        )
    }

    @Test
    fun listPreview_skipsTableContent() {
        val markdown = """
            | Service | Link |
            | --- | --- |
            | PushGo | [Open](https://example.com/pushgo) |
        """.trimIndent()

        val preview = MessagePreviewExtractor.listPreview(markdown)
        assertEquals("", preview)
    }

    @Test
    fun listPreview_skipsMarkdownImages() {
        val markdown = """
            ![Diagram](https://example.com/arch_(v2).png)
            1. Keep this line
        """.trimIndent()

        val preview = MessagePreviewExtractor.listPreview(markdown)
        assertEquals("1. Keep this line", preview)
    }

    @Test
    fun listPreview_skipsCodeBlocksAndHtmlButKeepsBlockquotesAndTasks() {
        val markdown = """
            ```json
            {"env":"prod"}
            ```
            <div>hidden</div>
            > Keep quote
            - [x] Keep task
        """.trimIndent()

        val preview = MessagePreviewExtractor.listPreview(markdown)
        assertEquals(
            """
            Keep quote
            - [x] Keep task
            """.trimIndent(),
            preview,
        )
    }

    @Test
    fun listPreview_skipsPureLinkCollections() {
        val markdown = """
            [Docs](https://example.com/docs) | [Status](https://example.com/status)

            Keep this paragraph after [link](https://example.com/keep) now
        """.trimIndent()

        val preview = MessagePreviewExtractor.listPreview(markdown)
        assertEquals("Keep this paragraph after link (https://example.com/keep) now", preview)
    }

    @Test
    fun listPreview_skipsLinkedImagesAndSinglePureLinks() {
        val markdown = """
            [![tupian](https://i.v2ex.co/H0LZ8hZ1.png "tupian")](https://i.v2ex.co/H0LZ8hZ1.png "tupian")

            [Standalone](https://example.com/only)

            Keep this paragraph after [link](https://example.com/keep) now
        """.trimIndent()

        val preview = MessagePreviewExtractor.listPreview(markdown)
        assertEquals("Keep this paragraph after link (https://example.com/keep) now", preview)
    }
}
