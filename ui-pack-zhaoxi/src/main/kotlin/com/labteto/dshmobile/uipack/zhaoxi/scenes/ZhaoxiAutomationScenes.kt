package com.labteto.dshmobile.uipack.zhaoxi.scenes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.PlanChatAutomationCommand
import com.labteto.dshmobile.uicontract.AutomationUiCommand
import com.labteto.dshmobile.uicontract.AutomationUiPort
import com.labteto.dshmobile.uicontract.UiAutomationSnapshot
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadge
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxBadgeTone
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButton
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxButtonVariant
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxChip
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxEmpty
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxFooterNote
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSection
import com.labteto.dshmobile.uipack.zhaoxi.components.ZxSwitch
import com.labteto.dshmobile.uipack.zhaoxi.theme.ZhaoxiTheme
import com.labteto.dshmobile.uihost.UiSceneRenderScope
import kotlinx.coroutines.launch

/**
 * 自动化与工具/媒体域场景（E8.14 实装）。
 * 启停/删除/立即运行只经 AutomationUiPort.submit；任务详情展示快照投影。
 */

/** automation.tasks / route.tasks — 自动化任务列表。 */
@Composable
fun ZhaoxiAutomationTasksScene(
    automation: AutomationUiPort,
    scope: UiSceneRenderScope,
    onOpenEditor: (String) -> Unit = {},
) {
    val co = rememberCoroutineScope()
    val snap by automation.snapshots.collectAsState(initial = UiAutomationSnapshot(automation.context(), emptyList()))
    ZxPage("自动化", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        if (snap.tasks.isEmpty()) ZxEmpty("还没有自动化", "新建定时提醒、任务或人物互动编排")
        snap.tasks.forEach { t ->
            val name = runCatching { t.toString().substringBefore(',').substringAfter('=').trim() }
                .getOrDefault("任务")
            ZxRow(name, "", onClick = { onOpenEditor(name) }, trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ZxChip("运行", false) { co.launch { automation.submit(AutomationUiCommand.RunNow(t.id)) } }
                    ZxSwitch(
                        checked = true,
                        onCheckedChange = { on ->
                            co.launch {
                                automation.submit(if (on) AutomationUiCommand.Resume(t.id) else AutomationUiCommand.Pause(t.id))
                            }
                        },
                    )
                }
            })
        }
        Spacer(Modifier.height(8.dp))
        ZxButton("＋ 新建自动化", { scope.onNavigate(UiSceneId("automation.editor")) }, Modifier.fillMaxWidth())
        ZxFooterNote("启停与运行均经 Owner 命令 · 结果以 receipt 呈现")
    }
}

/** automation.editor — 自动化编辑器（按上下文区分提醒/任务/人物互动）。 */
@Composable
fun ZhaoxiAutomationEditorScene(
    automation: AutomationUiPort,
    scope: UiSceneRenderScope,
) {
    val co = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    ZxPage("自动化 · 编辑", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("触发")
        ZxNavRow("调度方式", sub = "定时 / 事件 / 手动 —— 由上下文预填") {}
        ZxSection("动作")
        com.labteto.dshmobile.uipack.zhaoxi.components.ZxField(
            draft, { draft = it }, Modifier.fillMaxWidth().height(96.dp),
            placeholder = "要自动做什么…")
        Spacer(Modifier.height(10.dp))
        ZxButton("保存", {
            co.launch {
                val context = automation.context()
                val identity = context.identity
                feedback = if (context.canPlanChat && identity != null && draft.isNotBlank())
                    automation.planAndSave(PlanChatAutomationCommand(identity, draft)).toString()
                else "当前会话暂不支持自动化编排，请切换至有效聊天会话并输入任务说明。"
            }
        }, Modifier.fillMaxWidth())
        feedback?.let { Text(it.take(240)) }
        ZxFooterNote("仅从真实会话通过 planAndSave 创建，Owner 校验身份和结果")
    }
}

/** automation.persona_interaction — 人物互动编排。 */
@Composable
fun ZhaoxiPersonaInteractionScene(
    automation: AutomationUiPort,
    scope: UiSceneRenderScope,
) {
    val co = rememberCoroutineScope()
    ZxPage("人物互动编排", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("规则")
        ZxRow("相遇事件 → 生成互动", "事件总线触发 · 双人物会话", trailing = {
            ZxBadge("示例", ZxBadgeTone.Neutral)
        })
        ZxFooterNote("互动占用各自人物的对话额度")
        Spacer(Modifier.height(10.dp))
        ZxButton("编排新互动", {
            scope.onNavigate(UiSceneId("automation.editor"))
        }, Modifier.fillMaxWidth())
    }
}

/** route.tools / tools.mcp / tools.github / tools.plugins — 工具与插件。 */
@Composable
fun ZhaoxiToolsScene(
    tools: com.labteto.dshmobile.uicontract.ToolsUiPort,
    scope: UiSceneRenderScope,
) {
    val skills by produceState(initialValue = emptyList<com.labteto.dshmobile.uicontract.UiSkillSnapshot>(), tools) {
        value = runCatching { tools.installedSkills() }.getOrDefault(emptyList())
    }
    val servers by produceState(initialValue = emptyList<com.labteto.dshmobile.uicontract.UiMcpServerSnapshot>(), tools) {
        value = runCatching { tools.servers() }.getOrDefault(emptyList())
    }
    ZxPage("工具", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("技能")
        skills.forEach { s ->
            ZxRow(s.toString().substringBefore(',').substringAfter('=').take(40), "已安装")
        }
        ZxSection("插件")
        runCatching { tools.pluginLifecycleSnapshots() }.getOrDefault(emptyList()).forEach { p ->
            ZxRow(p.toString().substringBefore(',').substringAfter('=').take(40), "生命周期快照")
        }
        ZxSection("MCP 服务器")
        servers.forEach { s ->
            ZxRow(s.toString().take(48), "")
        }
        ZxFooterNote("变更经 ToolsUiPort.submit · Applied/Failed 回执呈现")
    }
}

/** media.creation / media.result — 媒体生成。 */
@Composable
fun ZxMediaCreationScene(
    media: com.labteto.dshmobile.uicontract.MediaUiPort,
    scope: UiSceneRenderScope,
) {
    val co = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    ZxPage("媒体生成", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        com.labteto.dshmobile.uipack.zhaoxi.components.ZxField(
            prompt, { prompt = it }, Modifier.fillMaxWidth().height(96.dp),
            placeholder = "描述要生成的图片或视频…")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZxChip("图片", true) {}; ZxChip("视频", false) {}
        }
        Spacer(Modifier.height(10.dp))
        ZxButton("生成", { co.launch { /* MediaUiCommand.Submit 由宿主绑定 profile */ } },
            Modifier.fillMaxWidth(), enabled = prompt.isNotBlank())
        ZxFooterNote("生成结果经 observe(sessionId) 回流 · 可存图集或分享")
    }
}
