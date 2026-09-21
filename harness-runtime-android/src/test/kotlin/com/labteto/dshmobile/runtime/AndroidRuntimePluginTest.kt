package com.labteto.dshmobile.runtime

import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolContext
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRuntimePluginTest {
    @Test
    fun pluginRegistersRuntimeToolsAndExecutesOnlyAfterApproval() = runBlocking {
        val root = createTempDir(prefix = "runtime-plugin-")
        try {
            val registry = PluginRegistry()
            registry.install(AndroidRuntimePlugin(root))

            assertTrue(registry.context.tools.names().contains("runtime_command_status"))
            assertTrue(registry.context.tools.names().contains("process_exec"))
            assertTrue(registry.context.tools.names().contains("terminal_open"))

            val input = buildJsonObject {
                put(
                    "command",
                    buildJsonArray {
                        add("sh")
                        add("-c")
                        add("printf runtime-ok")
                    },
                )
            }
            val blocked = registry.context.tools.execute("process_exec", input)
            assertTrue(blocked.isError)
            assertTrue(blocked.content.contains("需要人工审批"))

            val allowed = registry.context.tools.execute(
                "process_exec",
                input,
                context = ToolContext(approval = { true }),
            )
            assertFalse(allowed.isError)
            assertTrue(allowed.content.contains("退出码：0"))
            assertTrue(allowed.content.contains("runtime-ok"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun persistentTerminalSurvivesAcrossToolCalls() = runBlocking {
        val root = createTempDir(prefix = "runtime-terminal-")
        try {
            val registry = PluginRegistry()
            registry.install(AndroidRuntimePlugin(root))
            val approval = ToolContext(approval = { true })

            val opened = registry.context.tools.execute(
                "terminal_open",
                buildJsonObject {
                    put(
                        "command",
                        buildJsonArray {
                            add("sh")
                        },
                    )
                },
                context = approval,
            )
            assertFalse(opened.isError)
            val sessionId = opened.content.trim()
            assertTrue(sessionId.startsWith("term-"))

            val write = registry.context.tools.execute(
                "terminal_write",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("input", "printf terminal-ok\n")
                },
                context = approval,
            )
            assertFalse(write.isError)

            var terminalOutput = ""
            for (attempt in 0 until 40) {
                val read = registry.context.tools.execute(
                    "terminal_read",
                    buildJsonObject { put("session_id", sessionId) },
                )
                terminalOutput += read.content
                if (terminalOutput.contains("terminal-ok")) break
                delay(25)
            }
            assertTrue(terminalOutput.contains("terminal-ok"))

            val status = registry.context.tools.execute(
                "terminal_status",
                buildJsonObject { put("session_id", sessionId) },
            )
            assertTrue(status.content.contains("\"alive\":true"))

            val closed = registry.context.tools.execute(
                "terminal_close",
                buildJsonObject { put("session_id", sessionId) },
                context = approval,
            )
            assertFalse(closed.isError)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun processWorkingDirectoryCannotEscapeWorkspace() = runBlocking {
        val root = createTempDir(prefix = "runtime-boundary-")
        val outside = createTempDir(prefix = "runtime-outside-")
        try {
            val registry = PluginRegistry()
            registry.install(AndroidRuntimePlugin(root))

            val result = runCatching {
                registry.context.tools.execute(
                    "process_exec",
                    buildJsonObject {
                        put("command", buildJsonArray { add("sh"); add("-c"); add("pwd") })
                        put("working_directory", outside.absolutePath)
                    },
                    context = ToolContext(approval = { true }),
                )
            }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("工作目录必须位于本机工作区内"))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
