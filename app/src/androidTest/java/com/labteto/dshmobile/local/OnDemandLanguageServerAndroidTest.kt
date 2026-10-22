package com.labteto.dshmobile.local

import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.interop.lsp.LspProcessClient
import com.labteto.dshmobile.local.files.LocalWorkspaceToolGuide
import com.labteto.dshmobile.local.runtime.BundledNodeRuntime
import com.labteto.dshmobile.runtime.AndroidProcessRuntime
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An actual Android-only smoke, deliberately without mocked npm, Node or LSP transport.
 * CI on Android 16/17 catches Android executable, TLS and process compatibility regressions.
 */
class OnDemandLanguageServerAndroidTest {
    @Test fun workspaceToolGuideIsCreatedOnceAndPreservesUserEdits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workspace = File(context.cacheDir, "guide-smoke").apply {
            deleteRecursively()
            mkdirs()
        }
        assertTrue(LocalWorkspaceToolGuide.ensure(context, workspace))
        val readme = File(workspace, ".dsh/tools/README.md")
        val managedGuide = File(workspace, ".dsh/tools/777-runtime-guide.md")
        assertTrue(managedGuide.readText().contains("capability_search"))
        readme.writeText("# 自定义工具说明\\n")
        assertTrue(!LocalWorkspaceToolGuide.ensure(context, workspace))
        assertEquals("# 自定义工具说明\\n", readme.readText())
        managedGuide.writeText("旧版运行指南")
        assertTrue(!LocalWorkspaceToolGuide.ensure(context, workspace))
        assertTrue(managedGuide.readText().contains("capability_search"))
        assertEquals("# 自定义工具说明\\n", readme.readText())
    }

    @Test fun installTypeScriptAndServeRealWorkspaceSymbols() = runBlocking {
        withTimeout(240_000L) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val nodeRuntime = BundledNodeRuntime(context)
            nodeRuntime.prepare()
            val runtime = AndroidProcessRuntime(
                dynamicSearchPaths = nodeRuntime::searchPaths,
                baseEnvironment = nodeRuntime::environment,
            )
            val home = File(context.noBackupFilesDir, "lsp-android-smoke")
            if (home.exists()) check(home.deleteRecursively())
            val manager = OnDemandLanguageServerManager(
                home = home,
                commandAvailable = runtime::isCommandAvailable,
                resolveCommand = runtime::resolveCommand,
                processEnvironment = { runtime.processEnvironment() },
            )
            assertTrue(runtime.isCommandAvailable("node"))
            assertTrue("Node 必须带有可信 TLS 根证书",
                File(runtime.processEnvironment().getValue("NODE_EXTRA_CA_CERTS")).isFile)
            assertTrue(manager.installedCommand("probe.ts").isEmpty())
            val command = manager.ensureInstalled("probe.ts")
            assertTrue(command.isNotEmpty())
            assertEquals(command, manager.ensureInstalled("probe.ts"))

            val workspace = File(context.cacheDir, "lsp-smoke-project").apply { mkdirs() }
            val file = File(workspace, "probe.ts")
            val code = "export const hello = 123;\nhello;\n"
            file.writeText(code)
            val client = LspProcessClient(
                command = command,
                json = Json,
                workingDirectory = workspace,
                commandResolver = runtime::resolveCommand,
                environmentProvider = { runtime.processEnvironment() },
            )
            try {
                client.initialize(workspace.toURI().toString())
                client.didOpen(file.toURI().toString(), "typescript", 1, code)
                val symbols = client.documentSymbols(file.toURI().toString())
                assertTrue("应返回实际代码符号", symbols.toString().contains("hello"))
                val definition = client.definition(file.toURI().toString(), 1, 2)
                assertTrue("定义应指向实际源文件", definition.toString().contains("probe.ts"))
                val references = client.references(file.toURI().toString(), 1, 2)
                assertTrue("引用应定位实际源文件", references.toString().contains("probe.ts"))
                val hover = client.hover(file.toURI().toString(), 1, 2)
                assertTrue("悬停应返回相关类型", hover.toString().contains("hello"))
                val broken = File(workspace, "broken.ts").apply {
                    writeText("const label: string = 123;\n")
                }
                val errorUri = broken.toURI().toString()
                val revision = client.diagnosticRevision(errorUri)
                client.didOpen(errorUri, "typescript", 1, broken.readText())
                val diagnostics = client.awaitPublishedDiagnostics(
                    uri = errorUri, sinceRevision = revision, version = 1, timeoutMillis = 15_000,
                )
                assertTrue("真实 TS 类型错误应触发异步诊断",
                    diagnostics?.get("diagnostics").toString().contains("2322"))
            } finally {
                client.close()
            }
        }
    }

    @Test fun installPyrightAndQueryRealPythonSymbols() = runBlocking {
        withTimeout(240_000L) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val nodeRuntime = BundledNodeRuntime(context)
            nodeRuntime.prepare()
            val runtime = AndroidProcessRuntime(
                dynamicSearchPaths = nodeRuntime::searchPaths,
                baseEnvironment = nodeRuntime::environment,
            )
            val home = File(context.noBackupFilesDir, "pyright-android-smoke")
            if (home.exists()) check(home.deleteRecursively())
            val manager = OnDemandLanguageServerManager(
                home = home,
                commandAvailable = runtime::isCommandAvailable,
                resolveCommand = runtime::resolveCommand,
                processEnvironment = { runtime.processEnvironment() },
            )
            val command = manager.ensureInstalled("example.py")
            assertEquals(command, manager.ensureInstalled("example.py"))
            val workspace = File(context.cacheDir, "pyright-smoke").apply { mkdirs() }
            val file = File(workspace, "example.py")
            val code = "def greet(name: str) -> str:\n    return name\n\nvalue = greet('yes')\n"
            file.writeText(code)
            val client = LspProcessClient(
                command = command,
                json = Json,
                workingDirectory = workspace,
                commandResolver = runtime::resolveCommand,
                environmentProvider = { runtime.processEnvironment() },
            )
            try {
                client.initialize(workspace.toURI().toString())
                client.didOpen(file.toURI().toString(), "python", 1, code)
                val symbols = client.documentSymbols(file.toURI().toString())
                assertTrue("真实 Pyright 符号包含函数 greet", symbols.toString().contains("greet"))
            } finally {
                client.close()
            }
        }
    }

}
