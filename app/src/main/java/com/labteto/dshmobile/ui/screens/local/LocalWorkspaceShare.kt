package com.labteto.dshmobile.ui.screens.local

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.ArrayList

internal const val MAX_LOCAL_SHARE_FILES = 100

/** Do not expose app-owned configuration or paths outside the workspace to share targets. */
internal fun isShareableWorkspacePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path) return false
    val segments = path.split('/')
    return segments.all { it.isNotBlank() && it != "." && it != ".." } &&
        segments.first() != ".adsh"
}

/** Verify every chosen file again immediately before sending an Android share intent. */
internal fun resolveLocalWorkspaceShareFiles(root: File, paths: List<String>): List<File> {
    require(paths.isNotEmpty()) { "请先选择文件" }
    require(paths.size <= MAX_LOCAL_SHARE_FILES) { "单次最多分享 $MAX_LOCAL_SHARE_FILES 个文件" }
    val canonicalRoot = root.canonicalFile
    require(canonicalRoot.isDirectory) { "工作区暂时不可用" }
    val resolved = paths.distinct().map { path ->
        require(isShareableWorkspacePath(path)) { "不能分享应用内部资料或无效文件" }
        val requested = File(canonicalRoot, path)
        val actual = requested.canonicalFile
        require(actual.toPath().startsWith(canonicalRoot.toPath()) &&
            actual.toPath() == requested.toPath().normalize()
        ) { "文件已移动，或超出了工作区范围" }
        require(actual.isFile && actual.canRead()) { "文件已不存在或无法读取：${path.substringAfterLast('/')}" }
        actual
    }
    return resolved
}

internal fun localWorkspaceShareMime(mimes: List<String>): String {
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

/** Uses the existing non-exported FileProvider; only explicitly selected URIs get read grants. */
internal fun createLocalWorkspaceShareIntent(
    context: Context,
    workspacePath: String,
    selectedPaths: List<String>,
): Intent {
    val appWorkspace = File(context.filesDir, "local-harness/workspace").canonicalFile
    require(workspacePath.isNotBlank() && File(workspacePath).canonicalFile == appWorkspace) {
        "工作区位置已变化，请重新打开文件列表"
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
