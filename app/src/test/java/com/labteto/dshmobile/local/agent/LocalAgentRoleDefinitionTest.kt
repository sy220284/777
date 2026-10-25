package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAgentRoleDefinitionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun projectRoleNarrowsCallerToolsAndFreezesInstructionsAcrossFileEdits() {
        val workspace = LocalWorkspace(temporary.root)
        val file = File(temporary.root, ".agents/roles/auditor.json")
        file.parentFile.mkdirs()
        file.writeText("""{"version":2,"base_role":"review","instructions":"check actual receipts","allowed_tools":["read","grep"]}""")
        val frozen = resolveLocalAgentRole(workspace, "auditor", "task rule", false)
        assertFalse(frozen.allowMutation)
        assertEquals(setOf("read"), intersectAgentRoleTools(setOf("read", "write"), frozen.toolAllowlist))
        file.writeText("""{"version":3,"base_role":"execute","instructions":"changed"}""")
        assertTrue(frozen.instructions.contains("check actual receipts"))
        assertFalse(frozen.instructions.contains("changed"))
        val restored = decodeFrozenAgentRole(encodeFrozenAgentRole(frozen))
        assertEquals(frozen, restored)
        assertEquals(2, restored.profile.version)
    }
    @Test fun writableCustomRoleCannotElevateReadonlyDelegation() {
        val workspace = LocalWorkspace(temporary.root)
        val file = File(temporary.root, ".agents/roles/builder.json")
        file.parentFile.mkdirs()
        file.writeText("""{"version":1,"base_role":"execute","instructions":"implement"}""")
        assertTrue(runCatching { resolveLocalAgentRole(workspace, "builder", "", false) }.isFailure)
    }
    @Test fun rolePathAndUnsupportedAuthorityFieldsAreRejected() {
        val workspace = LocalWorkspace(temporary.root)
        assertTrue(runCatching { resolveLocalAgentRole(workspace, "../outside", "", true) }.isFailure)
        val file = File(temporary.root, ".agents/roles/unsafe.json")
        file.parentFile.mkdirs()
        file.writeText("""{"version":1,"base_role":"general","instructions":"x","approval_policy":"never"}""")
        assertTrue(runCatching { resolveLocalAgentRole(workspace, "unsafe", "", true) }.isFailure)
    }
}
