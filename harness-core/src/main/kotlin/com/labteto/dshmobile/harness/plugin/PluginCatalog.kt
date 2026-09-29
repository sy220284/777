package com.labteto.dshmobile.harness.plugin

/**
 * Process-local catalog of plugin definitions.
 *
 * The catalog intentionally contains factories and metadata only. Loading code from external
 * DEX/JAR artifacts is a separate trust boundary and can be added later without changing the
 * lifecycle/dependency contracts established here.
 */
class PluginCatalog(
    definitions: Iterable<PluginDefinition> = emptyList(),
) {
    private val entries = linkedMapOf<String, PluginDefinition>()

    init {
        definitions.forEach { register(it) }
    }

    @Synchronized
    fun register(definition: PluginDefinition, replace: Boolean = false) {
        val id = definition.descriptor.id
        if (!replace) require(id !in entries) { "插件已登记：$id" }
        entries[id] = definition
    }

    @Synchronized
    fun unregister(id: String): PluginDefinition? = entries.remove(id)

    @Synchronized
    fun definition(id: String): PluginDefinition? = entries[id]

    @Synchronized
    fun descriptor(id: String): PluginDescriptor? = entries[id]?.descriptor

    @Synchronized
    fun definitions(): List<PluginDefinition> = entries.values.toList()

    @Synchronized
    fun descriptors(): List<PluginDescriptor> = entries.values.map(PluginDefinition::descriptor)

    @Synchronized
    fun ids(): List<String> = entries.keys.toList()

    @Synchronized
    private fun snapshot(): Map<String, PluginDefinition> = LinkedHashMap(entries)

    fun resolveInstallOrder(
        requestedIds: Iterable<String>,
        installedVersions: Map<String, Int> = emptyMap(),
        apiVersion: Int = HARNESS_PLUGIN_API_VERSION,
    ): List<PluginDefinition> {
        require(apiVersion > 0) { "Harness 插件 API 版本必须大于 0" }
        val definitions = snapshot()
        val resolved = mutableListOf<PluginDefinition>()
        val visited = linkedSetOf<String>()
        val visiting = mutableListOf<String>()

        fun visit(id: String, minimumVersion: Int? = null) {
            val definition = definitions[id] ?: error("插件未登记：$id")
            val descriptor = definition.descriptor
            require(descriptor.apiVersion == apiVersion) {
                "插件 API 不兼容：$id 需要 ${descriptor.apiVersion}，当前 $apiVersion"
            }
            minimumVersion?.let { minimum ->
                require(descriptor.version >= minimum) {
                    "插件版本不满足依赖：$id 当前 ${descriptor.version}，至少需要 $minimum"
                }
            }

            installedVersions[id]?.let { installed ->
                require(installed == descriptor.version) {
                    "插件目录与运行版本不一致：$id 已安装 $installed，目录为 ${descriptor.version}"
                }
                minimumVersion?.let { minimum ->
                    require(installed >= minimum) {
                        "已安装插件版本不满足依赖：$id 当前 $installed，至少需要 $minimum"
                    }
                }
                visited += id
                return
            }

            if (id in visited) return
            val cycleIndex = visiting.indexOf(id)
            if (cycleIndex >= 0) {
                val cycle = (visiting.drop(cycleIndex) + id).joinToString(" -> ")
                error("插件依赖形成环：$cycle")
            }

            visiting += id
            descriptor.dependencies.forEach { dependency ->
                visit(dependency.id, dependency.minVersion)
            }
            visiting.removeAt(visiting.lastIndex)
            visited += id
            resolved += definition
        }

        requestedIds.toList().distinct().forEach { visit(it) }
        return resolved
    }
}
