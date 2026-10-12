package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.context.buildTrustedWorkCheckpointModelMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkScopedConstraintPolicyTest {
    private fun msg(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun compact(messages: List<JsonObject>, state: LocalStructuredWorkState? = null): LocalWorkCheckpoint {
        val summary = LocalWorkHistorySummaryStrategy.summarize(messages.takeLast(1), messages, 8_000, state)
        return requireNotNull(LocalWorkCheckpoint.latestFrom(
            listOf(buildTrustedWorkCheckpointModelMessage(summary.modelBlock)),
        ))
    }

    @Test
    fun specificEditRemovesOnlyQuotedRequirementAndSurvivesAnotherCompaction() {
        val original = LocalWorkCheckpoint(
            goals = listOf("升级软件"),
            constraints = listOf("必须兼容旧接口", "禁止删除用户数据"),
            decisions = emptyList(), failures = emptyList(), unfinished = emptyList(),
            progress = emptyList(), artifacts = emptyList(), tools = emptyList(),
        )
        val edit = msg("user", "将「必须兼容旧接口」改为「只允许使用新接口」")
        val updated = compact(listOf(buildTrustedWorkCheckpointModelMessage(original.toModelBlock()), edit))
        assertEquals(listOf("必须兼容旧接口"), updated.revokedConstraints)
        assertTrue(updated.constraints.any { it.contains("只允许使用新接口") })
        assertTrue(updated.constraints.contains("禁止删除用户数据"))
        assertFalse(updated.constraints.any { it.contains("必须兼容旧接口") })
        val again = compact(listOf(buildTrustedWorkCheckpointModelMessage(updated.toModelBlock()), msg("user", "继续核查")))
        assertTrue(again.revokedConstraints.contains("必须兼容旧接口"))
        assertTrue(again.constraints.contains("禁止删除用户数据"))
        assertFalse(again.constraints.any { it.contains("必须兼容旧接口") })
    }

    @Test
    fun quotedRevokeCannotAccidentallyDeleteDifferentScopedRequirements() {
        val original = LocalWorkCheckpoint(
            goals = listOf("检查各平台兼容性"),
            constraints = listOf("必须兼容旧接口", "平板端必须兼容旧接口", "不得删除用户数据"),
            decisions = emptyList(), failures = emptyList(), unfinished = emptyList(),
            progress = emptyList(), artifacts = emptyList(), tools = emptyList(),
        )
        val updated = compact(listOf(
            buildTrustedWorkCheckpointModelMessage(original.toModelBlock()),
            msg("user", "取消「必须兼容旧接口」这条约束"),
        ))
        assertFalse(updated.constraints.contains("必须兼容旧接口"))
        assertTrue(updated.constraints.contains("平板端必须兼容旧接口"))
        assertTrue(updated.constraints.contains("不得删除用户数据"))
    }

    @Test
    fun explicitReinstatementRestoresOnlyPreviouslyRevokedRuleAcrossCompactions() {
        val original = LocalWorkCheckpoint(
            goals = listOf("升级旧接口"),
            constraints = listOf("仅使用新接口", "不得删除用户数据"),
            revokedConstraints = listOf("必须兼容旧接口"),
            decisions = emptyList(), failures = emptyList(), unfinished = emptyList(),
            progress = emptyList(), artifacts = emptyList(), tools = emptyList(),
        )
        val update = compact(listOf(
            buildTrustedWorkCheckpointModelMessage(original.toModelBlock()),
            msg("user", "重新启用「必须兼容旧接口」"),
        ))
        assertTrue(update.constraints.contains("必须兼容旧接口"))
        assertTrue(update.constraints.contains("不得删除用户数据"))
        assertFalse(update.revokedConstraints.contains("必须兼容旧接口"))
        val again = compact(listOf(
            buildTrustedWorkCheckpointModelMessage(update.toModelBlock()),
            msg("user", "继续工作"),
        ))
        assertTrue(again.constraints.contains("必须兼容旧接口"))
        assertFalse(again.revokedConstraints.contains("必须兼容旧接口"))
    }

    @Test
    fun laterExplicitRevocationWinsOverEarlierReinstatement() {
        val original = LocalWorkCheckpoint(
            goals = listOf("软件升级"),
            constraints = listOf("不得删除用户数据"),
            revokedConstraints = listOf("必须兼容旧接口"),
            decisions = emptyList(), failures = emptyList(), unfinished = emptyList(),
            progress = emptyList(), artifacts = emptyList(), tools = emptyList(),
        )
        val checkpoint = buildTrustedWorkCheckpointModelMessage(original.toModelBlock())
        val changed = compact(listOf(
            checkpoint,
            msg("user", "重新启用「必须兼容旧接口」"),
            msg("user", "再次取消「必须兼容旧接口」这条约束"),
        ))
        assertFalse(changed.constraints.contains("必须兼容旧接口"))
        assertTrue(changed.revokedConstraints.contains("必须兼容旧接口"))
    }

    @Test
    fun questionsAndHypotheticalsCannotReinstateOldAuthority() {
        assertEquals(null, parseLocalWorkConstraintReinstatement("是否重新启用「必须兼容旧接口」？"))
        assertEquals(null, parseLocalWorkConstraintReinstatement("建议重新启用「必须兼容旧接口」"))
        assertEquals(null, parseLocalWorkConstraintReinstatement("讨论重新启用「必须兼容旧接口」"))
        assertEquals("必须兼容旧接口", parseLocalWorkConstraintReinstatement("重新启用「必须兼容旧接口」"))
    }

    @Test
    fun unquotedOrAmbiguousEditDoesNotRevokeAnyAuthority() {
        assertEquals(null, parseLocalWorkScopedConstraintEdit("旧接口可能不合适，讨论一下"))
        assertEquals(null, parseLocalWorkScopedConstraintEdit("把兼容旧接口改为新接口"))
        assertEquals(null, parseLocalWorkScopedConstraintEdit("建议把「旧接口」改为「新接口」吗？"))
    }
}
