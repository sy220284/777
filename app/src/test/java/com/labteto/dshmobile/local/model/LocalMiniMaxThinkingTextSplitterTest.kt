package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMiniMaxThinkingTextSplitterTest {
    @Test fun fragmentedMarkersNeverEnterVisibleDeltas() {
        val splitter = LocalMiniMaxThinkingTextSplitter()
        val fragments = listOf("  <th", "ink>我先核对", "调用情况</th", "ink>\n", "检查完成")
        val deltas = fragments.map(splitter::accept) + splitter.finish()
        assertEquals("\n检查完成", deltas.joinToString("") { it.content })
        assertEquals("我先核对调用情况", deltas.joinToString("") { it.reasoning })
        assertEquals(false, deltas.any { it.content.contains("<think>") || it.content.contains("</think>") })
    }

    @Test fun unclosedThinkingRemainsPrivate() {
        val splitter = LocalMiniMaxThinkingTextSplitter()
        val deltas = listOf("<thi", "nk>用户要我", "先查系统提示").map(splitter::accept) + splitter.finish()
        assertEquals("", deltas.joinToString("") { it.content })
        assertEquals("用户要我先查系统提示", deltas.joinToString("") { it.reasoning })
        assertEquals("", visibleAssistantText("<think>内部步骤没有关闭"))
        assertEquals("", visibleAssistantText("<thi"))
    }

    @Test fun ordinaryMarkdownAndQuotedTagsRemainUntouched() {
        for (text in listOf(
            "已完成检查，结果如下。",
            "```xml\n<think>这是用户要分析的代码</think>\n```",
            "命令示例包含 <think> 标签",
            "小于号 < think 只是普通文本",
        )) assertEquals(text, LocalMiniMaxThinkingTextSplitter.splitComplete(text).content)
    }

    @Test fun completedLeadingBlockPreservesEverythingAfterClosingTag() {
        val split = LocalMiniMaxThinkingTextSplitter.splitComplete(
            "<think>准备先查</think>**已完成**：检查了 3 个提交\n\n保留 Markdown"
        )
        assertEquals("准备先查", split.reasoning)
        assertEquals("**已完成**：检查了 3 个提交\n\n保留 Markdown", split.content)
    }

    @Test fun inconclusivePrefixDoesNotDisappear() {
        assertEquals("<ticket>已创建</ticket>",
            LocalMiniMaxThinkingTextSplitter.splitComplete("<ticket>已创建</ticket>").content)
        assertEquals("  完成", LocalMiniMaxThinkingTextSplitter.splitComplete("  完成").content)
    }
}
