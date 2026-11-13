package com.labteto.dshmobile.ui.screens.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolInventorySearchScoreTest {
    @Test fun exactAndPrefixNamesRankAheadOfDescriptions() {
        val exact = inventorySearchScore("research", "research", "skill:research", "anything")!!
        val prefix = inventorySearchScore("research", "research-check", "skill:research-check", "anything")!!
        val detail = inventorySearchScore("research", "other", "skill:other", "a research assistant")!!
        assertTrue(exact > prefix)
        assertTrue(prefix > detail)
        assertEquals(0, inventorySearchScore("", "foo", "bar", "baz"))
    }

    @Test fun searchingIsCaseInsensitiveAndRejectsNonMatches() {
        assertTrue(inventorySearchScore("MCP", "mcp-review", "skill:mcp-review", "tools")!! > 0)
        assertNull(inventorySearchScore("unrelated", "mcp-review", "skill:mcp-review", "tools"))
        assertEquals(120, inventorySearchScore("图像", "识别", "skill:image", "支持图像分析"))
    }
}
