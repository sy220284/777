package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class GitWorkspaceRuntimeTest {
    @Test fun registeredToolsRequireApprovalAndMergeOnlyTheReviewedTree() = runBlocking {
        val home = kotlin.io.path.createTempDirectory("workspace-tools-").toFile()
        val root = home.resolve("source").apply { mkdirs() }
        val process = AndroidProcessRuntime(defaultWorkingDirectory = root)
        suspend fun git(vararg args: String) {
            val result = process.execute(ProcessRequest(command = listOf("git") + args, timeoutMillis = 5_000))
            assertEquals(result.stderr, 0, result.exitCode)
        }
        try {
            git("init", "-q")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@localhost")
            root.resolve("code.txt").writeText("parent\n")
            git("add", ".")
            git("commit", "-qm", "initial")
            val registry = PluginRegistry()
            registry.install(AndroidRuntimePlugin(root, processRuntime = process))
            val denied = registry.context.tools.execute("workspace_fork", buildJsonObject {}, context = ToolContext(sessionId = "session"))
            assertTrue(denied.isError)
            assertFalse(home.resolve("source-agent-worktrees").exists())
            val context = ToolContext(sessionId = "session", approval = { true }, attributes = mapOf("call_id" to "fork-1"))
            val opened = registry.context.tools.execute("workspace_fork", buildJsonObject {}, context = context)
            assertFalse(opened.content, opened.isError)
            val admission = Json.parseToJsonElement(opened.content).jsonObject
            val id = admission.getValue("workspace_id").jsonPrimitive.content
            val listed = registry.context.tools.execute("workspace_list", buildJsonObject {}, context = ToolContext(sessionId = "session"))
            assertTrue(listed.content.contains(id))
            val foreign = registry.context.tools.execute("workspace_list", buildJsonObject {}, context = ToolContext(sessionId = "foreign"))
            assertFalse(foreign.content.contains(id))
            java.io.File(admission.getValue("root").jsonPrimitive.content, "code.txt").writeText("child\n")
            val status = registry.context.tools.execute("workspace_status", buildJsonObject { put("workspace_id", id) }, context = context)
            val tree = Json.parseToJsonElement(status.content).jsonObject.getValue("tree").jsonPrimitive.content
            assertEquals("parent\n", root.resolve("code.txt").readText())
            withContext(WorkspaceExecutionContext(root, java.io.File(admission.getValue("root").jsonPrimitive.content), id)) {
                val refused = runCatching {
                    registry.context.tools.execute("workspace_merge", buildJsonObject {
                        put("workspace_id", id); put("expected_tree", tree)
                    }, context = context)
                }
                assertTrue(refused.isFailure)
                assertTrue(refused.exceptionOrNull()?.message.orEmpty().contains("父任务"))
            }
            val merged = registry.context.tools.execute("workspace_merge", buildJsonObject {
                put("workspace_id", id); put("expected_tree", tree)
            }, context = context)
            assertFalse(merged.content, merged.isError)
            assertEquals("child\n", root.resolve("code.txt").readText())
        } finally {
            home.deleteRecursively()
        }
    }
}
