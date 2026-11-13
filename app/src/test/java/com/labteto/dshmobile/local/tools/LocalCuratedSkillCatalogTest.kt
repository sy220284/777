package com.labteto.dshmobile.local.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalCuratedSkillCatalogTest {
    @Test fun curatedUrlsStayOnTheOfficialRepositoryAndDirectory() {
        val url = curatedGitHubSkillUrl("example-skill")
        assertEquals("https://github.com/openai/skills/tree/main/skills/.curated/example-skill", url)
        val source = PublicGitHubSkillLocation.parse(url)
        assertEquals("openai", source.owner)
        assertEquals("skills", source.repository)
        assertEquals("skills/.curated/example-skill", source.directory)
    }

    @Test fun pathInjectionAndInvalidIdentifiersAreRejected() {
        listOf("../private", "source%2fescape", "other/branch", "", "a", "UPPER")
            .forEach { bad ->
                assertThrows(IllegalArgumentException::class.java) { curatedGitHubSkillUrl(bad) }
            }
    }
}
