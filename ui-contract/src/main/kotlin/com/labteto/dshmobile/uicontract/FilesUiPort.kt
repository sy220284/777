package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.session.LocalConversationFiles
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview

/** Native read capability. Path resolution and read budgets belong to the existing file owner. */
interface FilesUiPort {
    suspend fun workspaceFiles(): List<LocalWorkspaceFile>
    suspend fun conversationFiles(sessionId: String): LocalConversationFiles
    suspend fun previewWorkspaceFile(path: String): LocalWorkspaceFilePreview
}
