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
import com.labteto.dshmobile.harness.tools.functionToolSchema
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Diff against a locally verified remote-tracking branch. Never fetches remotely, modifies
 * the index or runs user-supplied commands; all subprocess argument boundaries are explicit.
 */
internal class GitRemoteDiff(
    private val workspaceRoot: File,
    private val processRuntime: AndroidProcessRuntime,
) {
    internal fun install(context: HarnessContext) {
        installLocalDiff(context)
        context.tools.register(
            HarnessTool(
                name = "git_diff_remote",
                schema = functionToolSchema(
                    "git_diff_remote",
                    "对比已存在的远端跟踪分支与当前 Git 工作树的已跟踪文件；不联网、不修改暂存区",
                    buildJsonObject {
                        put("repository", buildJsonObject { put("type", "string") })
                        put("remote", buildJsonObject { put("type", "string") })
                        put("branch", buildJsonObject { put("type", "string") })
                    },
                ),
                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.OPTIONAL,
                timeoutMillis = 30_000L,
                metadata = ToolMetadata(
                    family = "文件",
                    environmentBound = true,
                    discoveryKeywords = setOf("git", "diff", "远端", "分支", "差异"),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val repository = input["repository"]?.jsonPrimitive?.contentOrNull ?: "."
                    val remote = input["remote"]?.jsonPrimitive?.contentOrNull ?: "origin"
                    val branch = input["branch"]?.jsonPrimitive?.contentOrNull ?: "main"
                    try {
                        val result = compare(repository, remote, branch)
                        ToolResult(result.toString())
                    } catch (invalid: IllegalArgumentException) {
                        ToolResult(
                            content = "远端 Git 比较请求无效：" + invalid.message.orEmpty(),
                            isError = true,
                            errorCode = "INVALID_GIT_DIFF_REQUEST",
                            retryable = false,
                            recoveryHint = "检查仓库目录和已登记远端分支后重试。",
                        )
                    } catch (unavailable: IllegalStateException) {
                        ToolResult(
                            content = "远端 Git 比较失败：" + unavailable.message.orEmpty().take(512),
                            isError = true,
                            errorCode = "GIT_DIFF_UNAVAILABLE",
                            retryable = false,
                            recoveryHint = "先确认本地仓库与 refs/remotes/<远端>/<分支> 已存在。",
                        )
                    }
                },
            ),
        )
    }

    /** A second source for Codex-style reviews: staged and unstaged tracked files. */
    internal fun installLocalDiff(context: HarnessContext) {
        context.tools.register(HarnessTool(
            name = "git_diff_uncommitted",
            schema = functionToolSchema("git_diff_uncommitted",
                "查看当前工作区已跟踪文件的暂存与未暂存修改；只读、无需远端分支",
                buildJsonObject { put("repository", buildJsonObject { put("type", "string") }) }),
            access = ToolAccess.READ_ONLY, approvalPolicy = ToolApprovalPolicy.NEVER,
            exposure = ToolExposure.OPTIONAL, timeoutMillis = 30_000L,
            metadata = ToolMetadata(family = "文件", environmentBound = true,
                discoveryKeywords = setOf("git", "未提交", "本地修改", "差异", "代码审查")),
            executor = HarnessToolExecutor { _, input, _ ->
                val repository = input["repository"]?.jsonPrimitive?.contentOrNull ?: "."
                try {
                    ToolResult(compareUncommitted(repository).toString())
                } catch (failure: IllegalArgumentException) {
                    ToolResult("本地 Git 修改请求无效：" + failure.message.orEmpty(),
                        isError = true, errorCode = "INVALID_GIT_DIFF_REQUEST")
                } catch (failure: IllegalStateException) {
                    ToolResult("读取本地 Git 修改失败：" + failure.message.orEmpty().take(512),
                        isError = true, errorCode = "GIT_DIFF_UNAVAILABLE")
                }
            },
        ))
    }

    internal fun uninstall(context: HarnessContext) {
        context.tools.unregister("git_diff_remote")
        context.tools.unregister("git_diff_uncommitted")
    }

    /** Return only genuine local tracking refs; no implicit network requests. */
    internal suspend fun trackingBranches(repository: String): List<String> {
        val target = repositoryDirectory(repository)
        verifiedRepositoryRoot(target)
        return runGit(target, "for-each-ref", "--format=%(refname:short)", "refs/remotes")
            .lineSequence().map(String::trim)
            .filter { it.isNotEmpty() && '/' in it }
            .filter { ref ->
                val remote = ref.substringBefore('/')
                val branch = ref.substringAfter('/')
                branch != "HEAD" && validGitDiffRemoteName(remote) && validGitDiffBranchName(branch)
            }.distinct().take(100).toList()
    }

    private fun repositoryDirectory(repository: String): File {
        require(repository.isNotBlank() && !File(repository).isAbsolute) {
            "仓库路径必须为工作区内的相对路径"
        }
        val workspace = workspaceRoot.canonicalFile
        val target = File(workspace, repository).canonicalFile
        require(target == workspace || target.toPath().startsWith(workspace.toPath())) {
            "拒绝访问工作区之外的 Git 仓库"
        }
        require(target.isDirectory) { "Git 仓库目录不存在" }
        return target
    }

    private suspend fun verifiedRepositoryRoot(target: File): File {
        val workspace = workspaceRoot.canonicalFile
        val root = File(runGit(target, "rev-parse", "--show-toplevel")).canonicalFile
        require(root == workspace || root.toPath().startsWith(workspace.toPath())) {
            "Git 仓库根目录越过授权工作区"
        }
        return root
    }

    private suspend fun runGit(target: File, vararg args: String): String {
        val result = processRuntime.execute(ProcessRequest(
            command = listOf("git", "-c", "core.fsmonitor=false", "-C", target.path) + args,
            workingDirectory = target.path, timeoutMillis = 20_000L,
        ))
        check(!result.timedOut) { "Git 查询超时" }
        check(result.exitCode == 0) { "Git 查询失败：" + result.stderr.take(480) }
        return result.stdout.trimEnd()
    }

    /** Stable, versioned local diff; no git add, fetch, checkout, or untracked file inclusion. */
    internal suspend fun compareUncommitted(repository: String): JsonObject {
        val target = repositoryDirectory(repository)
        val root = verifiedRepositoryRoot(target)
        val headCommit = runGit(target, "rev-parse", "--verify", "HEAD^{commit}").trim()
        require(headCommit.matches(Regex("[0-9a-f]{40,64}"))) { "Git HEAD 版本异常" }
        val stat = runGit(target, "diff", "--no-ext-diff", "--no-textconv", "--stat", "HEAD", "--")
        val patch = runGit(target, "diff", "--no-ext-diff", "--no-textconv", "--unified=3", "HEAD", "--")
        val untracked = runGit(target, "ls-files", "--others", "--exclude-standard", "--")
            .lineSequence().count { it.isNotBlank() }
        val cap = 128 * 1024
        return buildJsonObject {
            put("repository", root.path)
            put("tracking_ref", "worktree/" + headCommit)
            put("head_commit", headCommit)
            put("stat", stat.take(12_000))
            put("patch", patch.take(cap))
            put("truncated", patch.length > cap)
            put("includes_tracked_worktree_changes", true)
            put("includes_untracked_files", false)
            put("untracked_file_count", untracked)
            put("network_requested", false)
        }
    }

    internal suspend fun compare(repository: String, remote: String, branch: String): JsonObject {
        require(validGitDiffRemoteName(remote) && validGitDiffBranchName(branch)) {
            "远端名称或分支包含无效字符"
        }
        val target = repositoryDirectory(repository)
        val root = verifiedRepositoryRoot(target)
        val trackingRef = "refs/remotes/" + remote + "/" + branch
        val trackedCommit = runGit(target, "rev-parse", "--verify", "--end-of-options", trackingRef + "^{commit}").trim()
        val headCommit = runGit(target, "rev-parse", "--verify", "HEAD^{commit}").trim()
        val base = runGit(target, "merge-base", headCommit, trackedCommit).trim()
        require(base.matches(Regex("[0-9a-f]{40,64}"))) { "Git 合并基线异常" }
        val stat = runGit(target, "diff", "--no-ext-diff", "--no-textconv", "--stat", base, "--")
        val patch = runGit(target, "diff", "--no-ext-diff", "--no-textconv", "--unified=3", base, "--")
        val cap = 128 * 1024
        return buildJsonObject {
            put("repository", root.path)
            put("tracking_ref", trackingRef)
            put("tracking_commit", trackedCommit)
            put("head_commit", headCommit)
            put("merge_base", base)
            put("stat", stat.take(12_000))
            put("patch", patch.take(cap))
            put("truncated", patch.length > cap)
            put("includes_tracked_worktree_changes", true)
            put("includes_untracked_files", false)
            put("network_requested", false)
        }
    }
}

internal fun validGitDiffRemoteName(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) &&
        !value.startsWith("-") && value != "." && value != ".."

internal fun validGitDiffBranchName(value: String): Boolean =
    value.length in 1..120 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")) &&
        value.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
        !value.contains("..") && !value.endsWith('.') && !value.endsWith(".lock")
