package com.labteto.dshmobile.local.tools

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

    @Test fun updateSourcePreservesOriginalBranchAndImmutableInstalledCommit() {
        val source = PublicGitHubSkillLocation.parse(
            "https://github.com/openai/skills/tree/main/skills/.curated/example-skill")
        val sha = "abcdef0123456789abcdef0123456789abcdef01"
        assertEquals(source to sha, parseGitHubSkillUpdateSource(
            githubSkillSourceEvidence(source, sha).toString(Charsets.UTF_8)))
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
    @Test fun advertisedGitRefsResolveWithoutGitHubRestApi() {
        val sha = "abcdef0123456789abcdef0123456789abcdef01"
        fun packet(value: String): String = "%04x".format(value.toByteArray().size + 4) + value
        val payload = (packet("# service=git-upload-pack\n") + "0000" +
            packet("$sha HEAD\u0000multi_ack thin-pack\n") +
            packet("$sha refs/heads/main\n") + "0000").toByteArray()
        assertEquals(sha, advertisedGitCommit(payload.inputStream(), "main"))
        assertThrows(java.io.IOException::class.java) {
            advertisedGitCommit(payload.inputStream(), "unavailable")
        }
    }

    @Test fun snapshotOnlyImportsRequestedSkillAndPinsProvenance() {
        val source = PublicGitHubSkillLocation.parse(
            "https://github.com/openai/skills/tree/main/skills/.curated/example-skill")
        val sha = "abcdef0123456789abcdef0123456789abcdef01"
        val zipped = ByteArrayOutputStream()
        ZipOutputStream(zipped).use { zip ->
            mapOf(
                "skills-$sha/other/secret.txt" to "unrelated",
                "skills-$sha/skills/.curated/example-skill/SKILL.md" to
                    "---\nname: example-skill\ndescription: Demo\n---\nInstructions",
                "skills-$sha/skills/.curated/example-skill/references/notes.md" to "notes",
                "skills-$sha/skills/.curated/another/SKILL.md" to "other",
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val selected = ByteArrayOutputStream()
        assertEquals(2, writePinnedGitHubSkillArchive(
            source, sha, zipped.toByteArray().inputStream(), selected))
        val found = linkedMapOf<String, String>()
        ZipInputStream(selected.toByteArray().inputStream()).use { zip ->
            while (true) {
                val next = zip.nextEntry ?: break
                found[next.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        assertEquals(setOf("SKILL.md", "references/notes.md", ".github-source.json"), found.keys)
        assertEquals("notes", found["references/notes.md"])
        assertEquals("https://github.com/openai/skills/tree/$sha/skills/.curated/example-skill",
            parseGitHubSkillSourceLink(found.getValue(".github-source.json")))
    }

}
