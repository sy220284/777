package com.labteto.dshmobile.local.feature

/** Top-level product ownership boundary used by Architecture 3.0 feature composition. */
internal enum class LocalFeatureModuleId {
    SHELL,
    CHAT,
    WORK,
    PROJECT,
    AUTOMATION,
    TOOLS,
    SETTINGS,
}

/** Stable local routes. A route is a subfeature contribution owned by exactly one module. */
internal enum class LocalFeatureRoute {
    HOME,
    PERSONA_GALLERY,
    DIARY,
    WORKSPACE,
    RUN_CENTER,
    PROJECT,
    TASKS,
    TOOLS,
    SETTINGS,
}

internal data class LocalFeatureModule(
    val id: LocalFeatureModuleId,
    val routes: List<LocalFeatureRoute>,
) {
    init {
        require(routes.isNotEmpty()) { "功能模块至少需要注册一个路由：$id" }
        require(routes.distinct().size == routes.size) { "功能模块存在重复路由：$id" }
    }
}

/**
 * Immutable compile-time catalog.
 *
 * Product features register their subfeature routes here once at application composition time.
 * Runtime plugin hot-swap remains owned by PluginCatalog/PluginManager and is intentionally separate.
 */
internal object LocalFeatureCatalog {
    val modules: List<LocalFeatureModule> = listOf(
        LocalFeatureModule(LocalFeatureModuleId.SHELL, listOf(LocalFeatureRoute.HOME)),
        LocalFeatureModule(
            LocalFeatureModuleId.CHAT,
            listOf(LocalFeatureRoute.PERSONA_GALLERY, LocalFeatureRoute.DIARY),
        ),
        LocalFeatureModule(
            LocalFeatureModuleId.WORK,
            listOf(LocalFeatureRoute.WORKSPACE, LocalFeatureRoute.RUN_CENTER),
        ),
        LocalFeatureModule(LocalFeatureModuleId.PROJECT, listOf(LocalFeatureRoute.PROJECT)),
        LocalFeatureModule(LocalFeatureModuleId.AUTOMATION, listOf(LocalFeatureRoute.TASKS)),
        LocalFeatureModule(LocalFeatureModuleId.TOOLS, listOf(LocalFeatureRoute.TOOLS)),
        LocalFeatureModule(LocalFeatureModuleId.SETTINGS, listOf(LocalFeatureRoute.SETTINGS)),
    )

    private val ownerByRoute: Map<LocalFeatureRoute, LocalFeatureModuleId> = buildMap {
        modules.forEach { module ->
            module.routes.forEach { route ->
                check(put(route, module.id) == null) { "功能路由被重复注册：$route" }
            }
        }
    }

    val routes: List<LocalFeatureRoute> = LocalFeatureRoute.entries.filter(ownerByRoute::containsKey)

    init {
        check(ownerByRoute.keys == LocalFeatureRoute.entries.toSet()) {
            "每个本机功能路由必须且只能归属一个功能模块"
        }
    }

    fun resolve(routeName: String?): LocalFeatureRoute? =
        routeName?.let { name -> routes.firstOrNull { it.name == name } }

    fun ownerOf(route: LocalFeatureRoute): LocalFeatureModuleId = ownerByRoute.getValue(route)

    fun routesFor(moduleId: LocalFeatureModuleId): List<LocalFeatureRoute> =
        modules.first { it.id == moduleId }.routes
}
