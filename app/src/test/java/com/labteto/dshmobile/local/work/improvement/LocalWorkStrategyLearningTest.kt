package com.labteto.dshmobile.local.work.improvement

import com.labteto.dshmobile.harness.optimization.StrategyComparisonDisposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkStrategyLearningTest {
    @Test fun strictValidJsonBecomesCandidateNotAnActivePolicy() {
        val result = parseProposal("""{"cue":"Kotlin 编译失败","guidance":"先检查构建日志中的实际错误，再修改最小文件并重新编译。","reason":"减少盲目返工","category":"prompt","evidence_tag":"tool_failure"}""")
        assertEquals("prompt", result?.category)
        assertEquals("tool_failure", result?.evidenceTag)
        assertTrue(result!!.guidance.contains("重新编译"))
    }

    @Test fun insufficientEvidenceReturnsNoCandidate() {
        assertNull(parseProposal("""{"cue":"","guidance":"","reason":"insufficient evidence","category":"workflow","evidence_tag":"tool_failure"}"""))
    }

    @Test fun missingOrInventedSourceEvidenceIsRejected() {
        assertNull(parseProposal("""{"cue":"Kotlin 编译失败","guidance":"先观察实际错误再修复。","reason":"真实编译错误","category":"workflow"}"""))
        assertNull(parseProposal("""{"cue":"Kotlin 编译失败","guidance":"先观察实际错误再修复。","reason":"真实编译错误","category":"workflow","evidence_tag":"invented_event"}"""))
        assertNull(parseProposal("NO_ENTRY"))
    }

    @Test fun invalidOrOversizedResponseIsIgnored() {
        assertNull(parseProposal("not json"))
        assertNull(parseProposal("""{"cue":"x","guidance":"short","reason":"x","category":"security","evidence_tag":"tool_failure"}"""))
        assertNull(parseProposal("x".repeat(5000)))
    }

    @Test fun activeAdviceRetiresWhenVerifiedQualityRegresses() {
        assertTrue(shouldRetireWorkAdvice(
            true, StrategyComparisonDisposition.QUALITY_REGRESSION, 4, 5, 800, 900))
    }

    @Test fun activeAdviceRetiresWhenBothToolsAndTokensAreWorse() {
        assertTrue(shouldRetireWorkAdvice(
            true, StrategyComparisonDisposition.NO_MEASURED_BENEFIT, 9, 5, 1800, 900))
        assertTrue(!shouldRetireWorkAdvice(
            true, StrategyComparisonDisposition.NO_MEASURED_BENEFIT, 5, 5, 900, 900))
    }

    @Test fun unadoptedCandidateWithNoImprovementDoesNotForceRetirement() {
        assertTrue(!shouldRetireWorkAdvice(
            false, StrategyComparisonDisposition.NO_MEASURED_BENEFIT, 9, 5, 1800, 900))
    }

    @Test fun codeFenceCanBeParsedButNeverChangesOfficialPrompt() {
        val text = "```json\n" +
            """{"cue":"重复工具调用","guidance":"每次调用前检验刚刚收到的实际工具结果，避免重复同一个请求。","reason":"减少重复请求","category":"workflow","evidence_tag":"tool_failure"}""" +
            "\n```"
        assertEquals("workflow", parseProposal(text)?.category)
    }
}
