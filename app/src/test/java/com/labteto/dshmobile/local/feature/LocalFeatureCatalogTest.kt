package com.labteto.dshmobile.local.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFeatureCatalogTest {
    @Test
    fun moduleRejectsEmptyAndDuplicateRouteRegistration() {
        val emptyFailure = runCatching {
            LocalFeatureModule(LocalFeatureModuleId.SHELL, emptyList())
        }.exceptionOrNull()
        assertTrue(emptyFailure?.message?.contains("至少需要注册一个路由") == true)

        val duplicateFailure = runCatching {
            LocalFeatureModule(
                LocalFeatureModuleId.WORK,
                listOf(LocalFeatureRoute.WORKSPACE, LocalFeatureRoute.WORKSPACE),
            )
        }.exceptionOrNull()
        assertTrue(duplicateFailure?.message?.contains("重复路由") == true)
    }

    @Test
    fun catalogResolvesKnownRoutesAndRejectsUnknownNames() {
        assertEquals(LocalFeatureRoute.HOME, LocalFeatureCatalog.resolve("HOME"))
        assertEquals(LocalFeatureRoute.SETTINGS, LocalFeatureCatalog.resolve("SETTINGS"))
        assertNull(LocalFeatureCatalog.resolve(null))
        assertNull(LocalFeatureCatalog.resolve("UNKNOWN"))
    }

    @Test
    fun everyRouteHasExactlyOneStableOwnerAndModuleRouteList() {
        val allRoutes = LocalFeatureCatalog.modules.flatMap(LocalFeatureModule::routes)

        assertEquals(LocalFeatureRoute.entries.toSet(), allRoutes.toSet())
        assertEquals(allRoutes.size, allRoutes.distinct().size)
        LocalFeatureCatalog.modules.forEach { module ->
            assertEquals(module.routes, LocalFeatureCatalog.routesFor(module.id))
            module.routes.forEach { route ->
                assertEquals(module.id, LocalFeatureCatalog.ownerOf(route))
            }
        }
        assertEquals(LocalFeatureRoute.entries.toList(), LocalFeatureCatalog.routes)
    }
}
