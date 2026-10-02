package com.labteto.dshmobile.harness

import com.labteto.dshmobile.harness.plugin.*
import com.labteto.dshmobile.harness.tools.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginTransactionTest {
    private class Resource { var closed = false }
    @Test fun rollbackPublishesRecreatedResourceInsteadOfClosedReference() = runTest {
        val registry = PluginRegistry()
        var resource = Resource()
        val owner = object : HarnessPlugin {
            override val id = "owner"
            override suspend fun install(context: HarnessContext) {
                resource = Resource()
                context.events.register("resource", resource)
            }
            override suspend fun uninstall(context: HarnessContext) {
                resource.closed = true
                context.events.unregister("resource")
            }
        }
        registry.install(owner)
        val old = resource
        assertTrue(runCatching { registry.replace(object : HarnessPlugin {
            override val id = "owner"
            override suspend fun install(context: HarnessContext) { error("candidate failed") }
        }) }.isFailure)
        assertTrue(old.closed)
        assertNotSame(old, resource)
        assertSame(resource, registry.context.events.get("resource"))
        assertFalse(resource.closed)
        assertEquals(PluginLifecycleState.ACTIVE, registry.lifecycleSnapshot("owner")?.state)
    }

    @Test fun suspendedInstallNeverPublishesPartialSurfaceAndRetainedContextFollowsLiveRegistry() = runTest {
        val registry = PluginRegistry()
        val registered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        lateinit var retained: HarnessContext
        val installing = async { registry.install(object : HarnessPlugin {
            override val id = "half"
            override suspend fun install(context: HarnessContext) {
                retained = context
                context.events.register("first", "ready")
                registered.complete(Unit)
                finish.await()
                context.events.register("second", "ready")
            }
        }) }
        registered.await()
        assertNull(registry.context.events.get("first"))
        assertNull(registry.context.events.get("second"))
        finish.complete(Unit)
        installing.await()
        assertEquals(listOf("first", "second"), registry.context.events.ids())
        retained.events.register("dynamic", "ready")
        assertEquals("ready", registry.context.events.get("dynamic"))
    }

    @Test fun cancelledBatchDoesNotPublishAnySuccessfulPrefix() = runTest {
        val registry = PluginRegistry()
        val entered = CompletableDeferred<Unit>()
        val stop = CompletableDeferred<Unit>()
        val installing = async { registry.installAll(listOf(
            object : HarnessPlugin {
                override val id = "prefix"
                override suspend fun install(context: HarnessContext) { context.events.register("prefix", "ready") }
                override suspend fun uninstall(context: HarnessContext) { context.events.unregister("prefix") }
            },
            object : HarnessPlugin {
                override val id = "tail"
                override suspend fun install(context: HarnessContext) { entered.complete(Unit); stop.await() }
            },
        )) }
        entered.await()
        assertTrue(registry.context.events.ids().isEmpty())
        installing.cancel(); runCurrent()
        assertTrue(registry.ids().isEmpty())
        assertTrue(registry.context.events.ids().isEmpty())
    }

    @Test fun replacementDrainsExecutingToolAndRejectsNewCallsBeforeClosingProvider() = runTest {
        val registry = PluginRegistry()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var closed = false
        val owner = object : HarnessPlugin {
            override val id = "owner"
            override suspend fun install(context: HarnessContext) {
                context.tools.register(HarnessTool(
                    name = "slow",
                    schema = functionToolSchema("slow", "测试工具"),
                    access = ToolAccess.READ_ONLY,
                    approvalPolicy = ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.CORE,
                    metadata = ToolMetadata("测试"),
                    executor = HarnessToolExecutor { _, _, _ ->
                    entered.complete(Unit); finish.await()
                    assertFalse(closed)
                    ToolResult("done")
                }))
            }
            override suspend fun uninstall(context: HarnessContext) { closed = true; context.tools.unregister("slow") }
        }
        registry.install(owner)
        val call = async { registry.context.tools.execute("slow", buildJsonObject {}) }
        entered.await()
        val replacing = async { registry.replace(object : HarnessPlugin {
            override val id = "owner"
            override suspend fun install(context: HarnessContext) = Unit
        }) }
        runCurrent()
        assertFalse(closed)
        assertFalse(replacing.isCompleted)
        assertTrue(registry.context.tools.execute("slow", buildJsonObject {}).isError)
        finish.complete(Unit)
        assertEquals("done", call.await().content)
        replacing.await()
        assertTrue(closed)
    }

    @Test fun managementEntryResolvesReplacementAndCannotUseDisabledInstance() = runTest {
        val first = object : HarnessPlugin { override val id = "p"; override suspend fun install(context: HarnessContext) = Unit }
        val next = object : HarnessPlugin { override val id = "p"; override suspend fun install(context: HarnessContext) = Unit }
        val manager = PluginManager(PluginCatalog(listOf(PluginDefinition(PluginDescriptor("p"), HarnessPluginFactory { first }))))
        manager.install("p")
        manager.replace(PluginDefinition(PluginDescriptor("p", version = 2), HarnessPluginFactory { next }))
        assertSame(next, manager.withActivePlugin("p") { plugin, _ -> plugin })
        manager.disable("p")
        assertTrue(runCatching { manager.withActivePlugin("p") { plugin, _ -> plugin } }.isFailure)
    }
    @Test fun drainingTimeoutLeavesPluginActiveAndReopensAdmission() = runTest {
        val registry = PluginRegistry()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var uninstalled = false
        registry.install(object : HarnessPlugin {
            override val id = "owner"
            override suspend fun install(context: HarnessContext) {
                context.tools.register(HarnessTool(
                    name = "slow",
                    schema = functionToolSchema("slow", "测试工具"),
                    access = ToolAccess.READ_ONLY,
                    approvalPolicy = ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.CORE,
                    metadata = ToolMetadata("测试"),
                    executor = HarnessToolExecutor { _, _, _ ->
                    entered.complete(Unit); finish.await(); ToolResult("done")
                }))
            }
            override suspend fun uninstall(context: HarnessContext) { uninstalled = true }
        })
        val call = async { registry.context.tools.execute("slow", buildJsonObject {}) }
        entered.await()
        val result = async { runCatching { registry.uninstall("owner") } }
        runCurrent()
        testScheduler.advanceTimeBy(30_001L)
        assertTrue(result.await().isFailure)
        assertFalse(uninstalled)
        assertEquals(PluginLifecycleState.ACTIVE, registry.lifecycleSnapshot("owner")?.state)
        finish.complete(Unit)
        call.await()
        assertFalse(registry.context.tools.execute("slow", buildJsonObject {}).isError)
    }

    @Test fun failedRestorationBlocksClosedToolsUntilFailedPluginIsDisabled() = runTest {
        val registry = PluginRegistry()
        var installs = 0
        registry.install(object : HarnessPlugin {
            override val id = "owner"
            override suspend fun install(context: HarnessContext) {
                if (++installs > 1) error("restore failed")
                context.tools.register(HarnessTool(
                    name = "resource",
                    schema = functionToolSchema("resource", "测试工具"),
                    access = ToolAccess.READ_ONLY,
                    approvalPolicy = ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.CORE,
                    metadata = ToolMetadata("测试"),
                    executor = HarnessToolExecutor { _, _, _ ->
                    error("closed resource must never execute")
                }))
            }
            override suspend fun uninstall(context: HarnessContext) { context.tools.unregister("resource") }
        })
        assertTrue(runCatching { registry.replace(object : HarnessPlugin {
            override val id = "owner"
            override suspend fun install(context: HarnessContext) { error("candidate failed") }
        }) }.isFailure)
        assertEquals(PluginLifecycleState.FAILED, registry.lifecycleSnapshot("owner")?.state)
        assertTrue(registry.context.tools.execute("resource", buildJsonObject {}).isError)
        assertTrue(registry.uninstall("owner"))
        registry.context.tools.register(HarnessTool(
                    name = "healthy",
                    schema = functionToolSchema("healthy", "测试工具"),
                    access = ToolAccess.READ_ONLY,
                    approvalPolicy = ToolApprovalPolicy.NEVER,
                    exposure = ToolExposure.CORE,
                    metadata = ToolMetadata("测试"),
                    executor = HarnessToolExecutor { _, _, _ -> ToolResult("ok") }))
        assertEquals("ok", registry.context.tools.execute("healthy", buildJsonObject {}).content)
    }

}
