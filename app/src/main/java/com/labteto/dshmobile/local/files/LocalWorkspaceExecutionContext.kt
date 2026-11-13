package com.labteto.dshmobile.local.files

import com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext
import java.io.File
import kotlinx.coroutines.currentCoroutineContext

/** Native file tools retain one root-specific observation cache for the entire child execution. */
internal class LocalWorkspaceExecutionContext(
    sourceRoot: File,
    val workspace: LocalWorkspace,
    workspaceId: String,
    val repositoryRoot: File = sourceRoot,
) : WorkspaceExecutionContext(sourceRoot, File(workspace.path), workspaceId) {
    private val prefix = repositoryRoot.canonicalFile.relativeTo(sourceRoot.canonicalFile).invariantSeparatorsPath
        .takeUnless { it == "." }.orEmpty()

    val guidance: String get() = "\n\n当前工作目录为独立 Git worktree：${root.path}。文件工具和本机命令均在此目录执行。" +
        (if (prefix.isEmpty()) "" else "原工作区路径的 $prefix/ 前缀在本目录内读写时须移除。") +
        "成果保留在此目录，由父任务审阅和整合；不得自行改写父目录或删除工作区。"

    fun localPath(sourceRelativePath: String): String {
        if (prefix.isEmpty()) return sourceRelativePath
        require(sourceRelativePath.startsWith("$prefix/")) { "路径不属于成员仓库：$sourceRelativePath" }
        return sourceRelativePath.removePrefix("$prefix/")
    }

    fun coversRepository(sourceRelativePath: String): Boolean = prefix.isNotEmpty() && sourceRelativePath == prefix
}

internal suspend fun executionLocalWorkspace(default: LocalWorkspace): LocalWorkspace =
    (currentCoroutineContext()[WorkspaceExecutionContext] as? LocalWorkspaceExecutionContext)?.workspace ?: default
