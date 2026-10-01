package com.labteto.dshmobile.local

import java.io.File

/**
 * Sandbox boundary for the on-device Harness, expressed as what is *forbidden* rather than what is
 * allowed.
 *
 * The boundary is drawn at firmware: partitions the device shipped with, plus the kernel interfaces
 * that control how it runs. Everything the user owns — the app's own workspace, its caches, shared
 * storage, anything installed or downloaded after the fact — is inside the boundary and may be
 * written without an approval prompt, because auto-approval here cannot grant access the
 * untrusted-app SELinux domain does not already permit.
 *
 * **Scope of this class.** It is the enforcement point for the file tools, which resolve every path
 * through it before touching the filesystem. It is *not* the guarantee for firmware as a whole:
 * shell commands run as child processes that can leave any working directory, and what stops them is
 * the kernel — `/system` and friends are mounted read-only, and other apps' private directories are
 * unreachable to this UID. The forbidden list below is therefore a defence-in-depth check for the
 * paths this class does see, not a substitute for the mount table.
 *
 * Not on the forbidden list, and deliberately so: `/data/data/<other app>`. It is user-installed
 * data rather than firmware, so it does not belong here — Android's per-app sandbox already keeps
 * it out of reach, and listing it would misrepresent where the guarantee comes from.
 */
class LocalSandboxBoundary(
    /** App-private workspace root; always inside the boundary. */
    val workspaceRoot: File,
    /** Additional writable roots beyond the workspace, typically shared storage. */
    val userRoots: List<File> = emptyList(),
    /**
     * Absolute path prefixes that are always denied. Defaults to the firmware set; callers may extend
     * it but should not remove entries, so the list is kept private and exposed read-only.
     */
    forbiddenPrefixes: List<String> = DEFAULT_FORBIDDEN_PREFIXES,
) {
    val forbiddenPrefixes: List<String> = forbiddenPrefixes.toList()

    private val canonicalWorkspace: String = workspaceRoot.canonicalFile.path.trimEnd(File.separatorChar)

    private val canonicalUserRoots: List<String> = userRoots.map {
        it.canonicalFile.path.trimEnd(File.separatorChar)
    }

    /** True when [candidate] names firmware or a kernel control surface. */
    fun isForbidden(candidate: File): Boolean {
        val canonical = runCatching { candidate.canonicalFile.path }.getOrNull() ?: return true
        return forbiddenPrefixes.any { prefix ->
            canonical == prefix || canonical.startsWith(prefix.trimEnd('/') + File.separatorChar)
        }
    }

    /** True when [candidate] is inside the app workspace. */
    fun isWorkspace(candidate: File): Boolean = within(canonicalWorkspace, candidate)

    /** True when [candidate] is inside one of the additional writable roots. */
    fun isUserRoot(candidate: File): Boolean = canonicalUserRoots.any { within(it, candidate) }

    /**
     * Whether [candidate] may be read or written at all.
     *
     * Fail-closed: a path inside the boundary but under no known root is still denied, so a widened
     * firmware set can never silently become the only guard.
     */
    fun isAllowed(candidate: File): Boolean {
        if (isForbidden(candidate)) return false
        return isWorkspace(candidate) || isUserRoot(candidate)
    }

    /** Whether a write to [candidate] may skip the approval prompt. */
    fun canAutoApprove(candidate: File): Boolean = isAllowed(candidate)

    private fun within(prefix: String, candidate: File): Boolean {
        val canonical = runCatching { candidate.canonicalFile.path }.getOrNull() ?: return false
        return canonical == prefix || canonical.startsWith(prefix + File.separatorChar)
    }

    companion object {
        /**
         * Firmware partitions and kernel control surfaces.
         *
         * `/data/data` itself is not listed: it holds user-installed apps, and the per-app sandbox —
         * not this list — is what keeps other apps' private files unreachable.
         */
        val DEFAULT_FORBIDDEN_PREFIXES = listOf(
            "/system", "/vendor", "/product", "/system_ext", "/odm", "/apex",
            "/proc", "/sys", "/dev",
            "/data/misc", "/data/system", "/data/adb",
        )

        /** Workspace-only boundary, matching the pre-existing sandbox semantics. */
        fun workspaceOnly(workspaceRoot: File): LocalSandboxBoundary =
            LocalSandboxBoundary(workspaceRoot = workspaceRoot)
    }
}
