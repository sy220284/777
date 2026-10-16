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

    /**
     * User-approved recovery of a failed, reloadable plugin only. Any incomplete cleanup is
     * retried by PluginRegistry; on failure its state remains FAILED and no stale tools are
     * re-admitted. Never replay an Agent tool call or silently recover a healthy plugin.
     */
    suspend fun recoverFailed(id: String): List<String> = mutationMutex.withLock {
        require(registry.lifecycleSnapshot(id)?.state == PluginLifecycleState.FAILED) {
            "插件未处于失败态：$id"
        }
        require(activeDependents(id).isEmpty()) {
            "插件仍被其他已启用插件依赖，禁止直接恢复：$id"
        }
        // A failed replacement can still own old resources; explicitly remove them first.
        // A failed installation has no installed instance and can proceed to a new install.
        if (registry.isInstalled(id)) registry.uninstall(id)
        // Failed installation may leave externally-owned resources after an unsuccessful
        // compensating uninstall. Retrying that cleanup is mandatory before fresh admission.
        else registry.cleanupFailed(id)
        val plan = catalog.resolveInstallOrder(listOf(id), installedVersions(), apiVersion)
        val plugins = plan.map(PluginDefinition::create)
        registry.installAll(plugins)
        plugins.map(HarnessPlugin::id)
    }

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

    /** UI management commands hold the mutation lock until their remote work finishes. */
    suspend fun <T> withActivePlugin(id: String, block: suspend (HarnessPlugin, HarnessContext) -> T): T =
        mutationMutex.withLock {
            val plugin = registry.activePlugin(id) ?: error("插件未启用或运行态异常：$id")
            block(plugin, registry.context)
        }

    /**
     * Read-only observation of an active plugin's snapshot without queueing behind remote commands.
     * This is not a lease or authorization: callers must not execute plugin commands through it.
     * A concurrent disable/replacement invalidates the result rather than displaying a stale instance.
     */
    suspend fun <T> observeActivePlugin(id: String, read: suspend (HarnessPlugin) -> T): T {
        val plugin = registry.activePlugin(id) ?: error("插件未启用或运行态异常：$id")
        val snapshot = read(plugin)
        check(registry.activePlugin(id) === plugin) { "插件状态已变化，请重新读取：$id" }
        return snapshot
    }

    fun installedPluginIds(): List<String> = registry.ids()

    fun descriptors(): List<PluginDescriptor> = catalog.descriptors()

    fun lifecycleSnapshots(): List<PluginLifecycleSnapshot> = registry.lifecycleSnapshots()

    fun lifecycleSnapshot(id: String): PluginLifecycleSnapshot? = registry.lifecycleSnapshot(id)

    private fun installedVersions(): Map<String, Int> = registry.ids()
        .filter { id -> registry.lifecycleSnapshot(id)?.state == PluginLifecycleState.ACTIVE }
        .associateWith { id ->
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
