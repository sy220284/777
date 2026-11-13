package com.labteto.dshmobile.local.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalPinnedInvocationTest {
    @Test fun encodesAndDecodesPersistentConnectorAndSkillSelections() {
        val expected = listOf(
            LocalPinnedInvocation(LocalPinnedInvocation.Kind.CONNECTOR, "github"),
            LocalPinnedInvocation(LocalPinnedInvocation.Kind.CONNECTOR, "figma-mcp"),
            LocalPinnedInvocation(LocalPinnedInvocation.Kind.SKILL, "code-review"),
        )
        assertEquals(expected, expected.map { LocalPinnedInvocation.decode(it.encode()) })
    }

    @Test fun rejectsInvalidOrCorruptedSelections() {
        listOf("connector:../x", "skill:../SKILL.md", "connector:foo/bar",
            "unknown:github", "skill:", "skill:INVALID", "connector:").forEach {
            assertNull(LocalPinnedInvocation.decode(it))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalPinnedInvocation(LocalPinnedInvocation.Kind.CONNECTOR, "../secret")
        }
    }

    @Test fun parsesOfficialAndThirdPartyGitHubSkillAddresses() {
        val official = PublicGitHubSkillLocation.parse(
            "https://github.com/openai/skills/tree/main/skills/.curated/example-skill")
        assertEquals("openai", official.owner)
        assertEquals("skills/.curated/example-skill", official.directory)
        val thirdParty = PublicGitHubSkillLocation.parse(
            "https://github.com/example/skill-box/blob/main/my-skill/SKILL.md")
        assertEquals("my-skill", thirdParty.directory)
    }

    @Test fun refusesNonGitHubOrTraversalSkillAddresses() {
        listOf(
            "https://evil.example/openai/skills/tree/main/skills/sample",
            "https://github.com/openai/skills/tree/main/../secrets",
            "https://github.com/openai/skills/tree/main",
            "http://github.com/openai/skills/tree/main/skills/sample",
        ).forEach { link ->
            assertThrows(IllegalArgumentException::class.java) {
                PublicGitHubSkillLocation.parse(link)
            }
        }
    }
}
