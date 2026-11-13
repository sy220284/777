package com.labteto.dshmobile.local.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalGitHubSkillSourceEvidenceTest {
    @Test fun evidenceBindsImmutableCommitToOriginalSource() {
        val source = PublicGitHubSkillLocation.parse(
            "https://github.com/openai/skills/tree/main/skills/.curated/example-skill")
        val sha = "abcdef0123456789abcdef0123456789abcdef01"
        val json = Json.parseToJsonElement(
            githubSkillSourceEvidence(source, sha).toString(Charsets.UTF_8)).jsonObject
        assertEquals("github", json.getValue("source_type").jsonPrimitive.content)
        assertEquals("main", json.getValue("source_ref").jsonPrimitive.content)
        assertEquals(sha, json.getValue("commit").jsonPrimitive.content)
        assertEquals(
            "https://github.com/openai/skills/tree/$sha/skills/.curated/example-skill",
            json.getValue("source_url").jsonPrimitive.content)
    }

    @Test fun shareLinkIsBoundToTheRecordedImmutableRevision() {
        val source = PublicGitHubSkillLocation.parse(
            "https://github.com/openai/skills/tree/main/skills/.curated/example-skill")
        val sha = "abcdef0123456789abcdef0123456789abcdef01"
        val evidence = githubSkillSourceEvidence(source, sha).toString(Charsets.UTF_8)
        assertEquals(
            "https://github.com/openai/skills/tree/$sha/skills/.curated/example-skill",
            parseGitHubSkillSourceLink(evidence),
        )
    }

    @Test fun tamperedOrNonGitHubOriginsCannotBecomeShareLinks() {
        val source = PublicGitHubSkillLocation.parse(
            "https://github.com/openai/skills/tree/main/skills/.curated/example-skill")
        val sha = "abcdef0123456789abcdef0123456789abcdef01"
        val evidence = githubSkillSourceEvidence(source, sha).toString(Charsets.UTF_8)
        listOf(
            evidence.replace("https://github.com/", "https://attacker.example/"),
            evidence.replace("\"commit\":\"$sha\"", "\"commit\":\"0000000000000000000000000000000000000000\""),
            evidence.replace("\"source_type\":\"github\"", "\"source_type\":\"http\""),
            evidence.replace("\"owner\":\"openai\"", "\"owner\":\"../../openai\""),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                parseGitHubSkillSourceLink(invalid)
            }
        }
    }

    @Test fun evidenceRejectsNonCommitVersionsAndPathInjection() {
        val source = PublicGitHubSkillLocation.parse(
            "https://github.com/openai/skills/tree/main/skills/.curated/example-skill")
        listOf("main", "abcd", "../foo", "f".repeat(41)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                githubSkillSourceEvidence(source, invalid)
            }
        }
    }
}
