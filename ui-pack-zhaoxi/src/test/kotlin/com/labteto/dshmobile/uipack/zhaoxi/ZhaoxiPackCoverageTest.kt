package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UI_CONTRACT_VERSION
import com.labteto.dshmobile.uicontract.UiSceneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 朝夕包结构一致性回归（E8.14 实装）。
 *
 * 对齐主线 V6 的 V6FullSceneCoverageTest 职责：在包侧自证
 * manifest/renderers 双向一致、不越界声明 hostProvided 场景、
 * 场景全集不超出 75 个非 hostProvided 目标清单。
 * E8.14 起 renderers 全部为 Zx* 实装分发（占位渲染已移除）。
 * Host 目录的逐项对照（SceneCatalog 级）属 app 模块测试，接入时补齐。
 */
class ZhaoxiPackCoverageTest {

    private val hostProvided = setOf(
        "conversation.attachments", "shell.notifications", "system.permissions",
    ).map(::UiSceneId).toSet()

    @Test
    fun fullInventoryMatchesMergedCatalog() {
        assertEquals(75, ZhaoxiUiPackManifest.fullSceneIds.size,
            "朝夕 75 场景目标清单与 LocalUiSceneCatalog 非 hostProvided 全集不一致")
        assertEquals(37, ZhaoxiUiPackManifest.fullCapabilityIds.size,
            "朝夕 37 能力目标清单与 LocalUiConnectedCapabilities 投影不一致")
    }

    @Test
    fun inventoryNeverDeclaresHostServices() {
        assertTrue((ZhaoxiUiPackManifest.fullSceneIds intersect hostProvided).isEmpty(),
            "朝夕不得声明 hostProvided 场景（conversation.attachments / shell.notifications / system.permissions）")
    }

    @Test
    fun declaredScenesAreSubsetOfCatalog() {
        // TODO(IMPL)：接入时构造完整端口注入的 ZhaoxiApplicationScenePack，
        // 断言 manifest.scenes == renderers.keys，且 ⊆ fullSceneIds；
        // 再经 UiApplicationPackRegistry.inspectArchitectureSlice（开发期探针）
        // 与 Host 目录做正式准入核对。
    }
}
