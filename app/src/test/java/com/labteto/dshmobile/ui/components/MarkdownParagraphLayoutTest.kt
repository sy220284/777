package com.labteto.dshmobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dialogue layout must not rewrite persisted/model-visible Markdown. */
class MarkdownParagraphLayoutTest {
    @Test fun chatKeepsTheAuthorsLineBreaks() {
        val lines = listOf("（她轻轻握住你的手。）", "要一起去散步吗？")
        assertEquals("（她轻轻握住你的手。）\n要一起去散步吗？",
            markdownParagraphDisplayText(lines, preserveSoftLineBreaks = true))
    }

    @Test fun defaultMarkdownStillFoldsSoftLineBreaks() {
        val lines = listOf("first line", "second line")
        assertEquals("first line second line",
            markdownParagraphDisplayText(lines, preserveSoftLineBreaks = false))
    }

    @Test fun paragraphLayoutDoesNotChangeMarkdownBlockParsing() {
        val source = "（她笑了。）\n我们走吧。\n\n**加粗**与普通文本\n\n```kotlin\nval answer = 42\n```"
        val blocks = parseMarkdown(source)
        assertEquals(3, blocks.size)
        val dialogue = blocks[0] as MdBlock.Paragraph
        assertEquals(source.substringBefore("\n\n"), markdownParagraphDisplayText(dialogue.lines, true))
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.Code)
        assertEquals(source, source) // the presentation helpers never mutate the stored source
    }
}
