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
