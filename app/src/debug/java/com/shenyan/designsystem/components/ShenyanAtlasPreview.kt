package com.shenyan.designsystem.components

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shenyan.designsystem.foundation.ShenyanMode
import com.labteto.dshmobile.ui.theme.DshTheme
import com.labteto.dshmobile.ui.theme.ThemePreference
import com.labteto.dshmobile.ui.theme.AccentPalettes
import com.shenyan.designsystem.foundation.SyOption
import kotlinx.coroutines.launch

/**
 * Debug-only, real Compose interactive laboratory for the ten user-provided UI atlases.
 * Launch with: adb shell am start -n
 * com.sy220284.dshmobile.debug/com.shenyan.designsystem.components.ShenyanAtlasActivity
 * Uses sample state only; it never contacts a real model, file system or media service.
 */
class ShenyanAtlasActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SyComponentAtlasGallery() }
    }
}

private val atlasSections = listOf(
    SyOption("basics", "01 基础组件"),
    SyOption("dialogs", "02 弹窗"),
    SyOption("sheets", "03 底部面板"),
    SyOption("gestures", "04 手势"),
    SyOption("feedback", "05 反馈"),
    SyOption("loading", "06 加载"),
    SyOption("onboarding", "07 引导"),
    SyOption("forms", "08 表单"),
    SyOption("media", "09 媒体"),
    SyOption("chat", "10 对话"),
)

@Composable
fun SyComponentAtlasGallery() {
    var section by remember { mutableStateOf("basics") }
    var theme by remember { mutableStateOf(ShenyanMode.LIGHT) }
    var accentKey by remember { mutableStateOf<String?>(null) }
    val host = remember { SnackbarHostState() }
    val atlasScroll = rememberScrollState()
    LaunchedEffect(section) { atlasScroll.scrollTo(0) }
    val scope = rememberCoroutineScope()
    DshTheme(
        preference = when (theme) {
            ShenyanMode.LIGHT -> ThemePreference.LIGHT
            ShenyanMode.MATTE -> ThemePreference.MATTE_BLACK
        },
        accentKey = accentKey,
    ) {
        Scaffold(snackbarHost = { SyUndoSnackbarHost(host) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)
                .verticalScroll(atlasScroll).testTag("sy-atlas-scroll")
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("神言 · 十类交互组件实验室",
                    style = MaterialTheme.typography.headlineSmall)
                Text("真实 Compose 组件 · 本机测试 · 触控 / 弹窗 / 动画 / 字号",
                    style = MaterialTheme.typography.bodySmall)
                SyTabs(atlasSections, section, onSelect = { section = it })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SyButton("典雅白", { theme = ShenyanMode.LIGHT }, compact = true,
                        style = if (theme == ShenyanMode.LIGHT) SyButtonStyle.PRIMARY
                        else SyButtonStyle.GHOST)
                    SyButton("磨砂黑", { theme = ShenyanMode.MATTE }, compact = true,
                        style = if (theme == ShenyanMode.MATTE) SyButtonStyle.PRIMARY
                        else SyButtonStyle.GHOST)
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AccentPalettes.ALL.forEach { palette ->
                        SyButton(palette.cnName, { accentKey = palette.key },
                            compact = true,
                            style = if (AccentPalettes.of(accentKey).key == palette.key)
                                SyButtonStyle.INFO else SyButtonStyle.GHOST)
                    }
                }
                when (section) {
                    "basics" -> AtlasBasics()
                    "dialogs" -> AtlasDialogs()
                    "sheets" -> AtlasSheets()
                    "gestures" -> AtlasGestures()
                    "feedback" -> AtlasFeedback(host)
                    "loading" -> AtlasLoading()
                    "onboarding" -> AtlasOnboarding()
                    "forms" -> AtlasForms()
                    "media" -> AtlasMedia()
                    "chat" -> AtlasChat()
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun AtlasBasics() {
    var text by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(true) }
    var slider by remember { mutableFloatStateOf(.34f) }
    var tab by remember { mutableStateOf("recent") }
    AtlasHeading("组件状态、触控与信息层级")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SyButton("主要按钮", {}, compact = true)
        SyButton("次要", {}, compact = true, style = SyButtonStyle.SECONDARY)
        SyButton("危险", {}, compact = true, style = SyButtonStyle.DANGER)
    }
    SyInput(text, { text = it }, label = "项目名称")
    SyTabs(listOf(SyOption("recent", "最近"), SyOption("saved", "收藏")),
        tab, { tab = it })
    SySwitch("开启提醒", checked, { checked = it })
    SySliderControl(value = slider, onValueChange = { slider = it })
    SyInlineBanner("这组控件使用同一组字体、状态和动态颜色", SyBannerKind.INFO)
}

@Composable
private fun AtlasDialogs() {
    var confirm by remember { mutableStateOf(false) }
    var dangerous by remember { mutableStateOf(false) }
    AtlasHeading("确认、警告和全屏弹窗")
    SyButton("打开普通确认", { confirm = true })
    SyButton("打开危险确认", { dangerous = true }, style = SyButtonStyle.DANGER)
    if (confirm) SyConfirmDialog("确认保存", "本次变更将保存在本机演示状态中。",
        false, onConfirm = { confirm = false }, onDismiss = { confirm = false })
    if (dangerous) SyConfirmDialog("确认删除样例", "这只是组件实验室，不会触及真实文件。",
        true, onConfirm = { dangerous = false }, onDismiss = { dangerous = false })
}

@Composable
private fun AtlasSheets() {
    var actionSheet by remember { mutableStateOf(false) }
    var selectSheet by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf("a")) }
    AtlasHeading("底部操作面板、多选面板、半屏/全屏")
    SyButton("打开操作面板", { actionSheet = true })
    SyButton("打开多选面板", { selectSheet = true }, style = SyButtonStyle.SECONDARY)
    if (actionSheet) SyActionSheet("更多操作", listOf(
        SyActionEntry("edit", "编辑", "修改当前选中内容", Icons.Default.Edit),
        SyActionEntry("share", "分享", "通过系统分享给其他应用", Icons.Default.Share),
        SyActionEntry("remove", "移除样例", destructive = true),
    ), onChoose = { actionSheet = false }, onDismiss = { actionSheet = false })
    if (selectSheet) SyMultiSelectSheet("选择标签",
        listOf(SyOption("a", "设计"), SyOption("b", "交互"),
            SyOption("c", "动效")),
        selectedIds = selected,
        onConfirm = { result -> selected = result; selectSheet = false },
        onDismiss = { selectSheet = false })
}

