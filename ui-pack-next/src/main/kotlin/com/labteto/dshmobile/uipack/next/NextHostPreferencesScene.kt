package com.labteto.dshmobile.uipack.next

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.labteto.dshmobile.uicontract.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Independent app presentation settings; writes go to the shared Host DataStore. */
@Composable
internal fun NextHostPreferencesScene(
    page: String, port: HostPreferencesUiPort, onBack: () -> Unit,
) {
    val state by port.snapshots.collectAsStateWithLifecycle(initialValue = UiHostPreferences())
    var theme by remember(state.theme) { mutableStateOf(state.theme) }
    var accent by remember(state.accent) { mutableStateOf(state.accent) }
    var scale by remember(state.textScale) { mutableFloatStateOf(state.textScale) }
    var transparency by remember(state.transparency) { mutableFloatStateOf(state.transparency) }
    var ambient by remember(state.ambientGlow) { mutableFloatStateOf(state.ambientGlow) }
    var emphasis by remember(state.componentEmphasis) { mutableFloatStateOf(state.componentEmphasis) }
    var motion by remember(state.motionScale) { mutableFloatStateOf(state.motionScale) }
    var bold by remember(state.textWeight) { mutableIntStateOf(state.textWeight) }
    var contrast by remember(state.adaptiveContrast) { mutableStateOf(state.adaptiveContrast) }
    var jobs by remember(state.notifyJobs) { mutableStateOf(state.notifyJobs) }
    var results by remember(state.notifyResults) { mutableStateOf(state.notifyResults) }
    var failures by remember(state.notifyFailures) { mutableStateOf(state.notifyFailures) }
    var action by remember(state.notifyNeedsAction) { mutableStateOf(state.notifyNeedsAction) }
    var chat by remember(state.notifyChat) { mutableStateOf(state.notifyChat) }
    var quiet by remember(state.quietEnabled) { mutableStateOf(state.quietEnabled) }
    var quietStart by remember(state.quietStartMinute) { mutableIntStateOf(state.quietStartMinute) }
    var quietEnd by remember(state.quietEndMinute) { mutableIntStateOf(state.quietEndMinute) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(command: UiHostPreferencesCommand) {
        if (busy) return
        busy = true
        scope.launch {
            try { notice = if (port.submit(command)) "设置已保存" else "设置未保存" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.message ?: "设置保存失败" }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when (page) {
            "settings.appearance" -> "界面与外观"
            "settings.notifications" -> "通知偏好"
            else -> "系统权限"
        }, style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onBack) { Text("返回") }
        notice?.let { Text(it) }
        when (page) {
            "settings.appearance" -> {
                Text("主题")
                for ((value, title) in listOf("system" to "跟随系统", "light" to "浅色",
                    "dark" to "深色", "matte_black" to "哑光黑")) {
                    FilterChip(selected = theme == value, onClick = { theme = value }, label = { Text(title) })
                }
                OutlinedTextField(accent, { accent = it.filter { char -> char.isLetterOrDigit() ||
                    char == '-' || char == '_' }.take(50) }, label = { Text("配色标识") })
                Text("阅读字号：${"%.2f".format(scale)}")
                Slider(scale, { scale = it }, valueRange = .9f..1.5f)
                Text("字重：$bold")
                Slider(bold.toFloat(), { bold = it.toInt() }, valueRange = 0f..2f, steps = 1)
                Text("材质透明度")
                Slider(transparency, { transparency = it })
                Text("环境辉光")
                Slider(ambient, { ambient = it })
                Text("组件强调")
                Slider(emphasis, { emphasis = it })
                Text("动效速度")
                Slider(motion, { motion = it }, valueRange = .7f..1.3f)
                Row {
                    Switch(checked = contrast, onCheckedChange = { contrast = it })
                    Text("背景自适应对比")
                }
                Button(enabled = !busy && accent.matches(Regex("[a-z0-9_-]{2,50}")),
                    onClick = { save(UiHostPreferencesCommand.Appearance(theme, accent, scale, bold,
                        transparency, ambient, emphasis, motion, contrast)) }) { Text("保存外观") }
            }
            "settings.notifications" -> {
                @Composable fun choice(text: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
                    Row {
                        Switch(checked = enabled, onCheckedChange = onChange)
                        Spacer(Modifier.width(12.dp))
                        Text(text)
                    }
                }
                choice("任务通知", jobs) { jobs = it }
                choice("完成通知", results) { results = it }
                choice("失败通知", failures) { failures = it }
                choice("需要操作提醒", action) { action = it }
                choice("聊天消息", chat) { chat = it }
                choice("非紧急提醒免打扰", quiet) { quiet = it }
                if (quiet) {
                    Text("免打扰开始分钟：$quietStart")
                    Slider(quietStart.toFloat(), { quietStart = it.toInt() }, valueRange = 0f..1439f)
                    Text("免打扰结束分钟：$quietEnd")
                    Slider(quietEnd.toFloat(), { quietEnd = it.toInt() }, valueRange = 0f..1439f)
                }
                Button(enabled = !busy, onClick = {
                    save(UiHostPreferencesCommand.Notifications(jobs, results, failures, action,
                        chat, quiet, quietStart, quietEnd))
                }) { Text("保存通知偏好") }
                TextButton(onClick = {
                    if (!port.openSystemNotifications()) notice = "无法打开系统通知设置"
                }) { Text("打开 Android 系统通知权限") }
            }
            else -> {
                Text("权限状态由 Android 系统管理，应用不会在此默许授予权限。")
                Button(onClick = {
                    if (!port.openSystemPermissions()) notice = "无法打开系统权限设置"
                }) { Text("打开 Android 应用权限设置") }
            }
        }
    }
}
