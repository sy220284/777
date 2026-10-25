package com.labteto.dshmobile.local.files

import com.labteto.dshmobile.harness.capability.WorkspaceExecutionContext
import java.io.File
import kotlinx.coroutines.currentCoroutineContext

/** Native file tools retain one root-specific observation cache for the entire child execution. */
internal class LocalWorkspaceExecutionContext(
    sourceRoot: File,
    val workspace: LocalWorkspace,
    workspaceId: String,
) : WorkspaceExecutionContext(sourceRoot, File(workspace.path), workspaceId)

internal suspend fun executionLocalWorkspace(default: LocalWorkspace): LocalWorkspace =
    (currentCoroutineContext()[WorkspaceExecutionContext] as? LocalWorkspaceExecutionContext)?.workspace ?: default
