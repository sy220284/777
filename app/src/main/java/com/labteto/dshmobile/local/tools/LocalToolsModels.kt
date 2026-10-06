package com.labteto.dshmobile.local.tools



/** One real file currently present in the app-private local Harness workspace. */
data class LocalWorkspaceFile(
    val path: String,
    val bytes: Long,
    val modifiedAt: Long,
)

/** Lightweight local preview; binary files remain visible without forcing them through UTF-8. */
data class LocalWorkspaceFilePreview(
    val file: LocalWorkspaceFile,
    val text: String? = null,
    val truncated: Boolean = false,
)
