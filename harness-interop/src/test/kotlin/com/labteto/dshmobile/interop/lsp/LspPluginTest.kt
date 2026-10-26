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

    /** Only respond when the matching request was actually received. Notifications are not RPCs. */
    private fun requestDrivenMockLsp(vararg replies: String): String {
        val prelude = """
            read_rpc() {
              while :; do
                IFS= read -r header || exit 31
                header=§(printf '%s' "§header" | tr -d '\r')
                length=§(printf '%s\n' "§header" | sed -n 's/^Content-Length: //p')
                [ -n "§length" ] || exit 32
                while IFS= read -r line; do
                  line=§(printf '%s' "§line" | tr -d '\r')
                  [ -z "§line" ] && break
                done
                payload=§(dd bs=1 count="§length" 2>/dev/null)
                case "§payload" in
                  *'"id":'*) return 0 ;;
                esac
              done
            }
        """.trimIndent().replace('§', '$')
        val responses = replies.joinToString("\n") { json ->
            val framed = "Content-Length: ${json.toByteArray().size}\r\n\r\n$json"
            "read_rpc\nprintf '%s' '$framed'"
        }
        return "$prelude\n$responses\nwhile IFS= read -r line; do :; done\n"
    }

    @Test fun defaultInitializeCapabilitiesEnablePushDiagnostics() {
        val textDocument = DEFAULT_LSP_CLIENT_CAPABILITIES["textDocument"] as? JsonObject
        assertTrue("客户端必须声明 textDocument 能力", textDocument != null)
        assertTrue("TypeScript 语言服务器依赖此声明才推送诊断",
            textDocument?.get("publishDiagnostics") is JsonObject)
    }

    @Test fun registersHiddenAutomaticSemanticTools() = runTest {
        val context = HarnessContext()
        val plugin = LspPlugin(temporary.root, Json, { emptyList() })
        plugin.install(context)
        assertEquals(10, context.tools.names().size)
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
        script.writeText(requestDrivenMockLsp(initialized, hover1, hover2))
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
                writeText(requestDrivenMockLsp(initialized, hover))
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


    @Test fun onDemandInstallationOccursOnlyOnFirstSemanticQueryAndRequiresApproval() = runBlocking {
        Assume.assumeTrue(File("/bin/sh").isFile)
        fun frame(json: String) =
            "Content-Length: " + json.toByteArray().size + "\r\n\r\n" + json
        val server = temporary.newFile("on-demand-server.sh")
        server.writeText(requestDrivenMockLsp(
            """{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}""",
            """{"jsonrpc":"2.0","id":2,"result":{"contents":"first"}}""",
            """{"jsonrpc":"2.0","id":3,"result":{"contents":"second"}}""",
        ))
        temporary.newFile("sample.ts").writeText("const answer = 42")
        var installs = 0
        var approvals = 0
        var installed = emptyList<String>()
        val context = HarnessContext()
        val plugin = LspPlugin(
            root = temporary.root,
            json = Json,
            command = { installed },
            canInstallOnDemand = { it?.endsWith(".ts") == true },
            installOnDemand = {
                installs++
                listOf("/bin/sh", server.absolutePath).also { installed = it }
            },
        )
        plugin.install(context)
        try {
            val status = context.tools.execute("lsp_status", JsonObject(emptyMap()))
            assertFalse(status.isError)
            assertEquals(0, installs)
            assertEquals(0, approvals)
            withTimeout(5_000) {
                val scope = ToolContext(approval = { approvals++; true })
                val first = context.tools.execute("lsp_hover",
                    buildJsonObject { put("path", "sample.ts") }, context = scope)
                val second = context.tools.execute("lsp_hover",
                    buildJsonObject { put("path", "sample.ts") }, context = scope)
                assertTrue(first.content.contains("first"))
                assertTrue(second.content.contains("second"))
            }
            assertEquals(1, installs)
            assertEquals(2, approvals) // download + first process launch
        } finally {
            plugin.uninstall(context)
        }
    }

    @Test fun rejectingDownloadNeverExecutesInstaller() = runTest {
        temporary.newFile("sample.py").writeText("a = 1")
        var installs = 0
        val context = HarnessContext()
        val plugin = LspPlugin(
            root = temporary.root,
            json = Json,
            command = { emptyList() },
            canInstallOnDemand = { true },
            installOnDemand = { installs++; listOf("node", "server.js") },
        )
        plugin.install(context)
        try {
            val result = context.tools.execute(
                "lsp_hover",
                buildJsonObject { put("path", "sample.py") },
                context = ToolContext(approval = { false }),
            )
            assertTrue(result.isError)
            assertEquals(0, installs)
        } finally {
            plugin.uninstall(context)
        }
    }


    @Test fun readOnlySubagentAndPlanModeCannotDownloadComponents() = runTest {
        temporary.newFile("check.py").writeText("value = 1")
        var installs = 0
        var approvals = 0
        val context = HarnessContext()
        val plugin = LspPlugin(
            root = temporary.root,
            json = Json,
            command = { emptyList() },
            canInstallOnDemand = { true },
            installOnDemand = { installs++; listOf("node", "server.js") },
        )
        plugin.install(context)
        try {
            for (scope in listOf(
                ToolContext(allowMutation = false, approval = { approvals++; true }),
                ToolContext(attributes = mapOf("plan_mode" to true),
                    approval = { approvals++; true }),
            )) {
                val result = context.tools.execute(
                    "lsp_hover",
                    buildJsonObject { put("path", "check.py") },
                    context = scope,
                )
                assertTrue(result.isError)
            }
            assertEquals(0, installs)
            assertEquals(0, approvals)
        } finally {
            plugin.uninstall(context)
        }
    }


    @Test fun componentRemovalRequiresApprovalAndCallsManagedUninstaller() = runTest {
        var removed = 0
        val context = HarnessContext()
        val plugin = LspPlugin(
            root = temporary.root,
            json = Json,
            command = { emptyList() },
            managedStatus = { "typescript 6.0.1 已安装" },
            uninstallOnDemand = { id -> removed++; id == "typescript" },
        )
        plugin.install(context)
        try {
            val status = context.tools.execute("lsp_status", JsonObject(emptyMap()))
            assertTrue(status.content.contains("typescript 6.0.1 已安装"))
            assertEquals(0, removed)
            val rejected = context.tools.execute(
                "lsp_uninstall_component",
                buildJsonObject { put("component", "typescript") },
                context = ToolContext(approval = { false }),
            )
            assertTrue(rejected.isError)
            assertEquals(0, removed)
            val ok = context.tools.execute(
                "lsp_uninstall_component",
                buildJsonObject { put("component", "typescript") },
                context = ToolContext(approval = { true }),
            )
            assertFalse(ok.isError)
            assertEquals(1, removed)
        } finally {
            plugin.uninstall(context)
        }
    }


    @Test fun diagnosticsCanArriveAfterInitializeWithoutPendingRequest() = runBlocking {
        Assume.assumeTrue(File("/bin/sh").isFile)
        val file = temporary.newFile("late.ts").apply { writeText("const value: string = 123;\n") }
        val uri = file.toURI().toString()
        // Real TypeScript servers can publish file:/// while Java File.toURI gives file:/.
        val publishedUri = java.net.URI("file", "", file.toURI().path, null).toString()
        fun frame(message: String) = "Content-Length: ${message.toByteArray().size}\r\n\r\n$message"
        val init = """{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}"""
        val notification = """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"$publishedUri","version":1,"diagnostics":[{"message":"Type number is not assignable to string","severity":1,"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]}}"""
        val script = temporary.newFile("delayed-diagnostics.sh").apply {
            writeText(
                "printf '%s' '" + frame(init) + "'\n" +
                    "sleep 1\n" +
                    "printf '%s' '" + frame(notification) + "'\n" +
                    "while IFS= read -r line; do :; done\n",
            )
        }
        val context = HarnessContext()
        val plugin = LspPlugin(
            root = temporary.root,
            json = Json,
            command = { listOf("/bin/sh", script.absolutePath) },
        )
        plugin.install(context)
        try {
            withTimeout(7_000) {
                val result = context.tools.execute(
                    "lsp_diagnostics",
                    buildJsonObject { put("path", "late.ts") },
                    context = ToolContext(approval = { true }),
                )
                assertFalse(result.isError)
                assertTrue(result.content.contains("\"published\":true"))
                assertTrue(result.content.contains("not assignable"))
            }
        } finally {
            plugin.uninstall(context)
        }
    }

    @Test fun diagnosticsTimeoutNeverClaimsAnEmptyErrorListIsFinal() = runBlocking {
        Assume.assumeTrue(File("/bin/sh").isFile)
        temporary.newFile("waiting.ts").writeText("const a = 1")
        val script = temporary.newFile("silent-diagnostics.sh").apply {
            writeText(
                requestDrivenMockLsp("""{"jsonrpc":"2.0","id":1,"result":{"capabilities":{}}}"""),
            )
        }
        val context = HarnessContext()
        val plugin = LspPlugin(
            root = temporary.root, json = Json,
            command = { listOf("/bin/sh", script.absolutePath) },
        )
        plugin.install(context)
        try {
            // The LSP has its own 4s publish budget; CI may spend additional time
            // starting a real child process. Do not turn that into a false failure.
            withTimeout(15_000) {
                val result = context.tools.execute(
                    "lsp_diagnostics",
                    buildJsonObject { put("path", "waiting.ts") },
                    context = ToolContext(approval = { true }),
                )
                assertFalse(result.isError)
                assertTrue(result.content.contains("timeout_waiting_for_publish"))
                assertTrue(result.content.contains("\"published\":false"))
            }
        } finally {
            plugin.uninstall(context)
        }
    }


}
