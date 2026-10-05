package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.LocalWorkCueKind
import com.labteto.dshmobile.local.runtime.extractLocalWorkCueSnippet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkStateCueExtractorTest {
    @Test
    fun longConstraintKeepsTheActualConstraintInsteadOfOnlyTheMessagePrefix() {
        val text = "背景说明".repeat(140) + "最终要求：必须保留当前分支并完成回归验证。" + "补充说明".repeat(80)

        val snippet = requireNotNull(
            extractLocalWorkCueSnippet(text, LocalWorkCueKind.CONSTRAINT, maxChars = 180),
        )

        assertTrue(snippet.contains("必须保留当前分支"))
        assertTrue(snippet.length <= 180)
        assertFalse(snippet.startsWith("背景说明背景说明背景说明"))
    }

    @Test
    fun decisionAndFailureUseTheirOwnCueSets() {
        assertTrue(
            requireNotNull(
                extractLocalWorkCueSnippet("处理完成后决定采用统一入口。", LocalWorkCueKind.DECISION),
            ).contains("决定采用"),
        )
        assertTrue(
            requireNotNull(
                extractLocalWorkCueSnippet("构建失败，原因是依赖冲突。", LocalWorkCueKind.FAILURE),
            ).contains("失败"),
        )
        assertNull(extractLocalWorkCueSnippet("普通状态更新", LocalWorkCueKind.CONSTRAINT))
    }
}
