package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.agent.AgentEnvironmentToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentWorkspaceEnvironmentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun invocation(name: String, mutation: Boolean = true, vararg values: Pair<String, String>) =
        AgentEnvironmentToolRequest(name, buildJsonObject { values.forEach { put(it.first, it.second) } }, "{}",
            mutation, "session", "call", "execution")

    @Test fun fileEffectsAndFreshObservationBelongToTheRemoteRoot() = runBlocking {
        val root = temporary.newFolder("remote")
        val environment = AgentWorkspaceEnvironment.create(root)
        try {
            val written = environment.execute(invocation("write", true, "path" to "src/a", "content" to "old"))
            assertEquals("old", java.io.File(root, "src/a").readText())
            assertEquals(64, written.fileVersions.getValue("src/a").length)
            assertTrue(runCatching { environment.execute(invocation("edit", true,
                "path" to "src/a", "old_text" to "old", "new_text" to "forged",
                "observed" to written.fileVersions.getValue("src/a"))) }.isFailure)
            assertEquals("old", java.io.File(root, "src/a").readText())
            assertTrue(runCatching { environment.execute(invocation("edit", true,
                "path" to "src/a", "old_text" to "old", "new_text" to "new")) }.isFailure)
            assertEquals("1: old", environment.execute(invocation("read", false, "path" to "src/a")).content)
            environment.execute(invocation("edit", true, "path" to "src/a", "old_text" to "old", "new_text" to "new"))
            assertEquals("new", java.io.File(root, "src/a").readText())
            java.io.File(root, "src/a").writeText("external change")
            assertTrue(runCatching { environment.execute(invocation("edit", true,
                "path" to "src/a", "old_text" to "new", "new_text" to "overwrite")) }.isFailure)
            assertEquals("external change", java.io.File(root, "src/a").readText())
        } finally { environment.close() }
    }

    @Test fun editingAnExecutablePreservesItsExecutionPermission() = runBlocking {
        val root = temporary.newFolder("remote")
        val file = java.io.File(root, "script.sh").apply {
            writeText("#!/bin/sh\nprintf old")
            assertTrue(setExecutable(true, true))
        }
        val environment = AgentWorkspaceEnvironment.create(root)
        try {
            environment.execute(invocation("read", false, "path" to "script.sh"))
            environment.execute(invocation("edit", true, "path" to "script.sh", "old_text" to "old", "new_text" to "new"))
            assertTrue(file.canExecute())
            assertEquals("#!/bin/sh\nprintf new", file.readText())
        } finally { environment.close() }
    }

    @Test fun readonlyTraversalAndSymlinkCannotWriteOutsideRemoteRoot() = runBlocking {
        val root = temporary.newFolder("remote")
        val outside = temporary.newFile("outside")
        outside.writeText("protected")
        java.nio.file.Files.createSymbolicLink(java.io.File(root, "link").toPath(), outside.toPath())
        val environment = AgentWorkspaceEnvironment.create(root)
        try {
            for ((path, writable) in listOf("../outside" to true, "link" to true, "local" to false)) {
                assertTrue(runCatching { environment.execute(invocation("write", writable,
                    "path" to path, "content" to "changed")) }.isFailure)
            }
            assertEquals("protected", outside.readText())
            assertFalse(java.io.File(root, "local").exists())
        } finally { environment.close() }
    }

    @Test fun nativeProcessReturnsRealExitCodeAndRemoteWorkingDirectory() = runBlocking {
        val root = temporary.newFolder("remote")
        val environment = AgentWorkspaceEnvironment.create(root)
        try {
            val input = buildJsonObject { put("command", buildJsonArray {
                add("sh"); add("-c"); add("pwd; printf remote > process-file; exit 7")
            }) }
            val result = environment.execute(AgentEnvironmentToolRequest("process_exec", input, input.toString(),
                true, "s", "c", "e"))
            assertTrue(result.isError)
            assertEquals(7, result.processExitCode)
            assertTrue(result.content.contains(root.canonicalPath))
            assertEquals("remote", java.io.File(root, "process-file").readText())
        } finally { environment.close() }
    }
    @Test fun defaultRemoteTerminalUsesAvailableShellAndPreservesExitReceipt() = runBlocking {
        val root = temporary.newFolder("remote")
        val environment = AgentWorkspaceEnvironment.create(root)
        fun request(name: String, input: JsonObject) = AgentEnvironmentToolRequest(name, input, input.toString(),
            true, "s", "c", "e")
        try {
            val terminal = environment.execute(request("terminal_open", buildJsonObject { put("tty", false) })).content
            environment.execute(request("terminal_write", buildJsonObject {
                put("session_id", terminal); put("input", "printf terminal-remote; exit 7\n")
            }))
            val output = StringBuilder()
            val final = kotlinx.coroutines.withTimeout(20_000) {
                var read: com.labteto.dshmobile.harness.tools.ToolResult
                do {
                    read = environment.execute(request("terminal_read", buildJsonObject { put("session_id", terminal) }))
                    output.append(read.content)
                    if (read.processExitCode == null) kotlinx.coroutines.delay(10)
                } while (read.processExitCode == null)
                read
            }
            assertTrue(output.contains("terminal-remote"))
            assertEquals(7, final.processExitCode)
            assertTrue(final.isError)
        } finally { environment.close() }
    }

}
