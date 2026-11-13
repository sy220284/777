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
    private val store = workspaceRoot.canonicalFile.let { File(it.parentFile, it.name + "-agent-worktrees") }
    /** Native UI reuses the registered, read-only remote Git comparison engine. */
    suspend fun remoteTrackingBranches(repository: String): List<String> =
        GitRemoteDiff(workspaceRoot, processRuntime).trackingBranches(repository)

    suspend fun compareLocal(repository: String, includeUntracked: Boolean = false): JsonObject =
        GitRemoteDiff(workspaceRoot, processRuntime).compareUncommitted(repository, includeUntracked)

    suspend fun compareRemote(repository: String, remote: String, branch: String): JsonObject =
        GitRemoteDiff(workspaceRoot, processRuntime).compare(repository, remote, branch)

    suspend fun compareCommit(repository: String, commit: String): JsonObject =
        GitRemoteDiff(workspaceRoot, processRuntime).compareCommit(repository, commit)


    suspend fun fork(sessionId: String, invocationId: String, repository: String = "."): JsonObject {
        val id = MessageDigest.getInstance("SHA-256").digest("$sessionId\u0000$invocationId".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        return operate(sessionId, "fork", buildJsonObject {
            put("workspace_id", id)
            put("repository", repository)
            put("invocation_id", invocationId)
        })
    }

    suspend fun operate(sessionId: String, action: String, arguments: JsonObject): JsonObject {
        require(sessionId.isNotBlank()) { "工作区操作必须绑定原会话" }
        val script = requireNotNull(GitWorkspaceRuntime::class.java.getResourceAsStream("/com/labteto/dshmobile/runtime/git_workspace.py")) {
            "安装包缺少 Git 工作区执行代码"
        }.bufferedReader().use { it.readText() }
        val result = processRuntime.executeWorkspaceOperation(ProcessRequest(
            command = listOf("python3", "-P", "-s", "-u", "-c", script, workspaceRoot.canonicalPath,
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
        for (action in listOf("fork", "list", "status", "compare_merge", "preview_merge", "merge", "discard")) {
            val name = "workspace_$action"
            val mutates = action !in setOf("status", "compare_merge", "preview_merge", "list")
            val properties = buildJsonObject {
                if (action == "fork") put("repository", buildJsonObject {
                    put("type", "string"); put("description", "工作区内 Git 仓库根目录，默认 .；包含当前未提交及未跟踪文件作为起点")
                }) else if (action != "list") put("workspace_id", buildJsonObject { put("type", "string") })
                if (action in setOf("compare_merge", "preview_merge", "merge", "discard")) put("expected_tree", buildJsonObject {
                    put("type", "string"); put("description", "最近 workspace_status 返回的 tree；变化后必须重新审阅")
                })
                if (action in setOf("preview_merge", "merge")) {
                    put("resolution", buildJsonObject {
                        put("type", "string")
                        put("enum", kotlinx.serialization.json.JsonArray(listOf("auto", "prefer_parent", "prefer_child", "select").map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        put("description", "经质量比较选择自动合并、保留父实现、采用子实现或逐文件择优")
                    })
                    put("path_choices", buildJsonObject {
                        put("type", "object")
                        put("description", "select 模式下，每个父子共同改动文件明确选择 parent 或 child；保留双方其余独立改动")
                        put("additionalProperties", buildJsonObject {
                            put("type", "string")
                            put("enum", kotlinx.serialization.json.JsonArray(listOf("parent", "child").map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        })
                    })
                }
                if (action == "merge") {
                    put("expected_source_tree", buildJsonObject {
                        put("type", "string"); put("description", "workspace_preview_merge 返回的 source_tree，锁定父工作区版本")
                    })
                    put("expected_merged_tree", buildJsonObject {
                        put("type", "string"); put("description", "workspace_preview_merge 返回的 merged_tree，锁定最终合并结果")
                    })
                    put("quality_decision", buildJsonObject {
                        put("type", "object")
                        put("description", "本次择优依据：resolution、reason、criteria（correctness/experience/maintainability）、reviewed_both；测试另按真实工具回执验证")
                    })
                }
            }
            val description = when (action) {
                "fork" -> "创建真实独立 Git worktree；保留父目录、HEAD 和暂存区，返回工作目录及基线。目录隔离不构成独立操作系统权限边界"
                "status" -> "检查本会话工作区相对基线的全部变更及 tree 指纹，含未提交、未跟踪和二进制文件"
                "compare_merge" -> "只读提供原始基线到父子实现的真实补丁、共同改动路径及各自独立修改，供功能质量与体验比较"
                "preview_merge" -> "只读预览 Git 自动合并、父实现优先、子实现优先或逐文件择优的精确结果；冲突或超限须继续审查"
                "list" -> "列出本会话工作区及创建/删除状态；找回取消或中断时未返回的 workspace_id 与基线"
                "merge" -> "基于三方质量决策与精确预览执行择优合并；版本变化、缺少比较理由或超限则拒绝，保留父目录 HEAD 和暂存区"
                else -> "确认 tree 未变化后删除本会话独立 worktree；保留父目录和删除回执"
            }
            context.tools.register(HarnessTool(
                name = name,
                schema = functionToolSchema(name, description, properties, required = when (action) {
                    "fork", "list" -> emptySet()
                    "status" -> setOf("workspace_id")
                    "compare_merge", "preview_merge", "discard" -> setOf("workspace_id", "expected_tree")
                    else -> setOf("workspace_id", "expected_tree", "expected_source_tree", "expected_merged_tree", "resolution", "quality_decision")
                }),
                access = if (mutates) ToolAccess.WORKSPACE_WRITE else ToolAccess.READ_ONLY,
                approvalPolicy = if (mutates) ToolApprovalPolicy.ALWAYS else ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL, timeoutMillis = 125_000L,
                metadata = ToolMetadata(family = "文件", environmentBound = true, discoveryKeywords = setOf("worktree", "工作区", "隔离", "合并", "git"),
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
                    val outcome = response["state"]?.jsonPrimitive?.contentOrNull
                    val incomplete = outcome in setOf("conflict", "rolled_back", "needs_inspection", "not_started", "needs_full_review")
                    ToolResult(response.toString(), isError = incomplete,
                        errorCode = if (incomplete) "WORKSPACE_INTEGRATION_INCOMPLETE" else null,
                        recoveryHint = when (outcome) {
                            "needs_full_review" -> "合并补丁超出完整预览限制，未生成可用合并凭据；需要完整检查后再决定修改。"
                            "conflict" -> "父子工作区修改冲突；先解决冲突并重新生成预览。"
                            "rolled_back", "needs_inspection", "not_started" ->
                                "检查 workspace_status 的 integration；已回滚不等于已合并，外部新修改会保留。"
                            else -> null
                        })
                },
            ))
        }
    }

    internal fun uninstall(context: HarnessContext) {
        listOf("fork", "list", "status", "compare_merge", "preview_merge", "merge", "discard").forEach { context.tools.unregister("workspace_$it") }
    }
}
