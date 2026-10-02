package com.labteto.dshmobile.harness.plugin

import com.labteto.dshmobile.harness.capability.CapabilityDescriptor
import com.labteto.dshmobile.harness.capability.CapabilityRegistry
import com.labteto.dshmobile.harness.registry.NamedRegistry
import com.labteto.dshmobile.harness.registry.RegistryEntries
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

private fun HarnessContext.fork(): HarnessContext = RegistryEntries.atomic {
    HarnessContext(tools.fork(), capabilities.fork(), events.fork(), projections.fork(),
        commands.fork(), models.fork(), settings.fork())
}

private fun HarnessContext.publishTo(destination: HarnessContext) = RegistryEntries.atomic {
    tools.publishTo(destination.tools)
    capabilities.publishTo(destination.capabilities)
    events.publishTo(destination.events)
    projections.publishTo(destination.projections)
    commands.publishTo(destination.commands)
    models.publishTo(destination.models)
    settings.publishTo(destination.settings)
}

interface HarnessPlugin {
    val id: String
    suspend fun install(context: HarnessContext)
    suspend fun uninstall(context: HarnessContext) = Unit
}

enum class PluginLifecycleState {
    INSTALLING,
    ACTIVE,
    REPLACING,
    UNINSTALLING,
    FAILED,
}

data class PluginLifecycleSnapshot(
    val id: String,
    val state: PluginLifecycleState,
    val lastError: String? = null,
)

