package com.labteto.dshmobile.harness.plugin

import com.labteto.dshmobile.harness.capability.CapabilityDescriptor
import com.labteto.dshmobile.harness.capability.CapabilityRegistry
import com.labteto.dshmobile.harness.registry.NamedRegistry
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class HarnessContext(
    val tools: ToolRegistry = ToolRegistry(),
    val capabilities: CapabilityRegistry = CapabilityRegistry(),
    val events: NamedRegistry<Any> = NamedRegistry(),
    val projections: NamedRegistry<Any> = NamedRegistry(),
    val commands: NamedRegistry<Any> = NamedRegistry(),
    val models: NamedRegistry<Any> = NamedRegistry(),
    val settings: NamedRegistry<Any> = NamedRegistry(),
)

interface HarnessPlugin {
    val id: String
    suspend fun install(context: HarnessContext)
    suspend fun uninstall(context: HarnessContext) = Unit
}

enum class PluginLifecycleState {
    INSTALLING,
    ACTIVE,
    UNINSTALLING,
    FAILED,
}

data class PluginLifecycleSnapshot(
    val id: String,
    val state: PluginLifecycleState,
    val lastError: String? = null,
)

private data class HarnessRegistrySnapshot(
    val tools: Map<String, HarnessTool>,
    val capabilities: Map<String, Pair<CapabilityDescriptor, Any>>,
    val events: Map<String, Any>,
    val projections: Map<String, Any>,
    val commands: Map<String, Any>,
    val models: Map<String, Any>,
    val settings: Map<String, Any>,
)

private fun HarnessContext.snapshotRegistries(): HarnessRegistrySnapshot = HarnessRegistrySnapshot(
    tools = tools.snapshot(),
    capabilities = capabilities.snapshot(),
    events = events.snapshot(),
    projections = projections.snapshot(),
    commands = commands.snapshot(),
    models = models.snapshot(),
    settings = settings.snapshot(),
)

private fun HarnessContext.restoreRegistries(snapshot: HarnessRegistrySnapshot) {
    tools.restore(snapshot.tools)
    capabilities.restore(snapshot.capabilities)
    events.restore(snapshot.events)
    projections.restore(snapshot.projections)
    commands.restore(snapshot.commands)
    models.restore(snapshot.models)
    settings.restore(snapshot.settings)
}

/**
 * Serializes plugin lifecycle transitions and makes registry changes transactional.
 *
 * A failed install/uninstall can otherwise leave tools or capabilities partially registered while
 * the plugin bookkeeping says the opposite. Registry surfaces are restored exactly on failure,
 * while plugin cleanup is attempted in a non-cancellable section so external resources are not
 * abandoned when a lifecycle coroutine is cancelled.
 */
class PluginRegistry(
    val context: HarnessContext = HarnessContext(),
) {
    private val installed = linkedMapOf<String, HarnessPlugin>()
    private val lifecycle = linkedMapOf<String, PluginLifecycleSnapshot>()
    private val lifecycleMutex = Mutex()

    suspend fun install(plugin: HarnessPlugin) = lifecycleMutex.withLock {
        installLocked(plugin)
    }

    /**
     * Atomically installs a startup plugin set.
     *
     * If any plugin fails, plugins installed by this batch are cleaned up in reverse order and all
     * registries/bookkeeping are restored to the exact state from before the batch.
     */
    suspend fun installAll(plugins: Iterable<HarnessPlugin>) = lifecycleMutex.withLock {
        val batch = plugins.toList()
        val ids = batch.map(HarnessPlugin::id)
        require(ids.size == ids.toSet().size) { "插件批次包含重复编号" }

        val registryBefore = context.snapshotRegistries()
        val installedBefore = synchronized(this) { LinkedHashMap(installed) }
        val lifecycleBefore = synchronized(this) { LinkedHashMap(lifecycle) }
        val installedByBatch = mutableListOf<HarnessPlugin>()
        var current: HarnessPlugin? = null

        try {
            batch.forEach { plugin ->
                current = plugin
                installLocked(plugin)
                installedByBatch += plugin
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                installedByBatch.asReversed().forEach { plugin ->
                    runCatching { plugin.uninstall(context) }
                }
            }
            context.restoreRegistries(registryBefore)
            synchronized(this) {
                installed.clear()
                installed.putAll(installedBefore)
                lifecycle.clear()
                lifecycle.putAll(lifecycleBefore)
                current?.let { failed ->
                    lifecycle[failed.id] = PluginLifecycleSnapshot(
                        id = failed.id,
                        state = PluginLifecycleState.FAILED,
                        lastError = error.message ?: error::class.java.simpleName,
                    )
                }
            }
            throw error
        }
    }

    suspend fun uninstall(id: String): Boolean = lifecycleMutex.withLock {
        val plugin = synchronized(this) { installed[id] } ?: return@withLock false
        val registryBefore = context.snapshotRegistries()
        synchronized(this) {
            lifecycle[id] = PluginLifecycleSnapshot(id, PluginLifecycleState.UNINSTALLING)
        }

        try {
            plugin.uninstall(context)
            synchronized(this) {
                installed.remove(id)
                lifecycle.remove(id)
            }
            true
        } catch (error: Throwable) {
            context.restoreRegistries(registryBefore)
            synchronized(this) {
                lifecycle[id] = PluginLifecycleSnapshot(
                    id = id,
                    state = PluginLifecycleState.FAILED,
                    lastError = error.message ?: error::class.java.simpleName,
                )
            }
            throw error
        }
    }

    @Synchronized
    fun ids(): List<String> = installed.keys.toList()

    @Synchronized
    fun isInstalled(id: String): Boolean = id in installed

    @Synchronized
    fun lifecycleSnapshots(): List<PluginLifecycleSnapshot> = lifecycle.values.toList()

    @Synchronized
    fun lifecycleSnapshot(id: String): PluginLifecycleSnapshot? = lifecycle[id]

    private suspend fun installLocked(plugin: HarnessPlugin) {
        require(plugin.id.isNotBlank()) { "插件编号不能为空" }
        synchronized(this) {
            require(plugin.id !in installed) { "插件已安装：${plugin.id}" }
            lifecycle[plugin.id] = PluginLifecycleSnapshot(plugin.id, PluginLifecycleState.INSTALLING)
        }

        val registryBefore = context.snapshotRegistries()
        try {
            plugin.install(context)
            synchronized(this) {
                installed[plugin.id] = plugin
                lifecycle[plugin.id] = PluginLifecycleSnapshot(plugin.id, PluginLifecycleState.ACTIVE)
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { plugin.uninstall(context) }
            }
            context.restoreRegistries(registryBefore)
            synchronized(this) {
                lifecycle[plugin.id] = PluginLifecycleSnapshot(
                    id = plugin.id,
                    state = PluginLifecycleState.FAILED,
                    lastError = error.message ?: error::class.java.simpleName,
                )
            }
            throw error
        }
    }
}
