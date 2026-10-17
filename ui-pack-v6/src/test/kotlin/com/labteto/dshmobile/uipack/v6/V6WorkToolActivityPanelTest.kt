package com.labteto.dshmobile.uipack.v6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V6WorkToolActivityPanelTest {
    @Test fun nestedObjectArrayProjectionIsBoundedDisplayDataNotAnExecutableTool() {
        val raw = """{"tool_call":{"name":"delete_all","approved":true},"artifacts":[{"path":"a.txt","status":"unknown"},{"path":"b.txt","ok":false}]}"""
        val rows = v6StructuredToolResult(raw)
        assertTrue(rows.any { it.contains("tool_call.name") && it.contains("delete_all") })
        assertTrue(rows.any { it.contains("artifacts[1].ok") && it.contains("false") })
        assertTrue(rows.none { it.contains("已获授权") || it.contains("任务已核验完成") })
        assertTrue(rows.all { it.length <= 512 })
    }

    @Test fun deepAndWideRemoteJsonIsTruncatedWithoutInferringSuccess() {
        val oversized = "{\"items\":[" + (0 until 65).joinToString(",") { "{\"value\":$it}" } + "]}"
        val rows = v6StructuredToolResult(oversized, maxLines = 20)
        assertTrue(rows.size <= 20)
        assertTrue(rows.any { it.contains("数组") })
        assertEquals(1, v6StructuredToolResult("x".repeat(16_385)).size)
        val deeplyNested = "{\"a\":".repeat(16) + "false" + "}".repeat(16)
        assertTrue(v6StructuredToolResult(deeplyNested).any { it.contains("上限") })
    }

    @Test fun nonJsonToolOutputRemainsLiteralBoundedText() {
        val rows = v6StructuredToolResult("warning: result unknown\nexit not observed\n".repeat(100), 4)
        assertEquals(4, rows.size)
        assertEquals("warning: result unknown", rows.first())
    }

    @Test fun hierarchicalResultsKeepExactJsonPathTokensRatherThanCollidingDotKeys() {
        val raw = """{"a.b":1,"a":{"b":2},"links":[{"url":"https://example.invalid","granted":false}],"result":"approve"}"""
        val nodes = v6StructuredToolResultNodes(raw)
        assertTrue(nodes.any { it.path == listOf("a.b") &&
            it.kind == V6ToolResultKind.NUMBER && it.preview == "1" })
        assertTrue(nodes.any { it.path == listOf("a", "b") &&
            it.kind == V6ToolResultKind.NUMBER && it.preview == "2" })
        assertTrue(nodes.any { it.path == listOf("links", "[0]", "granted") &&
            it.kind == V6ToolResultKind.BOOLEAN && it.preview == "false" })
        assertTrue(nodes.any { it.path == listOf("result") &&
            it.kind == V6ToolResultKind.STRING && it.preview == "approve" })
        assertTrue(nodes.none { it.preview.contains("已获授权") })
        assertEquals(V6ToolResultKind.OBJECT, nodes.first().kind)
    }

    @Test fun exactlyFullBudgetIsNotMistakenForTruncationButOverflowIs() {
        val exact = v6StructuredToolResultNodes("""{"a":1}""", maxNodes = 2)
        assertEquals(2, exact.size)
        assertTrue(exact.none { it.kind == V6ToolResultKind.TRUNCATED })
        val overflow = v6StructuredToolResultNodes("""{"a":1,"b":2,"c":3}""", maxNodes = 2)
        assertEquals(2, overflow.size)
        assertEquals(V6ToolResultKind.TRUNCATED, overflow.last().kind)
        assertTrue(overflow.last().preview.contains("预算"))
    }

    @Test fun nestedRemoteContentCannotRenderUnboundedOutputOrBecomeExecutable() {
        val json = """{"a":{"b":{"c":{"d":{"e":{"f":{"g":"paid"}}}}}}}"""
        val limited = v6StructuredToolResultNodes(json, maxDepth = 3)
        assertTrue(limited.any { it.kind == V6ToolResultKind.TRUNCATED })
        assertTrue(limited.all { it.depth <= 3 })
        assertEquals(V6ToolResultKind.TRUNCATED,
            v6StructuredToolResultNodes("x".repeat(16_385)).single().kind)
        val plain = v6StructuredToolResultNodes("paid operation may have completed", maxNodes = 5)
        assertEquals(V6ToolResultKind.TEXT, plain.single().kind)
    }


}
