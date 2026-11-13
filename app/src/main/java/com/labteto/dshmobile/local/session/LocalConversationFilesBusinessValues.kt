package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.tools.LocalWorkspaceFile

/** Current-session file projection derived from its durable tool event log. */
data class LocalConversationFiles(
    val artifacts: List<LocalWorkspaceFile> = emptyList(),
    val involved: List<LocalWorkspaceFile> = emptyList(),
) {
    val isEmpty: Boolean get() = artifacts.isEmpty() && involved.isEmpty()
}
