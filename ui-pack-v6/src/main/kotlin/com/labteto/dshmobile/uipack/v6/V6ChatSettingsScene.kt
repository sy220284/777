package com.labteto.dshmobile.uipack.v6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import com.labteto.dshmobile.uihost.rememberUiPrivateText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.SettingsUiCommand
import com.labteto.dshmobile.uicontract.SettingsUiPort
import com.labteto.dshmobile.uicontract.SettingsUiReceipt
import com.labteto.dshmobile.uicontract.UiSceneId
import com.labteto.dshmobile.uicontract.submit
import com.labteto.dshmobile.uihost.UiSceneNavigationResult
import com.shenyan.designsystem.components.SyButton
import com.shenyan.designsystem.components.SyButtonStyle
import com.shenyan.designsystem.components.SyGlassCard
import com.shenyan.designsystem.components.SyInput
import com.shenyan.designsystem.components.SyListItem
import com.shenyan.designsystem.components.SyNote
import com.shenyan.designsystem.components.SySwitch

private const val MAX_CUSTOM_CHAT_FILTERS = 50
private const val MAX_CUSTOM_CHAT_FILTER_CHARS = 32

/**
 * Independent V6 rendering of the existing chat-style guard management.
 * The Settings Owner still validates and persists each command. No UI-local filter store.
 */
@Composable
fun V6ChatStyleSettingsScene(settings: SettingsUiPort, onBack: () -> Unit) {
    val state by settings.state.collectAsStateWithLifecycle(initialValue = settings.initialState)
    var draft by rememberUiPrivateText("settings.chat.filter", settings) { "" }
    var feedback by remember(settings) { mutableStateOf<String?>(null) }
    var showHits by rememberSaveable(settings) { mutableStateOf(false) }

    fun submit(command: SettingsUiCommand): Boolean {
        return try {
            when (val receipt = settings.submit(command)) {
                SettingsUiReceipt.Applied -> {
                    feedback = "操作已提交，实际状态以最新设置为准"
                    true
                }
                SettingsUiReceipt.Submitted -> {
                    feedback = "请求已排队，以后续设置状态为准"
                    true
                }
                SettingsUiReceipt.Rejected -> {
                    feedback = "操作未被接受，请检查输入或刷新设置"
                    false
                }
                is SettingsUiReceipt.Failed -> {
                    feedback = receipt.message ?: "操作失败，请重试"
                    false
                }
            }
        } catch (failure: Exception) {
            feedback = failure.message ?: "设置操作失败"
            false
        }
    }

    val custom = state.chatStyleGuardCustomPhrases
    val personaFilters = state.personaBannedPhrases.map(String::trim)
        .filter(String::isNotBlank).distinct()
    val candidate = draft.trim()
    val canAdd = !state.loading && candidate.isNotEmpty() &&
        candidate.length <= MAX_CUSTOM_CHAT_FILTER_CHARS &&
        candidate !in custom && custom.size < MAX_CUSTOM_CHAT_FILTERS

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("聊天风格过滤", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        state.error?.let { SyNote(it) }
        feedback?.let { SyNote(it) }
        if (state.loading) {
            SyNote("正在读取当前设置，暂不能修改")
        } else {
            SyGlassCard(Modifier.fillMaxWidth()) {
                SySwitch("启用风格过滤", state.chatStyleGuardEnabled,
                    onCheckedChange = { submit(SettingsUiCommand.StyleGuard(it)) })
                if (!state.chatStyleGuardEnabled)
                    SyNote("过滤器已关闭，现有自定义规则仍保留。")
                Text("自定义过滤词（${custom.size}/$MAX_CUSTOM_CHAT_FILTERS）",
                    style = MaterialTheme.typography.titleMedium)
                SyNote("每条最多 $MAX_CUSTOM_CHAT_FILTER_CHARS 字，重复内容不会再次添加")
                SyInput(draft, { draft = it.replace("\n", " ").take(MAX_CUSTOM_CHAT_FILTER_CHARS) },
                    label = "新增过滤词")
                SyButton("添加过滤词", {
                    if (submit(SettingsUiCommand.AddStylePhrase(candidate))) draft = ""
                }, enabled = canAdd)
                custom.forEach { phrase ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("• $phrase", modifier = Modifier.weight(1f))
                        SyButton("移除", {
                            submit(SettingsUiCommand.RemoveStylePhrase(phrase))
                        }, style = SyButtonStyle.GHOST)
                    }
                }
                if (custom.isEmpty()) SyNote("尚无自定义过滤词")
            }
            SyGlassCard(Modifier.fillMaxWidth()) {
                Text("人物独立规则（${personaFilters.size}）",
                    style = MaterialTheme.typography.titleMedium)
                SyNote("人物自身的过滤约束只读展示，在人物编辑中维护。")
                personaFilters.forEach { phrase -> Text(phrase) }
                if (personaFilters.isEmpty()) Text("当前人物没有额外过滤词")
            }
            if (state.styleGuardHits.isNotEmpty()) SyGlassCard(Modifier.fillMaxWidth()) {
                SyButton("命中记录（${state.styleGuardHits.size}）", {
                    showHits = !showHits
                }, style = SyButtonStyle.SECONDARY)
                if (showHits) {
                    state.styleGuardHits.takeLast(8).asReversed().forEach { Text(it) }
                    SyButton("清除命中记录", {
                        if (submit(SettingsUiCommand.ClearStyleHits)) showHits = false
                    }, style = SyButtonStyle.SECONDARY)
                }
            }
        }
    }
}

/** V6 session-settings entry delegates navigation to Host, not an App-specific callback. */
@Composable
fun V6SessionSettingsScene(
    settings: SettingsUiPort,
    onNavigate: (UiSceneId) -> UiSceneNavigationResult,
    onBack: () -> Unit,
) {
    val state by settings.state.collectAsStateWithLifecycle(initialValue = settings.initialState)
    var feedback by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("会话设置", style = MaterialTheme.typography.titleLarge)
        SyButton("返回", onBack, style = SyButtonStyle.GHOST)
        feedback?.let { SyNote(it) }
        if (state.loading) SyNote("正在加载会话设置")
        else SyGlassCard(Modifier.fillMaxWidth()) {
            SyListItem("聊天风格过滤",
                if (state.chatStyleGuardEnabled) "已启用" else "已关闭",
                onClick = {
                    val result = onNavigate(UiSceneId("settings.chat"))
                    if (result !is UiSceneNavigationResult.Changed)
                        feedback = "当前界面无法打开聊天设置"
                })
        }
    }
}
