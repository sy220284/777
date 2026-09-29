package com.labteto.dshmobile.harness.plugin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dependency-aware runtime facade over [PluginRegistry].
 *
 * It keeps catalog metadata and live lifecycle state consistent, rejects disabling providers that
 * still have active dependents, and performs best-effort rollback when a live replacement fails.
 */
class PluginManager(
    val catalog: PluginCatalog,
    private val registry: PluginRegistry = PluginRegistry(),
    val apiVersion: Int = HARNESS_PLUGIN_API_VERSION,
) {
    private val mutationMutex = Mutex()

    init {
        require(apiVersion > 0) { "Harness 插件 API 版本必须大于 0" }
    }

    val context: HarnessContext
        get() = registry.context

    suspend fun install(id: String): List<String> = installAll(listOf(id))

    suspend fun enable(id: String): List<String> = install(id)

    suspend fun installAll(ids: Iterable<String>): List<String> = mutationMutex.withLock {
        val installedVersions = installedVersions()
        val plan = catalog.resolveInstallOrder(
            requestedIds = ids,
            installedVersions = installedVersions,
            apiVersion = apiVersion,
        )
        if (plan.isEmpty()) return@withLock emptyList()

        val plugins = plan.map(PluginDefinition::create)
        registry.installAll(plugins)
        plugins.map(HarnessPlugin::id)
    }

    suspend fun disable(id: String): Boolean = mutationMutex.withLock {
        if (!registry.isInstalled(id)) return@withLock false
        val dependents = activeDependents(id)
        require(dependents.isEmpty()) {
            "插件仍被已启用插件依赖：$id <- ${dependents.joinToString()}"
        }
        registry.uninstall(id)
    }

    suspend fun uninstall(id: String): Boolean = disable(id)

    suspend fun replace(definition: PluginDefinition) = mutationMutex.withLock {
        val replacement = definition.descriptor
        require(replacement.apiVersion == apiVersion) {
            "插件 API 不兼容：${replacement.id} 需要 ${replacement.apiVersion}，当前 $apiVersion"
        }
        require(registry.isInstalled(replacement.id)) { "插件未安装：${replacement.id}" }
        require(registry.lifecycleSnapshot(replacement.id)?.state == PluginLifecycleState.ACTIVE) {
            "插件运行态异常，必须先停用后再恢复：${replacement.id}"
        }

        val activeIds = registry.ids().toSet()
        replacement.dependencies.forEach { dependency ->
            require(dependency.id in activeIds) {
                "替换插件缺少已启用依赖：${replacement.id} -> ${dependency.id}"
            }
            val dependencyDescriptor = catalog.descriptor(dependency.id)
                ?: error("已启用插件未登记：${dependency.id}")
            require(dependencyDescriptor.version >= dependency.minVersion) {
                "替换插件依赖版本不足：${dependency.id} 当前 ${dependencyDescriptor.version}，至少需要 ${dependency.minVersion}"
            }
            require(!dependsTransitivelyOn(dependency.id, replacement.id, mutableSetOf())) {
                "替换插件会形成依赖环：${replacement.id} -> ${dependency.id} -> ${replacement.id}"
            }
        }

        activeIds.asSequence()
            .filterNot { it == replacement.id }
            .mapNotNull(catalog::descriptor)
            .forEach { descriptor ->
                descriptor.dependencies.firstOrNull { it.id == replacement.id }?.let { dependency ->
                    require(replacement.version >= dependency.minVersion) {
                        "替换版本会破坏已启用依赖：${descriptor.id} 至少需要 ${replacement.id} ${dependency.minVersion}，替换版本为 ${replacement.version}"
                    }
                }
            }

        val plugin = definition.create()
        registry.replace(plugin)
        catalog.register(definition, replace = true)
    }

    fun installedPluginIds(): List<String> = registry.ids()

    fun descriptors(): List<PluginDescriptor> = catalog.descriptors()

    fun lifecycleSnapshots(): List<PluginLifecycleSnapshot> = registry.lifecycleSnapshots()

    fun lifecycleSnapshot(id: String): PluginLifecycleSnapshot? = registry.lifecycleSnapshot(id)

    private fun installedVersions(): Map<String, Int> = registry.ids().associateWith { id ->
        require(registry.lifecycleSnapshot(id)?.state == PluginLifecycleState.ACTIVE) {
            "插件运行态异常，不能视为已启用：$id"
        }
        catalog.descriptor(id)?.version ?: error("已安装插件未登记：$id")
    }

    private fun activeDependents(id: String): List<String> = registry.ids()
        .filterNot { it == id }
        .filter { activeId ->
            catalog.descriptor(activeId)?.dependencies?.any { it.id == id } == true
        }

    private fun dependsTransitivelyOn(
        startId: String,
        targetId: String,
        seen: MutableSet<String>,
    ): Boolean {
        if (!seen.add(startId)) return false
        val descriptor = catalog.descriptor(startId) ?: return false
        return descriptor.dependencies.any { dependency ->
            dependency.id == targetId ||
                dependsTransitivelyOn(dependency.id, targetId, seen)
        }
    }
}
