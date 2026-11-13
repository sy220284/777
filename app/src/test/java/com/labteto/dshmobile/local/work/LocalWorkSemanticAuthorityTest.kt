package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.context.buildTrustedWorkCheckpointModelMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkSemanticAuthorityTest {
    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun checkpoint(
        omitted: List<JsonObject>,
        history: List<JsonObject>,
        state: LocalStructuredWorkState? = null,
    ): LocalWorkCheckpoint {
        val rendered = LocalWorkHistorySummaryStrategy.summarize(omitted, history, 8_000, state)
        return requireNotNull(LocalWorkCheckpoint.latestFrom(
            listOf(buildTrustedWorkCheckpointModelMessage(rendered.modelBlock)),
        ))
    }

    @Test
    fun broadConstraintRevocationReplacesOldCheckpointAndPersistsAcrossCompactions() {
        val old = LocalWorkCheckpoint(
            goals = listOf("完成项目"),
            constraints = listOf("必须兼容旧接口", "禁止修改旧数据格式"),
            decisions = emptyList(),
            failures = emptyList(),
            unfinished = emptyList(),
            progress = emptyList(),
            artifacts = emptyList(),
            tools = emptyList(),
        )
        val original = buildTrustedWorkCheckpointModelMessage(old.toModelBlock())
        val reset = message("user", "此前所有约束作废，今后必须使用新接口")
        val next = checkpoint(listOf(reset), listOf(original, reset),
            LocalStructuredWorkState(constraints = listOf("今后必须使用新接口"), constraintEpoch = 42L))
        assertEquals(42L, next.constraintEpoch)
        assertTrue(next.constraints.any { it.contains("新接口") })
        assertFalse(next.constraints.any { it.contains("旧接口") || it.contains("旧数据格式") })

        val recent = message("assistant", "继续验证")
        val again = checkpoint(listOf(recent),
            listOf(buildTrustedWorkCheckpointModelMessage(next.toModelBlock()), recent),
            LocalStructuredWorkState(constraints = listOf("今后必须使用新接口"), constraintEpoch = 42L))
        assertEquals(42L, again.constraintEpoch)
        assertFalse(again.constraints.any { it.contains("旧接口") })
        assertTrue(again.constraints.any { it.contains("新接口") })
    }

    @Test
    fun speculativeAssistantTextIsNeverPromotedToAuthorizedDecision() {
        val suggestion = message("assistant", "方案 A 有风险，尚未决定是否采用。")
        val old = message("assistant", "我建议采用方案 B，请用户确认。")
        val checkpoint = checkpoint(listOf(suggestion, old),
            listOf(suggestion, old), LocalStructuredWorkState())
        assertTrue(checkpoint.decisions.isEmpty())
        assertTrue(checkpoint.userDecisionsVerified)
    }

    @Test
    fun explicitUserApprovalBecomesDecisionButDiscussionDoesNot() {
        val discussion = message("user", "旧方案有风险，继续评估原因")
        val approval = message("user", "确认采用新方案 B")
        val checkpoint = checkpoint(listOf(discussion, approval),
            listOf(discussion, approval), LocalStructuredWorkState())
        assertTrue(checkpoint.decisions.any { it.contains("新方案 B") })
        assertFalse(checkpoint.decisions.any { it.contains("旧方案有风险") })
    }

    @Test
    fun unverifiedLegacyCheckpointDecisionCannotBeRelabeledAsApproved() {
        val legacy = LocalWorkCheckpoint(
            goals = emptyList(), constraints = emptyList(),
            decisions = listOf("助手建议采用方案 A"),
            failures = emptyList(), unfinished = emptyList(),
            progress = emptyList(), artifacts = emptyList(), tools = emptyList(),
        )
        val previous = buildTrustedWorkCheckpointModelMessage(legacy.toModelBlock())
        val newer = message("user", "继续核查")
        val next = checkpoint(listOf(newer), listOf(previous, newer), LocalStructuredWorkState())
        assertTrue(next.decisions.isEmpty())
        assertTrue(next.progress.any { it.contains("未核验方案") && it.contains("方案 A") })
    }

    @Test
    fun mentionsAndQuestionsMustNotCancelUnrelatedRequirements() {
        assertFalse(isExplicitWorkConstraintReset("旧规则有风险，先检查原因"))
        assertFalse(isExplicitUserWorkDecision("旧方案有风险，尚未决定是否采用"))
        assertFalse(isExplicitUserWorkDecision("可以考虑采用方案 B 吗？"))
        assertTrue(isExplicitWorkConstraintReset("此前所有约束作废，今后必须使用新接口"))
        assertTrue(isExplicitUserWorkDecision("确认采用新方案 B"))
        assertTrue(isExplicitUserWorkDecision("此前所有方案作废，改为使用路由 B"))
    }
}
