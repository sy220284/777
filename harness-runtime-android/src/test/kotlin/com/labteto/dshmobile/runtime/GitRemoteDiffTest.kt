package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.capability.ProcessRequest
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitRemoteDiffTest {
    @Test fun rejectsRefsWithOptionInjectionAndParentTraversal() {
        assertFalse(validGitDiffRemoteName("--upload-pack=sh"))
        assertFalse(validGitDiffRemoteName("../evil"))
        assertFalse(validGitDiffBranchName("../main"))
        assertFalse(validGitDiffBranchName("main..HEAD"))
        assertFalse(validGitDiffBranchName("refs/main.lock"))
        assertTrue(validGitDiffRemoteName("origin"))
        assertTrue(validGitDiffBranchName("release/v2.1"))
    }

    @Test fun readsTrackedChangesWithoutNetworkOrModifyingIndex() = runBlocking {
        val home = kotlin.io.path.createTempDirectory("remote-diff-").toFile()
        val root = home.resolve("source").apply { mkdirs() }
        val process = AndroidProcessRuntime(defaultWorkingDirectory = root)
        suspend fun git(vararg command: String): String {
            val result = process.execute(ProcessRequest(
                command = listOf("git") + command, timeoutMillis = 10_000,
            ))
            assertEquals(result.stderr, 0, result.exitCode)
            return result.stdout.trim()
        }
        try {
            git("init", "-q")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@localhost")
            root.resolve("source.txt").writeText("before\n")
            git("add", ".")
            git("commit", "-qm", "baseline")
            git("update-ref", "refs/remotes/origin/main", "HEAD")
            git("update-ref", "refs/remotes/origin/release/v2", "HEAD")
            assertEquals(listOf("origin/main", "origin/release/v2"),
                GitRemoteDiff(root, process).trackingBranches("."))
            assertTrue(runCatching {
                GitRemoteDiff(root, process).trackingBranches("../")
            }.isFailure)

            val expectedIndex = git("status", "--porcelain")
            root.resolve("source.txt").writeText("after\n")
            root.resolve("untracked.txt").writeText("hidden-new-file\n")
            val registry = PluginRegistry()
            registry.install(AndroidRuntimePlugin(root, processRuntime = process))
            val result = registry.context.tools.execute("git_diff_remote", buildJsonObject {
                put("remote", "origin")
                put("branch", "main")
            }, context = ToolContext(sessionId = "test-session"))
            assertFalse(result.content, result.isError)
            val receipt = Json.parseToJsonElement(result.content).jsonObject
            assertTrue(receipt.getValue("patch").jsonPrimitive.content.contains("+after"))
            assertFalse(receipt.getValue("patch").jsonPrimitive.content.contains("hidden-new-file"))
            assertEquals("false", receipt.getValue("network_requested").jsonPrimitive.content)
            assertTrue(root.resolve("untracked.txt").exists())
            // A diff can change observed worktree status but must never stage the changes.
            assertEquals("", git("diff", "--cached", "--name-only"))
            assertTrue(expectedIndex.isEmpty())
            val rejected = registry.context.tools.execute("git_diff_remote", buildJsonObject {
                put("branch", "../main")
            }, context = ToolContext(sessionId = "test-session"))
            assertTrue(rejected.isError)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun uncommittedReviewDiffWorksWithNoRemote() = runBlocking {
        val home = kotlin.io.path.createTempDirectory("local-review-git-").toFile()
        val root = home.resolve("source").apply { mkdirs() }
        val process = AndroidProcessRuntime(defaultWorkingDirectory = root)
        suspend fun git(vararg command: String) {
            val result = process.execute(ProcessRequest(
                command = listOf("git") + command, timeoutMillis = 10_000,
            ))
            assertEquals(result.stderr, 0, result.exitCode)
        }
        try {
            git("init", "-q")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@localhost")
            root.resolve("file.txt").writeText("old\\n")
            git("add", ".")
            git("commit", "-qm", "initial")
            root.resolve("file.txt").writeText("new\\n")
            root.resolve("other.txt").writeText("untracked secret\\n")
            val registry = PluginRegistry()
            registry.install(AndroidRuntimePlugin(root, processRuntime = process))
            val before = GitRemoteDiff(root, process).compareUncommitted(".")
            val tool = registry.context.tools.execute("git_diff_uncommitted",
                buildJsonObject { put("repository", ".") },
                context = ToolContext(sessionId = "review-session"))
            assertFalse(tool.content, tool.isError)
            val result = Json.parseToJsonElement(tool.content).jsonObject
            assertEquals(before.getValue("tracking_ref"), result.getValue("tracking_ref"))
            assertTrue(result.getValue("tracking_ref").jsonPrimitive.content.startsWith("worktree/"))
            assertTrue(result.getValue("patch").jsonPrimitive.content.contains("+new"))
            assertFalse(result.getValue("patch").jsonPrimitive.content.contains("untracked secret"))
            assertEquals("false", result.getValue("network_requested").jsonPrimitive.content)
            assertEquals("1", result.getValue("untracked_file_count").jsonPrimitive.content)
            git("diff", "--cached", "--exit-code")
            assertTrue(root.resolve("other.txt").exists())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun remoteTrackingRefMustExist() = runBlocking {
        val home = kotlin.io.path.createTempDirectory("remote-diff-missing-").toFile()
        val root = home.resolve("source").apply { mkdirs() }
        val process = AndroidProcessRuntime(defaultWorkingDirectory = root)
        try {
            val registry = PluginRegistry()
            registry.install(AndroidRuntimePlugin(root, processRuntime = process))
            val result = registry.context.tools.execute("git_diff_remote", buildJsonObject {
                put("remote", "origin")
                put("branch", "nonexistent")
            }, context = ToolContext(sessionId = "test-session"))
            assertTrue(result.isError)
        } finally {
            home.deleteRecursively()
        }
    }
}
