package com.labteto.dshmobile.local.work.improvement

import com.labteto.dshmobile.local.files.LocalWorkspace
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
        assertTrue(verification.contains("暂无合格的策略 A/B 比较"))
        assertTrue(workspace.readRaw("自我改进/03-提示词优化.md").contains("当前没有已验证"))
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
