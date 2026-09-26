package com.labteto.dshmobile.harness.plugin

import com.labteto.dshmobile.harness.agent.AgentRunInterceptor
import com.labteto.dshmobile.harness.capability.CapabilityRegistry
import com.labteto.dshmobile.harness.registry.NamedRegistry
import com.labteto.dshmobile.harness.tools.ToolRegistry

data class HarnessContext(
    val tools: ToolRegistry = ToolRegistry(),
    val capabilities: CapabilityRegistry = CapabilityRegistry(),
    val events: NamedRegistry<Any> = NamedRegistry(),
    val projections: NamedRegistry<Any> = NamedRegistry(),
    val commands: NamedRegistry<Any> = NamedRegistry(),
    val models: NamedRegistry<Any> = NamedRegistry(),
    val settings: NamedRegistry<Any> = NamedRegistry(),
    val runInterceptors: NamedRegistry<AgentRunInterceptor> = NamedRegistry(),
)

interface HarnessPlugin {
    val id: String
    suspend fun install(context: HarnessContext)
    suspend fun uninstall(context: HarnessContext) = Unit
}

class PluginRegistry(
    val context: HarnessContext = HarnessContext(),
) {
    private val installed = linkedMapOf<String, HarnessPlugin>()

    suspend fun install(plugin: HarnessPlugin) {
        require(plugin.id.isNotBlank()) { "插件编号不能为空" }
        synchronized(this) {
            require(plugin.id !in installed) { "插件已安装：${plugin.id}" }
        }
        plugin.install(context)
        synchronized(this) {
            installed[plugin.id] = plugin
        }
    }

    suspend fun uninstall(id: String): Boolean {
        val plugin = synchronized(this) { installed[id] } ?: return false
        plugin.uninstall(context)
        synchronized(this) {
            installed.remove(id)
        }
        return true
    }

    @Synchronized
    fun ids(): List<String> = installed.keys.toList()

    @Synchronized
    fun isInstalled(id: String): Boolean = id in installed
}
