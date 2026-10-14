package com.labteto.dshmobile.ui.screens.local

import com.shenyan.designsystem.foundation.SyMetric

import com.labteto.dshmobile.ui.shenyan.SyButton
import com.labteto.dshmobile.ui.shenyan.SySearchField
import com.labteto.dshmobile.ui.shenyan.SyTopBar

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.chat.*
import com.labteto.dshmobile.local.presentation.*
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.screens.tools.FluidCapabilityCatalogue
import com.labteto.dshmobile.ui.theme.*

/** Fixed design fixtures rendered by production components; no model, filesystem or paid effects. */
@Composable
internal fun FluidDesignScreenshot(screen: String) {
    when {
        screen.startsWith("persona-") -> FluidPersonaScreenshot(screen.substringAfter("persona-").toInt())
        screen == "gallery" -> FluidGalleryScreenshot()
        screen == "capability" -> FluidCapabilityCatalogue(
            listOf(
                LocalCapabilityUiEntry("read", "workspace", "READ_ONLY", "NEVER", emptyList()),
                LocalCapabilityUiEntry("edit", "workspace", "WORKSPACE_WRITE", "MUTATION", emptyList()),
                LocalCapabilityUiEntry("web_search", "network", "NETWORK", "NEVER", emptyList()),
                LocalCapabilityUiEntry("mcp_github_fetch", "mcp", "NETWORK", "NEVER", listOf("连接 GitHub")),
            ), false, false, 1, {}, {}, {}, {}, {}, null)
    }
}

private fun fluidPersonaFixture() = PersonaGalleryEntry("design-persona",
    PersonaProfile(id = "design-profile", name = "沈昀", portrait = "邻居 · 大学同学"),
    stories = listOf(PersonaGalleryStory("design-story", "上线前的这一周",
        chatState = ChatCharacterState(mood = "累，但安心", relationshipState = "稳定期",
            unresolvedThreads = listOf("接口文档"), updatedAt = 1,
            dynamics = RelationshipDynamics(sharedMoments = listOf("一起讨论接口文档", "修好了旧自行车"),
                sharedObjects = listOf("说过想去看一次海，一直没去"))))))

@Composable
private fun FluidPersonaScreenshot(section: Int) {
    val entry = remember { fluidPersonaFixture() }
    Column(Modifier.fillMaxSize().padding(SyMetric.gapLg), verticalArrangement = Arrangement.spacedBy(SyMetric.gapLg)) {
        SyTopBar("人物档案 · 设计样例", onBack = {}, backContentDescription = "返回")
        FluidPersonaIdentity(entry, "", false, {})
        FluidSectionNavigation(listOf("关系", "事件", "记忆", "设定"), section, {}, tag = "profileFixture")
        if (section == 3) PersonaDetails(entry.persona) else FluidPersonaStorySection(entry.stories.single(), section)
        Spacer(Modifier.weight(1f))
        SyButton("开始聊天", {}, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FluidGalleryScreenshot() {
    val entries = remember { listOf(fluidPersonaFixture(), fluidPersonaFixture().copy(id = "design-persona-2",
        persona = PersonaProfile(id = "design-profile-2", name = "陆棠", portrait = "周末看展吗"))) }
    Column(Modifier.fillMaxSize().padding(SyMetric.gapLg)) {
        SyTopBar("人物图集 · 设计样例", onBack = {}, backContentDescription = "返回")
        SySearchField("", {}, "搜索人物", Modifier.padding(bottom = 14.dp))
        FluidPersonaGalleryGrid(entries, {}, {}, Modifier.weight(1f))
    }
}
