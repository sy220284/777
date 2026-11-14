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
            log.append("goal/state", buildJsonObject {
                put("id", "goal-scoped")
                put("revision", 1)
                put("status", "active")
            })
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
            log.append("goal/state", buildJsonObject {
                put("id", "goal-scoped")
                put("revision", 2)
                put("status", "completed")
                put("startedAtSequence", start)
            })
            val signals = WorkExperienceBootstrap.collect(
                log, LocalGoal("审查", status = "completed", id = "goal-scoped", revision = 2, startedAtSequence = start),
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
            log.append("goal/state", buildJsonObject {
                put("id", "goal-scoped")
                put("revision", 1)
                put("status", "active")
            })
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
            log.append("goal/state", buildJsonObject {
                put("id", "goal-scoped")
                put("revision", 2)
                put("status", "completed")
                put("startedAtSequence", start)
            })
            val signals = WorkExperienceBootstrap.collect(
                log, LocalGoal("执行工具", status = "completed", id = "goal-scoped", revision = 2, startedAtSequence = start),
            )
            assertEquals(3, signals.toolFailures)
            assertEquals(2, signals.toolContractErrors)
            assertTrue(signals.supports("tool_contract_error"))
            val prompt = WorkExperienceBootstrap.reviewPrompt("完成任务", signals, true, true)
            assertTrue(prompt.contains("tool_contract_error: 2"))
            assertTrue(prompt.contains("参数结构违反当前注册工具的 Schema"))
            assertFalse(prompt.contains("SENSITIVE_ARGUMENT_CONTENT"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectedDiscoveryBeforeGoalCompletionDoesNotTeachAFalseSuccess() {
        val root = Files.createTempDirectory("777-finding-rejected").toFile()
        try {
            val log = LocalSessionEventLogRegistry(root, Json).get("rejected-finding")
            log.append("goal/state", buildJsonObject {
                put("id", "research"); put("revision", 1); put("status", "active")
            })
            val start = log.latestSequence()
            log.append("work/finding", buildJsonObject {
                put("finding_id", "finding-one")
                put("status", "supported")
            })
            log.append("work/finding", buildJsonObject {
                put("finding_id", "finding-one")
                put("status", "rejected")
            })
            log.append("goal/state", buildJsonObject {
                put("id", "research"); put("revision", 2)
                put("status", "completed"); put("startedAtSequence", start)
            })
            val signals = WorkExperienceBootstrap.collect(log,
                LocalGoal("研究结论", status = "completed", id = "research",
                    revision = 2, startedAtSequence = start))
            assertEquals(0, signals.supportedFindings)
            assertFalse(signals.supports("finding_supported"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completedGoalMetricsExcludeLaterGoalsAndTheirToolFailures() {
        val root = Files.createTempDirectory("777-goal-isolation").toFile()
        try {
            val log = LocalSessionEventLogRegistry(root, Json).get("sequential-goals")
            log.append("goal/state", buildJsonObject {
                put("id", "first")
                put("revision", 1)
                put("status", "active")
            })
            val firstStart = log.latestSequence()
            log.append("tool/result", buildJsonObject { put("is_error", false) })
            log.append("goal/state", buildJsonObject {
                put("id", "first")
                put("revision", 2)
                put("status", "completed")
                put("startedAtSequence", firstStart)
            })
            log.append("goal/state", buildJsonObject {
                put("id", "second")
                put("revision", 1)
                put("status", "active")
            })
            log.append("tool/result", buildJsonObject {
                put("is_error", true)
                put("error_code", "UNKNOWN_TOOL")
            })
            log.append("goal/transition-rejected", buildJsonObject {
                put("reason", "second-goal-only")
            })
            val first = LocalGoal(
                "第一个目标", status = "completed", id = "first",
                revision = 2, startedAtSequence = firstStart,
            )
            assertEquals(1 to 0, workGoalToolMetrics(log, first))
            val signals = WorkExperienceBootstrap.collect(log, first)
            assertEquals(0, signals.toolFailures)
            assertEquals(0, signals.toolContractErrors)
            assertEquals(0, signals.goalRejections)
            assertFalse(signals.canReflect)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedGoalWithSourcedFindingCanYieldAReviewCandidateWithoutCopyingDetails() {
        val root = Files.createTempDirectory("777-finding-learning").toFile()
        try {
            val log = LocalSessionEventLogRegistry(root, Json).get("finding-reflection")
            log.append("goal/state", buildJsonObject {
                put("id", "goal-finding"); put("revision", 1); put("status", "active")
            })
            val start = log.latestSequence()
            log.append("work/finding", buildJsonObject {
                put("finding_id", "finding-one")
                put("source_call_id", "read-one")
                put("status", "supported")
                put("summary", "PRIVATE_DISCOVERY_TEXT")
            })
            log.append("goal/state", buildJsonObject {
                put("id", "goal-finding")
                put("revision", 2)
                put("status", "completed")
                put("startedAtSequence", start)
            })
            val signals = WorkExperienceBootstrap.collect(
                log, LocalGoal("深入调查", status = "completed", id = "goal-finding",
                    revision = 2, startedAtSequence = start),
            )
            assertEquals(1, signals.supportedFindings)
            assertTrue(signals.supports("finding_supported"))
            assertTrue(signals.canReflect)
            val prompt = WorkExperienceBootstrap.reviewPrompt("深入调查", signals, true, true)
            assertTrue(prompt.contains("finding_supported: 1"))
            assertFalse(prompt.contains("PRIVATE_DISCOVERY_TEXT"))
            log.append("work/finding", buildJsonObject {
                put("finding_id", "finding-one")
                put("status", "rejected")
            })
            val revised = WorkExperienceBootstrap.collect(
                log, LocalGoal("深入调查", status = "completed", id = "goal-finding",
                    revision = 2, startedAtSequence = start),
            )
            // The completed Goal's event boundary is frozen. Edits after completion
            // do not rewrite the settled evaluation; historical findings are versioned.
            assertEquals(1, revised.supportedFindings)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedSourceFollowThroughCanProposeAReusableSuccessMethod() {
        val root = Files.createTempDirectory("777-source-follow-through").toFile()
        try {
            val log = LocalSessionEventLogRegistry(root, Json).get("follow-through")
            log.append("goal/state", buildJsonObject {
                put("id", "goal-research"); put("revision", 1); put("status", "active")
            })
            val start = log.latestSequence()
            log.append("work/source-observed", buildJsonObject {
                put("source", "src/Agent.kt"); put("kind", "slice")
                put("region", "lines:1-50"); put("has_next", true)
                put("content", "SECRET_SOURCE_CONTENT")
            })
            log.append("work/source-observed", buildJsonObject {
                put("source", "src/Agent.kt"); put("kind", "slice")
                put("region", "lines:51-100"); put("has_next", false)
            })
            log.append("goal/state", buildJsonObject {
                put("id", "goal-research"); put("revision", 2)
                put("status", "completed"); put("startedAtSequence", start)
            })
            val observed = WorkExperienceBootstrap.collect(log, LocalGoal(
                "完整核对来源", id = "goal-research", status = "completed",
                revision = 2, startedAtSequence = start,
            ))
            assertEquals(1, observed.followedSourceCursors)
            assertTrue(observed.supports("source_follow_through"))
            assertTrue(observed.canReflect)
            val prompt = WorkExperienceBootstrap.reviewPrompt("完整核对来源", observed, true, true)
            assertTrue(prompt.contains("source_follow_through: 1"))
            assertFalse(prompt.contains("SECRET_SOURCE_CONTENT"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun repeatingTheSameSourceRangeIsNotAnInvestigativeSuccess() {
        val root = Files.createTempDirectory("777-source-duplicate").toFile()
        try {
            val log = LocalSessionEventLogRegistry(root, Json).get("same-range")
            log.append("goal/state", buildJsonObject {
                put("id", "goal-read"); put("revision", 1); put("status", "active")
            })
            val start = log.latestSequence()
            log.append("work/source-observed", buildJsonObject {
                put("source", "src/Only.kt"); put("kind", "slice")
                put("region", "lines:1-20"); put("has_next", true)
            })
            log.append("work/source-observed", buildJsonObject {
                put("source", "src/Only.kt"); put("kind", "slice")
                put("region", "lines:1-20"); put("has_next", false)
            })
            log.append("goal/state", buildJsonObject {
                put("id", "goal-read"); put("revision", 2)
                put("status", "completed"); put("startedAtSequence", start)
            })
            val observed = WorkExperienceBootstrap.collect(log, LocalGoal(
                "核查重复读取", id = "goal-read", status = "completed",
                revision = 2, startedAtSequence = start,
            ))
            assertEquals(0, observed.followedSourceCursors)
            assertFalse(observed.supports("source_follow_through"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun starterTemplatesClearlyDenyTheyAreVerifiedLessons() {
        val text = WorkExperienceBootstrap.catalogMarkdown()
        assertTrue(text.contains("未验证经验"))
        assertTrue(text.contains("NO_ENTRY"))
        assertTrue(text.contains("google-research/harness2"))
    }
}
