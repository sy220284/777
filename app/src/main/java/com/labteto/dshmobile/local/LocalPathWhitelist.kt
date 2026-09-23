package com.labteto.dshmobile.local

import java.io.File

/** Kind of an authorized path root inside the on-device Harness sandbox. */
enum class LocalPathRootKind {
    /** App-private workspace root; writes are auto-approved by default. */
    WORKSPACE,

    /**
     * Externally authorized root (for example a shared media directory).
     * Auto-approval is opt-in per root via [LocalPathRoot.autoApproveWrites].
     */
    EXTERNAL,
}

/**
 * One authorized root of the sandbox.
 *
 * Paths are canonicalized on construction so that `..` segments and symlinks cannot widen the
 * boundary after the fact.
 *
 * @param kind stable classification, used for defaults and diagnostics.
 * @param path absolute root path.
 * @param autoApproveWrites whether writes inside this root skip the approval prompt.
 * @param readOnly whether this root rejects every write regardless of approval settings.
 */
data class LocalPathRoot(
    val kind: LocalPathRootKind,
    val path: String,
    val autoApproveWrites: Boolean = kind == LocalPathRootKind.WORKSPACE,
    val readOnly: Boolean = false,
) {
    /** Canonical prefix without a trailing separator, used for prefix matching. */
    val canonicalPrefix: String = File(path).canonicalFile.path.trimEnd(File.separatorChar)

    /** True when [candidate] is this root or a descendant of it. */
    fun contains(candidate: File): Boolean {
        val canonical = runCatching { candidate.canonicalFile.path }.getOrNull() ?: return false
        return canonical == canonicalPrefix ||
            canonical.startsWith(canonicalPrefix + File.separatorChar)
    }
}

/**
 * Multi-root path boundary for [LocalWorkspace].
 *
 * This replaces the previous single-root prefix check. It decides only **which paths may be
 * touched**; whether that touch needs a prompt is decided separately by the approval policy.
 *
 * Fail-closed: a path that matches no root is rejected. Nested roots are rejected at construction
 * time because they would make approval semantics ambiguous.
 */
class LocalPathWhitelist(roots: List<LocalPathRoot>) {

    val roots: List<LocalPathRoot> = roots.toList()

    init {
        require(this.roots.isNotEmpty()) { "路径白名单不能为空" }
        require(this.roots.any { it.kind == LocalPathRootKind.WORKSPACE }) {
            "路径白名单必须包含工作区根"
        }
        for (a in this.roots) for (b in this.roots) {
            if (a === b) continue
            require(!b.canonicalPrefix.startsWith(a.canonicalPrefix + File.separatorChar)) {
                "路径白名单存在嵌套根：${b.path} 位于 ${a.path} 之下"
            }
        }
    }

    /** First root containing [candidate], or null when the path is outside the sandbox. */
    fun match(candidate: File): LocalPathRoot? = roots.firstOrNull { it.contains(candidate) }

    /** Whether [candidate] may be read. */
    fun canRead(candidate: File): Boolean = match(candidate) != null

    /** Whether [candidate] may be written; read-only roots always reject writes. */
    fun canWrite(candidate: File): Boolean {
        val root = match(candidate) ?: return false
        return !root.readOnly
    }

    /** Whether a write to [candidate] may skip the approval prompt. */
    fun canAutoApproveWrite(candidate: File): Boolean {
        val root = match(candidate) ?: return false
        return !root.readOnly && root.autoApproveWrites
    }

    /** Convenience factory for the default single-root workspace sandbox. */
    companion object {
        fun workspaceOnly(workspaceRoot: File): LocalPathWhitelist =
            LocalPathWhitelist(
                listOf(LocalPathRoot(LocalPathRootKind.WORKSPACE, workspaceRoot.absolutePath)),
            )
    }
}
