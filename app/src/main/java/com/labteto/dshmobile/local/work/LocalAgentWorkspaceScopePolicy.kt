package com.labteto.dshmobile.local.work

/**
 * E10: a declared Team write scope is a canonical relative-workspace path.
 * It is an admission/conflict fact; it never substitutes for the actual
 * file-tool sandbox or the shared workspace mutation lease.
 */
internal fun normalizeLocalAgentWriteScopes(
    requested: List<String>,
    maxScopes: Int,
): List<String> {
    require(maxScopes > 0) { "TEAM_WRITE_SCOPE_LIMIT_INVALID" }
    require(requested.size <= maxScopes) { "TEAM_TASK_WRITE_SCOPE_LIMIT" }
    val normalized = requested.map { raw ->
        val clean = raw.trim().replace('\\', '/')
        require(clean.isNotBlank() && !clean.startsWith("/") &&
            !clean.startsWith("~") && ':' !in clean && '\u0000' !in clean &&
            clean.none { Character.isISOControl(it) }) {
            "TEAM_WRITE_SCOPE_INVALID"
        }
        require(clean.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "TEAM_WRITE_SCOPE_INVALID"
        }
        clean
    }
    require(normalized.distinct().size == normalized.size) {
        "TEAM_WRITE_SCOPE_DUPLICATE"
    }
    return normalized
}

internal fun localAgentWriteScopeOverlaps(left: String, right: String): Boolean =
    left == right || left.startsWith("$right/") || right.startsWith("$left/")

/**
 * Tool-time E10 scope admission. Scopes are declared by the current Task owner
 * in the existing Team EventLog. No model prompt or role can broaden them.
 * Only the native write/edit and a simple, fully path-audited git patch are
 * addressable here. A shell or download can write arbitrary files and cannot
 * safely prove that it stays inside one Team task's scope.
 */
internal fun enforceTeamMemberWorkspaceMutation(
    call: com.labteto.dshmobile.local.model.LocalToolCall,
    writeScopes: List<String>,
    workspaceRoot: java.io.File,
) {
    val canonical = com.labteto.dshmobile.local.tools.LocalToolPolicy.canonical(call.name)
    val unscopable = setOf(
        "bash", "download_file", "present", "skill_create", "skill_update",
        "skill_rollback", "extension_record", "mcp_create_local", "mcp_update_local",
        "mcp_rollback_local", "mcp_start_local",
    )
    require(canonical !in unscopable) {
        "TEAM_WRITE_TOOL_UNSCOPABLE:$canonical"
    }
    if (canonical !in setOf("write", "edit", "apply_patch")) return
    require(writeScopes.isNotEmpty()) {
        "TEAM_WRITE_SCOPE_REQUIRED: Lead must assign a task with explicit write_scopes"
    }
    val bounded = normalizeLocalAgentWriteScopes(writeScopes, 32)
    fun requirePath(path: String) {
        require(path == path.trim() && '\\' !in path) { "TEAM_WRITE_PATH_INVALID" }
        val normalized = normalizeLocalAgentWriteScopes(listOf(path), 1).single()
        require(bounded.any { normalized == it || normalized.startsWith("$it/") }) {
            "TEAM_WRITE_SCOPE_VIOLATION:$normalized"
        }
        val root = workspaceRoot.canonicalFile.toPath()
        var current = root
        for (part in normalized.split('/')) {
            current = current.resolve(part)
            require(!java.nio.file.Files.isSymbolicLink(current)) {
                "TEAM_WRITE_SYMLINK_DENIED:$normalized"
            }
        }
        require(current.normalize().startsWith(root)) { "TEAM_WRITE_PATH_ESCAPE" }
    }
    val args = call.arguments
    when (canonical) {
        "write", "edit" -> {
            val path = (args["path"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content ?: error("TEAM_WRITE_PATH_REQUIRED")
            requirePath(path)
        }
        "apply_patch" -> {
            val patch = (args["patch"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content ?: error("TEAM_WRITE_PATCH_REQUIRED")
            validateWorkspacePatchPaths(patch)
            require(!patch.contains("rename from ") && !patch.contains("rename to ") &&
                !patch.contains("copy from ") && !patch.contains("copy to ") &&
                !patch.contains("120000") && !patch.contains("GIT binary patch")) {
                "TEAM_WRITE_PATCH_UNSCOPABLE"
            }
            val headers = patch.lineSequence().filter { it.startsWith("diff --git ") }.toList()
            require(headers.isNotEmpty()) { "TEAM_WRITE_PATCH_HEADER_REQUIRED" }
            headers.forEach { header ->
                val m = Regex("""diff --git a/([^\s]+) b/([^\s]+)""").matchEntire(header)
                    ?: error("TEAM_WRITE_PATCH_HEADER_INVALID")
                requirePath(m.groupValues[1])
                requirePath(m.groupValues[2])
            }
            // Other path-bearing lines cannot smuggle a target outside the diff.
            patch.lineSequence().filter {
                it.startsWith("--- ") || it.startsWith("+++ ")
            }.forEach { header ->
                val raw = header.drop(4).substringBefore('\t').trim()
                if (raw != "/dev/null") {
                    require(raw.startsWith("a/") || raw.startsWith("b/")) {
                        "TEAM_WRITE_PATCH_HEADER_INVALID"
                    }
                    requirePath(raw.substring(2))
                }
            }
        }
    }
}
