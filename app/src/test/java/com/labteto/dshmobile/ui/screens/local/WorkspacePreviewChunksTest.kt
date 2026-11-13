package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePreviewChunksTest {
    @Test fun largePreviewRemainsCompleteWithBoundedTextLayoutBlocks() {
        val source = ("大文件预览\n" + "a".repeat(8_191) + "😀").repeat(80)
        val chunks = chunkWorkspacePreviewText(source)
        assertTrue(chunks.size > 100)
        assertTrue(chunks.all { it.length <= 4_096 })
        assertEquals(source, chunks.joinToString(""))
        assertTrue(chunks.none { it.last().isHighSurrogate() })
        assertTrue(chunks.drop(1).none { it.first().isLowSurrogate() })
    }

    @Test fun emptyAndSmallInputsAreStable() {
        assertEquals(emptyList<String>(), chunkWorkspacePreviewText(""))
        assertEquals(listOf("abc"), chunkWorkspacePreviewText("abc"))
        assertEquals(listOf("ab", "cd"), chunkWorkspacePreviewText("abcd", 2))
    }
}
