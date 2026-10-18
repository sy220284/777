package com.labteto.dshmobile.ui.host

import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uipack.zhaoxi.ZhaoxiUiPackManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard against silently losing routes during independent UI pack replacement. */
class ZhaoxiSceneCoverageTest {
    @Test fun everyNonSystemHostSceneIsDeclaredByE817() {
        val hostScenes = LocalUiSceneCatalog.requirements.filterNot { it.hostProvided }
            .map { it.id }.toSet()
        assertEquals("Zhaoxi pack scene inventory and the live Host catalog diverged",
            hostScenes, ZhaoxiUiPackManifest.fullSceneIds)
        assertEquals(76, hostScenes.size)
        assertTrue("Agent 审批策略必须是独立页面，不能与 Android 设备授权合并",
            LocalUiSceneCatalog.requirements.any {
                it.id == UiSceneId("settings.approval_mode") && !it.hostProvided
            })
        assertTrue(hostScenes.contains(UiSceneId("settings.approval_mode")))
        assertTrue(hostScenes.contains(UiSceneId("settings.permissions")))
    }

    @Test fun oldV6AndHostSystemServicesNeverBecomePackScenes() {
        val hostSystemIds = LocalUiSceneCatalog.requirements.filter { it.hostProvided }
            .map { it.id }.toSet()
        assertTrue(ZhaoxiUiPackManifest.fullSceneIds.intersect(hostSystemIds).isEmpty())
        assertTrue(ZhaoxiUiPackManifest.fullSceneIds.contains(UiSceneId("route.home")))
    }
}
