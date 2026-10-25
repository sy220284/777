package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.LocalWorkspaceExecutionContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalPersistentAgentWorkspaceTest {
    @Test fun absentWorkspaceIsLegacyButMalformedPresentBindingNeverBecomesShared() {
        assertNull(decodePersistentAgentWorkspaceId(buildJsonObject {}))
        val identity = "a".repeat(32)
        assertEquals(identity, decodePersistentAgentWorkspaceId(buildJsonObject { put("workspace_id", identity) }))
        listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(""), JsonObject(emptyMap())).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                decodePersistentAgentWorkspaceId(buildJsonObject { put("workspace_id", invalid) })
            }
        }
    }

    @Test fun repositoryScopeMapsDeclaredTeamPathsIntoTheActualChildRoot() {
        val home = kotlin.io.path.createTempDirectory("agent-paths-").toFile()
        try {
            val source = home.resolve("source").apply { mkdirs() }
            val repository = source.resolve("projects/app").apply { mkdirs() }
            val child = home.resolve("child").apply { mkdirs() }
            val binding = LocalWorkspaceExecutionContext(source, LocalWorkspace(child), "a".repeat(32), repository)
            assertEquals("src/main.kt", binding.localPath("projects/app/src/main.kt"))
            assertEquals(child.canonicalFile, binding.rootFor(source))
            assertTrue(binding.guidance.contains("projects/app/"))
            assertTrue(binding.coversRepository("projects/app"))
            assertFalse(binding.coversRepository("projects"))
            assertThrows(IllegalArgumentException::class.java) { binding.localPath("projects/other/main.kt") }
        } finally { home.deleteRecursively() }
    }

    @Test fun graphOutputTextMustPassTheSameCheckBeforeIntegrationAndAcceptance() {
        assertNotNull(workflowOutputRejection("", ""))
        assertNotNull(workflowOutputRejection("work in progress", "verified"))
        assertNull(workflowOutputRejection("VERIFIED result", " verified "))
    }
}
