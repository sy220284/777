package com.labteto.dshmobile.local

/**
 * Fail-closed path validation for auto-approved git patches.
 *
 * Git already rejects unsafe paths by default, but workspace auto-approval must not depend on an
 * external tool's defaults. We therefore validate every path-bearing unified-diff header before
 * invoking git apply.
 */
internal fun validateWorkspacePatchPaths(patch: String) {
    patch.lineSequence().forEach { line ->
        when {
            line.startsWith("diff --git ") -> {
                val paths = line.removePrefix("diff --git ").trim().split(Regex("\\s+"))
                require(paths.size == 2) { "补丁 diff 路径格式不受支持" }
                validatePatchPath(paths[0], stripGitPrefix = true)
                validatePatchPath(paths[1], stripGitPrefix = true)
            }
            line.startsWith("--- ") ->
                validatePatchPath(line.removePrefix("--- ").substringBefore('\t'), stripGitPrefix = true)
            line.startsWith("+++ ") ->
                validatePatchPath(line.removePrefix("+++ ").substringBefore('\t'), stripGitPrefix = true)
            line.startsWith("rename from ") ->
                validatePatchPath(line.removePrefix("rename from "), stripGitPrefix = false)
            line.startsWith("rename to ") ->
                validatePatchPath(line.removePrefix("rename to "), stripGitPrefix = false)
            line.startsWith("copy from ") ->
                validatePatchPath(line.removePrefix("copy from "), stripGitPrefix = false)
            line.startsWith("copy to ") ->
                validatePatchPath(line.removePrefix("copy to "), stripGitPrefix = false)
        }
    }
}

private fun validatePatchPath(raw: String, stripGitPrefix: Boolean) {
    var path = raw.trim()
    if (path == "/dev/null") return
    require(path.isNotEmpty()) { "补丁路径不能为空" }
    require(!path.startsWith('"') && !path.endsWith('"')) {
        "自动批准不接受带转义/引号的补丁路径，请改用 write/edit"
    }
    require('\\' !in path) { "补丁路径不允许反斜杠：$path" }
    if (stripGitPrefix && (path.startsWith("a/") || path.startsWith("b/"))) {
        path = path.substring(2)
    }
    require(!path.startsWith('/')) { "补丁禁止绝对路径：$path" }
    val segments = path.split('/')
    require(segments.none { it == ".." }) { "补丁禁止越出工作区：$path" }
    require(segments.any(String::isNotBlank)) { "补丁路径不能为空" }
}
