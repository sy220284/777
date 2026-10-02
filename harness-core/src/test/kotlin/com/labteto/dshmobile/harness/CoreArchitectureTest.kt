package com.labteto.dshmobile.harness

import com.labteto.dshmobile.harness.capability.CapabilityDescriptor
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.jobs.HarnessJobManager
import com.labteto.dshmobile.harness.plugin.PluginLifecycleState
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.session.FutureSessionVersionException
import com.labteto.dshmobile.harness.session.SessionDocument
import com.labteto.dshmobile.harness.session.VersionedSessionStore
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolContext
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
                        schema = functionToolSchema("echo", "测试工具"),
                                        access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
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
    fun concurrentDuplicatePluginInstallRunsOnlyOneInstaller() = runTest {
        val registry = PluginRegistry()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstInstalls = 0
        var secondInstalls = 0

        val firstPlugin = object : HarnessPlugin {
            override val id = "same-id"
            override suspend fun install(context: HarnessContext) {
                firstInstalls += 1
                entered.complete(Unit)
                release.await()
            }
        }
        val secondPlugin = object : HarnessPlugin {
            override val id = "same-id"
            override suspend fun install(context: HarnessContext) {
                secondInstalls += 1
            }
        }

        val first = async { registry.install(firstPlugin) }
        entered.await()
        val second = async { runCatching { registry.install(secondPlugin) } }
        yield()

        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.await()
        val duplicate = second.await()

        assertTrue(duplicate.isFailure)
        assertEquals(1, firstInstalls)
        assertEquals(0, secondInstalls)
        assertTrue(registry.isInstalled("same-id"))
    }

    @Test
    fun uninstallWaitsForInFlightInstallBeforeRunningCleanup() = runTest {
        val registry = PluginRegistry()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cleaned = false
        val plugin = object : HarnessPlugin {
            override val id = "ordered-plugin"
            override suspend fun install(context: HarnessContext) {
                entered.complete(Unit)
                release.await()
            }

            override suspend fun uninstall(context: HarnessContext) {
                cleaned = true
            }
        }

        val installing = async { registry.install(plugin) }
        entered.await()
        val uninstalling = async { registry.uninstall(plugin.id) }
        yield()

        assertFalse(uninstalling.isCompleted)
        release.complete(Unit)
        installing.await()

        assertTrue(uninstalling.await())
        assertTrue(cleaned)
        assertFalse(registry.isInstalled(plugin.id))
    }

    @Test
    fun failedPluginInstallRollsBackAllRegistriesAndRecordsFailure() = runTest {
        val registry = PluginRegistry()
        val plugin = object : HarnessPlugin {
            override val id = "broken-install"

            override suspend fun install(context: HarnessContext) {
                context.capabilities.register(CapabilityDescriptor("partial-capability"), "partial")
                context.events.register("partial-event", "partial")
                context.tools.register(
                    HarnessTool(
                        name = "partial-tool",
                        schema = functionToolSchema("partial-tool", "测试工具"),
                                        access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
executor = HarnessToolExecutor { _, _, _ -> ToolResult("partial") },
                    ),
                )
                error("install exploded")
            }
        }

        val result = runCatching { registry.install(plugin) }

        assertTrue(result.isFailure)
        assertFalse(registry.isInstalled(plugin.id))
        assertEquals(null, registry.context.tools.get("partial-tool"))
        assertEquals(null, registry.context.capabilities.get("partial-capability", String::class))
        assertTrue(registry.context.events.ids().isEmpty())
        assertEquals(
            PluginLifecycleState.FAILED,
            registry.lifecycleSnapshot(plugin.id)?.state,
        )
    }

    @Test
    fun batchInstallRollsBackEarlierPluginsWhenLaterPluginFails() = runTest {
        val registry = PluginRegistry()
        val first = object : HarnessPlugin {
            override val id = "first"
            override suspend fun install(context: HarnessContext) {
                context.tools.register(
                    HarnessTool(
                        name = "first-tool",
                        schema = functionToolSchema("first-tool", "测试工具"),
                                        access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
executor = HarnessToolExecutor { _, _, _ -> ToolResult("first") },
                    ),
                )
            }

            override suspend fun uninstall(context: HarnessContext) {
                context.tools.unregister("first-tool")
            }
        }
        val second = object : HarnessPlugin {
            override val id = "second"
            override suspend fun install(context: HarnessContext) {
                context.tools.register(
                    HarnessTool(
                        name = "second-tool",
                        schema = functionToolSchema("second-tool", "测试工具"),
                                        access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
executor = HarnessToolExecutor { _, _, _ -> ToolResult("second") },
                    ),
                )
                error("second failed")
            }
        }

        val result = runCatching { registry.installAll(listOf(first, second)) }

        assertTrue(result.isFailure)
        assertTrue(registry.ids().isEmpty())
        assertTrue(registry.context.tools.names().isEmpty())
        assertEquals(PluginLifecycleState.FAILED, registry.lifecycleSnapshot("second")?.state)
    }

    @Test
    fun failedPluginUninstallRestoresRegistrySurfaceAndKeepsPluginVisible() = runTest {
        val registry = PluginRegistry()
        val plugin = object : HarnessPlugin {
            override val id = "fragile-uninstall"
            override suspend fun install(context: HarnessContext) {
                context.tools.register(
                    HarnessTool(
                        name = "stable-tool",
                        schema = functionToolSchema("stable-tool", "测试工具"),
                                        access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") },
                    ),
                )
            }

            override suspend fun uninstall(context: HarnessContext) {
                context.tools.unregister("stable-tool")
                error("cleanup failed")
            }
        }
        registry.install(plugin)

        val result = runCatching { registry.uninstall(plugin.id) }

        assertTrue(result.isFailure)
        assertTrue(registry.isInstalled(plugin.id))
        assertNotNull(registry.context.tools.get("stable-tool"))
        assertEquals(PluginLifecycleState.FAILED, registry.lifecycleSnapshot(plugin.id)?.state)
    }

    @Test
    fun alwaysApprovalPolicyBlocksWithoutApprovalAndHonorsDecision() = runTest {
        val registry = PluginRegistry()
        var executions = 0
        registry.context.tools.register(
            HarnessTool(
                name = "danger",
                schema = functionToolSchema("danger", "测试工具"),
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                                access = ToolAccess.READ_ONLY,
                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
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
                schema = functionToolSchema("slow", "测试工具"),
                timeoutMillis = 25L,
                                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                exposure = ToolExposure.CORE,
                metadata = ToolMetadata("测试"),
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
    fun stopAllAndJoinWaitsForCancelledJobCleanup() = runTest {
        var cleaned = false
        val manager = HarnessJobManager(scope = this, onChanged = { })
        manager.start("cleanup") { _, _ ->
            try {
                delay(5_000L)
                "late"
            } finally {
                withContext(NonCancellable) {
                    delay(10L)
                    cleaned = true
                }
            }
        }
        yield()

        manager.stopAllAndJoin()

        assertTrue(cleaned)
        assertTrue(manager.list().contains("[cancelled]"))
    }

    @Test
    fun jobManagerBoundsConcurrencyAndPrunesFinishedRecords() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ids = ArrayDeque(listOf("job-a", "job-b", "job-c"))
        val manager = HarnessJobManager(
            scope = this,
            onChanged = { },
            idFactory = { ids.removeFirst() },
            maxConcurrentJobs = 1,
            maxRetainedJobs = 2,
        )

        val first = manager.start("first") { _, _ ->
            gate.await()
            "first done"
        }
        yield()
        val rejected = manager.start("blocked") { _, _ -> "should not run" }

        assertTrue(first.endsWith("job-a"))
        assertTrue(rejected.contains("并发已满"))

        gate.complete(Unit)
        advanceUntilIdle()
        manager.start("second") { _, _ -> "second done" }
        advanceUntilIdle()
        manager.start("third") { _, _ -> "third done" }
        advanceUntilIdle()

        val listing = manager.list()
        assertFalse(listing.contains("job-a"))
        assertTrue(listing.contains("job-b"))
        assertTrue(listing.contains("job-c"))
    }

    @Test
    fun persistedRunningJobRestoresInterruptedAndCanResumeSafely() = runTest {
        val snapshots = mutableListOf<List<com.labteto.dshmobile.harness.jobs.JobSnapshot>>()
        val restored = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                com.labteto.dshmobile.harness.jobs.JobSnapshot(
                    id = "job-safe",
                    label = "只读抓取",
                    status = "running",
                    output = "处理中",
                    resumeKind = "web_fetch",
                    resumePayload = "{\"url\":\"https://example.com\"}",
                    updatedAt = 10L,
                ),
            ),
            onSnapshotsChanged = { snapshots += it },
        )

        assertTrue(restored.list().contains("job-safe [interrupted]"))
        assertEquals("web_fetch", restored.interruptedSnapshots().single().resumeKind)

        val resumed = restored.resumePersistent("job-safe") { _, report ->
            report("恢复执行")
            "恢复完成"
        }
        assertTrue(resumed.contains("已恢复"))
        advanceUntilIdle()

        assertTrue(restored.list().contains("job-safe [completed]"))
        assertTrue(restored.output("job-safe").contains("恢复完成"))
        assertTrue(snapshots.last().single().status == "completed")
    }

    @Test
    fun nonResumableInterruptedJobIsNotAutoResumeCandidate() = runTest {
        val restored = HarnessJobManager(
            scope = this,
            onChanged = { },
            initialSnapshots = listOf(
                com.labteto.dshmobile.harness.jobs.JobSnapshot(
                    id = "job-shell",
                    label = "shell",
                    status = "running",
                    resumeKind = null,
                    resumePayload = null,
                ),
            ),
        )

        assertTrue(restored.list().contains("job-shell [interrupted]"))
        assertTrue(restored.interruptedSnapshots().isEmpty())
        assertTrue(restored.resumePersistent("job-shell") { _, _ -> "no" }.contains("不可恢复"))
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
    fun firstSuccessfulWriteSeedsRecoverableBackupAndLeavesNoTempFile() {
        val root = createTempDir(prefix = "seed-session-backup-")
        try {
            val store = VersionedSessionStore(root, json, clock = { 10L })
            store.write("s1", buildJsonObject { put("value", 7) }, updatedAt = 7L)

            assertTrue(File(root, "s1.backup.json").isFile)
            assertFalse(File(root, "s1.json.tmp").exists())

            File(root, "s1.json").writeText("{broken")
            val recovered = requireNotNull(store.read("s1"))
            assertTrue(recovered.recovered)
            assertEquals("7", recovered.document.payload["value"]?.jsonPrimitive?.content)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingPrimaryRecoversFromRotatedBackup() {
        val root = createTempDir(prefix = "missing-session-primary-")
        try {
            val store = VersionedSessionStore(root, json)
            store.write("s1", buildJsonObject { put("value", 1) }, updatedAt = 1L)
            store.write("s1", buildJsonObject { put("value", 2) }, updatedAt = 2L)

            assertTrue(File(root, "s1.json").delete())
            val recovered = requireNotNull(store.read("s1"))

            assertTrue(recovered.recovered)
            assertEquals("1", recovered.document.payload["value"]?.jsonPrimitive?.content)
            assertTrue(File(root, "s1.json").isFile)
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
