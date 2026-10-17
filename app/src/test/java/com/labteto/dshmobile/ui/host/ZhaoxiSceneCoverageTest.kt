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
        assertEquals(75, hostScenes.size)
    }

    @Test fun oldV6AndHostSystemServicesNeverBecomePackScenes() {
        val hostSystemIds = LocalUiSceneCatalog.requirements.filter { it.hostProvided }
            .map { it.id }.toSet()
        assertTrue(ZhaoxiUiPackManifest.fullSceneIds.intersect(hostSystemIds).isEmpty())
        assertTrue(ZhaoxiUiPackManifest.fullSceneIds.contains(UiSceneId("route.home")))
    }
}
