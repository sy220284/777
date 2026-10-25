package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.model.LocalToolCall
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.ToolWorkspaceAccess
import com.labteto.dshmobile.harness.tools.functionToolSchema
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
    private fun registeredTool(
        name: String,
        access: ToolAccess,
        workspace: ToolWorkspaceAccess,
    ) = HarnessTool(
        name = name,
        schema = functionToolSchema(name, "test"),
        access = access,
        approvalPolicy = ToolApprovalPolicy.NEVER,
        exposure = ToolExposure.CORE,
        metadata = ToolMetadata(family = "test", workspaceAccess = workspace),
        executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
    )

    @Test fun dynamicPluginsCannotSmuggleWorkspaceWritesUnderReadonlyOrMcpNames() {
        val root = temporary.newFolder("dynamic-gate")
        val forged = registeredTool("mcp_external_edit", ToolAccess.READ_ONLY, ToolWorkspaceAccess.WRITE)
        val privileged = registeredTool("github_push", ToolAccess.PRIVILEGED, ToolWorkspaceAccess.NONE)
        val benign = registeredTool("mcp_read", ToolAccess.READ_ONLY, ToolWorkspaceAccess.NONE)
        for (plugin in listOf(forged, privileged)) {
            val failure = runCatching {
                enforceTeamMemberWorkspaceMutation(
                    tool(plugin.name, "path", "src/Allowed.kt"), listOf("src"),
                    root, registeredTool = plugin,
                )
            }.exceptionOrNull()
            assertTrue(plugin.name, failure?.message.orEmpty().contains("TEAM_WRITE_TOOL_UNSCOPABLE"))
        }
        enforceTeamMemberWorkspaceMutation(
            tool("mcp_read", "query", "read-only"), emptyList(), root,
            registeredTool = benign,
        )
        assertTrue(runCatching {
            enforceTeamMemberWorkspaceMutation(
                tool("mcp_unregistered", "path", "src/Allowed.kt"), listOf("src"), root,
            )
        }.exceptionOrNull()?.message.orEmpty().contains("TEAM_WRITE_TOOL_UNREGISTERED"))
    }

    @Test fun sideEffectingBuiltinAndBackgroundFetchCannotBypassTeamWriteScopes() {
        val root = temporary.newFolder("team-side-effects")
        for (name in listOf("http_request", "job_kill", "memory_update", "media_image_create",
            "skill_create", "mcp_start_local", "subagent")) {
            assertTrue(name, runCatching {
                enforceTeamMemberWorkspaceMutation(
                    tool(name, "path", "src/Allowed.kt"), listOf("src"), root,
                )
            }.exceptionOrNull()?.message.orEmpty().contains("TEAM_WRITE_TOOL_UNSCOPABLE"))
        }
        val backgroundArgs = buildJsonObject { put("run_in_background", true) }
        val backgroundCall = LocalToolCall(
            "call-bg", "web_fetch", backgroundArgs, backgroundArgs.toString(),
        )
        assertTrue(runCatching {
            enforceTeamMemberWorkspaceMutation(backgroundCall, listOf("src"), root)
        }.exceptionOrNull()?.message.orEmpty().contains("web_fetch_background"))
        val readArgs = buildJsonObject { put("run_in_background", false) }
        enforceTeamMemberWorkspaceMutation(
            LocalToolCall("call-ro", "web_fetch", readArgs, readArgs.toString()),
            emptyList(), root,
        )
    }

    @Test fun exactNativeWriteContractMustMatchRegistryAndStayWithinTaskScope() {
        val root = temporary.newFolder("native-contract")
        val malicious = registeredTool("write", ToolAccess.READ_ONLY, ToolWorkspaceAccess.NONE)
        assertTrue(runCatching {
            enforceTeamMemberWorkspaceMutation(
                tool("write", "path", "src/Allowed.kt"), listOf("src"),
                root, registeredTool = malicious,
            )
        }.exceptionOrNull()?.message.orEmpty().contains("TEAM_WRITE_TOOL_CONTRACT_MISMATCH"))
        enforceTeamMemberWorkspaceMutation(
            tool("write", "path", "src/Allowed.kt"), listOf("src"), root,
            registeredTool = registeredTool(
                "write", ToolAccess.WORKSPACE_WRITE, ToolWorkspaceAccess.WRITE,
            ),
        )
    }

    @Test fun legacyWriteAliasUsesTheExactCanonicalRegisteredToolContract() {
        val root = temporary.newFolder("canonical-alias")
        val admitted = registeredTool(
            "write", ToolAccess.WORKSPACE_WRITE, ToolWorkspaceAccess.WRITE,
        )
        enforceTeamMemberWorkspaceMutation(
            tool("write_file", "path", "src/Allowed.kt"), listOf("src"),
            root, registeredTool = admitted,
        )
        val mismatched = registeredTool(
            "write_file", ToolAccess.WORKSPACE_WRITE, ToolWorkspaceAccess.WRITE,
        )
        assertTrue(runCatching {
            enforceTeamMemberWorkspaceMutation(
                tool("write_file", "path", "src/Allowed.kt"),
                listOf("src"), root, registeredTool = mismatched,
            )
        }.exceptionOrNull()?.message.orEmpty().contains("TEAM_WRITE_TOOL_IDENTITY_MISMATCH"))
        val replaced = registeredTool(
            "write", ToolAccess.PRIVILEGED, ToolWorkspaceAccess.NONE,
        )
        assertTrue(runCatching {
            enforceTeamMemberWorkspaceMutation(
                tool("write_file", "path", "src/Allowed.kt"),
                listOf("src"), root, registeredTool = replaced,
            )
        }.exceptionOrNull()?.message.orEmpty().contains("TEAM_WRITE_TOOL_CONTRACT_MISMATCH"))
    }

}
