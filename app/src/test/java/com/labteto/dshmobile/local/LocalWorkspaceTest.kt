package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.files.LocalFileObservationCache
import com.labteto.dshmobile.local.files.LocalWorkspace
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalWorkspaceTest {
    private lateinit var root: java.io.File
    private var external: java.io.File? = null
    private lateinit var workspace: LocalWorkspace

    @Before
    fun setUp() {
        root = Files.createTempDirectory("local-harness-workspace").toFile()
        workspace = LocalWorkspace(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        external?.deleteRecursively()
    }

    @Test
    fun writeReadListAndSearchStayInsideWorkspace() {
        workspace.write("notes/one.txt", "alpha\nbeta\ngamma")

        assertTrue(workspace.read("notes/one.txt", 2, 3).contains("2: beta"))
        assertTrue(workspace.list("notes").contains("notes/one.txt"))
        assertTrue(workspace.search("GAMMA").contains("notes/one.txt:3: gamma"))
    }

    @Test
    fun replacingAnExecutableFileKeepsItsExecutePermission() {
        workspace.write("scripts/run.sh", "#!/bin/sh\necho before\n")
        val script = root.resolve("scripts/run.sh")
        assertTrue(script.setExecutable(true, true))
        workspace.read("scripts/run.sh")
        workspace.edit("scripts/run.sh", "before", "after")
        assertTrue(script.canExecute())
        assertTrue(script.readText().contains("after"))
    }

    @Test
    fun traversalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            workspace.write("../escape.txt", "no")
        }
    }

    @Test
    fun writesAndToolOutputsCannotEscapeThroughTraversalOrSymlink() {
        external = Files.createTempDirectory("local-harness-write-external").toFile()
        Files.createSymbolicLink(root.toPath().resolve("escape"), external!!.toPath())

        assertThrows(IllegalArgumentException::class.java) {
            workspace.write("../outside.txt", "blocked")
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspace.toolOutputFile("../outside.bin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspace.write("escape/outside.txt", "blocked")
        }
        assertTrue(!external!!.resolve("outside.txt").exists())
    }

    @Test
    fun editRequiresUniqueObservationAndGlobFindsFiles() {
        workspace.write("AGENT_GUARDRAILS.md", "top level\n")
        workspace.write("src/one.kt", "val before = 1\n")
        workspace.write("src/two.txt", "before before\n")
        workspace.read("src/one.kt")
        workspace.read("src/two.txt")

        assertEquals("已编辑 src/one.kt", workspace.edit("src/one.kt", "before", "after"))
        assertTrue(workspace.read("src/one.kt").contains("after"))
        assertTrue(workspace.glob("**/*.kt").contains("src/one.kt"))
        assertTrue(workspace.glob("**/AGENT_GUARDRAILS.md").contains("AGENT_GUARDRAILS.md"))
        assertTrue(workspace.glob("**/*.md").contains("AGENT_GUARDRAILS.md"))
        assertThrows(IllegalArgumentException::class.java) {
            workspace.edit("src/two.txt", "before", "after")
        }
    }

    @Test
    fun doubleStarSlashAlsoMatchesTopLevelFiles() {
        workspace.write("AGENT_GUARDRAILS.md", "top-level")
        workspace.write("nested/AGENT_GUARDRAILS.md", "nested")

        val matches = workspace.glob("**/AGENT_GUARDRAILS.md").lineSequence().toSet()

        assertTrue("AGENT_GUARDRAILS.md" in matches)
        assertTrue("nested/AGENT_GUARDRAILS.md" in matches)
    }

    @Test
    fun editErrorExplainsThatWriteDoesNotCountAsRead() {
        workspace.write("fresh.txt", "before")

        val error = assertThrows(IllegalStateException::class.java) {
            workspace.edit("fresh.txt", "before", "after")
        }

        assertTrue(error.message.orEmpty().contains("write 创建或覆盖文件不算读取"))
        assertTrue(error.message.orEmpty().contains("包含待替换内容"))
    }

    @Test
    fun editRequiresFreshObservationAndRejectsStaleReads() {
        workspace.write("src/fresh.txt", "before")

        assertThrows(IllegalStateException::class.java) {
            workspace.edit("src/fresh.txt", "before", "after")
        }

        workspace.read("src/fresh.txt")
        workspace.writeToolArtifact("src/fresh.txt", "changed elsewhere")
        assertThrows(IllegalArgumentException::class.java) {
            workspace.edit("src/fresh.txt", "changed", "after")
        }

        workspace.read("src/fresh.txt")
        assertEquals("已编辑 src/fresh.txt", workspace.edit("src/fresh.txt", "changed", "after"))
    }

    @Test
    fun literalAndRegexSearchAreExplicitAndBounded() {
        workspace.write("notes.md", "## 规范\n铁律\n")
        assertEquals("未找到匹配内容", workspace.search("^##"))
        assertTrue(workspace.search("^##", regex = true).contains("notes.md:1:"))
        assertTrue(workspace.search("铁律|规范", regex = true).contains("notes.md:2:"))
        assertEquals("未找到匹配内容", workspace.search("铁律|规范"))
        assertThrows(IllegalArgumentException::class.java) { workspace.search("[", regex = true) }
    }

    @Test
    fun recursiveDiscoveryDoesNotFollowSymlinksOutsideWorkspace() {
        external = Files.createTempDirectory("local-harness-external").toFile().apply {
            resolve("secret.txt").writeText("outside-secret")
        }
        Files.createSymbolicLink(root.toPath().resolve("escape"), external!!.toPath())

        assertTrue(!workspace.list().contains("secret.txt"))
        assertTrue(!workspace.glob("**/*.txt").contains("secret.txt"))
        assertEquals("未找到匹配内容", workspace.search("outside-secret"))
    }
    @Test
    fun toolArtifactsPreserveLargeStructuredContentForLaterSearch() {
        val middleMarker = "critical-middle-record"
        val content = buildString {
            append("{\"items\":[")
            repeat(12_000) { index ->
                if (index > 0) append(',')
                append("{\"id\":").append(index).append(",\"name\":\"")
                append(if (index == 6_000) middleMarker else "item-$index")
                append("\"}")
            }
            append("]}")
        }

        val path = workspace.writeToolArtifact(".dsh/fetches/large.json", content)

        assertEquals(".dsh/fetches/large.json", path)
        assertEquals(content, workspace.readRaw(path))
        assertTrue(workspace.read(path, 1, 1).contains(middleMarker))
        assertTrue(workspace.search(middleMarker, path).contains(middleMarker))
    }

    @Test
    fun recursiveSearchCanResumeBeyondFiveThousandEntriesWithoutFalseNoMatch() {
        val docs = root.resolve("docs")
        docs.mkdirs()
        repeat(5_050) { index ->
            docs.resolve("entry-" + index.toString().padStart(5, '0') + ".txt").writeText(
                if (index == 5_040) "needle-beyond-first-page" else "ordinary record",
            )
        }
        val first = workspace.search("needle-beyond-first-page")
        assertTrue(first.contains("搜索未完成"))
        val cursor = Regex("""cursor="([^"]+)"""").find(first)!!.groupValues[1]
        val second = workspace.search("needle-beyond-first-page", cursor = cursor)
        assertTrue(second.contains("entry-05040.txt:1: needle-beyond-first-page"))
        assertFalse(second.contains("搜索未完成"))
    }

    @Test
    fun globAndListCanPagePastLegacyFourHundredResultLimit() {
        val directory = root.resolve("many")
        directory.mkdirs()
        repeat(415) { i ->
            directory.resolve("item-" + i.toString().padStart(4, '0') + ".txt").writeText("record")
        }
        val firstList = workspace.list("many")
        assertTrue(firstList.contains("目录扫描未完"))
        val listCursor = Regex("""cursor=(\d+)""").find(firstList)!!.groupValues[1].toInt()
        assertTrue(workspace.list("many", cursor = listCursor).contains("item-0414.txt"))
        val firstGlob = workspace.glob("*.txt", "many")
        assertTrue(firstGlob.contains("目录扫描未完"))
        val globCursor = Regex("""cursor=(\d+)""").find(firstGlob)!!.groupValues[1].toInt()
        assertTrue(workspace.glob("*.txt", "many", cursor = globCursor).contains("item-0414.txt"))
    }

    @Test
    fun searchResultPageCarriesLineCursorInsteadOfSilentlyLosingMatches() {
        val text = (1..250).joinToString("\n") { "needle row $it" }
        root.resolve("rows.txt").writeText(text)
        val first = workspace.search("needle")
        assertTrue(first.contains("搜索未完成"))
        val cursor = Regex("""cursor="([^"]+)"""").find(first)!!.groupValues[1]
        val second = workspace.search("needle", cursor = cursor)
        assertTrue(second.contains("rows.txt:250: needle row 250"))
        assertFalse(second.contains("rows.txt:1:"))
    }

    @Test
    fun largeFileReadStreamsRequestedLinesWithoutSizeRejection() {
        val big = root.resolve("large.txt")
        big.bufferedWriter().use { writer ->
            repeat(6_000) { writer.append("record " + it + " " + "x".repeat(1_000) + "\n") }
        }
        val excerpt = workspace.read("large.txt", 5_998, 6_000)
        assertTrue(excerpt.contains("5999: record 5998"))
        assertTrue(excerpt.contains("6000: record 5999"))
    }

    @Test
    fun shellInheritsRuntimePathAndEnvironment() = runBlocking {
        val runtimeBin = root.resolve("runtime-bin").apply { mkdirs() }
        runtimeBin.resolve("fake-runtime").apply {
            writeText("#!/bin/sh\necho runtime-ok\n")
            setExecutable(true)
        }
        val isolated = LocalWorkspace(
            root = root,
            extraSearchPaths = { listOf(runtimeBin) },
            environmentProvider = { mapOf("HARNESS_TEST_ENV" to "ready") },
            shellExecutable = "/bin/sh",
        )

        val output = isolated.shell("printf \"\$HARNESS_TEST_ENV:\"; fake-runtime", 5)

        assertTrue(output.contains("ready:runtime-ok"))
    }

    @Test
    fun backgroundShellFailureModeRejectsNonZeroExit() = runBlocking {
        val isolated = LocalWorkspace(root = root, shellExecutable = "/bin/sh")

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                isolated.shell("echo before-fail; exit 137", 5, throwOnFailure = true)
            }
        }

        assertTrue(error.message.orEmpty().contains("PROCESS_EXIT_137"))
        assertTrue(error.message.orEmpty().contains("before-fail"))
    }

    @Test
    fun fileObservationCacheIsBoundedAndLru() {
        val cache = LocalFileObservationCache(maxEntries = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        assertEquals("1", cache.get("a"))
        cache.put("c", "3")

        assertEquals(2, cache.size())
        assertEquals("1", cache.get("a"))
        assertEquals("3", cache.get("c"))
        assertEquals(null, cache.get("b"))
    }

    @Test
    fun modelSkillsExposeMetadataButRejectUserOnlyInstructions() {
        val publicDir = root.resolve(".dsh/skills/research").apply { mkdirs() }
        publicDir.resolve("SKILL.md").writeText(
            "---\ndescription: Verify sources\nwhen-to-use: During research\n---\n# Steps",
        )
        val privateDir = root.resolve(".dsh/skills/private-notes").apply { mkdirs() }
        privateDir.resolve("SKILL.md").writeText(
            "---\ndescription: User-only workspace\ndisable-model-invocation: true\n---\n# Notes",
        )

        val catalog = workspace.modelSkillCatalog()
        assertTrue(catalog.contains("research：Verify sources"))
        assertTrue(catalog.contains("适用：During research"))
        assertFalse(catalog.contains("private-notes"))
        assertTrue(workspace.readModelSkill("research").contains("# Steps"))
        assertTrue(workspace.skills().contains("private-notes"))
        assertTrue(workspace.readSkill("private-notes").contains("# Notes"))
        assertThrows(IllegalArgumentException::class.java) {
            workspace.readModelSkill("private-notes")
        }
    }

    @Test
    fun modelSkillCatalogFiltersBeforePagingAndCanReachSkillsAfter128Entries() {
        val skills = root.resolve(".dsh/skills")
        repeat(132) { i ->
            val dir = skills.resolve("private-" + i.toString().padStart(3, '0'))
            dir.mkdirs()
            dir.resolve("SKILL.md").writeText("---\ndescription: private\ndisable-model-invocation: true\n---\n")
        }
        val public = skills.resolve("zz-public")
        public.mkdirs()
        public.resolve("SKILL.md").writeText("---\ndescription: Public review\nwhen-to-use: code review\n---\n")
        val catalog = workspace.modelSkillCatalog()
        assertTrue(catalog.contains("zz-public：Public review"))
        assertFalse(catalog.contains("private-"))
    }

    @Test
    fun modelSkillCatalogOffersExplicitContinuationForLongDirectory() {
        val skills = root.resolve(".dsh/skills")
        repeat(132) { i ->
            val dir = skills.resolve("public-" + i.toString().padStart(3, '0'))
            dir.mkdirs()
            dir.resolve("SKILL.md").writeText("---\ndescription: review code\n---\n")
        }
        val first = workspace.modelSkillCatalog()
        assertTrue(first.contains("skill(offset=128"))
        assertTrue(workspace.modelSkillCatalog(offset = 128).contains("public-131"))
        val compact = workspace.modelSkillCatalog(maxChars = 180)
        assertTrue(compact.contains("目录未完"))
    }

    @Test
    fun fileObservationCacheRejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalFileObservationCache(maxEntries = 0)
        }
    }


    @Test
    fun skillReadsCannotEscapeSkillsDirectoryAndSymlinkSkillsAreHidden() {
        workspace.write(".dsh/skills/good/SKILL.md", "good-skill")
        workspace.write(".dsh/private/SKILL.md", "private-skill")
        external = Files.createTempDirectory("local-harness-external-skill").toFile().apply {
            resolve("SKILL.md").writeText("external-skill")
        }
        val skillsDir = root.resolve(".dsh/skills")
        Files.createSymbolicLink(skillsDir.toPath().resolve("external"), external!!.toPath())

        assertTrue(workspace.skills().contains("good"))
        assertTrue(!workspace.skills().contains("external"))
        assertTrue(workspace.readSkill("good").contains("good-skill"))

        assertThrows(IllegalArgumentException::class.java) {
            workspace.readSkill("../private")
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspace.readSkill("../../private")
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspace.readSkill("external")
        }
    }


    @Test
    fun skillRemovedAfterDiscoveryDoesNotReturnStaleInstructions() {
        workspace.write(".dsh/skills/volatile/SKILL.md", "original-skill")
        assertTrue(workspace.skills().contains("volatile"))

        root.resolve(".dsh/skills/volatile").deleteRecursively()

        assertTrue(!workspace.skills().contains("volatile"))
        assertThrows(IllegalArgumentException::class.java) {
            workspace.readSkill("volatile")
        }
    }

    @Test
    fun skillReplacedByExternalSymlinkAfterDiscoveryCannotEscapeWorkspace() {
        workspace.write(".dsh/skills/volatile/SKILL.md", "original-skill")
        assertTrue(workspace.skills().contains("volatile"))
        root.resolve(".dsh/skills/volatile").deleteRecursively()
        external = Files.createTempDirectory("local-harness-swapped-skill").toFile().apply {
            resolve("SKILL.md").writeText("external-secret")
        }
        Files.createSymbolicLink(
            root.resolve(".dsh/skills/volatile").toPath(),
            external!!.toPath(),
        )

        assertTrue(!workspace.skills().contains("volatile"))
        assertThrows(IllegalArgumentException::class.java) {
            workspace.readSkill("volatile")
        }
    }

}
