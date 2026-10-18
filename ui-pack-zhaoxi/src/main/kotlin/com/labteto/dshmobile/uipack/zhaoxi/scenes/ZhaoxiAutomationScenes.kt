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
import com.labteto.dshmobile.uicontract.submit
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
    val snap by automation.snapshots.collectAsState(initial = automation.snapshot())
    var filter by remember { mutableStateOf("全部") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val filtered = remember(snap.tasks, filter) {
        snap.tasks.filter { task ->
            when (filter) {
                "已计划" -> task.status == com.labteto.dshmobile.automation.AutomationStatus.SCHEDULED
                "运行中" -> task.status == com.labteto.dshmobile.automation.AutomationStatus.RUNNING
                "已暂停" -> task.status == com.labteto.dshmobile.automation.AutomationStatus.PAUSED
                else -> true
            }
        }
    }
    ZxPage("自动化", onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        ZxSection("任务状态")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("全部", "已计划", "运行中", "已暂停").forEach { label ->
                ZxChip(label, filter == label) { filter = label }
            }
        }
        ZxValueRow("当前列表", value = "${filtered.size} / ${snap.tasks.size}")
        feedback?.let { Text(it, color = ZhaoxiTheme.colors.ink2) }
        if (filtered.isEmpty()) ZxEmpty("没有符合条件的任务", "可切换状态筛选或创建新任务")
        filtered.forEach { task ->
            val paused = task.status == com.labteto.dshmobile.automation.AutomationStatus.PAUSED
            val actionAvailable = task.status !in setOf(
                com.labteto.dshmobile.automation.AutomationStatus.CANCELLED,
                com.labteto.dshmobile.automation.AutomationStatus.COMPLETED,
                com.labteto.dshmobile.automation.AutomationStatus.BLOCKED,
            )
            ZxRow(task.prompt.take(80),
                "${if (task.mode == com.labteto.dshmobile.automation.AutomationMode.CHAT) "聊天" else "工作"} · ${task.status.wireValue} · ${task.scheduleType.name}",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ZxChip("立即运行", false) {
                            feedback = automation.submit(AutomationUiCommand.RunNow(task.id)).toString()
                        }
                        if (actionAvailable) {
                            ZxSwitch(checked = !paused, onCheckedChange = { on ->
                                feedback = automation.submit(
                                    if (on) AutomationUiCommand.Resume(task.id) else AutomationUiCommand.Pause(task.id)
                                ).toString()
                            }, contentDescription = "${task.prompt.take(20)} · 启用状态")
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        ZxButton("新建自动化", { scope.onNavigate(UiSceneId("automation.editor")) }, Modifier.fillMaxWidth())
        ZxFooterNote("任务状态和开关来自 Automation Owner；立即运行仅代表提交请求，不代表执行成功")
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
        ZxRow("执行时间", "在任务描述中写明时间或触发条件，保存时由自动化服务解析")
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

/** 按 E8.18 独立场景分派工具中心、MCP、插件和 GitHub；所有状态来自 ToolsUiPort。 */
@Composable
fun ZhaoxiToolsScene(
    tools: com.labteto.dshmobile.uicontract.ToolsUiPort,
    scope: UiSceneRenderScope,
    destination: String = "route.tools",
) {
    val co = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<String?>(null) }
    var endpoint by remember { mutableStateOf("") }
    var serverId by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    val catalogue = remember(tools) { tools.capabilityCatalogue() }
    val plugins = remember(tools, refresh) { tools.pluginLifecycleSnapshots() }
    val installedIds = remember(tools, refresh) { tools.installedPluginIds() }
    val skills by produceState(initialValue = emptyList<com.labteto.dshmobile.uicontract.UiSkillSnapshot>(), tools) {
        value = tools.installedSkills()
    }
    val servers by produceState(initialValue = emptyList<com.labteto.dshmobile.uicontract.UiMcpServerSnapshot>(), tools, refresh) {
        value = tools.servers()
    }
    val githubConfigured by produceState(initialValue = false, tools, refresh) {
        value = tools.githubConfigured()
    }
    val heading = when (destination) {
        "tools.mcp" -> "MCP 连接"
        "tools.plugins" -> "插件管理"
        "tools.github" -> "GitHub"
        "work.capabilities" -> "能力目录"
        else -> "工具中心"
    }
    ZxPage(heading, onBack = { scope.onBack() }) {
        Spacer(Modifier.height(8.dp))
        feedback?.let { ZxSection("操作回执"); Text(it, color = ZhaoxiTheme.colors.ink2) }
        when (destination) {
            "tools.mcp" -> {
                ZxSection("连接方式 · HTTP")
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxField(
                    serverId, { serverId = it }, Modifier.fillMaxWidth(), placeholder = "服务器名称")
                com.labteto.dshmobile.uipack.zhaoxi.components.ZxField(
                    endpoint, { endpoint = it }, Modifier.fillMaxWidth(), placeholder = "HTTP 地址")
                ZxButton("连接服务器", { co.launch {
                    feedback = tools.submit(
                        com.labteto.dshmobile.uicontract.ToolsUiCommand.ConnectHttp(serverId, endpoint)
                    ).toString()
                    refresh++
                } }, enabled = serverId.isNotBlank() && endpoint.isNotBlank())
                ZxSection("已登记服务器")
                if (servers.isEmpty()) ZxEmpty("尚未添加 MCP 服务器", "填写名称和地址后连接")
                servers.forEach { server ->
                    ZxRow(server.id, "${server.transport} · ${server.connection} · ${server.tools.size} 个工具")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZxChip("检查连接", false) { co.launch {
                            feedback = tools.checkServerHealth(server.id).toString()
                            refresh++
                        } }
                        ZxChip("断开", false) { co.launch {
                            feedback = tools.submit(
                                com.labteto.dshmobile.uicontract.ToolsUiCommand.Disconnect(server.id)
                            ).toString()
                            refresh++
                        } }
                    }
                }
                ZxFooterNote("连接状态不代表工具已经授权执行；每次执行仍由原 Owner 验证")
            }
            "tools.plugins" -> {
                ZxSection("已安装插件")
                ZxValueRow("已安装数量", value = installedIds.size.toString())
                if (installedIds.isEmpty() && plugins.isEmpty()) ZxEmpty("尚无已安装插件", "插件由当前工具管理能力提供")
                installedIds.forEach { id ->
                    val lifecycle = plugins.firstOrNull { it.id == id }
                    ZxRow(id, lifecycle?.state?.name ?: "已安装 · 未提供生命周期状态")
                }
                plugins.filter { it.id !in installedIds }.forEach { item ->
                    ZxRow(item.id, item.state.name)
                }
                plugins.filter { it.state == com.labteto.dshmobile.uicontract.UiPluginLifecycleState.FAILED }
                    .forEach { item ->
                        ZxButton("恢复 ${item.id}", { co.launch {
                            feedback = tools.submit(
                                com.labteto.dshmobile.uicontract.ToolsUiCommand.RecoverFailedPlugin(item.id)
                            ).toString()
                            refresh++
                        } })
                    }
                ZxFooterNote("未提供的启停、安装操作不伪造；插件状态以 Owner 快照为准")
            }
            "tools.github" -> {
                ZxSection("连接状态")
                ZxValueRow("GitHub", value = if (githubConfigured) "已配置" else "未配置")
                ZxSection("可用能力")
                catalogue.filter { it.family.contains("github", ignoreCase = true) }
                    .forEach { cap -> ZxRow(cap.name, "${cap.access} · ${cap.approval}") }
                if (!githubConfigured) ZxFooterNote("GitHub 尚未配置；请通过已有授权配置入口连接")
            }
            "work.capabilities" -> {
                val grouped = catalogue.groupBy { it.family }
                if (grouped.isEmpty()) ZxEmpty("暂无能力", "已注册的能力会在这里出现")
                grouped.forEach { (group, items) ->
                    ZxSection(group)
                    items.forEach { cap ->
                        ZxRow(cap.name, "${cap.access} · ${cap.approval}")
                    }
                }
            }
            else -> {
                ZxSection("可用能力")
                catalogue.groupBy { it.family }.forEach { (family, items) ->
                    ZxRow(family, "${items.size} 项已登记能力",
                        onClick = { scope.onNavigate(UiSceneId("work.capabilities")) })
                }
                ZxSection("技能")
                if (skills.isEmpty()) ZxRow("暂无已安装技能", "当前未读取到技能")
                skills.forEach { item -> ZxRow(item.displayName, item.description) }
                ZxSection("连接与扩展")
                ZxRow("MCP 服务器", "${servers.size} 个已登记连接",
                    onClick = { scope.onNavigate(UiSceneId("tools.mcp")) })
                ZxRow("插件", "${installedIds.size} 个已安装插件",
                    onClick = { scope.onNavigate(UiSceneId("tools.plugins")) })
                ZxRow("GitHub", if (githubConfigured) "已配置" else "未配置",
                    onClick = { scope.onNavigate(UiSceneId("tools.github")) })
                ZxFooterNote("能力登记不等于运行许可；受控操作由原工具 Owner 执行")
            }
        }
    }
}
