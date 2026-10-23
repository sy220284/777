package com.labteto.dshmobile.local.work.improvement

import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import com.labteto.dshmobile.local.work.LocalGoal
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkExperienceBootstrapTest {
    @Test
    fun emptyEvidenceNeverForcesAnExtraReflection() {
        val observed = WorkExperienceBootstrap.Signals(0, 0, 0)
        assertFalse(observed.canReflect)
        assertFalse(observed.supports("tool_failure"))
        val prompt = WorkExperienceBootstrap.reviewPrompt("普通任务", observed, true, true)
        assertTrue(prompt.contains("NO_ENTRY"))
        assertTrue(prompt.contains("WITHOUT an independent baseline comparison"))
    }

    @Test
    fun onlyObservedTagsAreEligibleAndReviewLensesStayScoped() {
        val observed = WorkExperienceBootstrap.Signals(1, 0, 0)
        assertTrue(observed.canReflect)
        assertTrue(observed.supports("tool_failure"))
        assertFalse(observed.supports("goal_rejection"))
        assertFalse(observed.supports("quality_conflict"))
        val prompt = WorkExperienceBootstrap.reviewPrompt("修复代码", observed, true, false)
        assertTrue(prompt.contains("- tool_failure: 工具错误后的执行恢复").not())
        assertTrue(prompt.contains("工具错误后的执行恢复").not())
        assertTrue(prompt.contains("tool_failure: 真实工具结果存在错误"))
        assertFalse(prompt.contains("Goal 完成变更曾被拒绝"))
    }

    @Test
    fun reviewUsesOnlyDurableEventCountsAndNeverCopiesToolResults() {
        val root = Files.createTempDirectory("777-experience-bootstrap").toFile()
        try {
            val log = LocalSessionEventLogRegistry(root, Json).get("sample")
            log.append("goal/state", buildJsonObject { put("status", "active") })
            val start = log.latestSequence()
            log.append("tool/result", buildJsonObject {
                put("is_error", true)
                put("raw_output", "SECRET_FROM_TOOL")
            })
            log.append("tool/result", buildJsonObject { put("is_error", false) })
            log.append("goal/transition-rejected", buildJsonObject { put("reason", "SECRET_REASON") })
            log.append("work/output-quality", buildJsonObject {
                put("findings", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("PRIVATE_FINDING"))
                })
            })
            val signals = WorkExperienceBootstrap.collect(
                log, LocalGoal("审查", startedAtSequence = start),
            )
            assertEquals(1, signals.toolFailures)
            assertEquals(1, signals.goalRejections)
            assertEquals(1, signals.qualityConflicts)
            val prompt = WorkExperienceBootstrap.reviewPrompt("修复代码", signals, true, true)
            assertFalse(prompt.contains("SECRET_FROM_TOOL"))
            assertFalse(prompt.contains("SECRET_REASON"))
            assertFalse(prompt.contains("PRIVATE_FINDING"))
            assertTrue(prompt.contains("tool_failure: 1"))
            assertTrue(prompt.contains("quality_conflict: 1"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun toolSchemaAndUnknownToolErrorsAreSeparatelyClassifiedFromTransportFailures() {
        val root = Files.createTempDirectory("777-tool-contract-evidence").toFile()
        try {
            val log = LocalSessionEventLogRegistry(root, Json).get("model-route")
            log.append("goal/state", buildJsonObject { put("status", "active") })
            val start = log.latestSequence()
            log.append("tool/result", buildJsonObject {
                put("is_error", true)
                put("error_code", "INVALID_TOOL_ARGUMENTS")
                put("content", "SENSITIVE_ARGUMENT_CONTENT")
            })
            log.append("tool/result", buildJsonObject {
                put("is_error", true)
                put("error_code", "UNKNOWN_TOOL")
            })
            log.append("tool/result", buildJsonObject {
                put("is_error", true)
                put("error_code", "TOOL_TIMEOUT")
            })
            val signals = WorkExperienceBootstrap.collect(
                log, LocalGoal("执行工具", startedAtSequence = start),
            )
            assertEquals(3, signals.toolFailures)
            assertEquals(2, signals.toolContractErrors)
            assertTrue(signals.supports("tool_contract_error"))
            val prompt = WorkExperienceBootstrap.reviewPrompt("完成任务", signals, true, true)
            assertTrue(prompt.contains("tool_contract_error: 2"))
            assertTrue(prompt.contains("工具调用契约"))
            assertFalse(prompt.contains("SENSITIVE_ARGUMENT_CONTENT"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun starterTemplatesClearlyDenyTheyAreVerifiedLessons() {
        val text = WorkExperienceBootstrap.catalogMarkdown()
        assertTrue(text.contains("未验证经验"))
        assertTrue(text.contains("NO_ENTRY"))
        assertTrue(text.contains("google-research/harness2"))
    }
}
