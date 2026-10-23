package com.labteto.dshmobile.local.work.improvement

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.session.LocalSessionEventLogRegistry
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkImprovementArchiveTest {
    @Test
    fun createsSevenWorkspaceDocumentsAndPreservesUserNotesAcrossRefreshes() = withArchive { workspace, logs, archive ->
        archive.ensureInitialized()
        val names = workspace.files().map { it.path }.filter { it.startsWith("自我改进/") }
        assertEquals(7, names.size)
        workspace.write("自我改进/06-我的备注.md", "# 我的意见\n请优先修复工作区体验。\n")
        archive.refresh()
        assertEquals("# 我的意见\n请优先修复工作区体验。\n",
            workspace.readRaw("自我改进/06-我的备注.md"))
        assertTrue(workspace.readRaw("自我改进/00-自我改进总览.md").contains("Work 已结算回合：0"))
    }

    @Test
    fun onlyReportsGroundedWorkFactsAndDoesNotClaimUnimplementedLearning() = withArchive { workspace, logs, archive ->
        val log = logs.get("one")
        log.append("agent/run-checkpoint", buildJsonObject { put("mode", "chat") })
        log.append("turn/end", buildJsonObject { put("reason", "error") })
        log.append("agent/run-checkpoint", buildJsonObject { put("mode", "work") })
        log.append("turn/end", buildJsonObject { put("reason", "completed") })
        log.append("tool/result", buildJsonObject { put("is_error", false) })
        log.append("tool/result", buildJsonObject { put("is_error", true) })
        log.append("goal/state", buildJsonObject {
            put("id", "goal-one"); put("revision", 2); put("status", "completed")
            put("artifact_tool_call_id", "receipt-123")
        })
        log.append("goal/state", buildJsonObject {
            put("id", "goal-one"); put("revision", 2); put("status", "completed")
            put("artifact_tool_call_id", "receipt-123")
        })
        log.append("work/output-quality", buildJsonObject {
            put("findings", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("Sensitive prompt text should not be copied"))
            })
        })
        archive.refresh()
        val summary = workspace.readRaw("自我改进/00-自我改进总览.md")
        val problems = workspace.readRaw("自我改进/04-问题与迭代.md")
        val verification = workspace.readRaw("自我改进/05-验证与成效.md")
        assertTrue(summary.contains("Work 已结算回合：1"))
        assertTrue(summary.contains("完成状态已提交的 Goal：1"))
        assertTrue(summary.contains("其中有成果或测试执行凭据的 Goal：1"))
        assertTrue(summary.contains("Work 工具结果：2；其中错误结果：1"))
        assertTrue(problems.contains("发现 1 条冲突"))
        assertFalse(problems.contains("Sensitive prompt text"))
        assertTrue(verification.contains("暂无合格对照"))
        assertTrue(workspace.readRaw("自我改进/03-提示词优化.md").contains("暂无候选"))
    }

    @Test
    fun subsequentRefreshUpdatesFromNewEventsWithoutReplayingThem() = withArchive { workspace, logs, archive ->
        val log = logs.get("second")
        log.append("agent/run-checkpoint", buildJsonObject { put("mode", "work") })
        log.append("turn/end", buildJsonObject { put("reason", "step_limit") })
        archive.refresh()
        assertTrue(workspace.readRaw("自我改进/04-问题与迭代.md").contains("step_limit"))
        log.append("turn/end", buildJsonObject { put("reason", "completed") })
        archive.refresh()
        assertTrue(workspace.readRaw("自我改进/00-自我改进总览.md").contains("Work 已结算回合：2"))
        archive.refresh()
        assertTrue(workspace.readRaw("自我改进/00-自我改进总览.md").contains("Work 已结算回合：2"))
    }

    @Test
    fun matchedEvidenceCandidateIsTrialedOnceThenAdoptedWithinExactGoalScope() = withArchive { workspace, logs, archive ->
        val source = logs.get("baseline-session")
        source.append("work/improvement-candidate", buildJsonObject {
            put("id", "improvement-1")
            put("source_run_id", "baseline-run")
            put("goal_signature", workGoalComparisonKey(LocalGoal(
                description = "审计代码", id = "source",
                artifactPaths = listOf("report.md"), verificationCommands = listOf("test")
            )))
            put("goal_id", "source")
            put("goal_revision", 2)
            put("project_id", "project-alpha")
            put("profile_id", "profile-a")
            put("model", "test-model")
            put("category", "prompt")
            put("cue", "代码审计")
            put("guidance", "先确认依赖关系与实际测试结果，再执行代码修改。")
            put("reason", "避免返工")
            put("baseline_tool_calls", 5)
            put("baseline_tool_errors", 0)
            put("baseline_test_verified", true)
        })
        archive.refresh()
        val goal = LocalGoal("审计代码", "active", id = "different-goal",
            artifactPaths = listOf("report.md"), verificationCommands = listOf("test"))
        val advice = archive.adviceFor(goal, "project-alpha", "test-model", "profile-a")
        assertEquals("improvement-1", advice?.id)
        assertFalse(advice!!.active)
        assertEquals(null, archive.adviceFor(goal, "wrong-project", "test-model", "profile-a"))
        assertEquals(null, archive.adviceFor(goal.copy(verificationCommands = listOf("different-test")),
            "project-alpha", "test-model", "profile-a"))
        val trial = logs.get("trial-session")
        trial.append("work/improvement-trial", buildJsonObject { put("candidate_id", "improvement-1") })
        archive.refresh()
        assertEquals(null, archive.adviceFor(goal, "project-alpha", "test-model", "profile-a"))
        trial.append("work/improvement-adopted", buildJsonObject { put("candidate_id", "improvement-1") })
        archive.refresh()
        assertTrue(archive.adviceFor(goal, "project-alpha", "test-model", "profile-a")!!.active)
        assertTrue(workspace.readRaw("自我改进/03-提示词优化.md").contains("已采纳的动态工作指导"))
        trial.append("work/improvement-retired", buildJsonObject { put("candidate_id", "improvement-1") })
        archive.refresh()
        assertEquals(null, archive.adviceFor(goal, "project-alpha", "test-model", "profile-a"))
    }

    private fun withArchive(
        test: (LocalWorkspace, LocalSessionEventLogRegistry, LocalWorkImprovementArchive) -> Unit,
    ) {
        val root = Files.createTempDirectory("777-improvement-archive").toFile()
        try {
            val sessionRoot = root.resolve("sessions").apply { mkdirs() }
            val workspace = LocalWorkspace(root.resolve("workspace").apply { mkdirs() })
            val logs = LocalSessionEventLogRegistry(sessionRoot, Json)
            test(workspace, logs, LocalWorkImprovementArchive(workspace, logs, sessionRoot))
        } finally {
            root.deleteRecursively()
        }
    }
}
