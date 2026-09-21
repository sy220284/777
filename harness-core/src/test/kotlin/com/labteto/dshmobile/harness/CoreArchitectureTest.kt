package com.labteto.dshmobile.harness

import com.labteto.dshmobile.harness.capability.CapabilityDescriptor
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.session.FutureSessionVersionException
import com.labteto.dshmobile.harness.session.SessionDocument
import com.labteto.dshmobile.harness.session.VersionedSessionStore
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolResult
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreArchitectureTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun pluginCanRegisterAndUnregisterToolsAndCapabilities() = runTest {
        val registry = PluginRegistry()
        val plugin = object : HarnessPlugin {
            override val id = "test-plugin"

            override suspend fun install(context: HarnessContext) {
                context.capabilities.register(CapabilityDescriptor("clock"), "ready")
                context.tools.register(
                    HarnessTool(
                        name = "echo",
                        schema = buildJsonObject { put("name", "echo") },
                        executor = HarnessToolExecutor { _, input, _ ->
                            ToolResult(input["text"].toString())
                        },
                    ),
                )
            }

            override suspend fun uninstall(context: HarnessContext) {
                context.tools.unregister("echo")
                context.capabilities.unregister("clock")
            }
        }

        registry.install(plugin)
        assertTrue(registry.isInstalled("test-plugin"))
        assertNotNull(registry.context.tools.get("echo"))
        assertEquals("ready", registry.context.capabilities.get("clock", String::class))

        registry.uninstall("test-plugin")
        assertFalse(registry.isInstalled("test-plugin"))
        assertEquals(null, registry.context.tools.get("echo"))
    }

    @Test
    fun alwaysApprovalPolicyBlocksWithoutApprovalAndHonorsDecision() = runTest {
        val registry = PluginRegistry()
        var executions = 0
        registry.context.tools.register(
            HarnessTool(
                name = "danger",
                schema = buildJsonObject { put("name", "danger") },
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                executor = HarnessToolExecutor { _, _, _ ->
                    executions += 1
                    ToolResult("executed")
                },
            ),
        )

        val missing = registry.context.tools.execute("danger", buildJsonObject { })
        assertTrue(missing.isError)
        assertEquals(0, executions)

        var asked = 0
        val denied = registry.context.tools.execute(
            "danger",
            buildJsonObject { },
            context = ToolContext(
                approval = {
                    asked += 1
                    false
                },
            ),
        )
        assertTrue(denied.isError)
        assertEquals(1, asked)
        assertEquals(0, executions)

        val allowed = registry.context.tools.execute(
            "danger",
            buildJsonObject { },
            context = ToolContext(approval = { true }),
        )
        assertFalse(allowed.isError)
        assertEquals("executed", allowed.content)
        assertEquals(1, executions)
    }

    @Test
    fun registeredToolTimeoutReturnsErrorWithoutHangingCaller() = runTest {
        val registry = PluginRegistry()
        registry.context.tools.register(
            HarnessTool(
                name = "slow",
                schema = buildJsonObject { put("name", "slow") },
                timeoutMillis = 25L,
                executor = HarnessToolExecutor { _, _, _ ->
                    delay(5_000L)
                    ToolResult("late")
                },
            ),
        )

        val result = registry.context.tools.execute("slow", buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(result.content.contains("工具执行超时"))
    }
    @Test
    fun legacySessionMigratesWithCheckpointAndRestarts() {
        val root = createTempDir(prefix = "session-store-")
        try {
            File(root, "s1.json").writeText("""{"title":"旧会话","value":7}""")
            val store = VersionedSessionStore(root, json)

            val loaded = requireNotNull(store.read("s1"))
            assertTrue(loaded.legacy)
            assertTrue(loaded.migrated)
            store.write("s1", loaded.document.payload, updatedAt = 123L)

            assertTrue(File(root, "s1.checkpoint-v0.json").isFile)
            val restarted = VersionedSessionStore(root, json).read("s1")
            assertFalse(requireNotNull(restarted).legacy)
            assertEquals(1, restarted.document.formatVersion)
            assertEquals(123L, restarted.document.updatedAt)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptPrimaryRecoversFromLastGoodBackup() {
        val root = createTempDir(prefix = "recover-session-")
        try {
            val store = VersionedSessionStore(root, json, clock = { 999L })
            store.write("s1", buildJsonObject { put("value", 1) }, updatedAt = 1L)
            store.write("s1", buildJsonObject { put("value", 2) }, updatedAt = 2L)
            File(root, "s1.json").writeText("{broken")

            val loaded = requireNotNull(store.read("s1"))
            assertTrue(loaded.recovered)
            assertEquals("1", loaded.document.payload["value"]?.jsonPrimitive?.content)
            assertTrue(root.listFiles().orEmpty().any { it.name.startsWith("s1.corrupt-") })

            val restarted = requireNotNull(VersionedSessionStore(root, json).read("s1"))
            assertFalse(restarted.recovered)
            assertEquals("1", restarted.document.payload["value"]?.jsonPrimitive?.content)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun futureSessionIsRejected() {
        val root = createTempDir(prefix = "future-session-")
        try {
            File(root, "future.json").writeText(
                json.encodeToString(
                    SessionDocument.serializer(),
                    SessionDocument(99, "future", 1L, buildJsonObject { put("x", 1) }),
                ),
            )
            var rejected = false
            try {
                VersionedSessionStore(root, json).read("future")
            } catch (_: FutureSessionVersionException) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            root.deleteRecursively()
        }
    }
}
