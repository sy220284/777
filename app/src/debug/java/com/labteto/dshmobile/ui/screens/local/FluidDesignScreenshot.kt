package com.labteto.dshmobile.ui.screens.local

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.local.chat.*
import com.labteto.dshmobile.local.interaction.LocalQuestion
import com.labteto.dshmobile.local.presentation.*
import com.labteto.dshmobile.local.session.LocalHarnessMessage
import com.labteto.dshmobile.local.work.LocalGoal
import com.labteto.dshmobile.local.work.LocalTodoItem
import com.labteto.dshmobile.ui.components.*
import com.labteto.dshmobile.ui.screens.tools.FluidCapabilityCatalogue
import com.labteto.dshmobile.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Fixed design fixtures rendered by production components; no model, filesystem or paid effects. */
@Composable
internal fun FluidDesignScreenshot(screen: String) {
    when {
        screen.startsWith("work-") -> FluidWorkScreenshot(screen.substringAfter("work-"))
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

private fun fluidWorkFixture() = LocalWorkUiState(
    sessionId = "design-work", running = true,
    goal = LocalGoal("迁移鉴权模块"),
    plan = listOf("检查现有入口", "确认鉴权方案", "改写中间件", "验证与交付"),
    todos = listOf(LocalTodoItem("检查现有入口", "completed"), LocalTodoItem("确认鉴权方案", "in_progress")),
    pendingQuestion = LocalQuestion("design-decision", "鉴权方式选哪个？",
        listOf("mTLS · 生产环境，多实例", "JWT · 无状态，扩展容易", "Paseto · 默认配置更简单")),
)

@Composable
private fun FluidWorkScreenshot(page: String) {
    val section = when (page) { "goal" -> 0; "plan" -> 1; "decision" -> 2; "outputs" -> 4; else -> 3 }
    val state = remember { MutableStateFlow(fluidWorkFixture()) }
    val actions = remember {
        LocalWorkFeatureUiActions(
            workState = state, workspaceFiles = { emptyList() },
            conversationFiles = { error("固定设计截图不读取会话文件") },
            previewWorkspaceFile = { error("固定设计截图不读取文件") },
            backgroundJobOutput = { "" },
            artifactsForUi = { listOf(LocalArtifactUiItem("middleware.ts", "file", "design-call", 3, false)) },
            toolActivitiesForUi = { emptyList() }, eventSequenceForUi = { 3 }, stopBackgroundJob = { "" },
            startBackgroundAgent = { LocalWorkUiActionResult(false, "截图样例不执行") },
            sendBackgroundAgentMessage = { _, _ -> LocalWorkUiActionResult(false, "截图样例不执行") },
        )
    }
    Column(Modifier.fillMaxSize()) {
        DsTopBar("工作执行 · 设计样例", onBack = {})
        Column(Modifier.weight(1f)) {
            LocalWorkDesignSurface(actions, state.value.sessionId, "迁移鉴权模块", section, {},
                { _, _ -> false }, {}, {}, {}, execution = {
                    Column(Modifier.padding(DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
                        WorkTimelineStepRow(LocalHarnessMessage("design-step", "progress", "已检查现有入口，接下来确认鉴权方案。", createdAt = 1))
                        WorkTimelineStepRow(LocalHarnessMessage("design-tool", "tool", "", createdAt = 2, toolName = "read", toolIsError = false))
                        WorkTimelineStepRow(LocalHarnessMessage("design-block", "progress", "需要你选择方案。三个候选在部署和维护方式上有不同取舍。", createdAt = 3), active = true)
                    }
                })
        }
        DsConversationComposer(Modifier.padding(DsSpacing.medium)) {
            DsComposerField("", {}, "继续交代任务…", modifier = Modifier.fillMaxWidth())
        }
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
    Column(Modifier.fillMaxSize().padding(DsSpacing.medium), verticalArrangement = Arrangement.spacedBy(DsSpacing.medium)) {
        DsTopBar("人物档案 · 设计样例", onBack = {})
        FluidPersonaIdentity(entry, "", false, {})
        FluidSectionNavigation(listOf("关系", "事件", "记忆", "设定"), section, {}, tag = "profileFixture")
        if (section == 3) PersonaDetails(entry.persona) else FluidPersonaStorySection(entry.stories.single(), section)
        Spacer(Modifier.weight(1f))
        DsButton("开始聊天", {}, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FluidGalleryScreenshot() {
    val entries = remember { listOf(fluidPersonaFixture(), fluidPersonaFixture().copy(id = "design-persona-2",
        persona = PersonaProfile(id = "design-profile-2", name = "陆棠", portrait = "周末看展吗"))) }
    Column(Modifier.fillMaxSize().padding(DsSpacing.medium)) {
        DsTopBar("人物图集 · 设计样例", onBack = {})
        DsSearchField("", {}, "搜索人物", Modifier.padding(bottom = 14.dp))
        FluidPersonaGalleryGrid(entries, {}, {}, Modifier.weight(1f))
    }
}
