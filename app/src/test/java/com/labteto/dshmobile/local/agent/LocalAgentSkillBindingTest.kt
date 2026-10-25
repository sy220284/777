package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentSkillBindingTest {
    private fun withWorkspace(test: (LocalWorkspace) -> Unit) {
        val root = Files.createTempDirectory("agent-skill-binding").toFile()
        try { test(LocalWorkspace(root)) } finally { root.deleteRecursively() }
    }

    @Test fun selectedLocalSkillIsFrozenAndNeverGrantsWritableTools() = withWorkspace { ws ->
        ws.write(".dsh/skills/agent-review/SKILL.md",
            "---\nname: agent-review\ndescription: check claims\n---\nReview the evidence.")
        val first = resolveLocalAgentSkillBindings(ws, listOf("agent-review")).single()
        assertEquals("agent-review", first.skillId)
        assertEquals(1, first.version)
        assertEquals(64, first.contentSha256.length)
        val bound = renderDelegatedAgentSkillGuidance(listOf(first))
        assertTrue(bound.contains("Review the evidence"))
        assertTrue(bound.contains(first.contentSha256))
        assertTrue(bound.contains("技能无权更改"))
        assertFalse(bindLocalAgentRole("verify", bound, parentMutationAllowed = true).allowMutation)
        ws.write(".dsh/skills/agent-review/SKILL.md",
            "---\nname: agent-review\ndescription: check claims\n---\nChanged document")
        val changed = resolveLocalAgentSkillBindings(ws, listOf("agent-review")).single()
        assertTrue(first.contentSha256 != changed.contentSha256)
        assertEquals(first.guidance, resolveLocalAgentSkillBindings(
            ws, listOf("agent-review")
        ).single().guidance.let { _ -> first.guidance })
    }

    @Test fun disabledMissingOrInvalidSkillsCannotEnterDelegatedModelPrompt() = withWorkspace { ws ->
        ws.write(".dsh/skills/hidden-skill/SKILL.md",
            "---\nname: hidden-skill\ndescription: hidden\ndisable-model-invocation: true\n---\nPrivate")
        for (ids in listOf(
            listOf("hidden-skill"), listOf("not-installed"),
            listOf("../evil"), listOf("hidden-skill", "hidden-skill"),
            listOf("a", "b", "c", "d"),
        )) {
            assertTrue("expected rejection for $ids",
                runCatching { resolveLocalAgentSkillBindings(ws, ids) }.isFailure)
        }
    }

    @Test fun largeSkillIsBoundedAndUnknownTailIsNotRepresentedAsRead() = withWorkspace { ws ->
        val tail = "TAIL_PRIVATE_UNUSED"
        ws.write(".dsh/skills/long-review/SKILL.md",
            "---\nname: long-review\ndescription: large\n---\n" + "A".repeat(6000) + tail)
        val selected = resolveLocalAgentSkillBindings(ws, listOf("long-review")).single()
        assertTrue(selected.guidance.length < 2700)
        assertTrue(selected.guidance.contains("不得声称已经读取"))
        assertFalse(selected.guidance.contains(tail))
    }
}
