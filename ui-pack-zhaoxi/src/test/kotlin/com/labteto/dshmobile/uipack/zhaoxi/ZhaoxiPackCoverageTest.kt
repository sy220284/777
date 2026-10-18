package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiSceneId
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 朝夕包结构一致性回归（E8.14 实装）。
 *
 * 对齐主线 V6 的 V6FullSceneCoverageTest 职责：在包侧自证
 * manifest/renderers 双向一致、不越界声明 hostProvided 场景、
 * 场景全集不超出 76 个非 hostProvided 目标清单。
 * E8.14 起 renderers 全部为 Zx* 实装分发（占位渲染已移除）。
 * Host 目录的逐项对照（SceneCatalog 级）属 app 模块测试，接入时补齐。
 */
class ZhaoxiPackCoverageTest {

    private val hostProvided = setOf(
        "conversation.attachments", "shell.notifications", "system.permissions",
    ).map(::UiSceneId).toSet()

    @Test
    fun fullInventoryMatchesMergedCatalog() {
        assertEquals("朝夕 76 场景目标清单与 LocalUiSceneCatalog 非 hostProvided 全集不一致", 76, ZhaoxiUiPackManifest.fullSceneIds.size)
        assertEquals("朝夕 37 能力目标清单与 LocalUiConnectedCapabilities 投影不一致", 37, ZhaoxiUiPackManifest.fullCapabilityIds.size)
    }

    @Test
    fun inventoryNeverDeclaresHostServices() {
        assertTrue("朝夕不得声明 hostProvided 场景（conversation.attachments / shell.notifications / system.permissions）", (ZhaoxiUiPackManifest.fullSceneIds intersect hostProvided).isEmpty())
    }

    @Test
    fun missingFeatureOwnersNeverAdvertiseBusinessScenes() {
        val preferences = java.lang.reflect.Proxy.newProxyInstance(
            com.labteto.dshmobile.uicontract.HostPreferencesUiPort::class.java.classLoader,
            arrayOf(com.labteto.dshmobile.uicontract.HostPreferencesUiPort::class.java),
        ) { _, method, _ -> error("No business call expected: ${method.name}") } as com.labteto.dshmobile.uicontract.HostPreferencesUiPort
        val pack = ZhaoxiApplicationScenePack(preferences)
        assertEquals(setOf("settings.appearance", "settings.notifications", "settings.permissions").map(::UiSceneId).toSet(), pack.manifest.scenes)
        assertEquals(pack.manifest.scenes, pack.renderers.keys)
        assertTrue(pack.manifest.requiredCapabilities.isEmpty())
        assertTrue(ZhaoxiUiPackManifest.fullSceneIds.containsAll(pack.manifest.scenes))
    }
}
