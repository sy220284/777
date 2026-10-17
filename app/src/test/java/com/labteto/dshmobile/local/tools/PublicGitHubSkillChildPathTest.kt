package com.labteto.dshmobile.local.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicGitHubSkillChildPathTest {
    @Test
    fun validNestedFileResolvesOnlyUnderItsDeclaredParent() {
        assertEquals("skills/.curated/audit/SKILL.md",
            requireGitHubSkillChildPath("skills/.curated/audit", "SKILL.md", "skills/.curated/audit/SKILL.md"))
        assertEquals("skills/.curated/audit/tools.json",
            requireGitHubSkillChildPath("skills/.curated/audit", "tools.json", "skills/.curated/audit/tools.json"))
    }

    @Test
    fun crossDirectoryAndAliasEntryCannotImportUnselectedFile() {
        for (advertised in listOf(
            "skills/other/SKILL.md",
            "skills/.curated/audit/subdir/SKILL.md",
            "skills/.curated/audit/../other/SKILL.md",
            "skills/.curated/audit\\SKILL.md",
            "/skills/.curated/audit/SKILL.md",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                requireGitHubSkillChildPath("skills/.curated/audit", "SKILL.md", advertised)
            }
        }
    }

    @Test
    fun invalidNameNeverGetsNormalizedToAPath() {
        for (name in listOf(".", "..", "a/b", "", "../escape", "a\\b")) {
            assertThrows(IllegalArgumentException::class.java) {
                requireGitHubSkillChildPath("skills/audit", name, "skills/audit/$name")
            }
        }
    }
}
