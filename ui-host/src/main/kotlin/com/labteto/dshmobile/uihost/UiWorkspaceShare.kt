package com.labteto.dshmobile.uihost

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.ArrayList

const val MAX_LOCAL_SHARE_FILES = 100

/** Do not expose app-owned configuration or paths outside the workspace to share targets. */
fun isShareableWorkspacePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path) return false
    val segments = path.split('/')
    return segments.all { it.isNotBlank() && it != "." && it != ".." } &&
        segments.first() != ".adsh"
}

/** Verify every chosen file again immediately before sending an Android share intent. */
fun resolveLocalWorkspaceShareFiles(root: File, paths: List<String>): List<File> {
    require(paths.isNotEmpty()) { "share_selection_empty" }
    require(paths.size <= MAX_LOCAL_SHARE_FILES) { "share_selection_limit" }
    val canonicalRoot = root.canonicalFile
    require(canonicalRoot.isDirectory) { "share_workspace_missing" }
    val resolved = paths.distinct().map { path ->
        require(isShareableWorkspacePath(path)) { "share_invalid_relative_path" }
        val requested = File(canonicalRoot, path)
        val actual = requested.canonicalFile
        require(actual.toPath().startsWith(canonicalRoot.toPath()) &&
            actual.toPath() == requested.toPath().normalize()
        ) { "share_outside_workspace" }
        require(actual.isFile && actual.canRead()) { "share_file_missing" }
        actual
    }
    return resolved
}

fun localWorkspaceShareMime(mimes: List<String>): String {
    if (mimes.isEmpty()) return "application/octet-stream"
    if (mimes.distinct().size == 1) return mimes.first()
    val groups = mimes.map { it.substringBefore('/', "") }.distinct()
    return if (groups.size == 1 && groups.first() in setOf("image", "video", "audio", "text")) {
        "${groups.first()}/*"
    } else "*/*"
}

private fun shareMime(file: File): String {
    val extension = file.extension.lowercase()
    return when (extension) {
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "pdf" -> "application/pdf"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
}

/**
 * Host-owned platform share operation. The caller supplies a user selection; Host alone
 * resolves the app workspace, issues URI read grants and launches the native chooser.
 * Opening the chooser does not claim the receiving app accepted or persisted the file.
 */
suspend fun shareUiWorkspaceFiles(
    context: Context, workspacePath: String, selectedPaths: List<String>, chooserTitle: String,
) {
    val send = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        createLocalWorkspaceShareIntent(context, workspacePath, selectedPaths)
    }
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }
}

/** Uses the existing non-exported FileProvider; only explicitly selected URIs get read grants. */
fun createLocalWorkspaceShareIntent(
    context: Context,
    workspacePath: String,
    selectedPaths: List<String>,
): Intent {
    val appWorkspace = File(context.filesDir, "local-harness/workspace").canonicalFile
    require(workspacePath.isNotBlank() && File(workspacePath).canonicalFile == appWorkspace) {
        "share_workspace_changed"
    }
    val files = resolveLocalWorkspaceShareFiles(appWorkspace, selectedPaths)
    val uris = files.map { file ->
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val type = localWorkspaceShareMime(files.map(::shareMime))
    val clip = ClipData.newRawUri(files.first().name, uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        this.type = type
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = clip
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
    }
}
