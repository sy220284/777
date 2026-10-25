package com.labteto.dshmobile.local.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentWorkspaceScopePolicyTest {
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
