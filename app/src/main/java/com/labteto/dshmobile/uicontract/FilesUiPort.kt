package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.files.LocalWorkspaceDirectoryEntry
import com.labteto.dshmobile.local.files.LocalWorkspaceDirectoryPage
import com.labteto.dshmobile.local.files.LocalWorkspaceSearchPage
import com.labteto.dshmobile.local.session.LocalConversationFiles
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview

/** Native read capability. Path resolution and read budgets belong to the existing file owner. */
interface FilesUiPort {
    suspend fun workspaceFiles(): List<LocalWorkspaceFile>
    suspend fun workspaceDirectory(path: String): List<LocalWorkspaceDirectoryEntry>
    suspend fun workspaceDirectoryPage(path: String, offset: Int): LocalWorkspaceDirectoryPage
    suspend fun searchWorkspaceFiles(query: String): List<LocalWorkspaceFile>
    suspend fun searchWorkspacePage(query: String, offset: Int): LocalWorkspaceSearchPage
    suspend fun conversationFiles(sessionId: String): LocalConversationFiles
    suspend fun previewWorkspaceFile(path: String): LocalWorkspaceFilePreview

    /** User-granted SAF destination; the Owner alone validates and streams a workspace-local file. */
    suspend fun exportWorkspaceFile(path: String, output: java.io.OutputStream): Long =
        throw UnsupportedOperationException("Workspace export not supported by this Owner")
}
