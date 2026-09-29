package com.labteto.dshmobile.harness

import com.labteto.dshmobile.harness.plugin.HARNESS_PLUGIN_API_VERSION
import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.plugin.HarnessPluginFactory
import com.labteto.dshmobile.harness.plugin.PluginCatalog
import com.labteto.dshmobile.harness.plugin.PluginDefinition
import com.labteto.dshmobile.harness.plugin.PluginDependency
import com.labteto.dshmobile.harness.plugin.PluginDescriptor
import com.labteto.dshmobile.harness.plugin.PluginLifecycleState
import com.labteto.dshmobile.harness.plugin.PluginManager
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerTest {
    @Test
    fun installsDependenciesInTopologicalOrder() = runTest {
        val installs = mutableListOf<String>()
        val manager = manager(
            definition(
                "feature",
                dependencies = listOf(PluginDependency("core")),
                onInstall = { installs += "feature" },
            ),
            definition(
                "core",
                onInstall = { installs += "core" },
            ),
        )

        assertEquals(listOf("core", "feature"), manager.install("feature"))
        assertEquals(listOf("core", "feature"), installs)
        assertEquals(listOf("core", "feature"), manager.installedPluginIds())
    }

    @Test
    fun rejectsUnsatisfiedDependencyVersionBeforeAnyInstall() = runTest {
        val installs = mutableListOf<String>()
        val manager = manager(
            definition(
                "core",
                version = 1,
                onInstall = { installs += "core" },
            ),
            definition(
                "feature",
                dependencies = listOf(PluginDependency("core", minVersion = 2)),
                onInstall = { installs += "feature" },
            ),
        )

        val failure = runCatching { manager.install("feature") }

        assertTrue(failure.isFailure)
        assertTrue(installs.isEmpty())
        assertTrue(manager.installedPluginIds().isEmpty())
    }

    @Test
    fun rejectsDependencyCyclesBeforeAnyInstall() = runTest {
        val manager = manager(
            definition("a", dependencies = listOf(PluginDependency("b"))),
            definition("b", dependencies = listOf(PluginDependency("a"))),
        )

        val failure = runCatching { manager.install("a") }

        assertTrue(failure.isFailure)
        assertTrue(manager.installedPluginIds().isEmpty())
    }

    @Test
    fun rejectsIncompatiblePluginApiBeforeAnyInstall() = runTest {
        val manager = manager(
            definition("future", apiVersion = HARNESS_PLUGIN_API_VERSION + 1),
        )

        val failure = runCatching { manager.install("future") }

        assertTrue(failure.isFailure)
        assertTrue(manager.installedPluginIds().isEmpty())
    }

    @Test
    fun disablingProviderWithActiveDependentIsRejected() = runTest {
        val manager = manager(
            definition("core"),
            definition("feature", dependencies = listOf(PluginDependency("core"))),
        )
        manager.install("feature")

        val failure = runCatching { manager.disable("core") }

        assertTrue(failure.isFailure)
        assertTrue("core" in manager.installedPluginIds())
        assertTrue("feature" in manager.installedPluginIds())
    }

    @Test
    fun liveReplacementUpdatesRegistryAndCatalogTogether() = runTest {
        val registry = PluginRegistry()
        val manager = manager(
            definition(
                id = "replaceable",
                version = 1,
                onInstall = { it.events.register("owner", "old") },
                onUninstall = { it.events.unregister("owner") },
            ),
            registry = registry,
        )
        manager.install("replaceable")

        manager.replace(
            definition(
                id = "replaceable",
                version = 2,
                onInstall = { it.events.register("owner", "new") },
                onUninstall = { it.events.unregister("owner") },
            ),
        )

        assertEquals("new", registry.context.events.get("owner"))
        assertEquals(
            2,
            manager.descriptors().single { it.id == "replaceable" }.version,
        )
        assertEquals(
            PluginLifecycleState.ACTIVE,
            manager.lifecycleSnapshot("replaceable")?.state,
        )
    }

    @Test
    fun failedReplacementRestoresPreviousPluginAndCatalogVersion() = runTest {
        val registry = PluginRegistry()
        val manager = manager(
            definition(
                id = "replaceable",
                version = 1,
                onInstall = { it.events.register("owner", "old") },
                onUninstall = { it.events.unregister("owner") },
            ),
            registry = registry,
        )
        manager.install("replaceable")

        val failure = runCatching {
            manager.replace(
                definition(
                    id = "replaceable",
                    version = 2,
                    onInstall = {
                        it.events.register("owner", "broken")
                        error("replacement failed")
                    },
                    onUninstall = { it.events.unregister("owner") },
                ),
            )
        }

        assertTrue(failure.isFailure)
        assertEquals("old", registry.context.events.get("owner"))
        assertEquals(
            1,
            manager.descriptors().single { it.id == "replaceable" }.version,
        )
        assertEquals(
            PluginLifecycleState.ACTIVE,
            manager.lifecycleSnapshot("replaceable")?.state,
        )
    }

    @Test
    fun replacementCannotBreakAnActiveDependentVersionRequirement() = runTest {
        val registry = PluginRegistry()
        val manager = manager(
            definition("core", version = 2),
            definition(
                "feature",
                dependencies = listOf(PluginDependency("core", minVersion = 2)),
            ),
            registry = registry,
        )
        manager.install("feature")

        val failure = runCatching {
            manager.replace(definition("core", version = 1))
        }

        assertTrue(failure.isFailure)
        assertEquals(2, manager.descriptors().single { it.id == "core" }.version)
        assertTrue(registry.isInstalled("core"))
        assertTrue(registry.isInstalled("feature"))
    }

    @Test
    fun disablingUnknownPluginIsAnIdempotentNoOp() = runTest {
        val manager = manager(definition("core"))

        assertFalse(manager.disable("missing"))
    }

    @Test
    fun concurrentEnableStormInstallsPluginOnlyOnce() = runTest {
        var installs = 0
        val manager = manager(
            definition(
                "core",
                onInstall = { installs += 1 },
            ),
        )

        val attempts = (0 until 100).map {
            async { manager.enable("core") }
        }
        attempts.forEach { it.await() }

        assertEquals(1, installs)
        assertEquals(listOf("core"), manager.installedPluginIds())
    }

    @Test
    fun unorderedDisableAndReenablePreservesDependencyRules() = runTest {
        val manager = manager(
            definition("core"),
            definition("feature", dependencies = listOf(PluginDependency("core"))),
        )
        manager.install("feature")

        assertTrue(runCatching { manager.disable("core") }.isFailure)
        assertTrue(manager.disable("feature"))
        assertTrue(manager.disable("core"))
        assertEquals(listOf("core", "feature"), manager.enable("feature"))
    }

    @Test
    fun largeReverseOrderedCatalogStillResolvesDeterministically() = runTest {
        val definitions = (0 until 128).map { index ->
            definition(
                id = "plugin-" + index,
                dependencies = if (index == 0) {
                    emptyList()
                } else {
                    listOf(PluginDependency("plugin-" + (index - 1)))
                },
            )
        }.reversed()

        val manager = manager(*definitions.toTypedArray())
        val installed = manager.install("plugin-127")

        assertEquals(128, installed.size)
        assertEquals("plugin-0", installed.first())
        assertEquals("plugin-127", installed.last())
        assertEquals(installed, manager.installedPluginIds())
    }

    @Test
    fun failedPluginDoesNotPoisonUnrelatedPluginInstallation() = runTest {
        val registry = PluginRegistry()
        val manager = manager(
            definition(
                id = "broken",
                version = 1,
                onInstall = { it.events.register("broken-owner", "old") },
                onUninstall = { it.events.unregister("broken-owner") },
            ),
            definition(
                id = "independent",
                onInstall = { it.events.register("independent-owner", "ready") },
                onUninstall = { it.events.unregister("independent-owner") },
            ),
            registry = registry,
        )
        manager.install("broken")

        runCatching {
            manager.replace(
                definition(
                    id = "broken",
                    version = 2,
                    onInstall = {
                        it.events.register("broken-owner", "candidate")
                        error("replacement failed")
                    },
                    onUninstall = {
                        it.events.unregister("broken-owner")
                        error("cleanup failed")
                    },
                ),
            )
        }

        assertEquals(
            PluginLifecycleState.FAILED,
            manager.lifecycleSnapshot("broken")?.state,
        )
        assertEquals(listOf("independent"), manager.install("independent"))
        assertEquals("ready", registry.context.events.get("independent-owner"))
    }

    @Test
    fun replacementCleanupFailureLeavesLifecycleFailedInsteadOfPretendingHealthy() = runTest {
        val registry = PluginRegistry()
        val manager = manager(
            definition(
                id = "replaceable",
                version = 1,
                onInstall = { it.events.register("owner", "old") },
                onUninstall = { it.events.unregister("owner") },
            ),
            registry = registry,
        )
        manager.install("replaceable")

        val failure = runCatching {
            manager.replace(
                definition(
                    id = "replaceable",
                    version = 2,
                    onInstall = {
                        it.events.register("owner", "broken")
                        error("replacement failed")
                    },
                    onUninstall = {
                        it.events.unregister("owner")
                        error("cleanup failed")
                    },
                ),
            )
        }

        assertTrue(failure.isFailure)
        assertEquals("old", registry.context.events.get("owner"))
        assertEquals(1, manager.descriptors().single { it.id == "replaceable" }.version)
        assertEquals(
            PluginLifecycleState.FAILED,
            manager.lifecycleSnapshot("replaceable")?.state,
        )
        assertTrue(runCatching { manager.install("replaceable") }.isFailure)
    }

    private fun manager(
        vararg definitions: PluginDefinition,
        registry: PluginRegistry = PluginRegistry(),
    ): PluginManager = PluginManager(
        catalog = PluginCatalog(definitions.asList()),
        registry = registry,
    )

    private fun definition(
        id: String,
        version: Int = 1,
        apiVersion: Int = HARNESS_PLUGIN_API_VERSION,
        dependencies: List<PluginDependency> = emptyList(),
        onInstall: suspend (HarnessContext) -> Unit = { _ -> },
        onUninstall: suspend (HarnessContext) -> Unit = { _ -> },
    ): PluginDefinition = PluginDefinition(
        descriptor = PluginDescriptor(
            id = id,
            version = version,
            apiVersion = apiVersion,
            dependencies = dependencies,
        ),
        factory = HarnessPluginFactory {
            object : HarnessPlugin {
                override val id: String = id

                override suspend fun install(context: HarnessContext) {
                    onInstall(context)
                }

                override suspend fun uninstall(context: HarnessContext) {
                    onUninstall(context)
                }
            }
        },
    )
}