@Composable
private fun AtlasGestures() {
    var actionCount by remember { mutableIntStateOf(0) }
    AtlasHeading("左滑显示操作；滑动不会直接执行危险动作")
    SySwipeActionRow("收藏", onAction = { actionCount++ }) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Text("将这一行向左滑动，然后点击“收藏”",
                Modifier.fillMaxWidth().padding(18.dp))
        }
    }
    Text("操作次数：" + actionCount, style = MaterialTheme.typography.bodySmall)
    AtlasLiquidOptics()
}

/** Native optical study: the lens and page draw the SAME live words at the
 * same global positions. Sliding the lens changes which glyphs are refracted.
 * This is actual Compose + AGSL output, rather than a static image mockup. */
@Composable
private fun AtlasLiquidOptics() {
    var left by remember { mutableFloatStateOf(22f) }
    var top by remember { mutableFloatStateOf(37f) }
    var revision by remember { mutableIntStateOf(1) }
    val words = listOf(
        "玻璃光学 · 文字在边缘产生弯折",
        "实时折射：清晰的文字与背景层次",
        "拖动滑块，镜片内的文字随位置变化",
        "文字动态刷新 · 第 ${revision} 次光学测试",
    )
    val ink = MaterialTheme.colorScheme.onBackground
    val substrate = MaterialTheme.colorScheme.background
    val backing: @Composable (Modifier) -> Unit = { move ->
        Column(move.width(365.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            words.forEach { line ->
                Text(line, color = ink, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1)
            }
        }
    }
    AtlasHeading("真实像素折射 · 玻璃透过文字")
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep the whole lens visible at 320dp and with large system font scale.
        val maxLeft = (maxWidth - 254.dp).value.coerceAtLeast(0f)
        Column {
            SyLiveGlassScene(
                modifier = Modifier.fillMaxWidth().height(192.dp)
                    .clip(RoundedCornerShape(22.dp)).background(substrate),
                underlay = { backing(Modifier) },
                glass = {
                    SyMaterialSurface(
                        modifier = Modifier.align(Alignment.TopStart)
                            .offset(x = left.coerceIn(0f, maxLeft).dp,
                                y = top.coerceIn(0f, 77f).dp)
                            .width(254.dp).height(115.dp)
                            .pointerInput(maxLeft, density) {
                                detectDragGestures { change, drag ->
                                    left = (left + drag.x / density.density)
                                        .coerceIn(0f, maxLeft)
                                    top = (top + drag.y / density.density)
                                        .coerceIn(0f, 77f)
                                    change.consume()
                                }
                            }
                            .testTag("sy-liquid-optical-lens"),
                        tier = SyMaterialTier.L2_BAR,
                    ) {
                        Box(Modifier.fillMaxSize().padding(9.dp),
                            contentAlignment = Alignment.BottomEnd) {
                            Text("液态光学", color = ink,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
            )
            Slider(value = left.coerceIn(0f, maxLeft),
                onValueChange = { left = it }, valueRange = 0f..maxLeft.coerceAtLeast(1f))
        }
    }
    SyButton("刷新实时文字", { revision++ }, style = SyButtonStyle.SECONDARY)
}

@Composable
private fun AtlasFeedback(host: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    AtlasHeading("成功、警告、错误与撤销")
    SyInlineBanner("上传完成，已保存到本地", SyBannerKind.SUCCESS)
    SyInlineBanner("权限尚未授予，请检查系统设置", SyBannerKind.WARNING)
    SyInlineBanner("网络不可用，可在恢复后重试", SyBannerKind.ERROR,
        actionLabel = "重试", onAction = {})
    SyButton("演示可撤销 Snackbar", {
        scope.launch {
            val result = host.showSnackbar(
                message = "已从示例列表移除",
                actionLabel = "撤销",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                host.showSnackbar("已恢复演示项目")
            }
        }
    })
}

@Composable
private fun AtlasLoading() {
    var progress by remember { mutableFloatStateOf(.56f) }
    AtlasHeading("骨架屏、进度与真实状态")
    SySkeletonCard(Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        SyProgressRing(progress, "正在处理")
        Column {
            SyButton("增加进度", { progress = (progress + .1f).coerceAtMost(1f) })
            Text("进度值由演示状态提供")
        }
    }
}

@Composable
private fun AtlasOnboarding() {
    var finished by remember { mutableStateOf(false) }
    var coach by remember { mutableStateOf(false) }
    AtlasHeading("引导、轮播、提示气泡与页码")
    if (finished) SyInlineBanner("引导演示已完成", SyBannerKind.SUCCESS,
        actionLabel = "重看", onAction = { finished = false })
    else SyOnboardingCarousel(
        listOf(
            SyOnboardingPage("发现工具", "所有能力集中在一个入口"),
            SyOnboardingPage("创建任务", "清晰规划和执行每一步"),
            SyOnboardingPage("查看结果", "状态与成果始终可以追溯"),
        ),
        onComplete = { finished = true },
    )
    SyCoachMark("快捷操作", "这里提供更多操作，点按即可关闭。",
        expanded = coach, onDismiss = { coach = false }) {
        SyButton("显示操作提示", { coach = true },
            style = SyButtonStyle.SECONDARY)
    }
}

@Composable
private fun AtlasForms() {
    var selectedDay by remember { mutableStateOf("未选择") }
    var numeric by remember { mutableIntStateOf(3) }
    var showRange by remember { mutableStateOf(false) }
    AtlasHeading("日期、时间范围、多选、步进与验证")
    SyDatePickerField("计划日期", selectedDay, onDateSelected = { selectedDay = it.toString() })
    SyNumericStepper(numeric, { numeric = it }, minimum = 0, maximum = 20)
    SyButton("选择日期范围", { showRange = true })
    if (showRange) SyDateRangePickerDialog(true,
        onDismiss = { showRange = false },
        onConfirm = { _, _ -> showRange = false })
}

@Composable
private fun AtlasMedia() {
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(.4f) }
    var expanded by remember { mutableStateOf(true) }
    AtlasHeading("媒体进度、播放控制与迷你播放器")
    SyMediaTransport("演示视频 · 用户触控体验", playing, progress,
        "00:12", "00:30", onPlayPause = { playing = !playing },
        onSeek = { progress = it })
    if (expanded) SyMiniPlayer("正在播放 · 样例音轨", "本机 UI 演示", playing,
        onPlayPause = { playing = !playing },
        onExpand = { expanded = false }, onClose = { expanded = false })
    else SyButton("恢复迷你播放器", { expanded = true })
}

@Composable
private fun AtlasChat() {
    var value by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("暂无发送记录") }
    AtlasHeading("多行输入、附件、语音与发送状态")
    SyInlineBanner(last, SyBannerKind.INFO)
    SyChatComposer(value, onValueChange = { value = it },
        onSend = { last = "已发送演示消息：" + value; value = "" },
        onAttachment = { last = "打开附件选择（演示）" },
        onVoice = { last = "开始语音输入（演示）" })
}

@Composable
private fun AtlasHeading(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760,
    name = "神言交互组件实验室")
@Composable
private fun AtlasPreview() { SyComponentAtlasGallery() }
