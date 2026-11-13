package com.labteto.dshmobile.local.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUiDocumentGenerationPromptTest {
    @Test fun selectedReplyIsIncludedInGenerationRequest() {
        val input = uiDocumentGenerationPrompt("回复内容含有特定事实 X-774", "将回复变为表格")
        assertTrue(input.contains("回复内容含有特定事实 X-774"))
        assertTrue(input.contains("将回复变为表格"))
    }

    @Test fun extremelyLongReplyIsBoundedWithoutDroppingUserRequirement() {
        val input = uiDocumentGenerationPrompt("甲".repeat(80_000), "整理成文档")
        assertTrue(input.length <= 41_000)
        assertTrue(input.contains("整理成文档"))
        assertFalse(input.contains("甲".repeat(32_100)))
    }
}
