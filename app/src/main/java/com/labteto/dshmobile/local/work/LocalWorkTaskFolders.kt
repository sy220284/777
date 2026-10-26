package com.labteto.dshmobile.local.work

import java.io.File

/** Durable, human-readable folder names derived from the goal identity. */
internal data class LocalWorkTaskFolders(val work: String, val deliverables: String)

internal fun localWorkTaskFolderPaths(description: String, goalId: String): LocalWorkTaskFolders {
    val readable = description.trim()
        .replace(Regex("""[^\p{L}\p{N}]+"""), "-")
        .trim('-').take(28).trim('-')
    // User-facing auto-generated names prefer Chinese; the unique suffix is a stable technical ID.
    val title = if (readable.any { it in '\u4E00'..'\u9FFF' }) readable else "工作任务"
    val identity = goalId.filter(Char::isLetterOrDigit).take(8)
    require(identity.isNotBlank()) { "目标标识不能为空" }
    val name = "${title}-${identity}"
    return LocalWorkTaskFolders("任务/${name}", "交付成果/${name}")
}

internal fun ensureLocalWorkTaskFolders(root: File, description: String, goalId: String): LocalWorkTaskFolders {
    val paths = localWorkTaskFolderPaths(description, goalId)
    val base = root.canonicalFile.toPath()
    listOf(paths.work, paths.deliverables).forEach { relative ->
        val directory = File(root, relative).canonicalFile
        require(directory.toPath().startsWith(base)) { "任务目录超出工作区" }
        require(directory.isDirectory || directory.mkdirs()) { "无法建立任务目录：${relative}" }
    }
    return paths
}
