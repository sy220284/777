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
}
