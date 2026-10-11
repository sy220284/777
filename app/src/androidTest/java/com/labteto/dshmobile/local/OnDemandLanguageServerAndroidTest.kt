package com.labteto.dshmobile.local

import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.interop.lsp.LspProcessClient
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
                assertTrue("返回结构应为 JSON", symbols.toString().isNotBlank())
                val definition = client.definition(file.toURI().toString(), 1, 2)
                assertTrue("查询应完成 JSON-RPC 往返", definition.toString().isNotBlank())
            } finally {
                client.close()
            }
        }
    }
}
