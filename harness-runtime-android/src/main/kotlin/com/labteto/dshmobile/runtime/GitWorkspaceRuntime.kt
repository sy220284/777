package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.ToolWorkspaceAccess
import com.labteto.dshmobile.harness.tools.functionToolSchema
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Git owns branches/objects/worktrees; the immutable admission record owns workspace provenance. */
class GitWorkspaceRuntime(
    private val workspaceRoot: File,
    private val processRuntime: AndroidProcessRuntime,
) {
    private val store = File(workspaceRoot.parentFile, workspaceRoot.name + "-agent-worktrees")

    suspend fun fork(sessionId: String, invocationId: String, repository: String = "."): JsonObject {
        val id = MessageDigest.getInstance("SHA-256").digest("$sessionId\u0000$invocationId".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        return operate(sessionId, "fork", buildJsonObject {
            put("workspace_id", id)
            put("repository", repository)
        })
    }

    suspend fun operate(sessionId: String, action: String, arguments: JsonObject): JsonObject {
        require(sessionId.isNotBlank()) { "工作区操作必须绑定原会话" }
        val script = requireNotNull(GitWorkspaceRuntime::class.java.getResourceAsStream("/com/labteto/dshmobile/runtime/git_workspace.py")) {
            "安装包缺少 Git 工作区执行代码"
        }.bufferedReader().use { it.readText() }
        val result = processRuntime.execute(ProcessRequest(
            command = listOf("python3", "-u", "-c", script, workspaceRoot.canonicalPath,
                store.canonicalPath, sessionId, action),
            workingDirectory = workspaceRoot.canonicalPath,
            stdin = arguments.toString(), timeoutMillis = 120_000L,
        ))
        check(!result.timedOut) { "工作区操作超时，需先检查实际状态，不能直接认定未执行" }
        val response = runCatching { Json.parseToJsonElement(result.stdout).jsonObject }.getOrElse {
            error("工作区操作未返回完整回执：${result.stderr.take(2_000)}")
        }
        check(result.exitCode == 0) {
            response["error"]?.jsonPrimitive?.contentOrNull ?: "工作区操作失败：${result.stderr.take(2_000)}"
        }
        return response
    }

    internal fun install(context: HarnessContext) {
        for (action in listOf("fork", "status", "merge", "discard")) {
            val name = "workspace_$action"
            val mutates = action != "status"
            val properties = buildJsonObject {
                if (action == "fork") put("repository", buildJsonObject {
                    put("type", "string"); put("description", "工作区内 Git 仓库根目录，默认 .；包含当前未提交及未跟踪文件作为起点")
                }) else put("workspace_id", buildJsonObject { put("type", "string") })
                if (action in setOf("merge", "discard")) put("expected_tree", buildJsonObject {
                    put("type", "string"); put("description", "最近 workspace_status 返回的 tree；变化后必须重新审阅")
                })
            }
            val description = when (action) {
                "fork" -> "创建真实独立 Git worktree；保留父目录、HEAD 和暂存区，返回工作目录及基线。目录隔离不构成独立操作系统权限边界"
                "status" -> "检查本会话工作区相对基线的全部变更及 tree 指纹，含未提交、未跟踪和二进制文件"
                "merge" -> "三方合并已审阅工作区与父目录后续变更；冲突时不改父目录，成功时保留父目录 HEAD 和暂存区"
                else -> "确认 tree 未变化后删除本会话独立 worktree；保留父目录和删除回执"
            }
            context.tools.register(HarnessTool(
                name = name,
                schema = functionToolSchema(name, description, properties, required = when (action) {
                    "fork" -> emptySet()
                    "status" -> setOf("workspace_id")
                    else -> setOf("workspace_id", "expected_tree")
                }),
                access = if (mutates) ToolAccess.WORKSPACE_WRITE else ToolAccess.READ_ONLY,
                approvalPolicy = if (mutates) ToolApprovalPolicy.ALWAYS else ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL, timeoutMillis = 125_000L,
                metadata = ToolMetadata(family = "文件", discoveryKeywords = setOf("worktree", "工作区", "隔离", "合并", "git"),
                    workspaceAccess = if (mutates) ToolWorkspaceAccess.WRITE else ToolWorkspaceAccess.READ),
                executor = HarnessToolExecutor { toolContext, input, _ ->
                    currentCoroutineContext()[com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext]?.let { scope ->
                        require(action == "status" && input["workspace_id"]?.jsonPrimitive?.contentOrNull == scope.workspaceId) {
                            "子代理只能检查自身工作区；合并、创建和删除须由父任务执行"
                        }
                    }
                    val session = requireNotNull(toolContext.sessionId) { "工作区操作缺少会话上下文" }
                    val response = if (action == "fork") fork(session,
                        toolContext.attributes["call_id"] as? String ?: UUID.randomUUID().toString(),
                        input["repository"]?.jsonPrimitive?.contentOrNull ?: ".",
                    ) else operate(session, action, input)
                    ToolResult(response.toString(), isError = response["state"]?.jsonPrimitive?.contentOrNull == "conflict")
                },
            ))
        }
    }

    internal fun uninstall(context: HarnessContext) {
        listOf("fork", "status", "merge", "discard").forEach { context.tools.unregister("workspace_$it") }
    }
}
