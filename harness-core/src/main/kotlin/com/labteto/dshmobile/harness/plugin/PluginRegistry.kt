package com.labteto.dshmobile.harness.plugin

import com.labteto.dshmobile.harness.capability.CapabilityDescriptor
import com.labteto.dshmobile.harness.capability.CapabilityRegistry
import com.labteto.dshmobile.harness.registry.NamedRegistry
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.ToolRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

class PluginRegistry(
    val context: HarnessContext = HarnessContext(),
) {
    private data class ContextSnapshot(
        val tools: Map<String, HarnessTool>,
        val capabilities: List<Pair<CapabilityDescriptor, Any>>,
        val events: Map<String, Any>,
        val projections: Map<String, Any>,
        val commands: Map<String, Any>,
        val models: Map<String, Any>,
        val settings: Map<String, Any>,
    )

    private val installed = linkedMapOf<String, HarnessPlugin>()
    private val lifecycleMutex = Mutex()

    suspend fun install(plugin: HarnessPlugin) = lifecycleMutex.withLock {
        require(plugin.id.isNotBlank()) { "插件编号不能为空" }
        synchronized(this) {
            require(plugin.id !in installed) { "插件已安装：${plugin.id}" }
        }
        val snapshot = snapshotContext()
        try {
            plugin.install(context)
            synchronized(this) {
                installed[plugin.id] = plugin
            }
        } catch (error: Throwable) {
            restoreContext(snapshot)
            throw error
        }
    }

    suspend fun uninstall(id: String): Boolean = lifecycleMutex.withLock {
        val plugin = synchronized(this) { installed[id] } ?: return@withLock false
        val snapshot = snapshotContext()
        try {
            plugin.uninstall(context)
            synchronized(this) {
                installed.remove(id)
            }
            true
        } catch (error: Throwable) {
            restoreContext(snapshot)
            throw error
        }
    }

    @Synchronized
    fun ids(): List<String> = installed.keys.toList()

    @Synchronized
    fun isInstalled(id: String): Boolean = id in installed

    private fun snapshotContext(): ContextSnapshot = ContextSnapshot(
        tools = context.tools.snapshot(),
        capabilities = context.capabilities.snapshot(),
        events = context.events.snapshot(),
        projections = context.projections.snapshot(),
        commands = context.commands.snapshot(),
        models = context.models.snapshot(),
        settings = context.settings.snapshot(),
    )

    private fun restoreContext(snapshot: ContextSnapshot) {
        context.tools.restore(snapshot.tools)
        context.capabilities.restore(snapshot.capabilities)
        context.events.restore(snapshot.events)
        context.projections.restore(snapshot.projections)
        context.commands.restore(snapshot.commands)
        context.models.restore(snapshot.models)
        context.settings.restore(snapshot.settings)
    }
}
