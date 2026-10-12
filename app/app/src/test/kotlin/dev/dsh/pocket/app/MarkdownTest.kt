package dev.dsh.pocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun sampleMessageRendersTableListInlineCodeAndFileChip() {
        val blocks = parseMarkdown(SAMPLE_MARKDOWN)
        assertTrue("expected a GFM table", blocks.any { it is MdBlock.Table })
        assertTrue("expected a bullet list", blocks.any { it is MdBlock.BulletList })

        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(1, table.headers.size)
        assertEquals(2, table.headers[0].size)
        assertEquals(4, table.rows.size)
        // Value column cells are inline-code chips (no visible backticks in AST).
        val providerValue = table.rows[0][1]
        assertTrue(providerValue.any { it is MdSpan.Code && it.text == "qoder-cn" })
        assertFalse(providerValue.any { it is MdSpan.Text && it.text.contains('`') })

        val list = blocks.filterIsInstance<MdBlock.BulletList>().single()
        assertEquals(3, list.items.size)
        val firstItemSpans = list.items[0].filterIsInstance<MdBlock.Paragraph>().flatMap { it.spans }
        assertTrue(firstItemSpans.any { it is MdSpan.Code && it.text == "model: auto" })

        // File link chip from [cordis.patch.yml](cordis.patch.yml)
        val intro = blocks.filterIsInstance<MdBlock.Paragraph>().first()
        assertTrue(intro.spans.any { it is MdSpan.FileChip && it.path == "cordis.patch.yml" })
        assertFalse("backticks must not leak into text spans", intro.spans.any { it is MdSpan.Text && it.text.contains('`') })
    }

    @Test fun nestedListsAndInlineStyles() {
        val md = """
            1. outer
               - nested **bold** and *italic*
            2. second

            > quoted `code`

            ---

            # Heading
        """.trimIndent()
        val blocks = parseMarkdown(md)
        assertTrue(blocks.any { it is MdBlock.OrderedList })
        val ordered = blocks.filterIsInstance<MdBlock.OrderedList>().single()
        assertTrue(ordered.items[0].any { it is MdBlock.BulletList })
        assertTrue(blocks.any { it is MdBlock.Quote })
        assertTrue(blocks.any { it is MdBlock.Rule })
        assertTrue(blocks.any { it is MdBlock.Heading && it.level == 1 })
        val quote = blocks.filterIsInstance<MdBlock.Quote>().single()
        val qSpans = quote.children.filterIsInstance<MdBlock.Paragraph>().flatMap { it.spans }
        assertTrue(qSpans.any { it is MdSpan.Code && it.text == "code" })
    }

    @Test fun streamingPartialFence() {
        val partial = "intro\n\n```js\nconst x = 1"
        val blocks = parseMarkdown(partial, streaming = true)
        assertTrue(blocks.any { it is MdBlock.Paragraph })
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("js", code.language)
        assertTrue(code.incomplete)
        assertTrue(code.literal.contains("const x = 1"))
    }

    @Test fun fencedCodeHighlightProducesSpans() {
        val lit = highlightCode("const x = \"hi\" // c", "js")
        assertTrue(lit.isNotEmpty())
        assertTrue(lit.spanStyles.isNotEmpty())
    }

    @Test fun parseFileLinkRejectsHttp() {
        assertEquals(null, parseFileLink("https://example.com/a.yml"))
        assertEquals("cordis.patch.yml", parseFileLink("cordis.patch.yml"))
        assertEquals("/tmp/a.kt", parseFileLink("/tmp/a.kt#L12"))
    }
}