private data class PendingPluginCleanup(
    val plugin: HarnessPlugin,
    val context: HarnessContext,
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
 * the plugin bookkeeping says the opposite. Changes remain staged until commit; replacement
 * rollback publishes reinstalled resources. Uncertain resource state blocks tool admission until
 * cleanup succeeds. Failure cleanup is attempted in a non-cancellable section.
 */
class PluginRegistry(
    val context: HarnessContext = HarnessContext(),
) {
    private val installed = linkedMapOf<String, HarnessPlugin>()
    private val lifecycle = linkedMapOf<String, PluginLifecycleSnapshot>()
    private val lifecycleMutex = Mutex()
    private val pendingCleanup = linkedMapOf<String, PendingPluginCleanup>()

    suspend fun install(plugin: HarnessPlugin) = lifecycleMutex.withLock {
        context.tools.lifecycleTransition {
            val staged = context.fork()
            installLocked(plugin, staged)
            publishInstalled(staged, listOf(plugin))
        }
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
        require(ids.all(String::isNotBlank)) { "插件编号不能为空" }
        require(ids.size == ids.toSet().size) { "插件批次包含重复编号" }
        val activeIds = synchronized(this) { installed.keys.toSet() }
        require(ids.none(activeIds::contains)) {
            "插件批次包含已安装插件：" + ids.filter(activeIds::contains).joinToString()
        }

        val installedBefore = synchronized(this) { LinkedHashMap(installed) }
        val lifecycleBefore = synchronized(this) { LinkedHashMap(lifecycle) }
        val installedByBatch = mutableListOf<HarnessPlugin>()
        var current: HarnessPlugin? = null

        context.tools.lifecycleTransition {
            val staged = context.fork()
            try {
                batch.forEach { plugin ->
                    current = plugin
                    installLocked(plugin, staged)
                    installedByBatch += plugin
                }
                publishInstalled(staged, batch)
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    installedByBatch.asReversed().forEach { plugin ->
                        runCatching { plugin.uninstall(staged) }.exceptionOrNull()?.let(error::addSuppressed)
                    }
                }
                synchronized(this) {
                    installed.clear()
                    installed.putAll(installedBefore)
                    lifecycle.clear()
                    lifecycle.putAll(lifecycleBefore)
                    current?.let { failed ->
                        lifecycle[failed.id] = PluginLifecycleSnapshot(
                            failed.id, PluginLifecycleState.FAILED,
                            error.message ?: error::class.java.simpleName,
                        )
                    }
                }
                throw error
            }
        }
    }

    /**
     * Replaces an active plugin without exposing a partially-mutated registry surface.
     *
     * External resources cannot be snapshotted generically, so rollback re-runs the previous
     * plugin's installer after restoring the clean post-uninstall registry baseline. If either
     * replacement cleanup or previous-plugin restoration fails, the lifecycle remains FAILED.
     */
    suspend fun replace(plugin: HarnessPlugin) = lifecycleMutex.withLock {
        context.tools.lifecycleTransition { replaceLocked(plugin) }
    }

    suspend fun uninstall(id: String): Boolean = lifecycleMutex.withLock {
        val plugin = synchronized(this) { installed[id] } ?: return@withLock false
        context.tools.lifecycleTransition {
            val staged = context.fork()
            synchronized(this) {
                lifecycle[id] = PluginLifecycleSnapshot(id, PluginLifecycleState.UNINSTALLING)
            }
            try {
                retryPendingCleanupLocked(id)
                plugin.uninstall(staged)
                staged.publishTo(context)
                synchronized(this) {
                    installed.remove(id)
                    lifecycle.remove(id)
                    context.tools.markLifecycleSafe(installed.keys.none {
                        lifecycle[it]?.state == PluginLifecycleState.FAILED
                    })
                }
                true
            } catch (error: Throwable) {
                context.tools.markLifecycleSafe(false)
                synchronized(this) {
                    lifecycle[id] = PluginLifecycleSnapshot(
                        id, PluginLifecycleState.FAILED,
                        error.message ?: error::class.java.simpleName,
                    )
                }
                throw error
            }
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

    @Synchronized
    fun activePlugin(id: String): HarnessPlugin? =
        installed[id].takeIf { lifecycle[id]?.state == PluginLifecycleState.ACTIVE }

    private suspend fun replaceLocked(plugin: HarnessPlugin) {
        require(plugin.id.isNotBlank()) { "插件编号不能为空" }
        val previous = synchronized(this) { installed[plugin.id] }
            ?: error("插件未安装：${plugin.id}")
        if (previous === plugin) return

        retryPendingCleanupLocked(plugin.id)
        val staged = context.fork()
        var removedPrevious = false
        var replacementStarted = false
        var registryWithoutPrevious: HarnessRegistrySnapshot? = null
        synchronized(this) {
            lifecycle[plugin.id] = PluginLifecycleSnapshot(
                id = plugin.id,
                state = PluginLifecycleState.REPLACING,
            )
        }

        try {
            previous.uninstall(staged)
            removedPrevious = true
            registryWithoutPrevious = staged.snapshotRegistries()

            replacementStarted = true
            plugin.install(staged)
            staged.publishTo(context)
            synchronized(this) {
                installed[plugin.id] = plugin
                lifecycle[plugin.id] = PluginLifecycleSnapshot(
                    id = plugin.id,
                    state = PluginLifecycleState.ACTIVE,
                )
                context.tools.markLifecycleSafe(installed.keys.none {
                    lifecycle[it]?.state == PluginLifecycleState.FAILED
                })
            }
        } catch (error: Throwable) {
            var cleanupFailure: Throwable? = null
            var restoreFailure: Throwable? = null
            withContext(NonCancellable) {
                if (replacementStarted) {
                    val cleanupContext = staged.fork()
                    cleanupFailure = runCatching { plugin.uninstall(cleanupContext) }.exceptionOrNull()
                    if (cleanupFailure != null) {
                        pendingCleanup[plugin.id] = PendingPluginCleanup(plugin, cleanupContext)
                    }
                }
                if (removedPrevious) {
                    staged.restoreRegistries(checkNotNull(registryWithoutPrevious))
                    restoreFailure = runCatching { previous.install(staged) }.exceptionOrNull()
                    if (restoreFailure == null) staged.publishTo(context)
                }
            }

            cleanupFailure?.let(error::addSuppressed)
            restoreFailure?.let(error::addSuppressed)
            val restored = removedPrevious && cleanupFailure == null && restoreFailure == null
            if (!restored) context.tools.markLifecycleSafe(false)
            synchronized(this) {
                installed[plugin.id] = previous
                lifecycle[plugin.id] = PluginLifecycleSnapshot(
                    id = plugin.id,
                    state = if (restored) PluginLifecycleState.ACTIVE else PluginLifecycleState.FAILED,
                    lastError = if (restored) {
                        null
                    } else {
                        buildString {
                            append(error.message ?: error::class.java.simpleName)
                            cleanupFailure?.let {
                                append("; 替换插件清理失败：")
                                append(it.message ?: it::class.java.simpleName)
                            }
                            restoreFailure?.let {
                                append("; 原插件恢复失败：")
                                append(it.message ?: it::class.java.simpleName)
                            }
                        }
                    },
                )
            }
            throw error
        }
    }

    private suspend fun retryPendingCleanupLocked(id: String) {
        val pending = synchronized(this) { pendingCleanup[id] } ?: return
        try {
            withContext(NonCancellable) {
                pending.plugin.uninstall(pending.context)
            }
        } catch (error: Throwable) {
            context.tools.markLifecycleSafe(false)
            synchronized(this) {
                lifecycle[id] = PluginLifecycleSnapshot(
                    id = id,
                    state = PluginLifecycleState.FAILED,
                    lastError = "遗留插件资源清理失败：" + (error.message ?: error::class.java.simpleName),
                )
            }
            throw error
        }
        synchronized(this) {
            pendingCleanup.remove(id)
        }
    }

    private fun publishInstalled(staged: HarnessContext, plugins: List<HarnessPlugin>) = RegistryEntries.atomic {
        staged.publishTo(context)
        synchronized(this) {
            plugins.forEach { plugin ->
                installed[plugin.id] = plugin
                lifecycle[plugin.id] = PluginLifecycleSnapshot(plugin.id, PluginLifecycleState.ACTIVE)
            }
        }
    }

    private suspend fun installLocked(plugin: HarnessPlugin, staged: HarnessContext) {
        require(plugin.id.isNotBlank()) { "插件编号不能为空" }
        synchronized(this) {
            require(plugin.id !in installed) { "插件已安装：${plugin.id}" }
            lifecycle[plugin.id] = PluginLifecycleSnapshot(plugin.id, PluginLifecycleState.INSTALLING)
        }

        val registryBefore = staged.snapshotRegistries()
        try {
            plugin.install(staged)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { plugin.uninstall(staged) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            staged.restoreRegistries(registryBefore)
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
