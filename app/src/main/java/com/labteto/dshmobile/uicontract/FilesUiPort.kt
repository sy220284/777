package com.labteto.dshmobile.uicontract

import com.labteto.dshmobile.local.files.LocalWorkspaceDirectoryEntry
import com.labteto.dshmobile.local.files.LocalWorkspaceDirectoryPage
import com.labteto.dshmobile.local.files.LocalWorkspaceSearchPage
import com.labteto.dshmobile.local.files.LocalWorkspaceFileChange
import com.labteto.dshmobile.local.session.LocalConversationFiles
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview

/** Public read-only view of a locally published file; publishing ownership stays internal. */
data class UiPublishedDownload(val location: String, val uri: android.net.Uri, val currentVersion: Boolean)

/** Native read capability. Path resolution and read budgets belong to the existing file owner. */
interface FilesUiPort {
    suspend fun workspaceFiles(): List<LocalWorkspaceFile>
    suspend fun workspaceDirectory(path: String): List<LocalWorkspaceDirectoryEntry>
    suspend fun workspaceDirectoryPage(path: String, offset: Int): LocalWorkspaceDirectoryPage
    suspend fun searchWorkspaceFiles(query: String): List<LocalWorkspaceFile>
    suspend fun searchWorkspacePage(query: String, offset: Int): LocalWorkspaceSearchPage
    suspend fun searchSidebarWorkspacePage(query: String, offset: Int): LocalWorkspaceSearchPage
    suspend fun stopWorkspaceSearch(query: String): Boolean
    fun observeWorkspaceFiles(paths: List<String>): kotlinx.coroutines.flow.Flow<LocalWorkspaceFileChange>
    suspend fun conversationFiles(sessionId: String): LocalConversationFiles
    suspend fun previewWorkspaceFile(path: String): LocalWorkspaceFilePreview
    suspend fun previewWorkspaceFilePage(path: String, offset: Long): LocalWorkspaceFilePreview
    suspend fun importWorkspaceFile(uri: android.net.Uri): String
    suspend fun saveWorkspaceFileToDownloads(path: String): String
    suspend fun exportWorkspaceArchive(directory: String, destination: android.net.Uri): Int
    suspend fun exportWorkspaceFile(path: String, output: java.io.OutputStream): Long
    suspend fun publishedWorkspaceSnapshot(path: String): UiPublishedDownload?

}
