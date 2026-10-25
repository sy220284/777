package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalToolCall
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentWorkspaceScopePolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun tool(name: String, field: String, value: String): LocalToolCall {
        val args = buildJsonObject { put(field, value) }
        return LocalToolCall("tool-1", name, args, args.toString())
    }

    @Test fun nativeWritesRequireExplicitTaskScopeAndRejectSiblingOrParentEscapes() {
        val root = temporary.newFolder("scope-native")
        enforceTeamMemberWorkspaceMutation(tool("write", "path", "src/Ok.kt"),
            listOf("src"), root)
        enforceTeamMemberWorkspaceMutation(tool("edit", "path", "src/Ok.kt"),
            listOf("src/Ok.kt"), root)
        for (path in listOf("other.kt", "src-other/file.kt", "src/../other.kt", "/tmp/escape")) {
            assertTrue(path, runCatching {
                enforceTeamMemberWorkspaceMutation(tool("write", "path", path), listOf("src"), root)
            }.isFailure)
        }
        assertTrue(runCatching {
            enforceTeamMemberWorkspaceMutation(tool("write", "path", "src/Ok.kt"), emptyList(), root)
        }.exceptionOrNull()?.message.orEmpty().contains("TEAM_WRITE_SCOPE_REQUIRED"))
        enforceTeamMemberWorkspaceMutation(tool("read", "path", "src/Ok.kt"), emptyList(), root)
    }

    @Test fun workScopeRejectsSymlinkAncestorAndUnscopableProcessCommands() {
        val root = temporary.newFolder("scope-links")
        val outside = temporary.newFolder("outside-scope")
        java.nio.file.Files.createSymbolicLink(root.toPath().resolve("alias"), outside.toPath())
        assertTrue(runCatching {
            enforceTeamMemberWorkspaceMutation(tool("write", "path", "alias/f.txt"),
                listOf("alias"), root)
        }.exceptionOrNull()?.message.orEmpty().contains("TEAM_WRITE_SYMLINK_DENIED"))
        for (name in listOf("bash", "run_shell", "download_file", "skill_update")) {
            assertTrue(name, runCatching {
                enforceTeamMemberWorkspaceMutation(tool(name, "path", "src/Ok.kt"),
                    listOf("src"), root)
            }.exceptionOrNull()?.message.orEmpty().contains("TEAM_WRITE_TOOL_UNSCOPABLE"))
        }
    }

    @Test fun patchMustHaveExplicitAuditedHeadersWithinSameWriteScope() {
        val root = temporary.newFolder("scope-patch")
        val allowed = "diff --git a/src/Ok.kt b/src/Ok.kt\\n--- a/src/Ok.kt\\n+++ b/src/Ok.kt\\n@@ -1 +1 @@\\n-old\\n+new"
        enforceTeamMemberWorkspaceMutation(tool("apply_patch", "patch", allowed), listOf("src"), root)
        for (patch in listOf(
            allowed.replace("src/Ok.kt", "docs/Other.kt"),
            allowed.replace("src/Ok.kt", "../escape.kt"),
            allowed + "\\nrename to docs/Other.kt",
            "--- a/src/Ok.kt\\n+++ b/src/Ok.kt\\n@@ -1 +1 @@\\n-old\\n+new",
            allowed + "\\nnew file mode 120000",
        )) {
            assertTrue(runCatching {
                enforceTeamMemberWorkspaceMutation(tool("apply_patch", "patch", patch),
                    listOf("src"), root)
            }.isFailure)
        }
    }


    @Test fun relativeNestedPathsAreCanonicalAndOnlyTrueOverlapConflicts() {
        val scopes = normalizeLocalAgentWriteScopes(
            listOf(" src/main ", "docs/api.md"), maxScopes = 4,
        )
        assertEquals(listOf("src/main", "docs/api.md"), scopes)
        assertTrue(localAgentWriteScopeOverlaps("src", "src/main/Main.kt"))
        assertTrue(localAgentWriteScopeOverlaps("docs/api.md", "docs/api.md"))
        assertFalse(localAgentWriteScopeOverlaps("src/a", "src/ab"))
        assertFalse(localAgentWriteScopeOverlaps("src", "docs"))
    }

    @Test fun rootedPathsOrEscapingCannotBeReinterpretedAsRelativeWriteGrants() {
        for (path in listOf("/etc", "\\Windows", "C:/", "../outside", "src/../file",
            "./src", "src//main", "~/home", "src:a", "src/./main", "src/")) {
            assertTrue(path,
                runCatching { normalizeLocalAgentWriteScopes(listOf(path), 3) }.isFailure)
        }
    }

    @Test fun duplicatedOrOversizedDeclaredWriteScopesFailWithoutRelaxingOriginalGrant() {
        assertTrue(runCatching {
            normalizeLocalAgentWriteScopes(listOf("src", " src "), 5)
        }.exceptionOrNull()?.message.orEmpty().contains("TEAM_WRITE_SCOPE_DUPLICATE"))
        assertTrue(runCatching {
            normalizeLocalAgentWriteScopes(listOf("one", "two"), 1)
        }.exceptionOrNull()?.message.orEmpty().contains("TEAM_TASK_WRITE_SCOPE_LIMIT"))
    }
}
