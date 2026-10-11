package com.labteto.dshmobile.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.runtime.BundledGitRuntime
import com.labteto.dshmobile.local.runtime.BundledRuntimeLibraryStore
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledGitRuntimeTest {
    @Test
    fun parallelGitRuntimeInstancesDoNotReplaceEachOthersLibraries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "x86_64" || it == "arm64-v8a" }
            ?: return@runBlocking
        val instances = List(6) { BundledGitRuntime(context) }
        coroutineScope {
            instances.map { runtime -> async(Dispatchers.IO) { runtime.prepare() } }.awaitAll()
        }
        val runtime = instances.first()
        val environment = runtime.environment()
        val paths = requireNotNull(environment["LD_LIBRARY_PATH"]).split(File.pathSeparator)
        val gitLibraries = File(paths.first())
        assertTrue("Git PCRE2 dependency missing after parallel prepare", File(gitLibraries, "libpcre2-8.so").isFile)
        assertTrue(
            "Git library checksum or manifest invalid after parallel prepare",
            BundledRuntimeLibraryStore.isMaterializedValid(context, "git", abi, gitLibraries),
        )
        val git = File(runtime.binDir, "git")
        repeat(3) {
            val process = ProcessBuilder(git.absolutePath, "--version")
                .apply { environment().putAll(environment) }
                .redirectErrorStream(true)
                .start()
            assertTrue("parallel Git startup timed out", process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals("Git linker failed: $output", 0, process.exitValue())
        }
    }

    @Test
    fun bundledGitRunsCoreRepositoryWorkflowOnAndroid16() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = BundledGitRuntime(context)
        runtime.prepare()

        val git = File(runtime.binDir, "git")
        assertTrue("git command link is missing", git.exists())
        val environment = runtime.environment()
        val helperDir = File(requireNotNull(environment["GIT_EXEC_PATH"]))
        assertTrue("git-remote-http helper is missing", File(helperDir, "git-remote-http").exists())
        assertTrue("git-remote-https helper is missing", File(helperDir, "git-remote-https").exists())

        val work = File(context.cacheDir, "git-runtime-test").apply {
            deleteRecursively()
            mkdirs()
        }

        fun gitCommand(vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder(listOf(git.absolutePath) + args)
                .directory(work)
                .apply { environment().putAll(environment) }
                .redirectErrorStream(true)
                .start()
            assertTrue("bundled Git timed out: ${args.joinToString(" ")}", process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            return process.exitValue() to output
        }

        val (versionCode, version) = gitCommand("--version")
        assertEquals("git --version failed: $version", 0, versionCode)
        assertTrue("unexpected Git version: $version", version.contains("2.56.0"))

        val (initCode, initOutput) = gitCommand("init")
        assertEquals("git init failed: $initOutput", 0, initCode)

        File(work, "hello.txt").writeText("hello\n")
        val (untrackedCode, untracked) = gitCommand("status", "--porcelain")
        assertEquals("git status failed: $untracked", 0, untrackedCode)
        assertTrue("untracked file missing: $untracked", untracked.contains("hello.txt"))

        val (addCode, addOutput) = gitCommand("add", "hello.txt")
        assertEquals("git add failed: $addOutput", 0, addCode)

        val (diffCode, staged) = gitCommand("diff", "--cached", "--name-only")
        assertEquals("git diff failed: $staged", 0, diffCode)
        assertEquals("hello.txt", staged)

        val (commitCode, commitOutput) = gitCommand(
            "-c", "user.name=DSH Test",
            "-c", "user.email=dsh@example.invalid",
            "commit", "-m", "initial",
        )
        assertEquals("git commit failed: $commitOutput", 0, commitCode)

        val (logCode, subject) = gitCommand("log", "-1", "--pretty=%s")
        assertEquals("git log failed: $subject", 0, logCode)
        assertEquals("initial", subject)

        val (cleanCode, clean) = gitCommand("status", "--porcelain")
        assertEquals("final git status failed: $clean", 0, cleanCode)
        assertTrue("repository should be clean: $clean", clean.isEmpty())
    }
}
