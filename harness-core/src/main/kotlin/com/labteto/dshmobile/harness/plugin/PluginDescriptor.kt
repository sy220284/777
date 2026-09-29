package com.labteto.dshmobile.harness.plugin

const val HARNESS_PLUGIN_API_VERSION: Int = 1

data class PluginDependency(
    val id: String,
    val minVersion: Int = 1,
) {
    init {
        require(id.isNotBlank()) { "插件依赖编号不能为空" }
        require(minVersion > 0) { "插件依赖最低版本必须大于 0：$id" }
    }
}

data class PluginDescriptor(
    val id: String,
    val version: Int = 1,
    val apiVersion: Int = HARNESS_PLUGIN_API_VERSION,
    val dependencies: List<PluginDependency> = emptyList(),
    val capabilities: Set<String> = emptySet(),
    val entryPoint: String? = null,
) {
    init {
        require(id.isNotBlank()) { "插件编号不能为空" }
        require(version > 0) { "插件版本必须大于 0：$id" }
        require(apiVersion > 0) { "插件 API 版本必须大于 0：$id" }
        require(dependencies.none { it.id == id }) { "插件不能依赖自身：$id" }
        require(dependencies.map(PluginDependency::id).distinct().size == dependencies.size) {
            "插件依赖包含重复编号：$id"
        }
        require(capabilities.none(String::isBlank)) { "插件能力编号不能为空：$id" }
        entryPoint?.let { require(it.isNotBlank()) { "插件入口不能为空：$id" } }
    }
}

fun interface HarnessPluginFactory {
    fun create(): HarnessPlugin
}

data class PluginDefinition(
    val descriptor: PluginDescriptor,
    val factory: HarnessPluginFactory,
) {
    fun create(): HarnessPlugin = factory.create().also { plugin ->
        require(plugin.id == descriptor.id) {
            "插件工厂返回编号不匹配：期望 ${descriptor.id}，实际 ${plugin.id}"
        }
    }
}
