package com.labteto.dshmobile.interop.lsp

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.tools.ToolAccess
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.labteto.dshmobile.harness.tools.ToolContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LspPluginTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun registersToolsAndRequiresApprovalBeforeStartingProcess() = runTest {
        val context = HarnessContext()
        val plugin = LspPlugin(temporary.root, Json, { error("must not execute") })
        plugin.install(context)
        assertEquals(7, context.tools.names().size)
        assertEquals(ToolAccess.PROCESS, context.tools.get("lsp_start")!!.access)
        assertTrue(context.tools.execute("lsp_start", JsonObject(emptyMap())).isError)
        assertTrue(context.tools.execute("lsp_hover", buildJsonObject { put("path", "a.kt") }).isError)
        plugin.uninstall(context)
        assertTrue(context.tools.names().isEmpty())
    }

    @Test fun rejectsPathTraversalAndAbsolutePaths() {
        assertTrue(runCatching { LspPlugin.workspaceFile(temporary.root, "../outside.kt") }.isFailure)
        assertTrue(runCatching { LspPlugin.workspaceFile(temporary.root, "/tmp/outside.kt") }.isFailure)
        assertEquals(temporary.root.resolve("a.kt"), LspPlugin.workspaceFile(temporary.root, "a.kt"))
    }

    @Test fun modelToolCanInitializeOpenFileQueryAndStopServer() = runBlocking {
        org.junit.Assume.assumeTrue(java.io.File("/bin/sh").isFile)
        val initialized = """{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}"""
        val hover = """{"jsonrpc":"2.0","id":2,"result":{"contents":"example type"}}"""
        fun frame(message: String) = "Content-Length: ${message.toByteArray().size}\r\n\r\n$message"
        val script = temporary.newFile("server.sh")
        script.writeText("printf '%s' '" + frame(initialized) + frame(hover) + "'\nexec cat >/dev/null\n")
        temporary.newFile("example.txt").writeText("example")
        val context = HarnessContext()
        val plugin = LspPlugin(temporary.root, Json, { listOf("/bin/sh", script.absolutePath) })
        plugin.install(context)
        try {
            withTimeout(5_000) {
                assertFalse(context.tools.execute("lsp_start", JsonObject(emptyMap()),
                    context = ToolContext(approval = { true })).isError)
                val result = context.tools.execute("lsp_hover", buildJsonObject { put("path", "example.txt") })
                assertTrue(result.content.contains("example type"))
                assertFalse(context.tools.execute("lsp_stop", JsonObject(emptyMap()),
                    context = ToolContext(approval = { true })).isError)
            }
        } finally { plugin.uninstall(context) }
    }
}
