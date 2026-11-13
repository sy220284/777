package com.labteto.dshmobile.harness.capability

import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** Trusted execution binding, created by the workspace owner rather than model tool arguments. */
open class WorkspaceExecutionContext(
    val sourceRoot: File,
    val root: File,
    val workspaceId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceExecutionContext>

    fun rootFor(configured: File): File =
        if (configured.canonicalFile == sourceRoot.canonicalFile) root.canonicalFile else configured
}

suspend fun executionWorkspaceRoot(configured: File): File =
    currentCoroutineContext()[WorkspaceExecutionContext]?.rootFor(configured) ?: configured
