package com.labteto.dshmobile.local.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSkillDependenciesTest {
    @Test fun readsActualCodexAgentsMetadata() {
        val source = """
            interface:
              display_name: "Figma"
            dependencies:
              tools:
                - type: "mcp"
                  value: "figma"
                  description: "连接设计工具"
                  transport: "http"
                - type: "tool"
                  value: "android_screen"
                  description: "设备屏幕"
            policy:
              allow_implicit_invocation: true
        """.trimIndent()
        val actual = parseCodexSkillDependencies(source)
        assertEquals(2, actual.size)
        assertEquals(CodexSkillDependency("mcp", "figma", "连接设计工具"), actual[0])
        assertEquals(CodexSkillDependency("tool", "android_screen", "设备屏幕"), actual[1])
    }

    @Test fun preservesUnknownDependenciesAsUnresolved() {
        val actual = parseCodexSkillDependencies("""
            dependencies:
              tools:
                - type: "mcp"
                  value: "github"
                - custom: ["unrecognized"]
        """.trimIndent())
        assertTrue(actual.any { it.type == "mcp" && it.value == "github" })
        assertTrue(actual.any { it.type == "unknown" })
    }

    @Test fun noDeclaredRequirementsAreEmpty() {
        assertEquals(emptyList<CodexSkillDependency>(), parseCodexSkillDependencies("description: sample"))
        assertEquals(emptyList<CodexSkillDependency>(), parseCodexSkillDependencies("dependencies:\n  tools:\n"))
    }

    @Test fun emptyTypeOrValueDoesNotTurnIntoApprovedDependency() {
        val result = parseCodexSkillDependencies("""
            dependencies:
              tools:
                - type: mcp
                  description: Missing value
        """.trimIndent())
        assertEquals(1, result.size)
        assertEquals("unknown", result.single().type)
    }
}
