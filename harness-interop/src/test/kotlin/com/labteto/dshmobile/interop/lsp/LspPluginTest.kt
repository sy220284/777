package com.labteto.dshmobile.interop.lsp

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.resource.HarnessResourceBudget
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.harness.tools.ToolContext
import java.io.File
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LspPluginTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun registersHiddenAutomaticSemanticTools() = runTest {
        val context = HarnessContext()
        val plugin = LspPlugin(temporary.root, Json, { emptyList() })
        plugin.install(context)
        assertEquals(9, context.tools.names().size)
        assertFalse("lsp_start" in context.tools.names())
        assertFalse("lsp_stop" in context.tools.names())
        assertTrue("lsp_implementation" in context.tools.names())
        assertTrue("lsp_workspace_symbols" in context.tools.names())
        assertTrue("lsp_rename_preview" in context.tools.names())
        assertTrue("lsp_diagnostics" in context.tools.names())

        val status = context.tools.execute("lsp_status", JsonObject(emptyMap()))
        assertFalse(status.isError)
        assertTrue(status.content.contains("未检测到"))

        plugin.uninstall(context)
        assertTrue(context.tools.names().isEmpty())
    }

    @Test fun rejectsPathTraversalAndAbsolutePaths() {
        assertTrue(runCatching { LspPlugin.workspaceFile(temporary.root, "../outside.kt") }.isFailure)
        assertTrue(runCatching { LspPlugin.workspaceFile(temporary.root, "/tmp/outside.kt") }.isFailure)
        assertEquals(temporary.root.resolve("a.kt"), LspPlugin.workspaceFile(temporary.root, "a.kt"))
    }

    @Test fun mapsFileExtensionsToLanguageIds() {
        assertEquals("kotlin", LspPlugin.languageId(File("a.kt")))
        assertEquals("typescriptreact", LspPlugin.languageId(File("view.tsx")))
        assertEquals("python", LspPlugin.languageId(File("tool.py")))
        assertEquals("cpp", LspPlugin.languageId(File("native.cpp")))
    }

    @Test fun semanticQueryStartsServerOnceAfterApprovalAndReusesIt() = runBlocking {
        Assume.assumeTrue(File("/bin/sh").isFile)
        val initialized = """{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}"""
        val hover1 = """{"jsonrpc":"2.0","id":2,"result":{"contents":"example type"}}"""
        val hover2 = """{"jsonrpc":"2.0","id":3,"result":{"contents":"example type again"}}"""
        fun frame(message: String) = "Content-Length: ${message.toByteArray().size}\r\n\r\n$message"
        val script = temporary.newFile("server.sh")
        script.writeText(
            "printf '%s' '" + frame(initialized) + frame(hover1) + frame(hover2) + "'\nexec cat >/dev/null\n",
        )
        temporary.newFile("example.txt").writeText("example")
        var approvals = 0
        val context = HarnessContext()
        val plugin = LspPlugin(
            temporary.root,
            Json,
            command = { listOf("/bin/sh", script.absolutePath) },
        )
        plugin.install(context)
        try {
            withTimeout(5_000) {
                val toolContext = ToolContext(approval = {
                    approvals += 1
                    true
                })
                val first = context.tools.execute(
                    "lsp_hover",
                    buildJsonObject { put("path", "example.txt") },
                    context = toolContext,
                )
                val second = context.tools.execute(
                    "lsp_hover",
                    buildJsonObject { put("path", "example.txt") },
                    context = toolContext,
                )
                assertTrue(first.content.contains("example type"))
                assertTrue(second.content.contains("example type again"))
                assertEquals(1, approvals)
            }
        } finally {
            plugin.uninstall(context)
        }
    }

    @Test fun diagnosticsCancellationIsNotSwallowed() = runBlocking {
        Assume.assumeTrue(File("/bin/sh").isFile)
        val initialized = """{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}"""
        fun frame(message: String) = "Content-Length: ${message.toByteArray().size}\r\n\r\n$message"
        val script = temporary.newFile("slow-server.sh")
        script.writeText(
            "printf '%s' '" + frame(initialized) + "'\nwhile IFS= read -r line; do :; done\n",
        )
        temporary.newFile("example.kt").writeText("fun main() = Unit")
        val context = HarnessContext()
        val plugin = LspPlugin(
            temporary.root,
            Json,
            command = { listOf("/bin/sh", script.absolutePath) },
        )
        plugin.install(context)
        try {
            var timedOut = false
            try {
                withTimeout(250) {
                    context.tools.execute(
                        "lsp_diagnostics",
                        buildJsonObject { put("path", "example.kt") },
                        context = ToolContext(approval = { true }),
                    )
                }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
            }
            assertTrue("LSP diagnostics swallowed coroutine cancellation", timedOut)
        } finally {
            plugin.uninstall(context)
        }
    }

    @Test fun firstSemanticQueryWithoutProcessApprovalReturnsRecoveryHint() = runTest {
        temporary.newFile("example.kt").writeText("fun main() = Unit")
        val context = HarnessContext()
        val plugin = LspPlugin(temporary.root, Json, { listOf("mock-language-server") })
        plugin.install(context)
        try {
            val result = context.tools.execute(
                "lsp_diagnostics",
                buildJsonObject { put("path", "example.kt") },
            )
            assertTrue(result.isError)
            assertEquals("LSP_START_APPROVAL_REQUIRED", result.errorCode)
            assertTrue(result.recoveryHint.orEmpty().contains("批准"))
        } finally {
            plugin.uninstall(context)
        }
    }

    @Test fun missingServerFailsWithUsefulFallback() = runTest {
        temporary.newFile("example.kt").writeText("fun main() = Unit")
        val context = HarnessContext()
        val plugin = LspPlugin(temporary.root, Json, { emptyList() })
        plugin.install(context)
        try {
            val result = context.tools.execute(
                "lsp_hover",
                buildJsonObject { put("path", "example.kt") },
                context = ToolContext(approval = { true }),
            )
            assertTrue(result.isError)
            assertEquals("LSP_SERVER_UNAVAILABLE", result.errorCode)
            assertTrue(result.recoveryHint.orEmpty().contains("read、grep、glob"))
        } finally {
            plugin.uninstall(context)
        }
    }

    @Test fun cancellingStopStillClosesEveryLanguageServerLease() = runBlocking {
        Assume.assumeTrue(File("/bin/sh").isFile)
        fun frame(message: String) = "Content-Length: ${message.toByteArray().size}\r\n\r\n$message"
        fun server(name: String): File {
            val initialized = """{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}"""
            val hover = """{"jsonrpc":"2.0","id":2,"result":{"contents":"$name"}}"""
            return temporary.newFile("$name.sh").apply {
                writeText(
                    "printf '%s' '" + frame(initialized) + frame(hover) +
                        "'\nwhile IFS= read -r line; do :; done\n",
                )
            }
        }

        val firstServer = server("first")
        val secondServer = server("second")
        temporary.newFile("a.kt").writeText("val a = 1")
        temporary.newFile("b.kt").writeText("val b = 2")
        val scheduler = HarnessResourceScheduler(
            HarnessResourceBudget(
                maxModelRequests = 1,
                maxAgents = 1,
                maxLanguageServers = 2,
            ),
        )
        val context = HarnessContext()
        val plugin = LspPlugin(
            root = temporary.root,
            json = Json,
            command = { path ->
                listOf(
                    "/bin/sh",
                    if (path == "a.kt") firstServer.absolutePath else secondServer.absolutePath,
                )
            },
            resourceScheduler = scheduler,
        )
        plugin.install(context)
        try {
            val toolContext = ToolContext(approval = { true })
            withTimeout(5_000) {
                context.tools.execute(
                    "lsp_hover",
                    buildJsonObject { put("path", "a.kt") },
                    context = toolContext,
                )
                context.tools.execute(
                    "lsp_hover",
                    buildJsonObject { put("path", "b.kt") },
                    context = toolContext,
                )
            }
            assertEquals(2, scheduler.snapshot().activeLanguageServers)

            var cancelled = false
            try {
                withTimeout(250) { plugin.stop() }
            } catch (_: TimeoutCancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertEquals(0, scheduler.snapshot().activeLanguageServers)
            assertTrue(scheduler.snapshot().leases.isEmpty())
        } finally {
            runCatching { plugin.uninstall(context) }
        }
    }

}
